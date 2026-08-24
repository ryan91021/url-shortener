# URL Shortener · Baseline Load Test 結果（Day 33）

## 0. 量測條件（★ 沒有這一節，下面的數字沒有意義）

| 項目 | 值 |
| --- | --- |
| 日期 | **2026-08-22**（★ 計畫寫 2026-07-08，實際執行日是 2026-08-22，以實際為準）|
| 壓測工具 | k6 **v2.2.0**（commit/00a9a1b7f5, go1.26.5, darwin/arm64，本機 Apple M4 / 10 核 / 24 GB）|
| 壓測腳本 | `load-test/baseline.js`（**working tree 版本，尚未 commit**；base commit `a88da35`。⇒ 見 §5 待辦：commit 後回填 hash）|
| 壓測端位置 | 本機（UTC-7），目標在 **ap-east-2** ⇒ **RTT ≈ 158 ms**（實測地板 `http_req_duration.min`：Run 1 = **158.589 ms** / Run 2 = **157.715 ms**；`http_req_connecting` max 244–251 ms＝新連線握手）|
| 負載模型 | `constant-arrival-rate`（**開放模型**）、`timeUnit 1s`、`preAllocatedVUs 50` / `maxVUs 200`、每次 iteration `sleep(0.1)` |
| 讀寫比 | **70% GET `/api/v1/{code}`（期望 302）/ 30% POST `/api/v1/shorten`（期望 201）**；實測 Run 1 = 69.2 / 30.8、Run 2 = 69.95 / 30.05 |
| 短碼數量 | **10 個**（從 `UrlMappings` 查出來的真實短碼）⇒ 快取命中率預期 ≈ 99.9%，**實測 99.98%** |
| 服務端 | ECS Fargate **2 台** × **0.5 vCPU（512）/ 1 GB（1024）**、task def `:10`、image `a88da354c4d1836ad554e8664678d6d6e0a547c8` |
| 快取 | ElastiCache Redis 7.0.7、`cache.t4g.micro`、單節點、應用層 TTL **1 小時** |
| 資料庫 | DynamoDB on-demand（`UrlMappings` / `ClickAnalytics` / `ProcessedEvents`）|
| 非同步 | SQS `url-click-events`（visibility 30 s、redrive `maxReceiveCount=3`）→ Lambda `url-click-processor`（python3.12、128 MB、timeout 5s、**batch 10 / batchingWindow 0 / ReportBatchItemFailures**）；**⚠️ 帳號 Lambda 並發上限 = 10**（`UnreservedConcurrentExecutions` 也是 10，函式沒設 reserved）|
| Threshold | `http_req_duration: p(95)<500ms, p(99)<1000ms`；`http_req_failed: rate<0.01`；`{name:GET…} p(95)<500`；`{name:POST…} p(95)<800` |

### ⚠️ 這組數字的已知限制（★ 誠實寫下來，才知道它能回答什麼問題）
1. **延遲數字含 ≈158 ms 的跨太平洋 RTT。** 服務端真實延遲請看 CloudWatch `TargetResponseTime`（單位是**秒**）——Run 2 的 p50 只有 **2.34 ms**，而 k6 量到 165.4 ms，**差額 163 ms 全部是網路**。改善方向：Week 8 把壓測端搬到同 region 的 EC2。
2. **這是「Redis 命中路徑」的 baseline，不是「DynamoDB 讀路徑」的 baseline。** 10 個短碼 + 1 小時 TTL ⇒ 兩輪加起來只付了 **39 次** cache miss，命中率 ≈ 99.98%，DynamoDB `GetItem` 幾乎沒被走到。
3. **ALB 是 internet-facing，公網掃描機器人會混進 ALB 的計數**，而且 `GET /` 會回 **500**：全天 `Target_5XX = 11`、`ELB_5XX = 40`。⇒ **但兩個壓測窗內 5XX 都是 0**，雜訊全落在窗外的閒置時段。
4. **★★ Run 1 的 ALB 計數另有污染（比 bot 嚴重得多）**：ALB 窗內 `RequestCount = 30469`，k6 只送了 **29975**，多出來的 **494** 來自 **16:08 那一波暖機/中止的請求**（16:08 有 377 筆，16:09 有 **122 筆 `HTTPCode_ELB_4XX`**＝客戶端提前斷線）。⇒ **Run 1 伺服器端數字有 ±1.6% 雜訊；Run 2 完全乾淨。**
5. **沒有 autoscaling**，台數是手動固定的 2 台（全程 `desired = running = 2`，兩個 target 都 `healthy`，ALB 分流極均勻）。

---

## 1. 兩輪結果總表

| 指標 | Run 1（100 RPS）| Run 2（500 RPS）| 倍率 |
| --- | --- | --- | --- |
| 時間窗（UTC）| 16:09:04Z → 16:14:08Z | ≈17:54:44Z → 17:59:48Z | |
| 目標 RPS / 實際 `http_reqs` rate | 100 / **99.83** | 500 / **491.72** | 4.93× |
| `iterations` | **29 975**（預期 ≈30000）| **147 664**（預期 ≈150000）| 4.93× |
| **`dropped_iterations`** | **26**（0.09%）| **2 337**（1.55%）| ★ **>0 = 該輪數據無效** → 見下方註 ① |
| `vus`（peak）/ `vus_max` | 68 / 76 | **200** / **200**（★ 打到天花板）| |
| **`http_req_duration` min**（＝網路地板）| **158.589** ms | **157.715** ms | 1.00× |
| avg | 185.322 ms | 190.937 ms | 1.03× |
| **p50** | **166.662** ms | **165.431** ms | 0.99× |
| p90 | 234.566 ms | 238.219 ms | 1.02× |
| **p95** | **246.869** ms | **250.853** ms | 1.02× |
| **p99** | **285.117** ms | **516.615** ms | **1.81×** |
| **max** | **1 128.839** ms | **2 113.107** ms | 1.87× |
| **p99 / p50 倍率** | **1.71** | **3.12** | ★ 尾巴有多長 → 長了 1.8 倍 |
| `http_req_waiting` p95 | 246.755 ms | 250.765 ms | 1.02× |
| `http_req_failed` | **0.00%**（0 / 29 975）| **0.00%**（0 / 147 664）| |
| `checks_succeeded` | **100%**（29 975 / 29 975）| **100%**（147 664 / 147 664）| |
| GET p95（`{name:GET /api/v1/{code}}`）| 245.475 ms | 250.709 ms | 1.02× |
| GET p99 / max | 280.922 / 741.624 ms | **559.716** / 2 113.107 ms | 1.99× / 2.85× |
| POST p95（`{name:POST /api/v1/shorten}`）| 249.741 ms | 251.179 ms | 1.01× |
| POST p99 / max | 294.562 / 1 128.839 ms | 397.166 / 1 663.865 ms | 1.35× / 1.47× |
| `iteration_duration` p95 | 348.040 ms | 351.327 ms | 1.01× |
| **exit code** | **0（全綠）** | **0（全綠）**（★ 預期 99，**實際沒踩到 threshold**）| |

**★ 三個一定要寫清楚的註解：**

**① `dropped_iterations` 的實際意義**：Run 2 有 **2 337 次 iteration 根本沒送出去**（200 個 VU 全忙 ⇒ k6 排程器直接丟棄）。所以實際到達率是 **491.7 RPS 而不是 500**，而且**被丟掉的通常是「最慢的時刻」⇒ Run 2 的延遲數字偏樂觀**。直接證據：`vus_max` 峰值 = **200**，正好等於腳本設的 `maxVUs`。⇒ **下一輪必須先把 `maxVUs` 調到 ≥ 300 才量得準。**

**② exit code 0 ≠ 系統健康**：兩輪**所有 threshold 全過**（p95 250.9 < 500、p99 516.6 < 1000、失敗率 0.00%）。但**同一時間 SQS 積了 65 090 則訊息、最舊訊息落後 9 分 41 秒**（§2）。⇒ **★★ 今天最重要的一課：k6 只看得到同步回應那一段，非同步鏈路整個崩掉它一聲都不會吭。**

**③ p50 幾乎沒動（166.7 → 165.4 ms）**：158 ms 的 RTT 把服務端的變化整個蓋掉了。**唯一穿透 RTT 的訊號是 p99（285 → 517 ms）與 max（1.13 → 2.11 s）。**

---

## 2. 伺服器端對照（CloudWatch）

> 對照窗（對齊到分鐘）：Run 1 = `16:09:00Z–16:15:00Z`、Run 2 = `17:54:00Z–18:00:00Z`

| 指標 | Run 1 | Run 2 | 昨天（Day 32，~35 RPS，1 台）|
| --- | --- | --- | --- |
| ALB `RequestCount`（總）| **30 469**（含 494 筆非 k6）| **147 664**（★ 與 k6 完全一致）| 1047 |
| ALB 2XX / 3XX / 4XX / 5XX | 9 337 / 21 010 / 0 / **0** | 44 376 / 103 288 / 0 / **0** | 329 / 718 / 0 / 0 |
| ALB `HTTPCode_ELB_4XX`（★ 不是 target 回的）| **122** | **0** | — |
| **`TargetResponseTime` p50（秒）** | **0.002821** | **0.002344** | **0.00336** |
| p90（秒）| 0.011118 | 0.006844 | — |
| **p95（秒）** | **0.042998** | **0.044481** | **0.01328** |
| **p99（秒）** | **0.088280** | **0.332206** | **0.06087** |
| p99.9（秒）| **7.651390**（★ 第一分鐘暖機）| 0.964668 | — |
| max（秒）| **9.215778** | 1.953681 | 0.074193 |
| SampleCount | 30 347 | 147 664 | 1047 |
| **★ k6 p50 − CloudWatch p50×1000** | **163.8 ms** | **163.1 ms** | ★ **應該 ≈ RTT（158–162）→ 完全吻合** |
| ECS CPU avg / max（%）| 35.01 / **99.32** | **46.55** / **94.92** | 5.52 / **39.37** |
| ECS Mem max（%）| 34.62 | 37.89 | 27.05 |
| `CacheHit` Average / SampleCount | **1.0000** / 21 166 | **0.99978** / **103 288** | **1.0** / 718 |
| SQS 深度 max / 最舊訊息年齡 max | **3** / **4 s** | **54 699** 🚨 / **165 s** 🚨（窗外峰值 **65 090** / **581 s**）| 0 / 0 |
| SQS sent / received / deleted | 21 213 / 21 217 / 21 224 | **103 288 / 36 488 / 36 428** 🚨 | — |
| **Lambda Invocations / Throttles** | 15 098 / **609** 🚨 | 3 653 / **563** 🚨 | 485 / **0**（★ 06:50 那輪是 4）|
| **Lambda ConcurrentExecutions max** | **10 ＝上限** 🚨 | **10 ＝上限** 🚨 | **8**（★ 06:50 那輪是 **10 = 上限**）|
| Lambda Errors | 0 | 0 | — |
| Lambda Duration avg / max（ms）| 130.19 / 813.0 | **638.05** / 1133.87 | 143.4 / 660.3 |
| 每次 invocation 平均訊息數 | **1.41** | **9.99**（★ batch 打滿）| — |
| `ClickEventProcessingDuration` avg / n | 70.32 ms / 15 100 | **578.59 ms** / 3 647 | — |
| DLQ 深度 / alarm 狀態 | **0** / OK | **0** / OK | 0 / OK |
| Redis CPU / EngineCPU / CurrConnections | 2.35% / **0.71%** / 7 | 3.00% / **1.27%** / 7 | 4.39% / 0.32% / 6 |
| Redis CacheHits / CacheMisses / Evictions | 21 347 / 16 / **0** | 92 181 / 23 / **0** | — |

### 2a. 逐分鐘：哪一格先歪

**ECS CPU（Average / Maximum，%）**

| Run 1（UTC-7）| avg / max | Run 2（UTC-7）| avg / max |
| --- | --- | --- | --- |
| 09:09 | **77.37 / 99.32** ← 暖機 | 10:54 | 5.14 / 18.36 |
| 09:10 | 47.26 / 56.65 | 10:55 | **71.00 / 94.92** ← 峰值 |
| 09:11 | 30.49 / 38.78 | 10:56 | 54.87 / 63.81 |
| 09:12 | 29.62 / 44.89 | 10:57 | 48.42 / 50.46 |
| 09:13 | 19.88 / 26.80 | 10:58 | 52.57 / 57.62 |
| 09:14 | 5.43 / 16.90 | 10:59 | 47.30 / 54.22 |

**ALB `TargetResponseTime`（秒）p50 / p99 / max**

| Run 1 | p50 / p99 / max | Run 2 | p50 / p99 / max |
| --- | --- | --- | --- |
| 09:09 | 0.0063 / **6.4635** / **9.2158** | 10:54 | 0.0508 / **1.2435** / **1.9537** |
| 09:10 | 0.0030 / 0.0588 / 0.0795 | 10:55 | 0.0030 / 0.3996 / 1.0872 |
| 09:11 | 0.0026 / 0.0398 / 0.0782 | 10:56 | 0.0023 / 0.2641 / 1.0575 |
| 09:12 | 0.0025 / 0.0491 / 0.0738 | 10:57 | 0.0022 / 0.0095 / 0.0777 |
| 09:13 | 0.0024 / 0.0095 / 0.0650 | 10:58 | 0.0022 / 0.1033 / 1.0698 |
| 09:14 | 0.0024 / 0.0087 / 0.0189 | 10:59 | 0.0022 / 0.0089 / 0.0785 |

> ★ **兩輪的第一分鐘都是最慢的一分鐘**（Run 1 max 9.2 s、Run 2 max 1.95 s），之後 p50 立刻掉到 2.2–3.0 ms 並一路平穩。
> ★★ **CPU 不是今天的瓶頸**：500 RPS 下平均只有 46.55%，而且 `TargetResponseTime` p50 **比 100 RPS 那輪更低**（2.34 ms vs 2.82 ms，JIT 更熱）。

**SQS 積壓與排空（Run 2 之後）**

| 分鐘（UTC-7）| 深度 | 最舊訊息年齡 |
| --- | --- | --- |
| 10:54（開始）| 0 | 0 s |
| 10:55 | 2 752 | 10 s |
| 10:57 | 20 908 | 64 s |
| 10:59（壓測結束）| **54 699** | **165 s** |
| **11:00** | **65 090** ← **峰值** | 221 s |
| 11:05 | 37 380 | 381 s |
| 11:09 | 728 | **581 s** ← 年齡峰值（9 分 41 秒）|
| **11:10** | **0** | 0 s |

> 🚨 產生 103 288 則、只消化 36 428 則 ⇒ 積壓 **66 860 則**，**花 10 分鐘才排空**。
> ✅ 但 **DLQ 全程 = 0、`ClickAnalytics` 一則沒少 ⇒ 沒掉資料，只是遲到 10 分鐘。**

---

## 3. 三個獨立計數器交叉驗證

| | Run 1 | Run 2 | **全天（2026-08-22）** |
| --- | --- | --- | --- |
| ALB 3XX 總數（＝GET 成功數）| 21 010 | **103 288** | 124 539 |
| `CacheHit` SampleCount 總和 | 21 166 | **103 288** | **124 652** |
| SQS `NumberOfMessagesSent` | 21 213 | **103 288** | **124 652** |
| `ClickAnalytics` 今日 clickCount 增量 | 未單獨快照（見下）| 未單獨快照（見下）| **124 652** |
| **三者一致？** | ❌ 差 156 / 203（★ 已查明，非 bug）| ✅ **四個計數器一模一樣** | ✅ **SQS = CacheHit = ClickAnalytics = 124 652** |

**★ Run 2 是完美的一輪**：k6 的 `GET 302` check = ALB `3XX` = `CacheHit` SampleCount = SQS `NumberOfMessagesSent` = **103 288**。**四個完全獨立的計數器完全對上** ⇒ 讀路徑上**沒有任何一環靜默失敗**。

**★ Run 1 的差額已查明，不是 bug**：`CacheHit`（21 166）和 SQS（21 213）都比 ALB 3XX（21 010）**多**而不是少。原因是 **16:08 那波中止請求的尾巴落在 16:09 這一分鐘**——應用程式處理完了（發了 metric、送了 SQS），但客戶端提前斷線，ALB 記成 `ELB_4XX`（122 筆）而不是 `Target_3XX`。

**★ `ClickAnalytics` 為什麼沒有 per-run 數字**：它是**當日累計**計數器，兩輪之間**沒有先快照**（下次要先 scan 一次記下起始值）。但**全天總帳完全對得起來**：
`ALB 3XX 124 539` ＋ `ELB_4XX 中屬 GET 的 113 筆` ＝ **124 652** ＝ SQS sent ＝ `CacheHit` SampleCount ＝ `ClickAnalytics` 總和。

`ClickAnalytics` 全天實測（10 個短碼的 2026-08-22 clickCount）：

| shortCode | count | shortCode | count |
| --- | --- | --- | --- |
| `ivu46b2` | 12 604 | `q6w83nb` | 12 404 |
| `qspq70c` | 12 577 | `9fd6noh` | 12 376 |
| `rrrtxm4` | 12 555 | `am6vckv` | 12 354 |
| `qm90t53` | 12 546 | `x5l1h6j` | 12 236 |
| `25esld0` | 12 512 | `1mtdm5m` | 12 488 |
| | | **合計** | **124 652** |

---

## 4. Top 3 觀察 + 假設原因（★ 明天 findings.md 的原料）

1. 🚨 **觀察**：**Lambda 並發全程貼死在帳號上限 10。** Run 2 的**每一分鐘** `ConcurrentExecutions` Maximum 都是 **10.0**（17:53Z–18:12Z 逐分鐘查過，一次都沒低於 10），`Throttles` 93–141 次/分鐘（Run 2 窗內 563、Run 1 窗內就已經 609）。生產速率 = 103 288 / 300 s = **344 則/s**，消費速率（用 11:00 的 65 090 → 11:09 的 728 回推）= **≈ 119 則/s**。缺口 225 則/s × 300 s = **≈ 67 500 則** ≈ 實測積壓 **66 860 則**（sent − deleted）。**數字自洽。**
   **假設**：消費端天花板 = `並發 10 ÷ 單次 638 ms × 每批 10 則` ≈ **157 則/s**（理論），扣掉排程與 throttle 退避後實測 **≈ 119 則/s**；生產端 344 則/s ⇒ **必然線性積壓**。★ **Duration 從 130 ms 變 638 ms 不是 Lambda 變慢，是 batch 被填滿了**（每次 invocation 拿到的訊息數 1.41 → 9.99；**每則訊息成本幾乎沒變：49.9 ms → 57.9 ms**）。
   **明天要用什麼證據驗證**：提高並發要開 support ticket（上限是**帳號級**的，設 reserved / `MaximumConcurrency` 都無效）；Day 34 先做的是砍每則成本——handler 對每則訊息跑了 **2 次序列的 DynamoDB 呼叫**（`ProcessedEvents.put_item` 條件寫入 ＋ `ClickAnalytics.update_item`）。改成並行 / `batch_writer` 後，同樣 500 RPS 再跑一輪，看 `ClickEventProcessingDuration` avg 是否從 578 ms 掉到 ~300 ms、SQS 深度峰值是否從 65 090 砍半。

2. ⚠️ **觀察**：**`CallerRunsPolicy` 確實在作用，但沒有想像中致命。** Run 2 = 344 GET/s × 2 個任務 = **688 任務/s**，池子只有 4 條執行緒；**log 裡 0 筆 `RejectedExecutionException`**（Insights 掃過整個窗，`ERROR|Exception|RejectedExecution|failed to publish` 只命中 4 筆短碼碰撞 WARN）——**這正是 `CallerRunsPolicy` 的特徵：它不丟例外，它讓呼叫端自己跑**。而 `CacheHit` SampleCount = ALB 3XX = 103 288，**一筆任務都沒掉**。代價寫在延遲尾巴上：`TargetResponseTime` p99 從 **0.088 s → 0.332 s（3.8×）**，max 反覆碰到 **1.0–1.07 s**，但 **p50 完全沒受影響**（2.82 → 2.34 ms）。
   **假設**：網路呼叫（SQS `SendMessage` / CloudWatch `PutMetricData`）被退回 Tomcat 請求執行緒同步執行 ⇒ **非同步解耦在壓力下失效**，表現為「p50 不動、尾巴變胖」。
   **驗證方式**：log 已經有印執行緒名（例：`[io-8080-exec-78]`），一條 Insights `parse` 就能統計 `published click event` 落在 `click-async-*` vs `http-nio-8080-exec-*` 的比例——**若假設成立，Run 2 應該壓倒性落在 `http-nio-*`**。反向實驗：`maxPoolSize` 調到 32、`queueCapacity` 調到 1000 再跑 500 RPS，看 p99 是否掉回 0.1 s 以下。

3. ⚠️ **觀察**：**短碼碰撞率遠高於「隨機 36^7」該有的水準。** Run 1：9 230 次 POST → **3 筆** `Short code collision on attempt 1 of 3`；Run 2：44 376 次 POST → **4 筆**。而 `generateShortCode()` = `Long.toString(System.nanoTime(), 36)` 取**後 7 碼**——**這不是亂數，是時間戳低位**。若真是 36^7 ≈ 7.84×10¹⁰ 的均勻亂數，44 376 次寫入的期望碰撞 ≈ **0.013 次**，**實測 4 次 ≈ 期望的 300 倍**。
   **假設**：`nanoTime()` 有效解析度遠低於 1 ns，且取後 7 碼 ≡ `nanoTime mod 36^7`（**每 ≈78 秒繞回原點一次**），兩台 task 的 `nanoTime` 原點又互不相關 ⇒ 真實碼空間比 36^7 小好幾個數量級。
   **驗證方式**：換成 `SecureRandom` 抽 7 碼 base62，同樣 500 RPS 再跑一輪，看碰撞 WARN 是否歸零。★ **今天完全無害**（3 次 retry 全部第一次就成功，`http_req_failed = 0.00%`），但寫入量再大一個數量級就會出現「三次都撞」→ `ShortCodeGenerationException` → 500。

### ★ 已經寫在紙上的預設嫌疑犯（今天只記錄，不修）→ 加上今天的實測判決

- **`AsyncConfig.clickEventExecutor`：core 2 / max 4 / queue 100 / `CallerRunsPolicy`**，而每個 GET 丟【兩個】任務（SQS + CloudWatch）⇒ 350 GET/s = 700 任務/s，遠超池子的 ~200/s 天花板 ⇒ **`CallerRunsPolicy` 把網路呼叫退回 Tomcat 請求執行緒**，非同步解耦在壓力下失效。
  → ⚠️ **成立，但不是頭號兇手**。0 筆任務遺失（`CacheHit` SampleCount 完全對上），代價是 p99 漲 3.8 倍。**見觀察 2。**
- **Lambda 帳號並發上限 = 10**（`get-account-settings` 實測）⇒ 消費端天花板 ≈ 10 × (1/duration) × batch。
  → 🚨 **完全成立，這就是 bottleneck #1**。公式實測值：10 × (1/0.638 s) × 10 = 157 則/s（理論），實測 119 則/s。**見觀察 1。**
- **每個 GET 一次 `PutMetricData`**（`MetricPublisher`）⇒ 350 次 API 呼叫/秒，且例外被吞成 `warn`（靜默失敗）。
  → ✅ **今天沒有靜默失敗**：log 裡 **0 筆** `failed to publish CacheHit metric`，`CacheHit` SampleCount 與 ALB 3XX **完全相等**。但它仍是每秒 344 次 API 呼叫，是 Week 8 的成本 / 限流風險。
- **task 只有 0.5 vCPU**，2 台總共 1 vCPU。
  → ❌ **不成立**。500 RPS 下 CPU 平均只有 **46.55%**，峰值 94.92% 只出現在第一分鐘暖機。**還有一半餘裕 ⇒ 擴 ECS 沒用。**
- **每個 GET 印 3 行 log**（`com.urlshort.shortener` 是 `debug`），走 `awslogs` driver。
  → ⚠️ **量級確認**：Run 2 兩個 stream 合計 **398 620 行 / 5 分鐘**（199 344 + 199 276）≈ **1 329 行/秒**。目前沒造成可見延遲，但這是 CloudWatch Logs 的實質成本來源。

### ★ 兩個計畫外的收穫

- **ElastiCache 完全不是瓶頸**：`EngineCPUUtilization` 最高只有 **1.68%**、`CurrConnections` 穩定 **7**、`Evictions = 0`。**7 條連線就撐住了 344 GET/s。**
- **★ 第一次量到 cache stampede（快取擊穿）**：只有 10 個 key，Run 2 第一分鐘卻產生 **23 次** miss（Redis `CacheMisses` 與 app log `cache MISS` 都是 23，兩邊一致）；Run 1 之前的暖機只有 **16 次**。⇒ **同一個 key 在「第一次 miss 還沒回填 Redis」的空窗期被多個請求同時打中，到達率越高重複 miss 越多（16 → 23）。** ★ 這也解釋了 Run 1 為什麼 `CacheHit` Average = 1.0000——那 16 次 miss 全部發生在 16:08 的暖機，Run 1 的 5 分鐘裡一次 miss 都沒有；到 17:54（106 分鐘後）1 小時 TTL 已過期，Run 2 才重新付了 23 次。

---

## 5. 下一步（Day 34 / Week 8）
- [ ] Day 34：從上面 top 3 挑一個，用證據鎖定 **bottleneck #1**，寫 `load-test/findings.md` ⇒ **已鎖定：觀察 1（Lambda 帳號並發 10）**
- [ ] Day 34：驗證觀察 2 —— 用 Logs Insights 依執行緒名（`click-async-*` vs `io-8080-exec-*`）分群，量出 `CallerRunsPolicy` 實際退回了多少比例的任務
- [ ] **★ 下一輪壓測前必改**：`maxVUs` 200 → **≥ 300**（Run 2 打到天花板、`dropped_iterations = 2337` ⇒ 這輪延遲數字偏樂觀）
- [ ] **★ 量測衛生**：暖機要在正式計時窗開始前**至少 60 秒**跑完（避免像 Run 1 被 16:08 那波污染 494 筆）；兩輪之間先 `scan ClickAnalytics` 記下起始值，才有 per-run 增量
- [ ] **★ commit 後回填**：把 `git rev-parse --short HEAD` 的 hash 補進 §0「壓測腳本」那一格
- [ ] Day 35 / Week 8：dashboard 加一格 **ElastiCache**；把 **ALB / ElastiCache / ProcessedEvents 納入 Terraform**
- [ ] Week 8：dashboard 加一格 **SQS `ApproximateAgeOfOldestMessage`** 並掛 alarm（★ 今天證明它才是非同步鏈路的體溫計，比佇列深度更早也更直觀）
- [ ] Week 8：`ci.yml` 加 path filter（壓測腳本 / 文件的改動不要觸發 deploy）
- [ ] Week 8：壓測端搬到同 region EC2，消掉 **158 ms** 的量測偏差（★ 只有這樣 p50 才會變成有意義的指標）
- [ ] Week 8：`GET /` 應該回 404 而不是 500（全天 `Target_5XX = 11` / `ELB_5XX = 40` 都來自公網 bot）
- [ ] Week 8：`generateShortCode()` 改用 `SecureRandom`（觀察 3）
- [ ] Week 8：**不要**替 ECS 加 autoscaling 當作解方（CPU 只用了 46%）——真正該擴的是 **Lambda 並發**

---

## 附錄 · 數字出處

| 區塊 | 來源 |
| --- | --- |
| §1 全部 | `load-test/results-100rps.json` / `results-500rps.json`（k6 `--summary-export`）|
| §2 ALB | `AWS/ApplicationELB`，dimension `LoadBalancer=app/url-shortener-alb/6fb50f3251cd06b2` |
| §2 ECS | `AWS/ECS`，`ClusterName=url-shortener-cluster` / `ServiceName=url-shortener-service` |
| §2 CacheHit | `UrlShortener` namespace，`CacheHit`（無 dimension）|
| §2 SQS | `AWS/SQS`，`QueueName=url-click-events` / `url-click-events-dlq` |
| §2 Lambda | `AWS/Lambda`，`FunctionName=url-click-processor`；並發上限來自 `aws lambda get-account-settings` |
| §2 Redis | `AWS/ElastiCache`，`CacheClusterId=url-shortener-redis` |
| §3 `ClickAnalytics` | `aws dynamodb scan --table-name ClickAnalytics` |
| §4 log 統計 | CloudWatch Logs Insights over `/ecs/url-shortener-task` |
| §0 規格 | `aws ecs describe-task-definition --task-definition url-shortener-task:10`、`aws elasticache describe-cache-clusters`、`aws lambda list-event-source-mappings` |

## 附錄 · 寫報告當下未收尾的事項
1. 🚨 **ECS 還沒縮容**：`desiredCount = 2`、`runningCount = 2`、兩個 target 都 `healthy` ⇒ **day33.md block 2.7 第一步（`--desired-count 0`）尚未執行，2 台仍在燒錢。**
2. **`load-test/` 三個檔案還沒進版控**：`M load-test/baseline.js`、`?? results-100rps.json`、`?? results-500rps.json`。
3. **`baseline.js` 註解還停留在 Day 32**（第 3 行寫「Day 32」；短碼陣列末尾還留著 `// …把 2.2 ③ 查出來的另外 7 個貼進來…` 這行已失效的 TODO）。
