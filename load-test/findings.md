# URL Shortener · Bottleneck 分析（Day 34）

> 資料來源：`load-test/baseline-results.md`（Day 33 的兩輪）+ **Day 34 的兩輪 250 RPS 驗證**（`results-250rps-cold.json` / `results-250rps.json`）。
> 所有數字都可以用本文附的指令重新查出來（CloudWatch metrics 保留 15 個月）。
> ⚠️ **例外**：`/ecs/url-shortener-task` 的 log 保留期只有 **7 天**（實測 `retentionInDays = 7`）⇒ 本文引用的 log **已逐字抄錄**，不要依賴「回去查」。

## 0. 一句話結論

**Bottleneck #1 = Lambda 帳號並發上限 `ConcurrentExecutions = 10`。**
在它不變的前提下，非同步鏈路的吞吐天花板是 **112.7 則/s**（Day 34 直接量到，Day 33 回推是 119.2 ⇒ 兩次相差 5.5%），換算成端到端流量是 **≈ 161 RPS**。
同步路徑（ALB / ECS / Tomcat / Redis / DynamoDB）在 500 RPS 下**都還有一半以上的餘裕**。
⇒ **瓶頸不在請求路徑上，在請求路徑【之後】——所以 k6 完全看不到它。**

**★ Day 34 新增的第二個結論（原本不在計畫裡）：**
**Bottleneck #2 = ECS CPU，但它只在「JVM 剛啟動、還沒 JIT 完」的頭 4 分鐘存在。**
同樣 2 台、同樣的碼、同樣打 250 RPS：**冷的那輪只跑得出 178.6 RPS、CPU 五分鐘全程 100%、`TargetResponseTime` p95 = 3.238 s**；暖的那輪 **249.3 RPS、CPU 平均 32%、p95 = 0.00626 s**。**同一個系統，p95 差 517 倍。**
⇒ Day 33 寫的「CPU 不是瓶頸、還有一半餘裕」**只在暖機後成立**；這句話今天被自己的資料補上了但書。

---

## 1. 問題陳述

500 RPS × 5 分鐘的壓測中：
- k6 **全綠**（p95 250.9 ms < 500、p99 516.6 ms < 1000、`http_req_failed` **0.00%**、**exit code 0**）
- 同一時間 SQS 佇列從 0 堆到 **65,090 則**，最舊訊息落後 **581 秒（9 分 41 秒）**
- 壓測結束後**又花了 10 分鐘**才排空（17:59:48 停止 → 18:10 歸零）
- **沒有掉任何資料**（DLQ = 0、`ClickAnalytics` 全天總和 124,652 = SQS `NumberOfMessagesSent` 全天總和）

⇒ 這不是「錯誤」，是「**延遲**」：使用者拿到 302 一切正常，但那次點擊要 **9 分 41 秒後**才會出現在分析報表裡。

**Day 34 用 250 RPS 複製了同一個現象，只是規模剛好縮到一半**：k6 依然全綠（p95 **244.0 ms**、失敗率 **0.00%**），SQS 依然堆到 **13,980 則**、最舊訊息落後 **125 秒**。**一個 exit code 0 的壓測，換來兩分鐘的資料延遲。**

---

## 2. 證據（每條都附可重現的指令）

**證據 ①：Lambda 並發全程貼死在 10**
```bash
aws cloudwatch get-metric-statistics --namespace AWS/Lambda --metric-name ConcurrentExecutions \
  --dimensions Name=FunctionName,Value=url-click-processor \
  --start-time 2026-08-22T17:53:00Z --end-time 2026-08-22T18:12:00Z \
  --period 60 --statistics Maximum
```
→ 17:53Z–18:12Z 的**每一分鐘** Maximum 都是 **10.0**，19 分鐘裡一次都沒低於 10。
→ **Day 34 重跑同一條指令（15:24Z–15:30Z）：一樣是每分鐘 10.0，一次都沒低於 10。**
```bash
aws lambda get-account-settings --query 'AccountLimit.[ConcurrentExecutions,UnreservedConcurrentExecutions]'
```
→ **`10  10`**（帳號級上限，非函式級；`get-function-configuration` 的 `ReservedConcurrentExecutions = None`）

**證據 ②：持續被節流**
```bash
aws cloudwatch get-metric-statistics --namespace AWS/Lambda --metric-name Throttles \
  --dimensions Name=FunctionName,Value=url-click-processor \
  --start-time 2026-08-22T17:53:00Z --end-time 2026-08-22T18:12:00Z --period 60 --statistics Sum
```
→ 每分鐘 **93–141 次**，從頭到尾沒有一分鐘是 0。Run 2 窗內合計 **563**，Run 1 窗內就已經 **609**。
→ **Day 34 warm（15:24Z–15:30Z）逐分鐘 49 / 115 / 95 / 105 / 125 / 128 / 81，窗內合計 489；cold 窗內 551；全天 1,251。**
★ **到達率砍一半，throttle 幾乎沒少**（563 → 489）——因為節流的成因是**並發上限**，不是到達率。

**證據 ③：佇列積壓與排空曲線**
```bash
aws cloudwatch get-metric-statistics --namespace AWS/SQS \
  --metric-name ApproximateNumberOfMessagesVisible \
  --dimensions Name=QueueName,Value=url-click-events \
  --start-time 2026-08-22T17:52:00Z --end-time 2026-08-22T18:14:00Z --period 60 --statistics Maximum
```
→ Day 33 Run 2 深度：`0 → 2,752 → 16,655 → 20,908 → 36,886 → 54,699 → **65,090（峰值，壓測結束後一分鐘）** → … → 0（18:10）`
→ Day 33 Run 2 年齡：`10s → 53s → 64s → 112s → 165s → 221s → … → **581s（峰值）** → 0`

→ **Day 34 warm 深度（逐分鐘，UTC）**：`15:24 = 0 → 15:25 = 0 → 15:26 = 6,023 → 15:27 = 8,123 → 15:28 = 13,318 → **15:29 = 13,980（峰值）** → 15:30 = 4,930 → **15:31 = 0**`
→ **Day 34 warm 年齡**：`0 → 0 → 43s → 56s → 87s → 99s → **125s（峰值）** → 0`
★ **排空只花了 ≈ 2 分鐘**（Day 33 是 10 分鐘）——不是因為消費變快了（消費速率一模一樣），是因為**要排的東西只有五分之一**。

**證據 ④：Day 33 生產 103,288、消費 36,428**
```bash
aws cloudwatch get-metric-statistics --namespace AWS/SQS --metric-name NumberOfMessagesSent \
  --dimensions Name=QueueName,Value=url-click-events \
  --start-time 2026-08-22T17:54:00Z --end-time 2026-08-22T18:00:00Z --period 360 --statistics Sum
```
→ Sent **103,288** / Received **36,488** / Deleted **36,428** ⇒ 差 **66,860 則**。
→ **Day 34 warm（15:24:00Z–15:29:00Z，period 300）：Sent 41,842 / Received 29,312 / Deleted 29,212 ⇒ 差 12,630 則。**
⚠️ **`--period` 必須等於窗長（Day 33 是 360＝6 分鐘、Day 34 是 300＝5 分鐘）**。用 `--period 3600` 會從 start-time 起算一整小時，把排空期算進來（實測差 2.8 倍）。

**證據 ⑤：消費速率可以【直接量】，不必回推（★ Day 34 新增，最強的一條）**
```bash
aws cloudwatch get-metric-statistics --namespace AWS/SQS --metric-name NumberOfMessagesDeleted \
  --dimensions Name=QueueName,Value=url-click-events \
  --start-time 2026-08-24T15:24:00Z --end-time 2026-08-24T15:33:00Z --period 60 --statistics Sum
```
→ 逐分鐘：`2,172 / 7,000 / 6,770 / 6,530 / 6,740 / 6,770 / 5,860 / 0 / 0`
→ 穩態五分鐘平均 **6,762 則/分 = 112.7 則/s**。**七分鐘合計 41,842 ＝ 送進去的 41,842，一則不多一則不少。**
★ Day 33 是用「峰值深度回推」得到 119.2 則/s；**Day 34 用計數器直接量到 112.7**。兩個獨立方法差 **5.5%** ⇒ **這個天花板是實數，不是估計。**

**證據 ⑥：log 裡沒有任何錯誤（逐字抄錄，因為 7 天後會消失）**
Logs Insights over `/ecs/url-shortener-task`，Day 33 Run 2 窗 17:54:00Z–18:00:00Z，
`filter @message like /ERROR|Exception|failed to publish|collision|RejectedExecution/`：
```
2026-08-22T17:57:29.866Z  WARN […] c.urlshort.shortener.service.UrlService : Short code collision on attempt 1 of 3: shortCode=mbyjp0p
2026-08-22T17:57:32.716Z  WARN […] c.urlshort.shortener.service.UrlService : Short code collision on attempt 1 of 3: shortCode=nhd8hbt
2026-08-22T17:59:32.062Z  WARN […] c.urlshort.shortener.service.UrlService : Short code collision on attempt 1 of 3: shortCode=6gv2787
2026-08-22T17:59:35.171Z  WARN […] c.urlshort.shortener.service.UrlService : Short code collision on attempt 1 of 3: shortCode=7w9hbm2
```
→ 就這 4 行，**沒有** `RejectedExecutionException`、**沒有** `failed to publish CacheHit metric`、**沒有**任何 `ERROR`。

同一條查詢跑 **Day 34 warm 窗 15:24:00Z–15:29:00Z**（掃 161,645 行），**只命中 1 行**：
```
2026-08-24T15:26:14.642Z  WARN [requestId=ae7e4ddf-ca0a-4f84-b942-4ef05c3f9aa7] 1 --- [url-shortener] [io-8080-exec-92] c.urlshort.shortener.service.UrlService  : Short code collision on attempt 1 of 3: shortCode=4u3zvni
```
→ 一樣**沒有** `ERROR` / `Exception` / `RejectedExecution` / `failed to publish`。

**證據 ⑦：六個獨立計數器對到同一個數（★ Day 34 全天）**
| 計數器 | 2026-08-24 全天 |
| --- | --- |
| ALB `HTTPCode_Target_3XX_Count` | **78,253** |
| SQS `NumberOfMessagesSent` | **78,253** |
| SQS `NumberOfMessagesDeleted` | **78,253** |
| `UrlShortener/CacheHit` SampleCount | **78,253** |
| `ClickAnalytics` 當日 clickCount 總和（10 個短碼）| **78,253** |
| `ProcessedEvents` ItemCount 增量（205,248 − 126,995）| **78,253** |

→ **六個完全獨立的計數器一模一樣**，DLQ = 0、Lambda `Errors` = 0。⇒ **積壓期間沒有任何一則訊息掉、重複或靜默失敗。**
（`CacheHit` 的 **Sum = 78,087**，比 SampleCount 少 **166** ⇒ 全天 166 次 cache miss，全部落在 cold 那輪；見 §7.2）

---

## 3. 被排除的嫌疑犯（每個都附反證數字）

| 嫌疑犯 | 反證 | 判定 |
| --- | --- | --- |
| **ECS CPU 飽和** | **暖機後**：500 RPS 下 Average **46.55%**、250 RPS 下 **32.00%**（max 56.97%）；`TargetResponseTime` p50 隨時間**變快**（0.0508→0.0022 s）| ⚠️ **暖機後排除，冷啟動不排除** → 見下方 ★ |
| **DynamoDB 節流 / SDK 連線池不夠** | 三張表 `ThrottledRequests` **全天 0**；**Day 34 warm 窗 `UrlMappings` 的 `ConsumedReadCapacityUnits` = `0.0`**（一次 `GetItem` 都沒走到，因為 cache miss = 0）| ❌ 排除 |
| **Tomcat thread pool 飽和** | `application.yml` 沒設 `threads.max` ⇒ 預設 **200**/台。Little's Law：`L = 492.2 req/s × 0.013618 s = 6.70` 條（兩台合計）⇒ 每台 **3.35 / 200 = 1.7%**。ALB `RejectedConnectionCount` / `TargetConnectionErrorCount` 皆 **0** | ❌ 排除 |
| **Redis 單一連線** | `pom.xml` 確實沒有 `commons-pool2`（＝單一共用連線是真的），但 `EngineCPUUtilization` 峰值只有 **1.68%**（Day 34 warm **1.27%**）、`CurrConnections` **7**、`Evictions` **0**。Lettuce 的連線是**多工的** | ❌ 排除。「只有一條」≠「不夠」 |
| **`CallerRunsPolicy` 把非同步退回請求執行緒** | **Day 34 warm 直接量了**：41,842 筆 `published click event` 中 **41,828 筆（99.97%）在 `click-async-*`**，只有 **14 筆（0.03%）**在 Tomcat 執行緒 | ❌ **暖機後排除**（但 cold 那輪 **41.80%** ⇒ 見 §7.1） |

### ★ 唯一被 Day 34 推翻一半的判決：ECS CPU

Day 33 寫的是「CPU 不是今天的瓶頸，還有一半餘裕 ⇒ 擴 ECS 沒用」。**Day 34 的 cold 輪給了直接反例：**

| | cold（14:53–14:58Z）| warm（15:24–15:29Z）| 差距 |
| --- | --- | --- | --- |
| 目標 / 實際達成 RPS | 250 / **178.62** | 250 / **249.29** | ★ 冷的**打不到目標** |
| `dropped_iterations` | **17,061**（佔應送 iteration 的 28.4%）| **100**（0.17%）| 171× |
| k6 `vus` 峰值 | **200 ＝ `maxVUs` 天花板** | 87（分配到 149，沒碰天花板）| |
| ECS CPU Average（逐分鐘）| 63.49 / **89.90** / 78.63 / 70.69 / 44.74 | 8.61 / 38.88 / 38.85 / 42.00 / 31.65 | |
| ECS CPU **Maximum（逐分鐘）** | **100.0 / 100.0 / 100.0 / 100.0 / 100.0** | 49.81 / 52.60 / 45.80 / **56.97** / 47.25 | ★ **五分鐘全程頂到 100%** |
| ECS Mem max | 20.5% → **45.7%**（JIT code cache + heap 長出來）| 44.53%（已經長完，平的）| |
| `TargetResponseTime` p50 | **0.059944 s** | **0.0025006 s** | **24×** |
| p95 | **3.237986 s** | **0.0062604 s** | **517×** |
| p99 / max | 5.555555 / **15.614168 s** | 0.036995 / 0.396958 s | |
| k6 p95 / max | 3,414.5 / **15,776.7 ms** | 244.0 / 1,028.9 ms | **14×** |

**兩輪的差別只有一個：JVM 熱不熱。**
- 兩個 task 的 `startedAt` = **2026-08-24T14:40:54Z / 14:40:58Z**（`aws ecs describe-tasks`），task def **`:11`**，同一份 image。
- 14:41Z 開機那一分鐘 CPU 衝到 100%（Spring Boot 啟動），然後 **14:42Z–14:52Z 整整 11 分鐘 idle 在 0.5–2%**。
- **idle 的 JVM 不會 JIT**——熱路徑一次都沒被執行過，所以 14:53Z 第一發流量進來時是**直譯執行 + C1/C2 邊跑邊編譯**，0.5 vCPU 直接被吃光。
- 到 14:57Z CPU Average 已經從 89.90% 掉到 44.74%（JIT 收斂中）；再加上 15:18–15:21Z 那輪 10,001 筆的暖機，15:24Z 開跑時已經完全熱。

⇒ **修正後的判決**：
> **CPU 在穩態不是瓶頸（500 RPS 只用 46.55%），但在「task 剛起來的頭 ≈ 4 分鐘」它是【唯一】的瓶頸，而且嚴重到讓服務只剩 71% 的吞吐與 517 倍的 p95。**
> 這同時解釋了 Day 33 兩輪的第一分鐘為什麼都是最慢的一分鐘（Run 1 max **9.216 s**、Run 2 max **1.954 s**）——那是同一個現象的小號版本。
> ★ **這不是「擴 ECS」能解的**（加台數只會加更多要暖機的 JVM），要解要靠 **CRaC / AppCDS / 部署後 warm-up probe**，或**在 deploy 流程裡加一段暖機流量再切 target group**。

---

## 4. 量化模型（可以拿去預測）

```
每則訊息成本    = ClickEventProcessingDuration Sum / 該窗收到的訊息數
                Day33 Run 1（batch 1.41）: 70.315 ms / 1.405 = 50.0 ms
                Day34 cold （batch 5.89）: 1,654,952 ms / 30,156 = 54.9 ms
                Day33 Run 2（batch 9.99）: 578.588 ms / 9.989 = 57.9 ms
                Day34 warm （batch 9.90）: 1,804,667 ms / 29,312 = 61.6 ms
每次 invocation 固定開銷 = Duration avg − ClickEventProcessingDuration avg
                Day33 Run 2: 638.054 − 578.588 = 59.5 ms
                Day34 cold : 381.573 − 323.486 = 58.1 ms
                Day34 warm : 674.311 − 611.130 = 63.2 ms      ← 三輪都在 58–63 ms

消費能力（Day 33 回推） = (65,090 − 728) / 540 s          = 119.2 則/s
消費能力（Day 34 直測） = NumberOfMessagesDeleted 穩態平均 = 112.7 則/s   ← ★ 兩法差 5.5%
交叉驗證（Day 34）      = 677 invocations/min ÷ 60 × 9.90 則 = 111.7 則/s   ✅ 誤差 0.9%

Little's Law 驗證（Day 34 warm）：L = λ × W = 11.28 /s × 0.674 s = 7.61
CloudWatch 實測 ConcurrentExecutions Average = 8.026                     ✅ 誤差 5.2%
（Day 33 Run 2 同樣算法：11.67 × 0.638 = 7.44 vs 實測 7.87，誤差 5.5%）

要撐 500 RPS 需要的並發 = (344.3 則/s ÷ 9.9 則/批) × 0.674 s = 23.4 ≈ 24
實際只有 10 ⇒ 只有需求的 43%

臨界流量 = 112.7 則/s ÷ 0.6985（GET 佔比實測）= 161.3 RPS
```

**模型 vs 實測（四個點，跨 5 倍流量範圍）**

| 輪次 | 生產 | 模型預測積壓 | 實測 | 絕對誤差 | 相對誤差 |
| --- | --- | --- | --- | --- | --- |
| Day 33 Run 1（99.8 RPS）| 70.0 則/s | 70.0 < 112.7 ⇒ **不積壓** | 佇列深度 max **3** | 3 | ✅ |
| **Day 34 cold（178.6 RPS）** | **125.4 則/s** | (125.4−112.7)×240.4 = **3,053** | 峰值深度 **3,543** | **490** | **−13.8%** |
| **Day 34 warm（249.3 RPS）** | **174.1 則/s** | (174.1−112.7)×240.3 = **14,754** | 峰值深度 **13,980**；窗內 sent−deleted **12,630** | **774** | **+5.5%** |
| Day 33 Run 2（491.7 RPS）| 344.3 則/s | (344.3−112.7)×300 = **69,480** | **66,860**（sent−deleted）| **2,620** | **+3.9%** |

> ★ **註 1 · 為什麼 cold 那一輪相對誤差最大**：它離臨界點（161 RPS）只有 17 RPS，模型是「兩個相近的大數相減」，分母一小相對誤差就會炸。**但它的絕對誤差（490 則）反而是四個點裡最小的。** ⇒ 用這個模型時要看**絕對誤差**，它在 5 倍範圍內都穩定在 **≈ 0.5k–2.6k 則**。
> ★ **註 2 · 「實測積壓」有兩種量法，差 10%**：`窗內 sent − deleted` 依賴窗的邊界（Day 34 warm 的 5 分鐘窗比實際跑的 240 s 多了 21 s 的排空時間 ⇒ 12,630 偏低）；`ApproximateNumberOfMessagesVisible` 的峰值則是 SQS 自己的近似值、而且一分鐘才取樣一次。**兩個都列出來，不要挑對自己有利的那個。**
> ★ **註 3 · 臨界點還沒被真正卡住**：目前只知道 **100 RPS 不積壓、178.6 RPS 會積（峰值 3,543）**，預測值 161 RPS 落在這個區間裡 —— **沒有被推翻，但也還沒被證實**。要證實需要專門跑一輪 **160 RPS**（見 §9）。
> ★ **註 4 · 每則成本不是常數，它跟 batch 大小【正相關】**：50.0 → 54.9 → 57.9 → 61.6 ms（batch 1.41 → 5.89 → 9.99 → 9.90）。**這條直接削弱了修法 B**（見 §5）。

---

## 5. 預計修法（三個，附預期效果與代價）

**A. 提高 Lambda 帳號並發上限 10 → 100（★ 唯一能真正解決的）**
- 預期效果：消費能力 112.7 → **≈ 1,127 則/s** ⇒ 可撐 **≈ 1,610 RPS**（×10）
- 代價：配額本身**免費**；要開 support case
- ⚠️ 阻礙：`url-shortener-dev` **沒有 `servicequotas` 權限**（2026-08-24 再測一次仍是 `AccessDeniedException: not authorized to perform servicequotas:GetServiceQuota on lambda/L-B99A9384`）⇒ 要用 root / Console
- ⚠️ 副作用：寫入速率同步 ×10。**Day 34 實測**：`ClickAnalytics` 窗內 `ConsumedWriteCapacityUnits = 29,274`（≈ 112 WCU/s ÷ 10 個短碼 = **11.2 WCU/s 每 item**）⇒ 並發開到 100 會變 **≈ 112 WCU/s 每 item**（單 item 上限 1,000 WCU/s，仍安全）；但**若流量集中到單一熱門短碼就會撞牆** ⇒ 屆時需要 write sharding

**B. 加大 batch（不用求人，但收益比原本估的更小）**
- 做法：`BatchSize 10 → 100` + `MaximumBatchingWindowInSeconds ≥ 1`（AWS 規定 BatchSize > 10 時必須設）+ Lambda `timeout 5s → 30s` + SQS `VisibilityTimeout 30s → 180s`（AWS 建議 ≥ 6× timeout）
- 預期效果：只省下每次 invocation 的 **≈ 63 ms 固定開銷**。在 batch 10 時這開銷攤下來只有 **63.2 / 9.9 = 6.4 ms/則**，佔每則總成本（61.6 + 6.4 = **68.0 ms**）的 **9.4%** ⇒ 理論上界 **+10%**，不是原估的 +44%
- ★★ **為什麼比原估更差**：§4 註 4 的四輪資料顯示**每則成本隨 batch 變大而上升**（50.0 → 61.6 ms，batch 1.41 → 9.9）。**省下的固定開銷可能被上升的每則成本吃掉。** ⇒ **這條不要外插，Day 36 實測才算數。**
- ⚠️ 副作用：單次失敗的爆炸半徑從 10 則變成 100 則
- ⚠️ **這個修法必須被 API 驗證**（BatchSize > 10 的規則）——Day 36 實作時會當場知道

**★ B′（Day 34 新發現，比 B 便宜也比 B 確定）：把 handler 裡那一次 `put_metric_data` 拿掉**
- 現況：`lambda_function.py:78` 每次 invocation 同步呼叫一次 `cloudwatch.put_metric_data`，而且它**在 `ClickEventProcessingDuration` 的計時之外**（`duration_ms` 在 line 77 就算完了）⇒ **它就是那 58–63 ms 固定開銷的主體**
- 做法：改用 **EMF**（把 metric 用結構化 JSON `print` 到 stdout，CloudWatch Logs 自動抽取），API 呼叫歸零
- 預期效果：`Duration` 674.3 → **≈ 611 ms** ⇒ 吞吐 112.7 → **≈ 124 則/s（+10%）**
- 代價：**幾乎沒有**。不用改 batch、不用改 timeout、不用改 visibility、爆炸半徑不變、不用求人
- ⚠️ 但它跟 B 是**同一份收益**（都是在省固定開銷），**不能相加**

**C. 用 `TransactWriteItems` 把每則的兩次序列 DynamoDB 呼叫合併成一次**
- 現況（`lambda_function.py:33-55`）：`ProcessedEvents.put_item`（條件寫入，冪等 claim）→ `ClickAnalytics.update_item`（ADD clickCount），**序列**，合計 61.6 ms/則
  - Day 34 實測佐證：`ProcessedEvents` 窗內 WCU **29,277**、`ClickAnalytics` **29,274**，而該窗處理了 29,212 則 ⇒ **每則正好兩次寫入**
- ★ **不能並行**：claim 必須先成功才能聚合（否則 claim 失敗時已經多算一次）——**這兩步有因果順序**
- ★ **不能用 `BatchWriteItem`**：它**不支援 `ConditionExpression`** ⇒ 會拆掉 Day 30 才修好的冪等保證（連同 line 67-73 的補償刪除一起失效）
- ⇒ `TransactWriteItems` 是唯一同時保住「條件寫入」和「一次 API 呼叫」的選項
- 預期效果：每則 61.6 → **≈ 35–40 ms**（每則總成本 68.0 → **41.4–46.4 ms**）⇒ 吞吐 112.7 → **≈ 165–185 則/s（+46~64%）**
- 代價：交易寫入的 WCU 是普通寫入的 **2 倍**（29k → 58k WCU/窗）；失敗語義變成整筆 rollback

**★ 三個合起來看**：B′ + C 最多把天花板推到 **≈ 192–219 則/s ≈ 274–313 RPS**，**仍然撐不住 500 RPS**。
⇒ **要真正解決，A 是必要的；B′ / C 是「在等配額的期間先把每一分並發用好」。**
⇒ **順序建議：B′（今天就能做、零代價）→ C（要改 handler 語義）→ A（要求人）。**

---

## 6. 預期效果（可證偽的數字，Week 8 拿來對答案）

同樣 500 RPS × 5 分鐘、同樣 2 台 ECS、同樣 10 個短碼、**同樣先暖機**：

| 指標 | 現在（Day 33）| 做完 A（並發 100）之後的預測 |
| --- | --- | --- |
| SQS 佇列峰值 | **65,090** | **< 500** |
| 最舊訊息年齡峰值 | **581 s** | **< 5 s** |
| Lambda `Throttles` | 563 / 6 min | **0** |
| Lambda `ConcurrentExecutions` max | **10（貼死）** | ≈ 24（★ **不再貼死才是重點**）|
| 跑完後排空時間 | 10 分鐘 | **< 10 秒** |
| k6 p99 | 516.6 ms | **不會有明顯變化**（★ 它本來就看不到這條路徑）|

> ★ **最後一列是刻意寫的**：如果有人拿「k6 的 p99 沒改善」來說優化沒用，這一列就是預先寫好的反駁。
> ★★ **Day 34 已經先驗證了這一列**：到達率從 491.7 砍到 249.3（−49%），k6 p99 從 516.6 掉到 **256.3 ms**，但 **Lambda `Throttles` 只從 563 掉到 489（−13%）、`ConcurrentExecutions` max 一樣貼死在 10**。⇒ **k6 的數字和非同步鏈路的健康度是兩條互不相干的曲線，這件事現在有兩組資料支持。**

---

## 7. 次要發現（不是 bottleneck #1，但要記錄）

**1. ★ `CallerRunsPolicy` 的觸發條件不是「到達率」，是「CPU 有沒有飽和」——Day 33 的假設被自己的資料推翻了一半**

Day 33 寫的驗證方式是「依執行緒名分群，**若假設成立，Run 2 應該壓倒性落在 `http-nio-*`**」。Day 34 照做了：

```
filter @message like /published click event/
| parse @message "[url-shortener] [*]" as thread
| fields (thread like /click-async/) as isAsync
| stats count(*) as n by isAsync
```

| 輪次 | 達成 RPS | ECS CPU max | `click-async-*` | Tomcat 執行緒（＝`CallerRunsPolicy` 退回）|
| --- | --- | --- | --- | --- |
| **Day 34 cold** | 178.6 | **100%（全程）** | 17,550（58.20%）| **12,606（41.80%）** 🚨 |
| **Day 34 warm** | **249.3** | 56.97% | 41,828（**99.97%**）| **14（0.03%）** |

★★ **到達率更高的那一輪，退回率反而低了 1,400 倍。**
⇒ 決定 `CallerRunsPolicy` 會不會觸發的**不是每秒丟幾個任務**，而是**每個任務要跑多久**。CPU 被 JIT 吃光時，`SqsClient.sendMessage` 這種本來只要幾毫秒的呼叫被拖長，4 條執行緒排不完 100 格的佇列 ⇒ 溢位 ⇒ 退回 Tomcat 執行緒 ⇒ **請求延遲和非同步延遲互相放大**（這就是 cold 那輪 p95 3.2 s 的正回饋迴路）。
- 兩輪都是 **0 筆 `RejectedExecutionException`**、**0 筆任務遺失**（`published click event` 行數 = ALB 3XX：cold 30,156、warm 41,842，一則不差）——`CallerRunsPolicy` 不丟例外，它讓呼叫端自己跑。
- ALB 分流極均勻：warm 兩個 task 20,962 / 20,880（差 0.39%）；cold 15,124 / 15,032（差 0.61%）。
- ⇒ **修 `AsyncConfig`（core 2 / max 4 / queue 100）不是穩態的優先事項**，但它是「冷啟動事故」的放大器。真正該修的是暖機（§3 ★）。

**2. cache stampede（快取擊穿）第二次被量到，而且這次連鎖到了 CPU**
- Day 33 Run 2：只有 10 個 key，第一分鐘卻產生 **23 次** miss。
- **Day 34 cold：166 次 miss**（Redis `CacheMisses` 與 `UrlShortener/CacheHit` 的 SampleCount−Sum 都是 **166**，兩邊一致），全部集中在 **14:53Z 那一分鐘**。
- **Day 34 warm：0 次 miss**（`CacheHit` Average = **1.0**、Redis `CacheMisses` = **0**、`SetTypeCmds` = **0** ⇒ 連一次回填都沒有）。
- ⇒ **到達率越高、且回填越慢，重複 miss 越多**：16（暖機 ~35 RPS）→ 23（500 RPS，CPU 46%）→ **166（250 RPS，但 CPU 100%）**。★ **166 這個數字證明 stampede 的規模由「空窗期有多長」決定，不是由「到達率有多高」決定**——跟 §7.1 是同一個機制。
- 今天無害（`UrlMappings` 全天 `ThrottledRequests = 0`），key 數量放大後會變事故。

**3. 短碼碰撞率遠高於隨機該有的水準**
- `generateShortCode()` = `Long.toString(System.nanoTime(), 36)` 取後 7 碼——**這不是亂數，是時間戳低位**（取後 7 碼 ≡ `nanoTime mod 36^7`，約每 78 秒繞回原點）。
- Day 33 Run 2：44,376 次 POST → **4 筆**碰撞（均勻 36^7 的期望值 0.013）。
- **Day 34 warm：18,059 次 POST → 1 筆**碰撞（期望值 **0.0021** ⇒ **實測 ≈ 期望的 480 倍**）。
- 今天無害（3 次 retry 全部第一次就成功，0 個失敗請求），寫入量再大一個數量級會出現「三次都撞」→ 500。

**4. 量測基礎設施的缺口（★ Day 35 更正）**

> ⚠️ **更正**：本節原本寫「dashboard 沒有 `ApproximateAgeOfOldestMessage`」——**這是錯的**。
> 它從 Day 31（commit `5e557bf`）起就在 `terraform/dashboard.json:99`，是 widget ⑤ 的第 3 條
> metric（`yAxis: right`、`stat: Maximum`），而且線上 dashboard 實測也有
> （`aws cloudwatch get-dashboard … | grep -c ApproximateAgeOfOldestMessage` → 1）。
> ⇒ Day 33 那 581 秒、Day 34 那 125 秒**在 dashboard 上是看得到的**。
> ★ 真正的問題不是「看不到」，是**沒有人在看**——見下面的 (a)。

- **(a) 🚨 沒有任何一個 alarm 盯著積壓。** 實測 `describe-alarms`：**全帳號只有 1 個 alarm**
  （`url-click-events-dlq-not-empty`）。⇒ 佇列堆到 65,090 則、最舊訊息落後 581 秒，
  **沒有任何人會被通知**。★ **「有圖表」和「有告警」是兩件事：圖表要人去看，告警會來找人。**
- **(b) dashboard 沒有 ElastiCache** ⇒ 排除 Redis 只能靠 CLI（Day 33 選做 B → Day 34 選做 C，仍未做）
- **(c) dashboard 沒有 ECS `CPUUtilization` 的 Maximum**（widget ③ 的 `stat` 是 `Average`）
  ⇒ Day 34 cold 那輪「五分鐘全程 100%」在 Average 上只看到 63–90%，**看起來像還有餘裕，其實已經滿了**

  
**5. 量測衛生：Day 33 §5 的兩條待辦，一條沒做、一條做了**
- ❌ **`maxVUs` 200 → ≥300 沒做**（`load-test/baseline.js` 未修改，仍是 `maxVUs: 200`）。cold 那輪因此打到天花板 → **`dropped_iterations = 17,061`（28.4%）、只達成 178.6 RPS**。⇒ **cold 那輪的延遲數字嚴重偏樂觀，不能拿來當 baseline。**
- ✅ **暖機提前做了**：15:18–15:21Z 先跑了 10,001 筆，15:24:39Z 才正式開跑 ⇒ warm 那輪的 ALB `RequestCount` **59,901 ＝ k6 `http_reqs` 59,901**，**一筆雜訊都沒有**。

**6. 資料衛生**：`UrlMappings` 已累積約 **54,841 筆**（此為 `describe-table` 的 `ItemCount`，AWS 約每 6 小時才更新一次，**沒有算進 Day 34 全天的 33,589 筆 2XX 寫入**）；`ProcessedEvents` **205,248 筆**，但 TTL `expiresAt` 已 ENABLED，7 天自動清。

**7. ALB 上的公網雜訊（全天，兩個壓測窗內都是 0）**：`Target_5XX = 13`、`ELB_5XX = 187`、`ELB_4XX = 21`。⇒ `GET /` 回 500 的問題仍在（Week 8 待辦）。

---

## 8. 怎麼重現這份分析

```bash
export AWS_REGION=ap-east-2 AWS_PAGER=""
ALB=app/url-shortener-alb/6fb50f3251cd06b2

# Day 33 兩輪（--period 一律用 360 或 60）
R1S=2026-08-22T16:09:00Z ; R1E=2026-08-22T16:15:00Z    # 100 RPS
R2S=2026-08-22T17:54:00Z ; R2E=2026-08-22T18:00:00Z    # 500 RPS

# Day 34 兩輪（--period 一律用 300 或 60）
C1S=2026-08-24T14:53:00Z ; C1E=2026-08-24T14:58:00Z    # 250 RPS · cold（JVM 未暖機，數據僅供對照）
N1=2026-08-24T15:24:39Z                                # 250 RPS · warm 實際起跑時刻
W1S=2026-08-24T15:24:00Z ; W1E=2026-08-24T15:29:00Z    # 對齊到分鐘的比對窗

# 然後照 §2 的七條指令跑一次即可。
# ⚠️ --period 必須等於窗長（Day 33 = 360、Day 34 = 300）或 60（逐分鐘），【不要用 3600】。
# ⚠️ ECS CPU 一定要同時看 Average 和 Maximum —— cold 那輪只看 Average 會誤判成「還有餘裕」。
```

---

## 9. Day 35 / Week 8 待辦（★ 由本文的結論倒推）

- [ ] **★ 先做 B′**（把 `lambda_function.py:78` 的 `put_metric_data` 改成 EMF）——零代價、不用求人、預期 +10%，而且能**把「固定開銷是不是真的來自這一次 API 呼叫」這個假設一次驗掉**
- [ ] **★ 專門跑一輪 160 RPS × 5 min**，卡住 §4 註 3 那個臨界點（預測：佇列深度峰值 < 500、年齡 < 10 s；若積壓 > 2,000 表示模型高估了消費能力）
- [ ] **★ 修量測工具**：`load-test/baseline.js` 的 `maxVUs` 200 → **300**（Day 33 就寫了、Day 34 沒做、cold 那輪付了 17,061 筆 `dropped_iterations` 的代價）
- [x] **★ 修 dashboard 的缺口**（§7.4，**★ Day 36 更正並完成一半**）：
      ~~`ApproximateAgeOfOldestMessage`~~ **widget 從 Day 31 就有了**（§7.4 的更正）⇒ 真正缺的是
      **alarm**（✅ Day 36 建了 `url-click-events-lagging`）、**ECS CPU 的 Maximum**（✅ Day 36 補上）；
      **ElastiCache widget 仍未做**（排 Day 39）
- [ ] 開 support case 提高 Lambda 帳號並發 10 → 100（要用 root / Console，`url-shortener-dev` 沒有 `servicequotas` 權限）
- [ ] Day 36：實作修法 B / C，**並且用同一把尺（250 RPS × 4 min，先暖機）重跑**，對答案的欄位是 `ClickEventProcessingDuration` avg 與 SQS 峰值深度
- [ ] 解冷啟動（§3 ★）：deploy 後先打暖機流量再切 target group，或評估 AppCDS
- [ ] Week 8：`generateShortCode()` 改用 `SecureRandom`（§7.3）；`GET /` 回 404 而不是 500（§7.7）
- [ ] Week 8：壓測端搬到同 region EC2，消掉 **158 ms** 的量測偏差
