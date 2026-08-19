resource "aws_sns_topic" "ops_alerts" {
  name = "url-shortener-alerts"
}


resource "aws_sns_topic_subscription" "ops_email" {
  topic_arn = aws_sns_topic.ops_alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email

  # ★★ email 是「部分支援」的協定：apply 之後【必須去信箱點 Confirm subscription】。
  #    在確認之前：① 收不到任何通知
  #                ② provider 官方文件明說——未確認的訂閱【Terraform 無法刪除／取消訂閱】，
  #                   destroy 只會把它從 state 移掉、AWS 上仍然留著（Day 30 的地雷，卡點 3）
  #    確認狀態看 pending_confirmation 屬性。
}

resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  alarm_name        = "url-click-events-dlq-not-empty"
  alarm_description = "url-click-events-dlq 裡出現訊息 = 有 click event 重試 3 次仍失敗，需要人工查看 Lambda log"

  namespace   = "AWS/SQS"
  metric_name = "ApproximateNumberOfMessagesVisible" # ★ AWS 官方文件明確建議：監控 DLQ 就用這個
  dimensions = {
    QueueName = aws_sqs_queue.click_events_dlq.name # ★ SQS 只有 QueueName 這一個維度
  }

  statistic           = "Maximum" # ★ 這是「水位(gauge)」不是「計次(counter)」→ 不能用 Sum（卡點 5）
  period              = 300       # 5 分鐘（SQS 每分鐘推一次，5 分鐘一格夠靈敏又不吵）
  evaluation_periods  = 1         # 一格就報（DLQ 只要有東西就是異常，不需要「連續 N 次」）
  threshold           = 0
  comparison_operator = "GreaterThanThreshold" # > 0

  treat_missing_data = "notBreaching" # ★★ 佇列閒置時 SQS 根本不推 metric → 沒資料【不算異常】（卡點 6）

  alarm_actions = [aws_sns_topic.ops_alerts.arn] # 進 ALARM 時寄信
  ok_actions    = [aws_sns_topic.ops_alerts.arn] # 恢復 OK 時也寄一封（知道「已排除」）
}


# ──────────────────────────────────────────────────────────────
# Day 31 · 一頁式營運儀表板
#   ★ ALB / Target group 【不在】Terraform 裡（Day 26 的減載決定：舊資源不回頭補），
#     所以不能用資源參照。改用 data source 去 AWS「查」它現在的樣子 ——
#     這樣拿到的 arn_suffix 永遠是現實的值，不是我手抄進檔案的字串。
#   （Day 28 的 data.aws_iam_role.ecs_task 是同一招）
# ──────────────────────────────────────────────────────────────
data "aws_lb" "app" {
  name = "url-shortener-alb"
}

data "aws_lb_target_group" "app" {
  name = "url-shortener-tg"
}

resource "aws_cloudwatch_dashboard" "overview" {
  dashboard_name = "url-shortener-overview"

  # ★ templatefile() = 讀檔 + 把 ${...} 換掉。
  #   用 file() 也可以，但那樣就得把 ALB 的雜湊字尾硬寫進 JSON（見下方決策表）。
  dashboard_body = templatefile("${path.module}/dashboard.json", {
    region = "ap-east-2" # ★ 跟 provider.tf 一致；dashboard 是全域的，但 metric 有 region

    # ★★ arn_suffix 正好就是 CloudWatch 維度要的格式：
    #    app/url-shortener-alb/6fb50f…  /  targetgroup/url-shortener-tg/c883c…
    alb_suffix = data.aws_lb.app.arn_suffix
    tg_suffix  = data.aws_lb_target_group.app.arn_suffix
  })
}