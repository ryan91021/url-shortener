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
  1. 它動到**冪等語義**——那是 Day 24 + Day 30 兩天才修好的東西，而 Week 8 **只有一輪驗證**。
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

## 2. Day 36 的執行順序

1. **先開 ECS 暖機**（Day 34 證明過：不暖機的那輪只跑得出 178.6 RPS、p95 差 517 倍）
2. **改 D**（一行 CLI）→ 確認 `MemorySize = 512`
3. **部署 B′**（`cp lambda_function_v2.py lambda_function.py` → `zip` → `update-function-code`）
   ⚠️ **只把 `lambda_function.py` 打包進 zip**，不要 `zip function.zip *.py`
   ⚠️ ★ **本輪先【拿掉 v2 裡的 TransactWriteItems】，只留 EMF**——C 不在這一輪
4. **驗收 metric 還活著**（§4）——EMF 寫錯會靜默失敗
5. **同一把尺重測**：`k6 run -e RATE=250 -e DURATION=4m --summary-export=load-test/results-250rps-opt.json`
   ⚠️ **檔名不要覆蓋掉 `results-250rps.json`**（那是 before）
6. **順手補 dashboard 的 ECS CPU Maximum**（Day 34 cold 那輪只看 Average 會誤判成有餘裕）

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
