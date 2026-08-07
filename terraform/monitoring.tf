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