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

〈把 2.1 那張表整個貼進來，含「今天不做，因為 ___」那一欄〉

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
