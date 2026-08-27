# Iteration 1 結果 · Lambda 記憶體 128→512（D）+ put_metric_data→EMF（B′）

> 部署：Day 36（commit `c9d8331`）。量測：Day 37（2026-08-27）。
> 事前登記的預測寫在 `optimization-plan.md` §6，**本文寫完之前沒有動過它一個字**。
> ⚠️ 本文所有 CloudWatch 數字都可以用 §6 的指令重查（metrics 保留 15 個月）。
> 🚨 **例外**：`/ecs/url-shortener-task` 的 log 保留期只有 **7 天** ⇒ §5 的執行緒統計已逐字抄錄，2026-09-03 之後查不到。

## 0. 量測條件（★ 沒有這一節，下面的數字沒有意義）

| 項目 | Day 34 warm（before）| **Day 37 Round 1（after）** | 一致嗎 |
| --- | --- | --- | --- |
| 日期 / 窗（UTC）| 2026-08-24 15:24:39Z 起 4 min<br>對照窗 `15:24:00Z–15:29:00Z` | 2026-08-27 **16:20:05Z** 起 4 min<br>對照窗 **`16:20:00Z–16:25:00Z`** | — |
| ECS | 2 台 × 0.5 vCPU / 1 GB、task def **`:11`** | 2 台 × 同規格、task def **`:14`** | ⚠️ revision 不同，但 `git diff --stat 6f8ea8a c9d8331 -- shortener/` 是**空的**（Java 一行沒改）；image tag `6f8ea8af…` → `c9d8331c…` 是 CI 按 commit SHA 重建 |
| `HealthyHostCount` | 2（全程）| **2（全程）** | ✅ 實測 |
| 暖機 | 15:18–15:21Z，10,001 筆 | **16:13–16:17Z，14,349 筆**（ALB `RequestCount` 逐分鐘 2,421 / 2,379 / 3,600 / 3,600 / 2,349）| ✅ 同量級 |
| 壓測腳本 | `baseline.js`（`maxVUs 200`）| `baseline.js`（`maxVUs 300`）| ⚠️ 改過，但 before 的 `vus_max` = 149 **沒碰到天花板** ⇒ 對這一輪惰性 |
| 短碼 | 同 10 個 | 同 10 個 | ✅ |
| RATE / DURATION | 250 / 4m | 250 / 4m | ✅ |
| **Lambda** | **128 MB**、`put_metric_data` | **512 MB**、**EMF** | ★ **這就是處理組** |
| Lambda 並發上限 | 10 | **10（沒動）** | ✅ |
| ESM BatchSize | 10 / window 0 | **10 / window 0（沒動）** | ✅ |
| 資料量 | `UrlMappings` ≈ 54,841 筆 | `UrlMappings` 88,430 筆（Day 36 收工實測）| ⚠️ 已檢查：讀路徑 `CacheMisses` = **0**、寫是單 item PutItem ⇒ **不是干擾變數** |
| 壓測端 | 本機（UTC-7），RTT ≈ 158 ms | 同一台、同一條線（k6 `http_req_duration.min` = **157.822 ms**）| ✅ |

### 0.1 🚨 一個必須先講的方法學修正（否則 before/after 會差 3%）

2.4 的指令寫「窗 = N1 + 4 分鐘、`--period 240`」，但 **before 的數字（`findings.md` §2 證據④）是用 5 分鐘窗 `--period 300` 量的**，而且今天是 16:20:**05**Z 開跑 ⇒ 4 分鐘窗會切掉最後 5 秒。

| | 4 min 窗 `16:20–16:24`（照 2.4 字面）| **5 min 窗 `16:20–16:25`（本文採用）** |
| --- | --- | --- |
| SQS Sent / Received / Deleted | 40,707 / 40,701 / 40,690 | **42,010 / 42,010 / 42,010** |

⇒ **本文全部使用 5 分鐘窗**，才跟 before 同一把尺。

## 1. Round 1 · 250 RPS × 4 min（同一把尺 · before/after）

### 1a. k6（客戶端）

| 指標 | before | after | Δ |
| --- | --- | --- | --- |
| 實際 RPS | 249.29 | **248.98** | −0.1% |
| p50 | 165.236 ms | **169.593 ms** | +2.6% |
| p95 | 244.045 ms | **252.466 ms** | +3.5% |
| p99 | 256.332 ms | **302.653 ms** | +18.1% |
| max | 1,028.932 ms | **955.558 ms** | −7.1% |
| `dropped_iterations` | 100（0.167%）| **96（0.160%）** | ✅ < 1% |
| `vus_max` | 149 | **145** | ✅ < 300，也 < 舊尺的 200 |
| `http_req_failed` | 0.00% | **0.00%** | — |

> ★ **這一組預期【幾乎不會動】**，而那正是重點：**k6 走的是同步路徑，而我們改的是非同步路徑。**
> `findings.md` §6 早就把這一列寫成「預先寫好的反駁」——**如果有人拿「k6 的 p99 沒改善」說優化沒用，這一列就是答案。**
> ⚠️ 但 p99 +18.1% 不是雜訊，它跟 §1b 最後兩列（ECS CPU、`TargetResponseTime`）是同一件事 ⇒ 見 §5。

### 1b. 伺服器端（★ 這裡才是今天的答案）

| 指標 | before | after | Δ | 判定 |
| --- | --- | --- | --- | --- |
| **每則成本**（`Sum ÷ MessagesReceived`）| **61.5675 ms** | **9.8146 ms** | **−84.1%** | ✅ **門檻 ≤ 43.1 ms** |
| batch（`Received ÷ Invocations`）| 9.896 | **2.189** | −77.9% | ★ 記下來，下一列要靠它解讀 |
| `ClickEventProcessingDuration` avg | 611.130 ms | **21.484 ms** | −96.5% | ⚠️ **受 batch 汙染，只當參考** |
| `Duration` avg | 674.311 ms | **23.270 ms** | −96.6% | |
| **固定開銷**（`Duration − 上一列`）| **63.180 ms** | **1.786 ms** | **−97.2%** | ✅ **門檻 < 15 ms** |
| SQS 深度峰值 | **13,980** | **0**（逐分鐘全 0）| — | ✅ 預測 ≈ 0 |
| SQS 最舊訊息年齡峰值 | **125 s** | **4 s** | −96.8% | ✅ 預測 ≈ 0 |
| `Throttles`（窗內）| **489** | **643** | **+31.5%** | ❌ 預測 0～個位數 ⇒ 見下 |
| 節流率（`Throttles ÷ (Inv+Throttles)`）| 14.17% | **3.24%** | **−77%** | ★ 這才是可比的那個數 |
| `Invocations` | 2,962 | **19,192** | ×6.5 | ★ batch 塌了 ⇒ 次數變多 |
| `Errors` | 0 | **0** | — | ✅ |
| `ConcurrentExecutions` **Maximum** | **10（每分鐘）** | **10（每分鐘）** | 不變 | ⚠️ 見 §3.1 |
| `ConcurrentExecutions` **Average** | **7.80 – 8.22** | **3.31 – 3.58** | **−57%** | ★★ 真正掉的是這個 |
| 消費能力（`Deleted` 穩態）| **112.7 則/s**（飽和時實測）| **140.0 則/s**（= 到達率，**未飽和 ⇒ 只是下界**）| ≥ +24% | ⚠️ 這把尺量不到新天花板 ⇒ §4 |
| ECS CPU avg / **max** | 39.91% / **56.97%** | **66.76% / 95.43%** | **×1.67 / ×1.68** | 🚨 **反向惡化 ⇒ §5** |
| `TargetResponseTime` p50 / p95 / p99 | 2.5006 / 6.2604 / 36.995 ms | **4.0315 / 63.755 / 81.324 ms** | ×1.6 / **×10.2** / ×2.2 | 🚨 **反向惡化 ⇒ §5** |

**★ `Throttles` 變多但其實變好了**：batch 從 9.90 塌到 2.19 ⇒ invocation 次數 ×6.5（2,962 → 19,192），被節流的「機會」跟著變多。**節流率 14.17% → 3.24%（−77%）**，而且 `Received = Deleted = Sent = 42,010`、佇列深度 0 ⇒ **這 643 次節流全部被 ESM 重試吸收，一則都沒掉、一則都沒卡。**

### 1c. ★ 六個獨立計數器對到同一個數（承 Day 33/34 的交叉驗證紀律）

```
ALB RequestCount        59,904   = 2XX 17,894 + 3XX 42,010          ✅ 一則不差（4XX/5XX 皆 0）
k6 iterations           59,904   = ALB RequestCount                  ✅ 一則不差
ALB 3XX                 42,010
SQS Sent                42,010   = ALB 3XX                           ✅
SQS Received            42,010   = SQS Sent                          ✅
SQS Deleted             42,010   = SQS Received                      ✅ ★ before 是 41,842/29,312/29,212
ClickAnalytics  WCU     42,010   = 每則正好一次 UpdateItem            ✅
ProcessedEvents WCU     42,010   = 每則正好一次 PutItem               ✅
```
> ⚠️ 唯一沒對上的：`UrlMappings` WCU **17,724** vs 2XX **17,894**（差 170，0.95%）。**未驗證**，不影響本文任何結論。

## 2. 歸因：D 和 B′ 各貢獻了多少（★ 用 `optimization-plan.md` §3 的算術）

```
① D 的縮放倍率 k
   同輪直接相除： k = 61.5675 / 9.8146 = 6.27x
   ⚠️ 這個 6.27x 【被 batch 汙染】——before batch 9.90、after batch 2.19，
      而 findings §4 註 4 證明每則成本會隨 batch 上升。
   ★ batch 對齊的估計（兩邊都接近 batch 1）：
      Day33 Run1 (batch 1.41 @128MB) 50.046 ms  vs  Day36 煙霧 (batch 1.00 @512MB) 15.7585 ms
      => k = 3.18x   ← 保守下界（1.41 vs 1.00 其實還對 128MB 那邊有利）
   ⇒ 【D 的真實縮放倍率落在 3.2x – 6.3x】，而記憶體只加了 4x（128→512）。
     ★ 這一列只有 D 動得到——B′ 的 emit_emf() 在 duration_ms 之後（lambda_function.py:104-105），
       不在計時範圍內。

② B′ 的貢獻（固定開銷 63.180 → 1.786 ms）
   上界估計：「只做 D」的固定開銷 ≈ 63.180 / k（若它 100% 是 CPU）
      k = 4.00 => 15.80 ms  =>  B′ 貢獻 ≈ 15.80 − 1.786 = 14.0 ms
      k = 6.27 => 10.07 ms  =>  B′ 貢獻 ≈ 10.07 − 1.786 =  8.3 ms
   ⇒ 61.4 ms 的固定開銷降幅裡，B′ 約佔 8–14 ms，D 約佔其餘 47–53 ms。

③ 交叉檢查（Day 36 煙霧測試，batch = 1.0、512 MB、真實流量前）：
   每則 15.7585 ms · 固定開銷 1.604 ms
   ⇒ B′ 把固定開銷從 63.18 壓到 1.60（−97.5%），今天真實流量下量到 1.786（−97.2%）
   ⇒ 【B′ 的效果已經是定論，兩個獨立場景差 11%】
```

## 3. 事前登記的預測 vs 實測（★ `optimization-plan.md` §6，一個字都沒改過）

| 指標 | before | 預測 | 實測 | 對了嗎 |
| --- | --- | --- | --- | --- |
| `ClickEventProcessingDuration` avg | 611.130 ms | 190–358 ms | **21.484 ms** | ⚠️ 遠低於預測區間，但**理由是錯的** ⇒ §3.1 |
| `Duration` avg | 674.311 ms | 198–366 ms | **23.270 ms** | ⚠️ 同上 |
| 固定開銷 | 63.180 ms | < 15 ms | **1.786 ms** | ✅ **對，而且超額** |
| 消費能力 | 112.7 則/s | 200–400 則/s | **≥ 323.1 則/s**（Round 2 未飽和下界）| ✅ **落在區間內** |
| 250 RPS 佇列峰值 | 13,980 | ≈ 0 | **0** | ✅ **★ 最大膽的那一格，中了** |
| `ConcurrentExecutions` max | 10 | 「仍然貼死 10」 | **Max 仍 10 / Avg 8.0 → 3.4** | ⚠️ **一半對一半錯** ⇒ §3.1 |
| `Throttles` | 489 | 0～個位數 | **643** | ❌ **錯**（但節流率 14.17% → 3.24%）|

### 3.1 ★★ 預測表本身的三個瑕疵（Day 37 發現，**沒有回頭修改預測表**）

1. **門檻寫在一個會被 batch 汙染的指標上。** `ClickEventProcessingDuration` 是**每次 invocation 的迴圈時間**，跟 batch 成正比；而優化成功的直接後果就是**佇列變淺 ⇒ batch 從 9.90 塌到 2.19**。⇒ 那條「≤ 427.8 ms」的線會被**輕鬆通過，但理由是錯的**。
   ⇒ 正確的等價門檻是**每則成本 ≤ 43.1 ms**（`61.5675 × 0.7`），本文全部以它為準。實測 **9.8146 ms**。

2. **「`ConcurrentExecutions` 仍然貼死 10」和「佇列峰值 ≈ 0」看起來自相矛盾——但真正的答案是【Maximum 和 Average 是兩個不同的統計量】。**
   Day 37 的判定規則（`optimization-plan.md` §7 規則 4）預測「max 3–6，不是 10」；`optimization-plan.md` §6 預測「仍然貼死 10」。**實測兩邊各對一半：**

   | | before（Day 34 warm）| after（Round 1）| after（Round 2）|
   | --- | --- | --- | --- |
   | `ConcurrentExecutions` **Maximum**（逐分鐘）| 10.0 | **10.0** | **10.0** |
   | `ConcurrentExecutions` **Average**（逐分鐘）| 7.80 – 8.22 | **3.31 – 3.58** | **4.67 – 4.90** |

   ⇒ **「還會碰到天花板」和「貼死在天花板」是兩件事。** before 是 Avg 8.0（真的貼死）；after 突發時仍會摸到 10（所以 Max 還是 10、Throttles 還有 643 次），但**平均只用掉 3.4 / 10**。
   ⇒ §6 那一列在 **Maximum** 上是對的、在「貼死」上是錯的；§7 規則 4 在 **Maximum** 上是錯的、而它猜的 3–6 剛好命中 **Average**。
   ⇒ **正確的判定指標是 Average，不是 Maximum。** 帳號並發上限是天花板不是地板，而**只有 Average 能告訴你離天花板多遠**。

3. **`Throttles` 的門檻寫成了絕對值，但它跟 batch 綁在一起。** batch 塌了 ⇒ invocation 次數 ×6.5 ⇒ 被節流的機會 ×6.5。**絕對值 489 → 643（+31.5%）看起來變差，節流率 14.17% → 3.24%（−77%）才是真相。**
   ⇒ 教訓跟第 1 點同一條：**優化會改變指標的「組成」，所以門檻必須寫在一個對那個組成不變的量上。**

> ★★ **這一節刻意留在報告裡。** 一張被事後修飾過的預測表，價值比沒有預測表更低——**因為它會讓你以為自己有紀律。**

## 4. Round 2 · 500 RPS × 5 min（找新天花板）+ 選尺推導

> 窗：**`16:52:00Z–16:57:00Z`**（`--period 300`）。排空觀察窗到 `17:07:00Z`。

```
為什麼要換尺：
   積壓速率 = 生產速率 − 消費能力 = (RPS × 0.6985) − C
   before（C = 112.7）：250 RPS => 174.6 − 112.7 = +61.9 則/s => 4 分鐘堆 ≈ 14,900（實測 13,980）✅
   after （C ≥ 323.1）：250 RPS => 174.6 − 323.1 = 【負的】       => 永遠不會積壓，佇列恆為 0
★★ 一把好尺的定義不是「精準」，是【它的量程涵蓋你要找的那個數字】。
   250 RPS 在優化前是好尺（天花板 161 RPS 落在它下面）；優化後非同步天花板被推到 462 RPS 之上，
   它整個落在量程之外 —— 它只會回報一個漂亮的 0，而【0 沒有資訊量】。
⇒ 兩輪的分工：舊尺證明「我改好了」，新尺回答「下一個瓶頸在哪」。
⚠️ 但 Round 2 也【沒有】量到非同步天花板 —— 因為【同步路徑先垮了】（§5）。
   我們只知道 C ≥ 323.1 則/s（佇列全程 0），上界【未量到】。
```

| 指標 | Day 33 Run 2（舊尺，僅供定性對照）| **Day 37 Round 2（新尺）** |
| --- | --- | --- |
| 實際 RPS / `dropped_iterations` / `vus_max` | 491.72 / 2,337（1.55%）/ **200（撞天花板）** | **464.39 / 10,152（7.26%）/ 300（🚨 又撞天花板）** |
| SQS 深度峰值 / 年齡峰值 | 65,090 / 581 s | **0 / 2 s** ✅ |
| SQS Sent / Received / Deleted | 103,288 / — / ≈36,400 | **96,969 / 96,963 / 96,917**（6 分鐘窗：Sent = Deleted = **97,510**，一則不差）|
| 每則成本 / batch | — | **10.2844 ms / 4.099** |
| 固定開銷 | — | **2.1683 ms** |
| `Invocations` / `Throttles` / `Errors` | — | **23,658 / 645（節流率 2.65%）/ 0** |
| `ConcurrentExecutions` Max / **Avg** | 10（貼死）| **10 / 4.67–4.90** |
| ECS CPU avg / **max** | 46.55% / **94.92%** | **94.97% / 99.99%** 🚨 |
| `TargetResponseTime` p50 / p95 / p99 | — | **12.153 / 830.24 / 1,471.4 ms** 🚨 |
| k6 p50 / p95 / p99 / max | 165.431 / 250.853 / 516.615 / 2,113.1 ms | **205.548 / 1,021.749 / 1,672.241 / 4,521.4 ms** |
| `ClickAnalytics` WCU（窗內）/ 全表 WCU/s / 每 item WCU/s | — | **96,944 / 323.1 / 32.31**（單 item 上限 1,000 ⇒ **3.2%**；單分割區上限 1,000 ⇒ **32.3%**）|
| `ProcessedEvents` / `UrlMappings` WCU | — | **96,945 / 42,110**（`ThrottledRequests` 三張表皆 **0**）|
| DynamoDB `SuccessfulRequestLatency`（伺服器端）| — | UpdateItem **2.539 ms** / PutItem **2.617 ms** / **2.598 ms**（before 2.4211 / 2.5305）|
| Redis `CacheMisses` / `EngineCPUUtilization` max | 第一分鐘 23 次 | **0（全程）/ 1.27%** ✅ |

> ⚠️ **與 Day 33 Run 2 只能做定性比較**（有沒有積壓），不能做定量比較——舊尺 `maxVUs 200` 只打出 491.7 RPS 且丟掉 2,337 筆 iteration。

### 4.1 ★ 交叉驗證：Round 2 的帳也對得起來

```
ALB RequestCount   139,139 = 2XX 42,104 + 3XX 97,028 + 5XX 7        ✅ 一則不差
ALB 3XX             97,028 ≈ SQS Sent 96,969（窗邊差 59，0.06%）
UrlMappings WCU     42,110 ≈ ALB 2XX 42,104（差 6，0.014%）          ✅
k6 http_reqs       139,849 vs ALB RequestCount 139,139（差 710，0.51%）
   ⇒ 窗尾在途請求。佐證：6 分鐘窗的 SQS Sent 97,510 − 5 分鐘窗 96,969 = 541 則 ≈ 774 個請求 ✅
```

**★ 那 7 個 5XX 不是壓力造成的**（實測 `/ecs/url-shortener-task` log，`16:55:49.759Z–16:55:51.459Z` 共 7 筆，1.7 秒內）：

```
org.springframework.web.servlet.resource.NoResourceFoundException: No static resource .
org.springframework.web.servlet.resource.NoResourceFoundException: No static resource .well-known/security.txt.
```

⇒ **是網際網路掃描器打 `GET /` 和 `GET /.well-known/security.txt`**，被 `GlobalExceptionHandler` 轉成 500。
⇒ 這正是 `findings.md` §7.7 早就記下的「`GET /` 回 500 而不是 404」——**今天第一次在真實流量裡看到它發射**。k6 完全沒受影響（`http_req_failed` = 0.00%）。

## 5. Bottleneck #2

**假設**：**同步 redirect 路徑上的 ECS Fargate CPU 飽和，而觸發它的機制是 `AsyncConfig` 那個有界執行緒池（`core 2 / max 4 / queue 100 / CallerRunsPolicy`）在高到達率下溢位，把 SQS `SendMessage` 退回 Tomcat 請求執行緒。**

**證據**：

1. **CPU 真的飽和了。** Round 2 逐分鐘 ECS CPU Average **86.30 / 95.31 / 95.81 / 93.84 / 94.92 %**，Maximum **99.96 / 99.94 / 99.99 / 98.42 / 98.91 %**。★ 這是第一次用到 Day 36 補的那條 Maximum——**只看 Average 會說「還有 5% 餘裕」，Maximum 說的是「已經到頂」。**

2. **★★ 直接量到 `CallerRunsPolicy` 溢位**（`findings.md` §7.1 的同一條 Insights 查詢）：

   | 輪次 | 達成 RPS | ECS CPU max | `click-async-*` | Tomcat 執行緒（＝退回）|
   | --- | --- | --- | --- | --- |
   | Day 34 cold | 178.6 | 100%（全程）| 17,550（58.20%）| 12,606（41.80%）|
   | Day 34 warm | 249.3 | 56.97% | 41,828（99.97%）| 14（0.03%）|
   | **Day 37 Round 1** | **248.98** | **95.43%** | **41,874（99.68%）** | **136（0.32%）** |
   | **Day 37 Round 2** | **464.39** | **99.99%** | **70,199（72.25%）** | **26,966（27.75%）** 🚨 |

   ⇒ **Round 2 有 27.75% 的 click event 是由請求執行緒自己送去 SQS 的。**

3. **`TargetResponseTime` 是雙峰的，而雙峰正是 `CallerRunsPolicy` 的指紋**：p50 **12.15 ms**、p95 **830.24 ms**、p99 **1,471.4 ms**。★ 沒退回的請求還是 12 ms；被退回的那 27.75% 要多扛一次跨網路 `SendMessage`。

4. **這不是壓測端的天花板。** `TargetResponseTime` 由 ALB 量、**不含客戶端與太平洋 RTT**。算術對得上：
   `伺服器端 p95 830.24 ms + RTT 158 ms = 988.2 ms` vs `k6 p95 1,021.75 ms`（差 **+3.4%**）。
   ⇒ **`vus_max` 撞 300、`dropped_iterations` 7.26% 是【結果】不是【原因】**：伺服器變慢 ⇒ 每個 VU 被佔住更久 ⇒ k6 為了維持 500 RPS 需要更多 VU ⇒ 撞到 300 ⇒ 丟 iteration。

5. **`AsyncConfig` 的真正瓶頸是 `corePoolSize = 2`，不是 `maxPoolSize = 4`。** Java `ThreadPoolExecutor` 的規則是**佇列滿了才開新執行緒**——`queueCapacity = 100` 意味著穩態下**只有 2 條執行緒在跑**：
   ```
   每台每秒要送的 click event = 3XX ÷ 300 s ÷ 2 台
      Round 1:  42,010 / 300 / 2 =  70.0 則/s/台  => 2 條執行緒每則要 ≤ 28.6 ms  ✅ 溢位 0.32%
      Round 2:  97,028 / 300 / 2 = 161.7 則/s/台  => 2 條執行緒每則要 ≤ 12.4 ms  ❌ 溢位 27.75%
   ```
   ⚠️ 而 `MetricPublisher` 也標了 `@Async("clickEventExecutor")` ⇒ **兩個生產者共用同一個 2 條執行緒的池。**

**反證（被排除的候選）**：

| 候選 | 反證數字 | 判決 |
| --- | --- | --- |
| **非同步鏈路 / Lambda 並發** | 佇列深度**全程 0**、年齡峰值 **2 s**、`ConcurrentExecutions` Avg 只有 **4.9 / 10**、`Errors` 0 | ❌ 排除。**bottleneck #1 已經不是綁住系統的那一個** |
| **DynamoDB** | `ThrottledRequests` 三張表皆 **0**；`SuccessfulRequestLatency` **2.54–2.62 ms**（before 2.42–2.53，**幾乎沒動**）；`ClickAnalytics` 全表 323.1 WCU/s = 單分割區上限的 **32.3%** | ❌ 排除（但 32.3% 值得盯，×3 就撞牆）|
| **Redis** | `CacheMisses` **0（全程）**、`EngineCPUUtilization` max **1.27%** | ❌ 排除。連 cache stampede 都沒有 |
| **壓測端（k6 / 本機 / 太平洋）** | 見證據 4：伺服器端 p95 就有 830 ms，算術對得上 k6 的 p95 | ❌ **降級為「共同症狀」**，不是主因 |
| **JVM 冷啟動 / JIT 未收斂** | Round 2 在 Round 1 之後 27 分鐘跑、之前還有 14,349 筆暖機 ⇒ **完全是熱的** | ❌ 排除。★ **這推翻了 `findings.md` §7.1 的一半結論** |
| **ECS 台數不足 / ALB 分流不均** | `HealthyHostCount` 全程 **2**；`RejectedConnectionCount` 0 | ❌ 排除（加台數能緩解，但不是成因）|

**⚠️ 未驗證（誠實記錄，不編造）**：

1. 🚨 **同樣 250 RPS、同樣的 Java 原始碼，ECS CPU 從 Day 34 的 39.91% 變成今天的 66.76%（×1.67），`TargetResponseTime` p95 從 6.26 ms 變成 63.76 ms（×10.2）。**
   已排除的混淆因子（都實測過）：台數同為 2、task def `:11`/`:14` 的 cpu/memory 完全相同（512 / 1024）、暖機做了且更多（14,349 > 10,001）、`git diff -- shortener/` 是空的。
   **`CallerRunsPolicy` 溢位不能解釋它**（Round 1 只溢位 0.32%）。
   剩下的候選：**image 重建造成的 base layer / JRE 漂移**（tag `6f8ea8af…` → `c9d8331c…`，CI 按 commit SHA 重建）、或 Fargate 底層主機差異。**兩者今天都沒查。**
2. **因果方向沒有被實驗分離。** 「CPU 飽和 ⇒ `SendMessage` 變慢 ⇒ 池溢位」和「池溢位 ⇒ 請求執行緒做網路 I/O ⇒ CPU 飽和」是一個**正回饋迴路**（`findings.md` §7.1 已描述），**兩輪觀測資料無法分辨誰先動**。
3. **非同步鏈路的真天花板沒有量到**（同步路徑先垮）。只知道 `C ≥ 323.1 則/s`（≥ 2.87× before）。

**下一步（Day 38）**：

1. **★★ 第一順位：把 `AsyncConfig` 的 `corePoolSize` 2 → 16（或 `queueCapacity` 100 → 0 改用 `SynchronousQueue`），重跑 500 RPS。**
   ⇒ **這是一個能分離因果的實驗**：若溢位掉到 ≈ 0 **且** CPU 明顯下降 ⇒ 池是主因；若 CPU 仍然 95% ⇒ 主因在別處（回去查未驗證 #1）。
   ⇒ 成本：改一行、重 build image、爆炸半徑小、不動冪等語義。**比修法 C（`TransactWriteItems`）便宜也確定得多。**
2. **查未驗證 #1**：比對 `6f8ea8af…` 與 `c9d8331c…` 兩個 image 的 manifest / base layer digest。
3. **修 `findings.md` §7.7**：`GET /` 與未知路徑回 **404** 而不是 500（今天在真實流量裡發射了 7 次）。
4. ⏸ **修法 C 降級**：D 已經把 client-side 工作壓掉 84%，C 能省的絕對毫秒數只剩約 1/6，而它動到冪等語義。**在 bottleneck #2 修好之前不值得做。**

## 6. 怎麼重現本文

```bash
export AWS_REGION=ap-east-2 AWS_PAGER=""
R1S=2026-08-27T16:20:00Z ; R1E=2026-08-27T16:25:00Z   # Round 1（250 RPS × 4 min）—— --period 300 或 60
R2S=2026-08-27T16:52:00Z ; R2E=2026-08-27T16:57:00Z   # Round 2（500 RPS × 5 min）—— --period 300 或 60
BFS=2026-08-24T15:24:00Z ; BFE=2026-08-24T15:29:00Z   # before（Day 34 warm）    —— --period 300 或 60
# ⚠️ --period 必須等於窗長或 60，【不要用 3600】（Day 34 實測：同一個指標會差 2.8 倍）
# ⚠️ 窗長必須是 5 分鐘（見 §0.1）：4 分鐘窗會切掉 16:20:05Z 那一輪的最後 5 秒，counters 差 3%
```

指令本體見 `optimization-plan.md` §4，加上今天用到的三條：

```bash
# ① ConcurrentExecutions 要同時看 Maximum 和 Average（§3.1 第 2 點）
aws cloudwatch get-metric-statistics --namespace AWS/Lambda --metric-name ConcurrentExecutions \
  --dimensions Name=FunctionName,Value=url-click-processor \
  --start-time $R2S --end-time $R2E --period 60 --statistics Maximum Average \
  --query 'sort_by(Datapoints,&Timestamp)[].[Timestamp,Maximum,Average]' --output text

# ② ECS CPU 要看 Maximum（Day 36 補的那條線）
aws cloudwatch get-metric-statistics --namespace AWS/ECS --metric-name CPUUtilization \
  --dimensions Name=ClusterName,Value=url-shortener-cluster Name=ServiceName,Value=url-shortener-service \
  --start-time $R2S --end-time $R2E --period 60 --statistics Average Maximum \
  --query 'sort_by(Datapoints,&Timestamp)[].[Timestamp,Average,Maximum]' --output text

# ③ ★★ CallerRunsPolicy 溢位率（Logs Insights，log 只留 7 天！）
filter @message like /published click event/
| parse @message "[url-shortener] [*]" as thread
| fields (thread like /click-async/) as isAsync
| stats count(*) as n by isAsync
```

## 7. ★★ 可以寫進履歷的量化數字（Day 38 直接用）

| 數字 | 出處 |
| --- | --- |
| 非同步消費端每則成本 **61.57 ms → 9.81 ms（−84.1%）** | §1b |
| 每次 invocation 的固定開銷 **63.18 ms → 1.79 ms（−97.2%）** | §1b |
| 同樣 250 RPS 下 SQS 積壓 **13,980 則 → 0 則** | §1b |
| 資料新鮮度落後 **125 s → 4 s（−96.8%）** | §1b |
| Lambda 節流率 **14.17% → 3.24%（−77%）** ⚠️ 絕對值是 489 → 643，**要講率不要講量** | §1b、§3.1 第 3 點 |
| 非同步吞吐 **112.7 則/s → ≥ 323.1 則/s（≥ 2.87×，上界未量到）** | §4 |
| 端到端臨界流量 **161 RPS → ≥ 462 RPS（≥ 2.87×）** | §4 |
| 成本 **US$0.011 → US$0.020 / 天**（差一美分）| `optimization-plan.md` §1 D |
| ⚠️ **k6 的 p50/p95 幾乎沒動**（+2.6% / +3.5%）| §1a（★ 這一列要主動講，不要等人問）|
| ★ **而且我找到了下一個瓶頸並且指認了機制**：`CallerRunsPolicy` 溢位 0.32% → **27.75%** | §5 |
