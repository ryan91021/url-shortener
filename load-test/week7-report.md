# Week 7 週報 · Load Test → 鎖定 bottleneck #1

## 這一週做了什麼

| Day | 產出 | 一句話 |
| --- | --- | --- |
| 31 | `terraform/dashboard.json`（7 個 widget）| 給系統裝上眼睛 |
| 32 | `load-test/baseline.js` | 做出一把可重複的尺（constant-arrival-rate，70/30 讀寫）|
| 33 | `load-test/baseline-results.md` + 2 個 results json | 第一次真的量到數字（100 / 500 RPS）|
| 34 | `load-test/findings.md` + 2 個 250 RPS 驗證輪 | **指認兇手**：Lambda 帳號並發上限 10 |
| 35 | `load-test/optimization-plan.md` + `lambda_function_v2.py` | 把結論變成下週一可執行的東西 |

## 四輪壓測總表

| 輪次 | 目標 / 實際 RPS | k6 結果 | SQS 佇列峰值 | 最舊訊息年齡峰值 | Lambda 並發 |
| --- | --- | --- | --- | --- | --- |
| Day 33 Run 1 | 100 / **99.8** | 綠（exit 0）| **3** | ~0 s | 貼死 10 |
| Day 33 Run 2 | 500 / **491.7** | 綠（exit 0）| **65,090** | **581 s** | 貼死 10 |
| Day 34 cold | 250 / **178.6** | 🔴 **紅（exit 99）** | 3,543 | — | 貼死 10 |
| Day 34 warm | 250 / **249.3** | 綠（exit 0）| **13,980** | **125 s** | 貼死 10 |

## 結論

**Bottleneck #1 = Lambda 帳號並發上限 `ConcurrentExecutions = 10`。**
消費能力天花板 **112.7 則/s**（直接量到）⇒ 端到端臨界流量 **≈ 161 RPS**。
同步路徑（ALB / ECS / Tomcat / Redis / DynamoDB）在 500 RPS 下**都還有一半以上餘裕**。

**Bottleneck #2 = ECS CPU，但只存在於 JVM 剛啟動的頭 ≈ 4 分鐘**（cold vs warm 的 p95 差 **517 倍**）。

**★ 本週最重要的一句話**：**k6 唯一一次變紅，是問題回到請求路徑上的那一次（cold JVM）。**
佇列堆了 65,090 則、資料遲到 9 分 41 秒的那一次，它 **exit 0、全綠**。
⇒ **壓測工具只看得見它自己走過的那條路。**

## Week 8 backlog（★ 從 Day 35 的「10 項落後」搬過來，附不做的理由）

| # | 項目 | 出處 | 現況 | 決定 |
| --- | --- | --- | --- | --- |
| 1 | ALB / ElastiCache 納入 Terraform | Day 31 選做 B → 32 D → 35 | 未做（`state list` = 16，兩者都不在）| ⏸ **Week 8 之後**：import 至少 2–3 h；它們每月個位數美元，而 Week 8 天天要用、根本不會 destroy；**優化週動基礎設施＝給自己製造變數** |
| 2 | dashboard 加 ElastiCache widget | Day 33 選做 B → 34 C → 35 | 未做 | ⏸ **Day 39**（畫圖表那天一起）：Redis 已被 Day 34 用 CLI 排除（`EngineCPUUtilization` 峰值 1.27%），不是阻礙 |
| 3 | dashboard 補 ECS CPU 的 Maximum | findings §7.4-(c) | **✅ Day 36 完成** | — |
| 4 | `ApproximateAgeOfOldestMessage` 的 alarm | findings §7.4-(a) | **✅ Day 36 完成**（`url-click-events-lagging`，連續 3 分鐘 > 60 s）| — |
| 5 | `baseline.js` `maxVUs` 200 → 300 | Day 33 §5 → findings §9 | ✅ Day 35 完成 | — |
| 6 | `scripts/post-100.sh` | Day 31 選做 C → 32 A → 33 E → 34 E → 35 | 未做（`BASE` 預設 localhost、沒帶 `X-API-Key`）| 🗑 **Day 40 直接刪掉**並在 README 註明「已由 `load-test/baseline.js` 取代」：連續五天沒人修＝它沒有使用者了 |
| 7 | `ci.yml` 加 path filter | Day 32 選做 B → 33 C → 34 D → 35 | 未做 | ⏸ **Week 8 之後**：`paths-ignore` 會連 `test` job 一起跳過；要的是「跳過 deploy、保留 test」⇒ 得在 deploy job 的 `if:` 加條件。**沒想清楚不要做** |
| 8 | 短碼搬到 `short-codes.json` | Day 32 選做 C → 33 D | 未做（`baseline.js:47-59` 硬寫 10 個）| ⏸ **Week 8 之後**：Week 8 **不會換短碼**（要保持條件一致），它的價值這週用不到 |
| 9 | CI 加 `terraform fmt -check` + `validate` | Day 31 選做 D | 未做 | ⏸ **Day 40**：今天手動跑過了，自動化留到收官 |
| 10 | `results-250rps-cold.json` 進 git | Day 34 收尾漏掉 | ✅ Day 35 完成 | — |
| **11** | **收掉 Lambda 角色的 `cloudwatch:PutMetricData`** | **Day 36 新增（B′ 上線）** | 未做，**刻意** | ⏸ **Day 37 確認 EMF 穩定後**：現在收掉會讓 B′ 沒辦法 2 分鐘回滾 |
| **12** | **`/aws/lambda/url-click-processor` 設保留期** | **Day 36 新增（見 (I)）** | 未做（`retention = None`、已存 19.3 MB）| ⏸ **Day 40**：`findings.md` §8「怎麼重現這份分析」依賴 Day 33/34 的 log，Week 8 期間不能砍 |
| **13** | **`terraform-sns-cloudwatch-alerts` 這張 policy 沒有 IaC** | **Day 36 新增（見 §2.6）** | 手動管理，**刻意** | ✅ **保持現狀**：它是 Terraform 自己的執行憑證，交給 Terraform 管＝一次 destroy 就把自己鎖在門外。**寫進 README 即可** |

## ★★ 兩條英文履歷 bullet（Week 8 Day 38 直接用）

> 寫作規則：**動作 + 方法 + 量化結果**。不要寫「optimized」「improved」而不給數字。

1. **Load-tested a Spring Boot / AWS URL shortener with k6 (open-model, constant arrival rate,
   70/30 read-write) and identified the primary throughput bottleneck as a Lambda account
   concurrency limit of 10 — a limit invisible to the load test itself, which stayed green
   (p99 517 ms, 0.00% failures) while the async click-analytics pipeline fell 581 s behind
   and backed up 65,090 messages in SQS.**

2. **Built a quantitative model of the async consumer using Little's Law (L = λ × W) that
   predicted queue backlog within 1–6% across a 5× traffic range (100 / 178 / 249 / 492 RPS),
   then used per-operation CloudWatch metrics to show 92% of per-message cost was client-side
   CPU on an under-provisioned 128 MB Lambda — a one-line, instantly reversible fix.**

> ★ 第 1 條賣的是「**我發現了量測工具看不見的東西**」。
> ★ 第 2 條賣的是「**我的結論是可以預測未來的模型，不是一次性的觀察**」。
> ⚠️ 兩條都刻意**沒有**寫「提升了 N%」——因為那個數字要到 Day 37 重測完才會有。
> **不要提前寫還沒量到的數字。**
