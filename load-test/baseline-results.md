# URL Shortener · Baseline Load Test 結果（Day 33 · first run）

> 本檔依 `plan/week7/day33.md` block 2.6 的骨架填寫。**所有數字都是從 `load-test/results-*.json`、CloudWatch、CloudWatch Logs Insights、DynamoDB 實際查出來的**，不是估計值。
> ⚠️ 與 day33.md 的差異：計畫寫的日期是 2026-07-08，**實際執行日是 2026-08-22**；本檔以實際執行日為準。

---

## 0. 量測條件（★ 沒有這一節，下面的數字沒有意義）

| 項目 | 值 |
| --- | --- |
| 日期 | **2026-08-22（Sat）** |
| 壓測工具 | k6 **v2.2.0**（commit/00a9a1b7f5, go1.26.5, **darwin/arm64**，本機 Apple M4 / 10 核 / 24 GB）|
| 壓測腳本 | `load-test/baseline.js`（**working tree 版本，尚未 commit**；base commit = `a88da35` "Day32: add k6 baseline load test script"）|
| 壓測端位置 | 本機（UTC-7），目標在 **ap-east-2** ⇒ **RTT ≈ 158 ms**（實測地板：Run 1 `http_req_duration.min` = **158.589 ms**、Run 2 = **157.715 ms**）|
| 負載模型 | `constant-arrival-rate`（**開放模型**）、`timeUnit 1s`、`preAllocatedVUs 50` / `maxVUs 200`、每次 iteration `sleep(0.1)` |
| 讀寫比（實測）| Run 1 **69.2% GET / 30.8% POST**；Run 2 **69.95% GET / 30.05% POST**（腳本目標 70/30，`Math.random()` 抽樣）|
| 短碼數量 | **10 個**真實短碼（`q6w83nb, qm90t53, qspq70c, rrrtxm4, 9fd6noh, x5l1h6j, ivu46b2, 25esld0, am6vckv, 1mtdm5m`）|
| 服務端 | ECS Fargate **2 台** × **0.5 vCPU（512）/ 1 GB（1024）**、task def **`:10`**、image `a88da354c4d1836ad554e8664678d6d6e0a547c8`、log driver `awslogs` |
| 快取 | ElastiCache Redis **7.0.7**、`cache.t4g.micro`、**單節點**、應用層 TTL **1 小時**（`UrlService.CACHE_TTL`）|
| 資料庫 | DynamoDB **on-demand**（`UrlMappings` / `ClickAnalytics` / `ProcessedEvents`）|
| 非同步 | SQS `url-click-events`（visibility 30 s、redrive `maxReceiveCount=3` → DLQ）→ Lambda `url-click-processor`（python3.12、128 MB、timeout 5 s、**ESM batchSize 10 / batchingWindow 0 / ReportBatchItemFailures**）|
| **⚠️ Lambda 並發** | **帳號上限 `ConcurrentExecutions = 10`、`UnreservedConcurrentExecutions = 10`**（實測 `get-account-settings`）；函式**沒有**設 reserved concurrency |
| Threshold | `http_req_duration: p(95)<500ms, p(99)<1000ms`；`http_req_failed: rate<0.01`；`{name:GET…} p(95)<500`；`{name:POST…} p(95)<800` |

### 時間窗（★ 全部 UTC；本機是 UTC-7）

| | 開始（UTC）| 結束（UTC）| 長度 |
| --- | --- | --- | --- |
| **Run 1（100 RPS）** | 2026-08-22 **16:09:04Z** | **16:14:08Z** | 304 s |
| **Run 2（500 RPS）** | 2026-08-22 **≈17:54:44Z** | **17:59:48Z** | ≈304 s |

> CloudWatch 對照一律用對齊到分鐘的窗：Run 1 = `16:09:00Z–16:15:00Z`、Run 2 = `17:54:00Z–18:00:00Z`。

### ⚠️ 這組數字的已知限制（★ 誠實寫下來，才知道它能回答什麼問題）

1. **延遲數字含 ≈158 ms 的跨太平洋 RTT。** 服務端真實延遲請看 CloudWatch `TargetResponseTime`（單位是**秒**）：Run 2 的 p50 是 **2.34 ms**，而 k6 量到 165 ms —— **差額 163 ms 全部是網路**。改善方向：把壓測端搬到同 region 的 EC2。
2. **這是「Redis 命中路徑」的 baseline，不是「DynamoDB 讀路徑」的 baseline。** 兩輪加起來只有 **39 次** cache miss（Run 1 窗內 0、Run 2 窗內 23、Run 1 之前的暖機 16），命中率 ≈ **99.98%**，DynamoDB `GetItem` 幾乎沒被走到。
3. **★★ Run 1 的 ALB 計數不乾淨。** ALB 窗內 `RequestCount = 30469`，但 k6 只送了 **29975**，多出來的 **494** 來自 **16:08 那一波暖機/中止的請求**（16:08 有 377 筆、16:09 有 122 筆 `HTTPCode_ELB_4XX`＝客戶端提前斷線）。⇒ **Run 1 的伺服器端數字有 ±1.6% 的雜訊；Run 2 完全乾淨（見 §3）。**
4. **ALB 是 internet-facing**，公網掃描機器人會混進計數。全天有 `Target_5XX = 11`、`ELB_5XX = 40`，但**兩個壓測窗內 5XX 都是 0**（雜訊都落在窗外的閒置時段）。
5. **沒有 autoscaling**，台數是手動固定的 2 台，全程 `desired = running = 2`、兩個 target 都 `healthy`。
6. **★ `dropped_iterations` 兩輪都 > 0**（Run 1 = 26、Run 2 = **2337**）⇒ 嚴格說兩輪的「達成 RPS」都沒有 100% 打滿（見 §1 註解）。

---

## 1. 兩輪結果總表（k6 客戶端視角）

| 指標 | Run 1（100 RPS）| Run 2（500 RPS）| 倍率 |
| --- | --- | --- | --- |
| 時間窗（UTC）| 16:09:04 → 16:14:08 | ≈17:54:44 → 17:59:48 | |
| 目標 RPS / 實際 `http_reqs` rate | 100 / **99.83** | 500 / **491.72** | 4.93× |
| `iterations` | **29 975** | **147 664** | 4.93× |
| **`dropped_iterations`** | **26**（0.09%）| **2 337**（1.55%）| ★ 見下方註解 |
| `vus`（peak）/ `vus_max`（peak）| 68 / 76 | **200 / 200**（★ 打到 `maxVUs` 天花板）| |
| **`http_req_duration` min**（＝網路地板）| **158.589 ms** | **157.715 ms** | — |
| avg | 185.322 ms | 190.937 ms | 1.03× |
| **p50** | **166.662 ms** | **165.431 ms** | 0.99× |
| p90 | 234.566 ms | 238.219 ms | 1.02× |
| **p95** | **246.869 ms** | **250.853 ms** | 1.02× |
| **p99** | **285.117 ms** | **516.615 ms** | **1.81×** |
| **max** | 1 128.839 ms | **2 113.107 ms** | 1.87× |
| **p99 / p50 倍率** | **1.71** | **3.12** | ★ 尾巴變長了 1.8 倍 |
| `http_req_waiting` p95 | 246.755 ms | 250.765 ms | |
| `http_req_failed` | **0.00%**（0 / 29 975）| **0.00%**（0 / 147 664）| |
| `checks_succeeded` | **100%**（29 975 / 29 975）| **100%**（147 664 / 147 664）| |
| GET p95（`{name:GET /api/v1/{code}}`）| 245.475 ms | 250.709 ms | |
| GET p99 / max | 280.922 / 741.624 ms | **559.716** / 2 113.107 ms | |
| POST p95（`{name:POST /api/v1/shorten}`）| 249.741 ms | 251.179 ms | |
| POST p99 / max | 294.562 / 1 128.839 ms | 397.166 / 1 663.865 ms | |
| `iteration_duration` p95 | 348.040 ms | 351.327 ms | |
| **exit code** | **0（全綠）** | **0（全綠）** | ★ 見下方註解 |

**★ 三個必須寫清楚的註解：**

1. **`dropped_iterations` 的意義**：Run 2 有 **2 337 次 iteration 根本沒送出去**（k6 的 200 個 VU 全部忙碌 ⇒ 排程器丟棄）。所以 Run 2 的實際到達率是 **491.7 RPS 而不是 500**，而且**被丟掉的那些通常是「最慢的時刻」**——換句話說 **Run 2 的延遲數字偏樂觀**。★ `vus_max` 打到 200（＝腳本設的 `maxVUs`）就是直接證據；下一輪要把 `maxVUs` 調高到 ≥ 300 才量得準。
2. **exit code 0 ≠ 系統健康**：兩輪**所有 threshold 都通過**（`http_req_duration` p95 250.9 < 500、p99 516.6 < 1000、失敗率 0）。但同一時間 **SQS 積了 65 090 則訊息、最舊訊息落後 9 分 41 秒**（§2）。⇒ **★★ 今天最重要的一課：k6 只看得到同步回應那一段，非同步鏈路整個崩掉它一聲都不會吭。**
3. **p50 幾乎沒動（166.7 → 165.4 ms）**：因為 158 ms 的 RTT 把服務端的變化整個蓋掉了。**唯一穿透 RTT 的訊號是 p99（285 → 517 ms）和 max（1.13 → 2.11 s）。**

---

## 2. 伺服器端對照（CloudWatch）

| 指標 | Run 1（16:09–16:15Z）| Run 2（17:54–18:00Z）| 昨天（Day 32，~35 RPS，1 台）|
| --- | --- | --- | --- |
| ALB `RequestCount`（總）| **30 469**（含 494 筆非 k6，見 §0 限制 3）| **147 664**（★ 與 k6 完全一致）| 1 047 |
| ALB 2XX / 3XX / 4XX / 5XX | 9 337 / 21 010 / 0 / **0** | 44 376 / 103 288 / 0 / **0** | 329 / 718 / 0 / 0 |
| ALB `HTTPCode_ELB_4XX` | **122**（16:08 那波中止留下的斷線）| **0** | — |
| **`TargetResponseTime` p50（秒）** | **0.002 821** | **0.002 344** | 0.003 36 |
| p90（秒）| 0.011 118 | 0.006 844 | — |
| **p95（秒）** | **0.042 998** | **0.044 481** | 0.013 28 |
| **p99（秒）** | **0.088 280** | **0.332 206** | 0.060 87 |
| p99.9（秒）| **7.651 390**（★ 16:09 那一分鐘的暖機）| 0.964 668 | — |
| avg / max（秒）| 0.036 417 / **9.215 778** | 0.013 618 / 1.953 681 | — / 0.074 193 |
| SampleCount | 30 347 | 147 664 | 1 047 |
| **★ k6 p50 − CloudWatch p50×1000** | **163.8 ms** | **163.1 ms** | ★ 應該 ≈ RTT，**完全吻合** |
| ECS CPU avg / max（%）| 35.01 / **99.32** | **46.55** / **94.92** | 5.52 / 39.37 |
| ECS Mem avg / max（%）| 30.07 / 34.62 | 35.45 / 37.89 | — / 27.05 |
| `CacheHit` Average / Sum / SampleCount | **1.000 0** / 21 166 / 21 166 | **0.999 78** / 103 265 / **103 288** | 1.0 / — / 718 |
| **SQS 深度 max（窗內）** | **3** | **54 699** 🚨（★ 窗外峰值 **65 090**）| 0 |
| **SQS 最舊訊息年齡 max（窗內）** | **4 s** | **165 s** 🚨（★ 窗外峰值 **581 s**）| 0 |
| SQS sent / received / deleted | 21 213 / 21 217 / 21 224 | **103 288 / 36 488 / 36 428** 🚨 | — |
| **Lambda Invocations / Errors / Throttles** | 15 098 / 0 / **609** 🚨 | 3 653 / 0 / **563** 🚨 | 485 / — / 0 |
| **Lambda ConcurrentExecutions max / avg** | **10（＝帳號上限）** / 7.27 | **10（＝帳號上限）** / 7.87 | 8 |
| Lambda Duration avg / max（ms）| 130.19 / 813.00 | **638.05** / 1 133.87 | 143.4 / 660.3 |
| 每次 invocation 平均訊息數 | 21 217 / 15 098 ≈ **1.41** | 36 488 / 3 653 ≈ **9.99**（★ batch 打滿）| — |
| `ClickEventProcessingDuration` avg / max / n | 70.32 / 661.92 ms / 15 100 | **578.59** / 1 047.27 ms / 3 647 | — |
| DLQ 深度 / alarm 狀態 | **0** / OK | **0** / OK | 0 / OK |
| Redis CPU / EngineCPU / CurrConnections | 2.35% / **0.71%** / 7 | 3.00% / **1.27%** / 7 | 4.39% / 0.32% / 6 |
| Redis CacheHits / CacheMisses / Evictions | 21 347 / 16 / **0** | 92 181 / 23 / **0** | — |

### 2a. 逐分鐘：哪一格先歪

**ECS CPU（Average / Maximum，%）**

| 分鐘（本機 UTC-7）| Run 1 avg / max | 分鐘 | Run 2 avg / max |
| --- | --- | --- | --- |
| 09:09 | **77.37 / 99.32** ← 暖機 | 10:54 | 5.14 / 18.36 |
| 09:10 | 47.26 / 56.65 | 10:55 | **71.00 / 94.92** ← 峰值 |
| 09:11 | 30.49 / 38.78 | 10:56 | 54.87 / 63.81 |
| 09:12 | 29.62 / 44.89 | 10:57 | 48.42 / 50.46 |
| 09:13 | 19.88 / 26.80 | 10:58 | 52.57 / 57.62 |
| 09:14 | 5.43 / 16.90 | 10:59 | 47.30 / 54.22 |

**ALB `TargetResponseTime`（秒）p50 / p99 / max**

| 分鐘 | Run 1 | 分鐘 | Run 2 |
| --- | --- | --- | --- |
| 09:09 | 0.0063 / **6.4635** / **9.2158** | 10:54 | 0.0508 / **1.2435** / **1.9537** |
| 09:10 | 0.0030 / 0.0588 / 0.0795 | 10:55 | 0.0030 / 0.3996 / 1.0872 |
| 09:11 | 0.0026 / 0.0398 / 0.0782 | 10:56 | 0.0023 / 0.2641 / 1.0575 |
| 09:12 | 0.0025 / 0.0491 / 0.0738 | 10:57 | 0.0022 / 0.0095 / 0.0777 |
| 09:13 | 0.0024 / 0.0095 / 0.0650 | 10:58 | 0.0022 / 0.1033 / 1.0698 |
| 09:14 | 0.0024 / 0.0087 / 0.0189 | 10:59 | 0.0022 / 0.0089 / 0.0785 |

> ★ **兩輪的第一分鐘都是最慢的一分鐘，而且慢得很誇張**（Run 1 max 9.2 s、Run 2 max 1.95 s），之後 p50 立刻掉到 2.2–3.0 ms 並且**一路平穩到結束**。
> ★★ 這代表 **CPU 不是今天的瓶頸**：500 RPS 下 ECS CPU 平均只有 46.55%，兩台總共 1 vCPU 還有一半餘裕，`TargetResponseTime` p50 甚至**比 100 RPS 那輪更低**（2.34 ms vs 2.82 ms，JIT 更熱）。

**SQS 佇列深度 / 最舊訊息年齡（Run 2 起，含跑完之後的排空）**

| 分鐘（UTC-7）| 深度 | 最舊訊息年齡 | 說明 |
| --- | --- | --- | --- |
| 10:54 | 0 | 0 s | 壓測開始 |
| 10:55 | 2 752 | 10 s | 已經開始堆 |
| 10:56 | 16 655 | 53 s | |
| 10:57 | 20 908 | 64 s | |
| 10:58 | 36 886 | 112 s | |
| 10:59 | **54 699** | **165 s** | 壓測結束（10:59:48）|
| **11:00** | **65 090** ← **峰值** | 221 s | ★ 峰值在**壓測結束之後**才出現 |
| 11:02 | 48 820 | 316 s | 開始排空 |
| 11:05 | 37 380 | 381 s | |
| 11:08 | 10 340 | 531 s | |
| 11:09 | 728 | **581 s** ← 年齡峰值（9 分 41 秒）| |
| **11:10** | **0** | 0 s | 排空完成 |

> 🚨 **產生 103 288 則，只消化掉 36 428 則 ⇒ 積壓 66 860 則，花了整整 10 分鐘才排空。**
> ✅ **但 DLQ 全程 = 0，`ClickAnalytics` 一則都沒少（§3）⇒ 沒有掉資料，只是「遲到了 10 分鐘」。**

---

## 3. 三個獨立計數器交叉驗證

| | Run 1（16:09–16:15Z）| Run 2（17:54–18:00Z）| **全天（2026-08-22）** |
| --- | --- | --- | --- |
| ALB 3XX 總數（＝GET 成功數）| 21 010 | **103 288** | 124 539 |
| `CacheHit` SampleCount 總和 | 21 166 | **103 288** | **124 652** |
| SQS `NumberOfMessagesSent` | 21 213 | **103 288** | **124 652** |
| `ClickAnalytics` 今日 clickCount 總和 | — | — | **124 652** |
| **三者一致？** | ❌ 差 156 / 203（★ 見下）| ✅ **三個數字一模一樣** | ✅ **SQS = CacheHit = ClickAnalytics = 124 652** |

**★ Run 2 是完美的一輪**：k6 的 `GET 302` check = 103 288 = ALB `3XX` = `CacheHit` SampleCount = SQS `NumberOfMessagesSent`。**四個完全獨立的計數器完全對上** ⇒ 讀路徑上**沒有任何一環靜默失敗**。

**★ Run 1 的差額已經查清楚了，不是 bug**：`CacheHit`（21 166）和 SQS（21 213）都比 ALB 3XX（21 010）**多**，而不是少。原因是 **16:08 那波暖機/中止請求的尾巴落在 16:09 這一分鐘裡**——它的請求被應用程式處理完（發了 metric、送了 SQS），但客戶端提前斷線，ALB 記成 `ELB_4XX`（122 筆）而不是 `Target_3XX`。

**★ 全天總帳也對得起來**：ALB 3XX（124 539）+ ELB_4XX 裡屬於 GET 的部分（129 筆中的 113）= **124 652** = SQS sent = `CacheHit` SampleCount = `ClickAnalytics` 總和。

**`ClickAnalytics` 全天實測（10 個短碼各自的 2026-08-22 clickCount）**

| shortCode | clickCount | shortCode | clickCount |
| --- | --- | --- | --- |
| `ivu46b2` | 12 604 | `q6w83nb` | 12 404 |
| `qspq70c` | 12 577 | `9fd6noh` | 12 376 |
| `rrrtxm4` | 12 555 | `am6vckv` | 12 354 |
| `qm90t53` | 12 546 | `x5l1h6j` | 12 236 |
| `25esld0` | 12 512 | **合計** | **124 652** |
| `1mtdm5m` | 12 488 | | |

> ✅ **`ClickAnalytics` 增量 = SQS sent，一則不多一則不少** ⇒ SQS → Lambda → DynamoDB 這條鏈路 **`ProcessedEvents` 冪等去重也沒有誤殺**（`Errors = 0`、DLQ = 0、`skipped` 沒造成漏計）。

---

## 4. Top 3 觀察 + 假設原因（★ 明天 findings.md 的原料）

### 觀察 ① 🚨 **瓶頸 #1：Lambda 帳號並發上限 10 —— 非同步消費端只有生產端的 1/3**

**數字 + 出處**：
- `AccountLimit.ConcurrentExecutions = 10`、`UnreservedConcurrentExecutions = 10`（`aws lambda get-account-settings`）。
- Run 2 的 **每一分鐘** `ConcurrentExecutions` 的 Maximum 都是 **10.0**（17:53Z–18:12Z 逐分鐘查過，一次都沒低於 10）。
- `Throttles` 全程 93–141 次/分鐘（Run 2 窗內合計 **563**，Run 1 窗內就已經 **609**）。
- 生產速率 = 103 288 則 / 300 s = **344 則/s**；消費速率（用排空段回推：11:00 的 65 090 → 11:09 的 728，9 分鐘）= **≈ 119 則/s**。
- 缺口 ≈ 225 則/s × 300 s = **≈ 67 500 則** ≈ 實測積壓 **66 860 則**（sent − deleted）。✅ **數字自洽。**

**假設**：消費端天花板 = `並發 10 ÷ 單次 invocation 638 ms × 每批 10 則` ≈ **157 則/s**（理論），扣掉 invocation 之間的排程與 throttle 退避後實測 **≈ 119 則/s**。生產端 344 則/s ⇒ **必然積壓，而且積壓速度是線性的**。

**★ 為什麼 Duration 從 130 ms 變成 638 ms**：不是 Lambda 變慢，是 **batch 被填滿了**。Run 1 每次 invocation 只拿到 1.41 則（佇列是空的，`batchingWindow = 0` ⇒ 有幾則就拿幾則）；Run 2 佇列永遠有幾萬則 ⇒ 每次都拿滿 10 則。**每則訊息的處理成本其實幾乎沒變：70.32/1.41 = 49.9 ms → 578.59/9.99 = 57.9 ms。**

**明天要用什麼證據驗證**：
- 把 `ScalingConfig.MaximumConcurrency` 或 reserved concurrency 設起來**沒有用**（上限是**帳號級**的 10），要開 support ticket 提額；**Day 34 可以先做的**是把「每則 57.9 ms」壓下去——handler 對每則訊息做了 **2 次序列化的 DynamoDB 呼叫**（`ProcessedEvents.put_item` 條件寫入 + `ClickAnalytics.update_item`）。改成 `batch_writer` / 並行化，理論上可以把每則成本砍半 ⇒ 消費能力翻倍到 ≈ 240 則/s。
- 驗證方式：同樣 500 RPS 跑一輪，看 `ClickEventProcessingDuration` 的 avg 是否從 578 ms 掉到 ~300 ms，以及 SQS 深度峰值是否從 65 090 掉一半。

---

### 觀察 ② 🚨 **`AsyncConfig.clickEventExecutor` 的 `CallerRunsPolicy` 確實在壓力下把工作退回 Tomcat 執行緒（但沒有想像中致命）**

**數字 + 出處**：
- `AsyncConfig`：`corePoolSize = 2`、`maxPoolSize = 4`、`queueCapacity = 100`、`CallerRunsPolicy`。
- **每個 GET 丟兩個非同步任務**：`ClickEventPublisher.publish()`（SQS `SendMessage`）+ `MetricPublisher.recordCacheHit()`（CloudWatch `PutMetricData`），兩個都標 `@Async("clickEventExecutor")`。
- Run 2 = 103 288 GET / 300 s = **344 GET/s × 2 = 688 任務/s**，而池子只有 **4 條執行緒**。
- **實測沒有任何 `RejectedExecutionException`**（Logs Insights 掃過整個窗，`ERROR|Exception|RejectedExecution|failed to publish` 只命中 4 筆短碼碰撞的 WARN）——**這正是 `CallerRunsPolicy` 的特徵：它不丟例外，它讓呼叫端自己跑。**
- **`CacheHit` SampleCount = 103 288 = ALB 3XX，一筆都沒少** ⇒ 兩個任務**全部都被執行了**，只是有一部分是在 Tomcat 的 `http-nio-8080-exec-*` 執行緒上同步執行的。

**假設**：`CallerRunsPolicy` 把 SQS/CloudWatch 的網路呼叫退回請求執行緒 ⇒ **非同步解耦在壓力下失效**，這就是 `TargetResponseTime` p99 從 **0.088 s（Run 1）漲到 0.332 s（Run 2，3.8×）**、max 反覆碰到 **1.0–1.07 s** 的來源。但因為 SQS `SendMessage` 本身只要幾毫秒，**p50 完全沒受影響（2.82 → 2.34 ms）**，只有尾巴變胖。

**明天要用什麼證據驗證**：
- 直接證據：把 log pattern 加上執行緒名，統計 `published click event` 這行出現在 `click-async-*` vs `http-nio-8080-exec-*` 的比例。**如果 `CallerRunsPolicy` 真的在作用，Run 2 應該有壓倒性多數落在 `http-nio-*`。**（目前 log 有印執行緒名，例如 `[io-8080-exec-78]`，Insights 一條 `parse` 就能分群。）
- 反向實驗：把 `maxPoolSize` 調到 32、`queueCapacity` 調到 1000 再跑一次 500 RPS，看 `TargetResponseTime` p99 是否從 0.332 s 掉回 0.1 s 以下。

---

### 觀察 ③ ⚠️ **短碼產生器的實際碰撞率遠高於「隨機 36^7」該有的水準**

**數字 + 出處**：
- Run 1：9 230 次 POST → **3 筆** `Short code collision on attempt 1 of 3`；Run 2：44 376 次 POST → **4 筆**。
- `UrlService.generateShortCode()` = `Long.toString(System.nanoTime(), 36)` 取**後 7 碼**——**這不是亂數，是時間戳的低位**。
- 如果真的是 36^7 ≈ 7.84×10¹⁰ 的均勻亂數，44 376 次寫入的期望碰撞數 ≈ 44376² / (2 × 7.84×10¹⁰) ≈ **0.013 次**。**實測 4 次 ≈ 期望值的 300 倍。**

**假設**：`nanoTime()` 的有效解析度遠低於 1 ns（實際可能是 µs 級），而且取後 7 碼等於 `nanoTime mod 36^7`（≈ **每 78 秒就繞回原點一次**）⇒ 真正的碼空間比 36^7 小好幾個數量級，兩台 task 各自的 `nanoTime` 又互不相關 ⇒ 碰撞機率被放大。

**影響評估（今天不修）**：**目前完全無害**——`MAX_ATTEMPTS = 3` 的 retry 全部第一次就成功，7 筆碰撞沒有造成任何一個失敗請求（`http_req_failed = 0.00%`、`POST 201` check 100% 通過）。但**寫入量再大一個數量級就會開始出現「三次都撞」→ `ShortCodeGenerationException` → 500**。

**明天要用什麼證據驗證**：把 `generateShortCode()` 換成 `SecureRandom` 抽 7 碼 base62，再跑一次同樣的 500 RPS，看碰撞 WARN 是否歸零。

---

### ★ 已經寫在紙上的預設嫌疑犯 —— 今天的實測結論

| 嫌疑犯 | 今天的實測結論 |
| --- | --- |
| **`AsyncConfig.clickEventExecutor`：core 2 / max 4 / queue 100 / `CallerRunsPolicy`**，每個 GET 丟兩個任務 | ⚠️ **成立，但不是頭號兇手**。沒有掉任何任務（`CacheHit` SampleCount 完全對上），代價是 `TargetResponseTime` p99 漲 3.8 倍。⇒ **觀察 ②** |
| **Lambda 帳號並發上限 = 10** | 🚨 **完全成立，這就是 bottleneck #1**。全程貼在 10，throttle 563–609 次/窗，佇列積到 65 090、落後 9 分 41 秒。⇒ **觀察 ①** |
| **每個 GET 一次 `PutMetricData`**（`MetricPublisher`），例外被吞成 `warn`（靜默失敗）| ✅ **今天沒有失敗**：log 裡 **0 筆** `failed to publish CacheHit metric`，`CacheHit` SampleCount 與 ALB 3XX **完全相等**。⇒ **這條路徑目前是可信的**（但它仍然是每秒 344 次 API 呼叫，是 Week 8 的成本/限流風險） |
| **task 只有 0.5 vCPU，2 台總共 1 vCPU** | ❌ **不成立**。500 RPS 下 CPU 平均只有 **46.55%**，峰值 94.92% 只出現在第一分鐘的暖機。**CPU 還有一半餘裕。** |
| **每個 GET 印 3 行 log**（`com.urlshort.shortener` 是 `debug`），走 `awslogs` driver | ⚠️ **量級確認**：Run 2 兩個 stream 合計 **398 620 行 / 5 分鐘**（199 344 + 199 276）≈ **1 329 行/秒**。目前沒造成可見延遲，但這是 CloudWatch Logs 的實質成本來源。 |
| **ElastiCache 是不是瓶頸？** | ❌ **完全不是**。`EngineCPUUtilization` 最高只有 **1.68%**，`CurrConnections` 穩定 7、`Evictions = 0`。★ **兩台 JVM 各一條 Lettuce 共用連線 + 監控，7 條連線就撐住了 344 GET/s。** |
| **ALB 有沒有真的分流到兩台？** | ✅ **有，而且非常平均**。Run 2 兩個 log stream：199 344 vs 199 276（差 0.03%）；Run 1：41 186 vs 40 854（差 0.8%）。兩個 target 全程 `healthy`。 |

### ★ 額外收穫：**cache stampede（快取擊穿）第一次被量到**

- 只有 **10 個** key，但 Run 2 的第一分鐘產生了 **23 次** cache miss（Redis `CacheMisses = 23`、app log `cache MISS` = 23，兩邊完全一致）。
- Run 1 之前的暖機（16:08）只有 **16 次** miss。
- ⇒ **同一個 key 在「第一次 miss 還沒回填 Redis」的空窗期被多個請求同時打中**。到達率越高，重複 miss 越多（16 → 23）。
- 今天無害（DynamoDB on-demand 扛得住 23 次 `GetItem`），但**這就是教科書上 cache stampede 的雛形**；如果 key 數量從 10 變成 10 萬、TTL 同時到期，就會變成真正的事故。
- ★ **順帶解釋了 Run 1 為什麼 `CacheHit` Average = 1.000 0**：那 16 次 miss 全部發生在 16:08 的暖機，**Run 1 的 5 分鐘裡一次 miss 都沒有**；到了 17:54（暖機後 106 分鐘）1 小時 TTL 已過期 ⇒ Run 2 重新付了 23 次 miss。

---

## 5. 下一步（Day 34 / Week 8）

- [ ] **Day 34（最高優先）**：`load-test/findings.md` —— 用觀察 ① 的數字鎖定 **bottleneck #1 = Lambda 帳號並發上限 10**，並實驗「把 handler 的兩次序列 DynamoDB 呼叫合併/並行」能把消費能力從 119 則/s 推到多少
- [ ] **Day 34**：驗證觀察 ② —— 用 Logs Insights 依執行緒名（`click-async-*` vs `io-8080-exec-*`）分群，量出 `CallerRunsPolicy` 實際退回了多少比例的任務
- [ ] **★ 下一輪壓測前一定要改**：`maxVUs` 從 200 提高到 ≥ 300（Run 2 的 `vus_max` 打到 200 天花板、`dropped_iterations = 2337` ⇒ 這一輪的延遲數字偏樂觀）
- [ ] **★ 量測衛生**：暖機（warm-up）要**在正式計時窗開始前至少 60 秒**跑完，避免像 Run 1 那樣被 16:08 那波污染 494 筆
- [ ] Day 35 / Week 8：dashboard 加一格 **ElastiCache**（`EngineCPUUtilization` / `CurrConnections` / `Evictions`）；把 **ALB / ElastiCache / ProcessedEvents 納入 Terraform**
- [ ] Week 8：dashboard 加一格 **SQS `ApproximateAgeOfOldestMessage`** 並掛 alarm（★ 今天證明了它才是非同步鏈路的體溫計，比佇列深度更早、更直觀）
- [ ] Week 8：`ci.yml` 加 path filter（壓測腳本 / 文件的改動不要觸發 deploy）
- [ ] Week 8：壓測端搬到同 region EC2，消掉 158 ms 的量測偏差（★ 只有這樣 p50 才會變成有意義的指標）
- [ ] Week 8：`GET /` 應該回 404 而不是 500（全天 `Target_5XX = 11` / `ELB_5XX = 40` 都來自公網 bot）
- [ ] Week 8：`generateShortCode()` 改用 `SecureRandom`（觀察 ③）
- [ ] Week 8：把 ECS 的 autoscaling 接上（今天是手動 2 台；CPU 46% 的餘裕代表**擴 ECS 沒用**，真正該擴的是 Lambda 並發）

---

## 附錄 A · 這份報告的每個數字是從哪裡來的

| 區塊 | 來源 |
| --- | --- |
| §1 全部 | `load-test/results-100rps.json`、`load-test/results-500rps.json`（k6 `--summary-export`）|
| §2 ALB | `aws cloudwatch get-metric-statistics --namespace AWS/ApplicationELB`，dimension `LoadBalancer=app/url-shortener-alb/6fb50f3251cd06b2` |
| §2 ECS | `--namespace AWS/ECS`，dimensions `ClusterName=url-shortener-cluster`,`ServiceName=url-shortener-service` |
| §2 CacheHit | `--namespace UrlShortener --metric-name CacheHit`（無 dimension）|
| §2 SQS | `--namespace AWS/SQS`，`QueueName=url-click-events` / `url-click-events-dlq` |
| §2 Lambda | `--namespace AWS/Lambda`，`FunctionName=url-click-processor`；並發上限來自 `aws lambda get-account-settings` |
| §2 Redis | `--namespace AWS/ElastiCache`，`CacheClusterId=url-shortener-redis` |
| §3 `ClickAnalytics` | `aws dynamodb scan --table-name ClickAnalytics` |
| §4 log 統計 | CloudWatch Logs Insights over `/ecs/url-shortener-task` |
| §0 服務端規格 | `aws ecs describe-task-definition --task-definition url-shortener-task:10`、`aws elasticache describe-cache-clusters`、`aws lambda list-event-source-mappings` |

## 附錄 B · 未收尾的事項（★ 寫這份報告時的實際狀態）

1. 🚨 **ECS 還沒縮容**：查詢時 `desiredCount = 2`、`runningCount = 2`、兩個 target 都 `healthy`。**day33.md block 2.7 的第一步（`--desired-count 0`）尚未執行——這是 2 台在燒錢。**
2. **`load-test/` 三個檔案還沒進版控**：`git status` = `M load-test/baseline.js`、`?? load-test/results-100rps.json`、`?? load-test/results-500rps.json`。
3. **`baseline.js` 的註解還停留在 Day 32**（第 3 行寫「Day 32」、短碼陣列末尾還留著 `// …把 2.2 ③ 查出來的另外 7 個貼進來…` 這行已經失效的 TODO——10 個短碼其實已經填好了）。
