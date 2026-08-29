# Performance: Baseline → Optimization Round 1

One optimisation round on the asynchronous click-event pipeline, documented end to end:
methodology, baseline, a pre-registered hypothesis with falsifiable thresholds, the measured
result, and a Little's Law analysis of both bottlenecks. Raw experiment logs are linked in §5.

## 1. Test Methodology

| Element | Detail |
| --- | --- |
| Tool / model | k6, `constant-arrival-rate` (**open model**) — a fixed number of requests enters per second regardless of how slow the service gets |
| Traffic mix | **70% `GET /api/v1/{code}` -> 302** (`redirects: 0`) / **30% `POST /api/v1/shorten` -> 201**, over 10 fixed short codes |
| System under test | 2 x Fargate **0.5 vCPU / 1 GB** behind an ALB; ElastiCache `cache.t4g.micro`; DynamoDB on-demand; SQS -> Lambda (**account concurrency limit 10**) |
| Known measurement bias | The load generator runs on a laptop in **UTC-7**; the system runs in **ap-east-2**. Floor RTT is **~158 ms** (k6 `http_req_duration.min` = 157.8 ms), so **client-side percentiles are dominated by that floor** |
| Controls | Every run is warmed up first (JIT); same 2 tasks, same 10 short codes, same RATE / DURATION; `load-test/baseline.js` was **not modified between before and after** |

Two rules govern every number below.

Every window is aligned to the minute and `--period` always equals the window length. Using
`--period 3600` on a 5-minute window inflates the same metric by 2.8x (measured on Day 34).

A run is only accepted if six independent counters agree: k6 `iterations` = ALB `RequestCount`
= (2XX + 3XX + 5XX), ALB 3XX = SQS `Sent` = `Received` = `Deleted`, and DynamoDB
`ConsumedWriteCapacityUnits` on both consumer tables = SQS `Deleted`.

## 2. Baseline

Measured 2026-08-24, 250 RPS x 4 min, warm JVM, window `15:24:00Z-15:29:00Z` (`--period 300`).

### Client side (k6) — all green
| Metric | Value |
| --- | --- |
| Achieved rate | 249.29 RPS |
| p50 / p95 / p99 | 165.2 / 244.0 / 256.3 ms |
| `http_req_failed` | 0.00% |
| Exit code | 0 |

### Server side — where the problem actually was
| Metric | Value |
| --- | --- |
| SQS backlog peak | **13,980 messages** |
| Oldest-message age peak | **125 s** |
| Messages sent / received / deleted | 41,842 / 29,312 / 29,212 (**12,630 stuck**) |
| Per-message consumer cost | **61.6 ms** |
| Fixed overhead per invocation | **63.2 ms** |
| Lambda `ConcurrentExecutions` avg / max | **8.0 / 10** |
| Lambda `Throttles` | **489** (14.2% of invocation attempts) |
| Sustained consumer throughput | **112.7 msg/s** |

**A load test that exits 0 while the data pipeline falls two minutes behind.** That gap is the
entire point of this document: `http_req_failed` cannot see an asynchronous backlog.

### Bottleneck #1
Lambda **account** concurrency limit = 10 (not a function-level reserved concurrency — the account
quota). Two independent methods agreed on the ceiling: back-calculating from peak queue depth gave
119.2 msg/s (Day 33); counting `NumberOfMessagesDeleted` directly gave 112.7 msg/s (Day 34) —
**5.5% apart, so the ceiling is a measurement, not an estimate.**

## 3. Optimization Round 1

### 3.1 Hypothesis

**H1.** Of the 61.6 ms per-message cost, DynamoDB server-side time accounts for only 4.95 ms
(PutItem 2.53 ms + UpdateItem 2.42 ms, from `SuccessfulRequestLatency`, which excludes network and
client). The remaining **92% is client-side CPU** — and at 128 MB the function gets
**128/1769 = 7.24% of one vCPU**. *Falsifiable threshold: per-message cost must drop >= 30%.*

**H2.** The 63.2 ms gap between `Duration` and `ClickEventProcessingDuration` is dominated by the
one synchronous `cloudwatch.put_metric_data` call, which sits **outside** the timed region
(`duration_ms` is computed at `lambda_function.py:104`, the metric call was after it).
*Falsifiable threshold: fixed overhead must drop below 15 ms.*

### 3.2 Change

| # | Change | Blast radius | Rollback |
| --- | --- | --- | --- |
| D | Lambda memory **128 -> 512 MB** (one CLI call, no code, no IAM) | none | `--memory-size 128`, seconds |
| B' | `put_metric_data` -> **EMF** (structured JSON on stdout, zero API calls) | metric could silently vanish | revert + redeploy |

Deployed together in one round (Day 36, commit `c9d8331`) — **and still separately attributable**,
because two metrics decompose the effect:

| Metric | Covers | Affected by D | Affected by B' |
| --- | --- | --- | --- |
| `ClickEventProcessingDuration` | the record loop only | yes | **no** (EMF is emitted after the timer stops) |
| `Duration` - `ClickEventProcessingDuration` | fixed overhead | yes | yes |

Two changes, two independent metrics, one round. Not deployed: `TransactWriteItems` (touches
idempotency semantics) and `BatchSize 10 -> 100` (see §5).

### 3.3 Result

Same ruler: 250 RPS x 4 min, warm, same 10 short codes, `baseline.js` unchanged.
Measured 2026-08-27, window `16:20:00Z-16:25:00Z` (`--period 300`).

| Metric | Before | After | Delta | Threshold |
| --- | --- | --- | --- | --- |
| **Per-message cost** | 61.6 ms | **9.8 ms** | **-84%** | <= 43.1 ms (H1) |
| **Fixed overhead / invocation** | 63.2 ms | **1.8 ms** | **-97%** | < 15 ms (H2) |
| SQS backlog peak | 13,980 | **0** | -100% | ~ 0 |
| Oldest-message age peak | 125 s | **4 s** | -97% | ~ 0 |
| Messages sent / received / deleted | 41,842 / 29,312 / 29,212 | **42,010 / 42,010 / 42,010** | 0 stuck | equal |
| Lambda throttle **rate** | 14.2% | **3.2%** | -77% | - |
| `ConcurrentExecutions` avg | 8.0 | **3.4** | -57% | - |
| Async throughput | 112.7 msg/s | **>= 323.1 msg/s** | **>= 2.87x** | 200-400 |

**Both hypotheses hold.** H1 predicted >= 30%; we measured 84%. H2 predicted < 15 ms; we measured
1.8 ms (a Day 36 smoke test on an idle queue had already measured 1.6 ms — two independent
scenarios, 11% apart).

### What did NOT move, and why that is the point

| Metric | Before | After |
| --- | --- | --- |
| k6 p50 | 165.2 ms | 169.6 ms (+2.6%) |
| k6 p95 | 244.0 ms | 252.5 ms (+3.5%) |
| `http_req_failed` | 0.00% | 0.00% |
| Achieved RPS | 249.29 | 248.98 |

**k6 exercises the synchronous path; the change was on the asynchronous path.** This was written
down *before* the run (`findings.md` §6), not rationalised afterwards. If a client-side percentile
had improved here, that would have been evidence the model was wrong.

### Two absolute numbers that got worse, and how to read them
* `Throttles` went **489 -> 643 (+31%)**. Batch size collapsed from 9.90 to 2.19 (a drained queue
  yields smaller batches), so invocations rose 6.5x — more chances to be throttled. The **rate**
  fell 14.2% -> 3.2%, and every throttle was absorbed by the ESM retry: sent = received = deleted.
* `ConcurrentExecutions` **Maximum stayed at 10**. Only the **Average** moved (8.0 -> 3.4). Bursts
  still touch the account ceiling; the system no longer *sits* on it. Maximum and Average are
  different statistics, and only the Average tells you how far from the ceiling you are.

### 3.4 Analysis: Little's Law, twice

Little's Law: **L = lambda x W** (items in system = arrival rate x time in system).

#### Station 1 — the Lambda consumer, where L is capped from outside

The account concurrency limit pins **L <= 10**. That is not a tuning knob we control, so with L
fixed, **W is the only lever**:

    max throughput = (L / W) x batch_size

| | W (`Duration` avg) | batch | Ceiling = (10/W) x batch | Measured |
| --- | --- | --- | --- | --- |
| Before | 674.3 ms | 9.90 | 146.8 msg/s | 112.7 msg/s (77% of ceiling) |
| After  | **23.3 ms** | 2.19 | **940.7 msg/s** | >= 323.1 msg/s (never saturated) |

The 23% gap between ceiling and measurement in the *before* row is the throttle duty cycle: 14.2%
of invocation attempts were rejected and retried, and a retried attempt does not consume W but does
consume wall-clock.

**We never raised the limit. We cut W by 29x, and throughput rose 2.87x on the same limit of 10.**
That is the whole optimisation in one sentence — and it is exactly what Little's Law predicts when
you cannot move L.

> Cross-check: L = lambda x W = 9.87 inv/s x 0.674 s = 6.66, while CloudWatch reported an average
> concurrency of 8.0. The ~20% excess is environment-occupied time that billed `Duration` does not
> include (poll + init + throttle back-off). Useful to know before trusting `ConcurrentExecutions`
> as a proxy for utilisation.

#### Station 2 — a thread pool that really is too small (bottleneck #2)

The producer side publishes each click event through a bounded Spring executor
(`config/AsyncConfig.java:20-24`):

    corePoolSize = 2, maxPoolSize = 4, queueCapacity = 100, CallerRunsPolicy

**A Java `ThreadPoolExecutor` only creates threads beyond `corePoolSize` once the queue is full.**
With a 100-slot queue, steady state runs on **two threads**, not four. Applying L <= c:

| Round | Rate per task | Threads serving | Overflow to caller | Implied W per `SendMessage` |
| --- | --- | --- | --- | --- |
| 250 RPS | 42,010 / 300 s / 2 = **70.0 ev/s** | c = 2 | **0.32%** | 2 / 69.8 = **28.7 ms** |
| 500 RPS | 97,028 / 300 s / 2 = **161.7 ev/s** | c = 4 (queue full) | **27.75%** | 4 / 116.8 = **34.2 ms** |

Stability requires `lambda <= c / W`. At 250 RPS that needs W <= 2/70.0 = **28.6 ms** — just met.
At 500 RPS it needs W <= 4/161.7 = **24.7 ms** — missed, so 27.75% of publishes fell back onto the
Tomcat request thread under `CallerRunsPolicy`.

The fingerprint is a **bimodal** server-side latency: ALB `TargetResponseTime` p50 **12.2 ms** but
p95 **830 ms** and p99 **1,471 ms**. Requests that did not overflow are still fast; the 27.75% that
did pay a full cross-network `SendMessage` inline. ECS CPU sat at **95% average / 100% maximum**.

That also explains why k6 reported `vus_max` hitting its 300 cap and 7.3% dropped iterations: those
are **consequences**, not causes. Server-side p95 (830 ms, measured by the ALB, excluding the
client's 158 ms RTT) plus RTT gives 988 ms against k6's observed p95 of 1,022 ms — **3.4% apart**.
A load-generator-bound test would have shown fast server-side latency with dropped iterations; we
saw the opposite.

> Caveat: the two W values above are **derived from counters, not measured directly**, and they
> assume the pool ran at c = 2 and c = 4 respectively. Direct measurement (a timer around
> `SqsClient.sendMessage`) is in §5.

## 4. Lessons Learned

**1. An optimisation can change what a metric means.** The pre-registered threshold was written on
`ClickEventProcessingDuration` average (`<= 427.8 ms`). But that metric is per-*invocation*, so it
scales with batch size — and draining the queue is precisely what shrinks the batch (9.90 -> 2.19).
The threshold would have passed easily for the wrong reason. The batch-invariant quantity is
**per-message cost** = `Sum / NumberOfMessagesReceived`, and every number above uses it.
*Rule learned: write thresholds on a quantity that is invariant to the thing you are changing.*

**2. Absolute counts and rates can point in opposite directions.** `Throttles` rose 31% while the
throttle rate fell 77%, for the same reason as (1).

**3. `Maximum` and `Average` are not interchangeable.** `ConcurrentExecutions` Maximum stayed at 10
in both runs; only the Average moved (8.0 -> 3.4). Reading Maximum alone would have said "nothing
changed"; reading Average alone would have missed that bursts still reach the ceiling.

**4. The pre-registered prediction table was not edited afterwards.** Three of its rows turned out
to be flawed (see `load-test/optimization-plan.md` §6.1). They are recorded as errata next to the
original, not corrected in place — a prediction table you are allowed to edit is not a prediction.

## 5. Future Work

Ranked by expected value, not by ease.

1. **`AsyncConfig.corePoolSize` 2 -> 16** (or shrink `queueCapacity` so the pool grows sooner).
   One line, small blast radius, no semantic change. It is also a **causally separating experiment**:
   if overflow drops to ~0 *and* ECS CPU falls, the pool was the cause; if CPU stays at 95%, it was not.
2. **Measure `SendMessage` latency directly** (a Micrometer timer around
   `ClickEventPublisher.publish`) to replace the derived 28.7 / 34.2 ms in §3.4 with a measurement.
3. **Explain a still-open anomaly**: at identical 250 RPS with byte-identical Java
   (`git diff 6f8ea8a c9d8331 -- shortener/` is empty), ECS CPU went 39.9% -> 66.8% and ALB p95
   6.3 ms -> 63.8 ms between Day 34 and Day 37. Task count, task size and warm-up were all
   verified identical. Remaining suspect: base-image drift between two CI rebuilds.
4. **Raise the Lambda account concurrency limit 10 -> 100** (a support case; `url-shortener-dev`
   lacks `servicequotas` permission). Little's Law says this multiplies the ceiling by 10 — but
   §3.4 station 2 says the synchronous path would break first.
5. **`GET /` should return 404, not 500.** Seven ALB 5XX during the 500 RPS run were internet
   scanners hitting `/` and `/.well-known/security.txt`, turned into 500 by the global handler.
6. **Skip the deploy job on documentation-only pushes** — CI currently rebuilds the image and
   registers a new task-definition revision for a two-file Markdown change.
7. **Move the load generator into the same region.** 158 ms of RTT is 52% of the client-side p99;
   any client-side percentile work is invisible underneath it.
8. **Not planned: `BatchSize 10 -> 100`.** Its entire benefit was amortising the per-invocation
   fixed overhead, and B' already cut that from 63.2 ms to 1.8 ms. Measured per-message cost rose
   from 9.81 ms at batch 2.19 to 10.28 ms at batch 4.10 — the slope is **positive**.

Raw data and the full experiment log: [`load-test/iteration-1-results.md`](load-test/iteration-1-results.md),
[`load-test/findings.md`](load-test/findings.md), [`load-test/optimization-plan.md`](load-test/optimization-plan.md).
