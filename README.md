# url-shortener

Production-style URL shortener on AWS. Java 17 / Spring Boot 3.5 / DynamoDB / Redis (ElastiCache)
/ SQS + Lambda / ECS Fargate + ALB / Terraform.

## Layout
- `shortener/` — Spring Boot app (Maven)
- `terraform/` — IaC for the data-plane resources, IAM least-privilege policy, alarms, secrets
- `lambda/`    — Python consumer that aggregates click events into DynamoDB

## Run locally
```bash
cd shortener
./mvnw spring-boot:run          # needs AWS credentials that can read the API key secret
```

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

## CI
GitHub Actions runs `./mvnw -B test` on every push to `main`. The suite is designed to pass with
**no AWS credentials and no Redis**; tests that need real infrastructure are named `*IT` and are
excluded from the default surefire run.