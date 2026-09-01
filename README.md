# url-shortener

Production-style URL shortener on AWS. Java 17 / Spring Boot 3.5 / DynamoDB / Redis (ElastiCache)
/ SQS + Lambda / ECS Fargate + ALB / Terraform.

## Layout
- `shortener/`  — Spring Boot app (Maven)
- `terraform/`  — IaC for the data-plane resources, IAM least-privilege policy, alarms, secrets
- `lambda/`     — Python consumer that aggregates click events into DynamoDB
- `load-test/`  — k6 script, raw run results, and the experiment log behind [PERFORMANCE.md](PERFORMANCE.md)

## Architecture

```
                    +--------------+
    client --HTTP-->|     ALB      | :80   health check: /actuator/health
                    +------+-------+
              +------------+------------+
              v                         v
        +-----------+             +-----------+   2 x Fargate, 0.5 vCPU / 1 GB
        |  task 1   |             |  task 2   |   Java 17 / Spring Boot 3.5
        +-----+-----+             +-----+-----+
              |                         |
              |  (1) GET /api/v1/{code} -> 302          read path, SYNCHRONOUS
              |      Redis --miss--> DynamoDB UrlMappings, write back TTL 1h
              |
              |  (2) @Async publish  (clickEventExecutor: core 2 / max 4 / queue 100,
              v      CallerRunsPolicy = back-pressure, NOT fire-and-forget)
        +----------+  maxReceiveCount 3   +-----+     +-----+
        |   SQS    |--------------------->| DLQ |---->| SNS |--> email alarm
        +----+-----+                      +-----+     +-----+
             |  (3) event source mapping, BatchSize 10, ReportBatchItemFailures
             v
       +--------------+  claim-first dedup (conditional write + compensating delete)
       |    Lambda    |  512 MB, python3.12, EMF metrics (no PutMetricData call)
       +------+-------+
              +--> DynamoDB ProcessedEvents  (messageId, TTL 7d)  -- the claim
              +--> DynamoDB ClickAnalytics   (ADD clickCount)     -- the aggregate
```

**The read path is synchronous and cached; the click-analytics path is asynchronous and
idempotent.** Everything measured in [PERFORMANCE.md](PERFORMANCE.md) is about the second path —
which is why the client-side percentiles barely move.

## Quick start

```bash
cd shortener
docker compose up -d redis        # the app expects Redis on localhost:6379

# API key from a local property instead of Secrets Manager (this is the path CI uses)
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --app.security.api-key-source=property \
  --app.security.api-key=local-dev-key"
```

Needs AWS credentials that can reach DynamoDB (`UrlMappings`, `ClickAnalytics`) and SQS
(`url-click-events`) in `ap-east-2` — the app resolves the queue URL at startup and **fails fast**
if it cannot. That is deliberate: a service that cannot publish click events should not report
itself healthy.

Tests need neither: `./mvnw -B test` runs **16 tests with no AWS credentials and no Redis**
(anything needing real infrastructure is named `*IT`, which Surefire's default includes do not
match).

## Authentication (simplified)

Write endpoints require an `X-API-Key` header; a missing or wrong key returns **401**.

| Endpoint | Auth |
| --- | --- |
| `POST /api/v1/shorten` | 🔒 API key required |
| `POST` / `GET` on `/api/v1/cache-test/**` | 🔒 API key required (debug only) |
| `GET /api/v1/{shortCode}` | public (this is the product) |
| `GET /api/v1/analytics/{shortCode}` | public (see Limitations) |
| `GET /actuator/health` | public (ALB health check) |

The key lives in **AWS Secrets Manager** (`url-shortener/api-key`, JSON `{"apiKey": "..."}`) and is
read **once at startup**. It is never committed, never in `.env`, and never in Terraform state.

**This is a deliberately simplified scheme.** A single shared key proves *possession*, not *identity*:
there is no per-user attribution, no expiry, and rotation means telling every client. In production
this would be replaced by **Amazon Cognito** or an **OAuth2 Resource Server** issuing short-lived JWTs.

## Limitations (known, not accidental)
- `GET /api/v1/analytics/{shortCode}` has no ownership model — anyone who knows a short code can read
  its click counts (a classic IDOR). Fixing it requires a notion of "who owns this link".
- The ALB listener is HTTP:80 (no TLS); production would terminate HTTPS with an ACM certificate.

## Bring-up (from a cold, scaled-to-zero environment)

This project is normally parked at `desiredCount = 0`: the data plane (DynamoDB, SQS, the secret,
the alarms) and the ALB stay up, and only the compute is switched off. Bringing it back:

```bash
export AWS_REGION=ap-east-2
aws ecs update-service --cluster url-shortener-cluster --service url-shortener-service \
  --task-definition url-shortener-task:18 --desired-count 2

TG=$(aws elbv2 describe-target-groups --names url-shortener-tg \
       --query 'TargetGroups[0].TargetGroupArn' --output text)
aws elbv2 describe-target-health --target-group-arn "$TG" \
  --query 'TargetHealthDescriptions[*].TargetHealth.State' --output text   # wait for 2 x healthy
```

**Measured 2026-08-31: about 95 s from `update-service` to two healthy targets.** That figure is
reconstructed from ECS service events rather than read off a stopwatch — ECS logged *started 2
tasks* at 16:33:44, *registered 2 targets* at 16:34:20, and *reached a steady state* at 16:34:57
(PDT), with the API call going out shortly before the first of those.

The floor is set by the health check, not by the app: `HealthyThreshold 2` x `Interval 30 s` = 60 s
of passing checks before a target is in service, on top of Fargate pulling a 153 MB image and the
JVM starting. So ~95 s is close to the practical minimum for this shape of service, and it is the
number to budget against when someone asks for a live demo.

**Not covered by this path** (and deliberately so):
* The compute plane — ALB, ECS service/cluster, ElastiCache, the Lambda, ECR — is **not** in
  Terraform. Importing it is a few hours of work and is tracked as backlog item #1. `terraform plan`
  on this repo reports *No changes* even with the service scaled to zero, because the service is not
  something it manages.
* 🚨 `terraform destroy` would drop `UrlMappings` (~152k items) and `ClickAnalytics`; neither table
  has deletion protection. The Terraform here is for *drift detection and re-creation of the data
  plane*, **not** for a routine teardown/rebuild cycle. Teardown is the one-line command below, and
  nothing about a cold environment ever requires `terraform destroy`.

Teardown is one command: `aws ecs update-service --cluster url-shortener-cluster \
--service url-shortener-service --desired-count 0` — then confirm `runningCount` actually reaches 0
rather than assuming it did.

**Idle cost note.** The ALB and the ElastiCache node (`url-shortener-redis`, `cache.t4g.micro`) stay
up between demos (~$__/month) — a deliberate trade against a 30–60 minute rebuild that has never
been rehearsed: recreating the cache means a new cluster, a new `SPRING_DATA_REDIS_HOST` in the task
definition, and a new revision, and the ECS deployment circuit breaker is disabled. Taken while
applications are out. **Reassess if no interview has materialised by 2026-11-01.**

ECR keeps only the 10 most recent images (lifecycle policy, applied 2026-08-31); CI tags by commit
SHA, so without it they accumulate forever. Ten is chosen to keep `c9d8331` — task definition `:14`,
the build measured in [PERFORMANCE.md](PERFORMANCE.md) round 1 — rather than the tighter 5, which
would have expired it at the very next push.

## Demo (against a running deployment)

```bash
export AWS_REGION=ap-east-2
ALB=$(aws elbv2 describe-load-balancers --names url-shortener-alb \
        --query 'LoadBalancers[0].DNSName' --output text)
API_KEY=$(aws secretsmanager get-secret-value --secret-id url-shortener/api-key \
        --query SecretString --output text | jq -r .apiKey)

# 1. create  (returns 401 without the key)
CODE=$(curl -s -X POST "http://$ALB/api/v1/shorten" \
  -H 'Content-Type: application/json' -H "X-API-Key: $API_KEY" \
  -d '{"longUrl":"https://example.com/demo"}' | jq -r .shortCode)

# 2. redirect -> 302  (public, no key needed)
curl -sI "http://$ALB/api/v1/$CODE" | head -1               # HTTP/1.1 302 Found
curl -sI "http://$ALB/api/v1/$CODE" | grep -i '^location:'  # Location: https://example.com/demo

# 3. redirect again -> Redis cache hit (see UrlShortener/CacheHit in CloudWatch)
curl -sI "http://$ALB/api/v1/$CODE" > /dev/null

# 4. analytics -> the three clicks arrive via SQS + Lambda, a few seconds behind
sleep 15 && curl -s "http://$ALB/api/v1/analytics/$CODE" | jq
#    { "2026-08-31": 3 }    <- last actually run 2026-08-31 (shortCode 1hw1aat);
#                              CacheHit summed to 2 over the same window: 1 miss + 2 hits
```

The `sleep` in step 4 is the point, not an inconvenience: click aggregation is asynchronous, so
the counter is eventually consistent with the redirect that produced it.

## Performance

Load-tested with k6 (open model, 70% redirect / 30% create). One optimisation round is documented
end to end in **[PERFORMANCE.md](PERFORMANCE.md)** — methodology, baseline, hypothesis,
falsifiable thresholds, result, and a Little's Law analysis of both bottlenecks.

Headline: at the same 250 RPS the asynchronous consumer's **per-message cost fell 61.6 ms -> 9.8 ms
(-84%)** and the **SQS backlog went from 13,980 messages (125 s behind) to zero** — while the
client-side percentiles barely moved, because the change was on the asynchronous path.

## CI
GitHub Actions runs `./mvnw -B test` on every push to `main`. The suite is designed to pass with
**no AWS credentials and no Redis**; tests that need real infrastructure are named `*IT` and are
excluded from the default surefire run.