# 優化方案（Day 35 預寫 · Day 36 執行）

> 來源：`load-test/findings.md`（Day 34）+ Day 35 新增的修法 D。
> ⚠️ **本文今天不執行任何一項。** index 指定「先不 deploy，週一一次 deploy + 驗證」，
> 而且 Week 8 是減載版、**只做一輪優化驗證** ⇒ 那一輪改什麼，必須今天就決定好。

## 0. 一句話

**這一輪改兩件事：Lambda 記憶體 128 → 512 MB（D），以及把 `put_metric_data` 換成 EMF（B′）。**
兩者都不動冪等語義，而且**可以在同一輪裡分別歸因**（見 §3）。
`TransactWriteItems`（C）和加大 batch（B）**寫好但不上**，理由見 §2。

## 1. 四個修法

### D · Lambda 記憶體 128 → 512 MB　★ 這一輪的主角
- **為什麼昨天沒看到**：昨天在找「誰是瓶頸」（查 `ConcurrentExecutions` / `Throttles`），
  今天在找「怎麼修」（查 `MemorySize` / `SuccessfulRequestLatency`）。**兩次不同的調查。**
- **證據**：每則成本 61.57 ms，其中 DynamoDB 伺服器端只有 **4.95 ms（8%）**
  （`SuccessfulRequestLatency`：PutItem 2.5305 ms / UpdateItem 2.4211 ms，Day 34 warm 窗實測）
  ⇒ **92% 是 client-side CPU**，而 Lambda 只給了 **128/1769 = 7.24% 的一顆核**。
- **附帶發現**：`Max Memory Used: 96 MB / 128 MB = 75%` ⇒ 離 OOM 不遠。
- **做法**：
  ```bash
  aws lambda update-function-configuration \
    --function-name url-click-processor --memory-size 512 --region ap-east-2 \
    --query '[MemorySize,LastModified]' --output text
  ```
- **預測**：消費能力 112.7 → **200–380 則/s**（保守 / 樂觀，見 findings 的模型）
- **可證偽門檻**：`ClickEventProcessingDuration` 的 Average **必須下降 ≥ 30%**，否則「92% 是 CPU」這個假設就是錯的。
- **成本**：全天 78,253 則的 Lambda 費用 US$0.011 → **US$0.020**（差一美分）
- **回滾**：`--memory-size 128`，**秒級生效**，不用重新打包。
- **風險**：★ 幾乎沒有。不動程式碼、不動語義、不動 IAM。
- ⚠️ **不要超過 1,769 MB**：那之後給的是多於一顆 vCPU，而 Python 單執行緒**用不到**。

### B′ · `put_metric_data` → EMF　★ 跟 D 同一輪做
- **證據**：`lambda_function.py:78` 每次 invocation 同步呼叫一次 `cloudwatch.put_metric_data`，
  而且它**在 `ClickEventProcessingDuration` 的計時之外**（`duration_ms` 在 line 77 就算完了）
  ⇒ 它是那 **63.18 ms** 固定開銷（`674.311 − 611.130`）的主體。
- **做法**：部署 `lambda_function_v2.py` 裡的 `emit_emf()`（零 API 呼叫）
- **預測**：`Duration − ClickEventProcessingDuration` 這個差值應**明顯縮小**
- **成本**：多一行 log（約 300 bytes × 每次 invocation），可忽略
- **回滾**：改回 `put_metric_data`，重新打包 + `update-function-code`
- **風險**：⚠️ 欄位名寫錯會**靜默失敗**——metric 不出現，而且**沒有任何錯誤訊息**。
  ⇒ 部署後**第一件事**是確認 metric 還在（§4 的驗收指令）。
- **附帶收穫**：上線後可以收掉 `cloudwatch:PutMetricData` 這張權限（先改 code、再收權限）

### C · `TransactWriteItems`　★ 寫好，這一輪【不上】
- **做法**：`lambda_function_v2.py` 已經實作
- **為什麼不上**：
  1. 它動到**冪等語義**——那是 Day 24 + Day 30 兩天才修好的東西。
     ⚠️ **Day 36 更正**：原文寫「Week 8 只有一輪驗證」，那是誤讀。index 的 Week 8 調整說明是
     「本週**幾乎照原計畫**」，而日程本身就是兩輪（Day 36 優化 #1 / Day 38 優化 #2）。
     ⇒ **C 的正確定位是「Day 38 的候選」，不是「被擱置」。** 它排在 D 後面的真正理由是第 2 條。
  2. ★★ **D 會把 C 的收益吃掉四分之三**：C 省的是「一次 client-side 往返」，
     而 D 讓所有 client-side 工作快 4 倍 ⇒ 做完 D 之後，C 能省的絕對毫秒數只剩 1/4。
     ⇒ **先做 D，再重新評估 C 值不值得那個風險。**
  3. 代價要記下來：交易寫入的 WCU 是普通寫入的 **2×**（58,551 → 117,102 WCU / 窗）
- **附帶收穫**（上線後）：Day 30 的補償刪除整段可刪；可收掉 `dynamodb:DeleteItem`；
  順便關掉一個「update 成功但回應遺失 → 補償刪除 → 重投 → 重複計數」的視窗

### B · BatchSize 10 → 100　★ 最後才考慮
- **為什麼排最後**：要同時改三個地方——ESM `BatchSize` + `MaximumBatchingWindowInSeconds ≥ 1`
  （AWS 規定 BatchSize > 10 時必填）、Lambda `Timeout 5s → 30s`、
  SQS `VisibilityTimeout 30s → 180s`（**★ 這一項在 Terraform 裡**：`terraform/main.tf:57`）
  ⇒ **爆炸半徑從 10 則變成 100 則**，而 findings §4 註 4 顯示**每則成本隨 batch 變大而上升**
  （50.0 → 61.6 ms，batch 1.41 → 9.9）⇒ **省下的固定開銷可能被吃掉。**
- **現況**：ESM `43f3dd04-fc03-4561-95f3-e38694ab3223`，`BatchSize 10`、
  `MaximumBatchingWindowInSeconds 0`、`ReportBatchItemFailures`、`ScalingConfig null`（實測）

## 2. 執行順序（★ Day 36 修正：把「部署」和「量測」拆成兩天）

> ⚠️ **本節在 Day 36 被改過。** 原版把「同一把尺重測」放在 Day 36，那是錯的：
> index Day 36 的任務是「build/deploy + **準備好下一次 load test（保持條件一致）**」、
> 驗收是「deploy 完成、服務正常、health check 綠」；index Day 37 才是「用**完全相同**的
> script / RATE / duration **再跑一次**」。⇒ **原版會讓 before/after 的兩把尺條件不一致。**

### Day 36（部署日 · 不量測）
1. **修法 D**：`--memory-size 512` → `wait function-updated` → 確認 `MemorySize = 512`
2. **修法 B′**：把 `emit_emf()` **併回 `lambda_function.py`**（★ **不是** `cp lambda_function_v2.py`，
   那會把 `TransactWriteItems` 一起帶上線）→ `zip` → `update-function-code` → `wait`
3. **驗收**：`aws lambda invoke --log-type Tail` 一條指令同時看到
   ① EMF 的那行 JSON ② `REPORT … Memory Size: 512 MB`
4. **首航 `url-shortener-task:13`**（`:12` / `:13` 從沒跑過）：ECS 拉 1 台 → target healthy →
   curl 幾發 → 確認整條非同步鏈通 → **縮回 0**
5. **`terraform apply`**：widget ③ 補 ECS CPU `Maximum`、新增 `url-click-events-lagging` alarm
   ⚠️ **先放寬 IAM policy `terraform-sns-cloudwatch-alerts`**，否則 apply 會在 alarm 上 AccessDenied

### Day 37（量測日 · 條件必須跟 Day 34 warm 一模一樣）
1. **先暖機**（Day 34 證明過：不暖機那輪只跑得出 178.6 RPS、p95 差 517 倍）
2. **同一把尺**：`k6 run -e RATE=250 -e DURATION=4m --summary-export=load-test/results-250rps-opt.json`
   ⚠️ 檔名**不要**覆蓋 `results-250rps.json`（那是 before）
   ⚠️ **2 台**、**同 10 個短碼**、**同樣先暖機**——三個條件缺一不可
3. **★ 再加一輪 500 RPS**（理由見 `iteration-1-results.md` §4 的「選尺推導」：
   250 RPS 那把尺在優化後**已經量不到新天花板**了）
4. 寫 `load-test/iteration-1-results.md`（before/after 對照表 + 找 bottleneck #2）

### Day 38（第二輪優化 · 見 §1 的 C）
- `TransactWriteItems` 在這裡重新評估——**不是被永久擱置**（index Week 8 有兩輪優化）

## 3. ★★ 為什麼 D 和 B′ 可以同一輪做，還能分別歸因

| 指標 | 涵蓋範圍 | D 會影響？ | B′ 會影響？ |
| --- | --- | --- | --- |
| `ClickEventProcessingDuration` | `lambda_function.py` 的 22–77 行（record 迴圈）| **✅ 會** | ❌ **不會**（`put_metric_data` 在 line 78，計時之外）|
| `Duration − ClickEventProcessingDuration` | 固定開銷（`put_metric_data` + runtime）| ✅ 會 | ✅ 會 |

⇒ **第一列把 D 的效果單獨隔離出來。** 知道 D 的縮放倍率之後，就能推算「D 單獨會把固定開銷壓到多少」，
差額就是 B′ 的貢獻。★ **兩個獨立的 metric，剛好把兩個變數分開 —— 這是可以一次改兩件事的唯一理由。**

## 4. 部署後的驗收指令

```bash
export AWS_REGION=ap-east-2 AWS_PAGER=""

# ① 配置真的變了
aws lambda get-function-configuration --function-name url-click-processor \
  --query '[MemorySize,Timeout,LastModified]' --output text        # ★ 預期 512  5  <剛剛>

# ② ★★ EMF 沒有靜默失敗 —— metric 還在，而且有【新的】資料點
aws cloudwatch get-metric-statistics --namespace UrlShortener \
  --metric-name ClickEventProcessingDuration \
  --start-time <重測開始> --end-time <重測結束> --period 60 \
  --statistics Average SampleCount \
  --query 'sort_by(Datapoints,&Timestamp)[].[Timestamp,Average,SampleCount]' --output text
# ⚠️ 完全沒有資料點 = EMF 格式寫錯（"Name" vs "MetricName"、Timestamp 秒 vs 毫秒）

# ③ Duration 有沒有掉
aws cloudwatch get-metric-statistics --namespace AWS/Lambda --metric-name Duration \
  --dimensions Name=FunctionName,Value=url-click-processor \
  --start-time <重測開始> --end-time <重測結束> --period 60 --statistics Average --output text

# ④ 兇手還在不在（★ 並發上限沒改，它應該【還是】貼死在 10）
aws cloudwatch get-metric-statistics --namespace AWS/Lambda --metric-name ConcurrentExecutions \
  --dimensions Name=FunctionName,Value=url-click-processor \
  --start-time <重測開始> --end-time <重測結束> --period 60 --statistics Maximum --output text
```

## 5. 已排除，不要再回頭做（★ 面試時這一節比 §1 更值錢）

| 修法 | 為什麼不做 | 反證數字 |
| --- | --- | --- |
| index 情境 A · DynamoDB SDK 連線池 | ① 參數名就不對：你用的是**同步** `DynamoDbClient`（Apache HTTP client，`maxConnections` 預設 **50**），`maxConcurrency` 是**非同步 Netty client** 的參數 ② Little's Law：每台 37.6 PutItem/s × 5 ms = **0.19 條 / 50 = 0.4%** | `UrlMappings` `ThrottledRequests` 全天 **0** |
| index 情境 B · `tomcat.threads.max: 400` | Little's Law：Day 34 warm `L = 249.3 × 0.003631 s = ` **0.9 條**（兩台合計）/ 400 | ALB `RejectedConnectionCount` = **0** |
| index 情境 C · `lettuce.pool.max-active: 50` | `EngineCPUUtilization` 峰值 **1.27%**、`CurrConnections` **7**、`Evictions` **0**。Lettuce 的單一連線是**多工的** | 「只有一條」≠「不夠」 |
| 擴 ECS（2 台 → 10 台）| 積壓的成因在**消費端**，不在生產端 ⇒ 加生產者只會讓佇列堆更快 | Lambda `ConcurrentExecutions` 每分鐘都是 **10.0** |
| 提高 Lambda 帳號並發（修法 A）| **仍然是唯一能真正解決的**，但 `url-shortener-dev` 沒有 `servicequotas` 權限（實測 `AccessDenied`）⇒ 要用 root / Console 開 case。**已排入 Week 8** | `AccountLimit.ConcurrentExecutions = 10` |

## 6. Day 36 執行紀錄（★ 事實，不是計畫）

| 項目 | 結果 |
| --- | --- |
| 修法 D | `MemorySize` 128 → **512**，`LastModified` = **2026-08-26T16:25:22Z** |
| 修法 B′ | `lambda_function.py` 3 個 hunk；`CodeSize` 2,325 → **2,896** |
| EMF 驗收 | 手動 invoke 看到 EMF JSON + `REPORT … Memory Size: 512 MB`；metric 出現在 **16:44Z**（手動）與 **17:10Z**（真流量，`SampleCount 20`）|
| 修法 C | **未部署**（`lambda_function_v2.py` 保留，標記為 Day 38 候選）|
| `:13` 首航 | task `createdAt` 17:08:18Z → `startedAt` 17:09:03Z（**45.3 s**）→ 服務 steady **17:09:33Z（74.6 s）**；★ image pull 只花 **5.6 s** |
| 煙霧測試 | 20 發 GET → 20 個 302；佇列排空；`Errors` = 0、`Throttles` = 0 |
| Terraform | `state list` 15 → **16**；dashboard widget ③ 多一條 Maximum（線上 `grep -c "CPU % (max)"` = 1）；新增 alarm `url-click-events-lagging`（State = **OK**）|
| IAM | `terraform-sns-cloudwatch-alerts` 的 `CwAlarmAdmin` Resource 放寬成 `…:alarm:url-click-events-*` |
| **今天沒做的** | **沒有跑那把 250 RPS 的尺**（見 §2 的 Day 37）、沒有 build image、沒有動 task definition、沒有改一行 Java |

> 📌 上面的時間我用 UTC 寫（`17:08:18Z` ＝ 本機 `10:08:18 -07:00`）——★ **紀錄一律用 UTC**，因為所有 CloudWatch 查詢都是 UTC，混用時區是這種報告最常見的錯。

### ★★ 事前登記的預測（Day 37 拿這一頁對答案，不准事後改）

| 指標 | Day 34 warm（before）| **Day 36 預測（after）** | 判定 |
| --- | --- | --- | --- |
| `ClickEventProcessingDuration` avg | **611.130 ms** | **190–358 ms** | ★★ **必須 ≤ 427.8 ms（−30%）**，否則「92% 是 CPU」這個假設就錯了 |
| `Duration` avg | **674.311 ms** | **198–366 ms** | — |
| 固定開銷（`Duration` − 上一列）| **63.181 ms** | **< 15 ms** | B′ 的效果 |
| 消費能力 | **112.7 則/s** | **200–400 則/s** | — |
| 端到端臨界流量 | **161 RPS** | **290–580 RPS** | — |
| **250 RPS × 4 min 的 SQS 佇列峰值** | **13,980** | **≈ 0**（174.6 則/s ＜ 保守值 200）| ★★ **這一格是今天最大膽的預測** |
| Lambda `ConcurrentExecutions` max | **10（貼死）**| **仍然貼死 10** | ★ 並發上限沒動，它不該變 |

### 6.1 ★★ 這張預測表本身的三個瑕疵（Day 37 對答案時發現，**上面那張表一個字都沒改**）

> ★ 全文與實測數字見 `load-test/iteration-1-results.md` §3.1。這裡留一份結論，因為**瑕疵屬於預測表，不屬於結果報告**。

1. **門檻寫在一個會被 batch 汙染的指標上。** `ClickEventProcessingDuration` 是**每次 invocation 的迴圈時間**，跟 batch 成正比；而優化成功的直接後果就是佇列變淺 ⇒ **batch 從 9.90 塌到 2.19**。⇒ 「≤ 427.8 ms」那條線會被**輕鬆通過，但理由是錯的**（實測 21.484 ms）。
   ⇒ 正確的等價門檻是**每則成本 ≤ 43.1 ms**（`61.5675 × 0.7`），實測 **9.8146 ms**。★ 這正是 §7 規則 1 在跑之前就換掉的那個軸。

2. **最後一列和第 6 列看起來自相矛盾，但真正的答案是【Maximum 和 Average 是兩個不同的統計量】。**

   | | before（Day 34 warm）| after（Round 1）| after（Round 2）|
   | --- | --- | --- | --- |
   | `ConcurrentExecutions` **Maximum**（逐分鐘）| 10.0 | **10.0** | **10.0** |
   | `ConcurrentExecutions` **Average**（逐分鐘）| 7.80 – 8.22 | **3.31 – 3.58** | **4.67 – 4.90** |

   ⇒ **「還會碰到天花板」和「貼死在天花板」是兩件事。** §6 這一列在 **Maximum** 上是對的、在「貼死」上是錯的；**§7 規則 4 猜的「3–6」在 Maximum 上是錯的，卻剛好命中 Average。**
   ⇒ **正確的判定指標是 Average**——帳號並發上限是天花板不是地板，而只有 Average 能告訴你離天花板多遠。

3. **`Throttles` 的門檻寫成了絕對值，但它跟 batch 綁在一起。** batch 塌了 ⇒ invocation 次數 ×6.5（2,962 → 19,192）⇒ 被節流的機會 ×6.5。**絕對值 489 → 643（+31.5%）看起來變差，節流率 14.17% → 3.24%（−77%）才是真相。**

> ★★ **三個瑕疵是同一個病**：**優化會改變指標的「組成」，所以門檻必須寫在一個對那個組成不變的量上。**
> 🚨 **紀律：上面那張預測表沒有被回頭修改過一個字。** 事前登記的價值全部來自「它不能被改」——
> **承認自己三天前寫錯了，跟偷偷改掉它，是兩件完全不同的事。**

## 7. Day 37 的判定規則（★ 在跑第一發之前寫下來，寫完才准按 Enter）

### 規則 1 · D 的效果（主要判定）
    每則成本 = ClickEventProcessingDuration Sum ÷ 同窗 NumberOfMessagesReceived
    before（Day 34 warm，batch 9.90）= 61.57 ms
    ★★ 門檻：≤ 43.1 ms（= 61.57 × 0.7，事前登記的 −30% 換算到 batch 不變性的軸上）
    ⚠️ 【不要】用 ClickEventProcessingDuration 的 Average 直接比 —— 它跟 batch 成正比，
       而 batch 今天預期會從 9.9 塌到 ≈ 1（Day 36 煙霧測試實測 batch = 1.0）

### 規則 2 · B′ 的效果
    固定開銷 = Duration avg − ClickEventProcessingDuration avg
    before = 63.181 ms   門檻 < 15 ms（事前登記）
    ★ Day 36 煙霧測試已經量到 1.604 ms —— 今天是在真實流量下確認它

### 規則 3 · 端到端
    SQS ApproximateNumberOfMessagesVisible 峰值
    before = 13,980   預測 ≈ 0
    ⚠️ 若 > 2,000 ⇒ 消費能力沒到 174.6 則/s ⇒ 回頭看規則 1 是不是也沒過

### 規則 4 · ★ 不要誤讀的那一格
    ConcurrentExecutions max：預期 3–6，【不是 10】
    ★ 並發上限是天花板不是地板。佇列 ≈ 0 ⟺ 消費端沒飽和 ⟺ 它一定 < 10。
      §6 那張表的最後一列和第 6 列自相矛盾（詳見 §6.1）。

### 規則 5 · 尺本身有沒有效（★ 每一輪都要先看這一條）
    dropped_iterations ÷ iterations < 1%   且   vus_max < 300
    ⚠️ 任一條不成立 ⇒ 那一輪量到的是【壓測端】的天花板，系統端數字只能當【下界】

### ★★ Round 2（500 RPS）的三條分岔（跑之前就決定好）
| 看到什麼 | 意義 | 下一步 |
| --- | --- | --- |
| 佇列峰值 > 5,000 且 `ConcurrentExecutions` max = 10 | ✅ 飽和了，量到新天花板 | 消費能力 = `NumberOfMessagesDeleted` 穩態平均；bottleneck #2 仍是並發上限，但被推遠了 |
| 佇列 ≈ 0 且 `ConcurrentExecutions` max < 10 | ⚠️ 消費端仍未飽和 ⇒ 新天花板 > 500 RPS | bottleneck #2 **不在非同步鏈路上** ⇒ 去看 ECS CPU Maximum / `TargetResponseTime` / 壓測端；要量確切天花板 ⇒ 直接灌佇列（選做 A）|
| `dropped_iterations` > 1% 或 `vus_max` 撞 300 | 🚨 量到的是壓測端天花板 | bottleneck #2 = **壓測基礎設施**（findings §9 早就寫了「搬到同 region EC2」）——**這也是一個誠實的結論** |
