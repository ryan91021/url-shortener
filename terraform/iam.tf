# ──────────────────────────────────────────────────────────────
# Day 28 · ECS task role 最小權限
#   角色本身（ecsTaskRole-urlshortener）是 Day 17 用 console 建的，
#   依 Week 6 減載版原則【不回頭 import】——這裡用 data source 查它的名字就好。
# ──────────────────────────────────────────────────────────────
data "aws_iam_role" "ecs_task" {
  name = "ecsTaskRole-urlshortener"
}

data "aws_iam_policy_document" "ecs_task_least_privilege" {
  # ① 短網址主表：讀 + 條件式寫
  statement {
    sid    = "UrlMappingsItemAccess"
    effect = "Allow"
    actions = [
      "dynamodb:GetItem",    # UrlRepository.findByShortCode（快取 miss）
      "dynamodb:PutItem",    # UrlRepository.save（attribute_not_exists 條件式寫入）
      "dynamodb:UpdateItem", # UrlService.recordClick（★ 目前無呼叫端，見下方決策表）
    ]
    resources = [aws_dynamodb_table.url_mappings.arn] # ★ 參照 Day 26 的成果，不硬寫 ARN
  }

  # ② 分析表：★★ 只給 Query，【絕不】給任何寫入（寫入者是 Lambda，不是 ECS）
  statement {
    sid       = "ClickAnalyticsReadOnly"
    effect    = "Allow"
    actions   = ["dynamodb:Query"] # AnalyticsService.getClickAnalytics
    resources = [aws_dynamodb_table.click_analytics.arn]
  }

  # ③ 點擊事件佇列：只准「解析 URL + 發訊息」，不准收、不准刪
  statement {
    sid    = "PublishClickEvents"
    effect = "Allow"
    actions = [
      "sqs:GetQueueUrl", # ClickEventPublisher @PostConstruct（少了它 app 起不來）
      "sqs:SendMessage", # ClickEventPublisher.publish
    ]
    resources = [aws_sqs_queue.click_events.arn] # ★ 只有來源佇列，【沒有】DLQ
  }

  # ④ 自訂指標：PutMetricData 不支援 resource-level →
  #    改用【條件鍵 cloudwatch:namespace】把它鎖死在自己的 namespace
  statement {
    sid       = "PutOwnNamespaceMetrics"
    effect    = "Allow"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"] # ★ 這個 action 只接受 "*"，靠 condition 收斂

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = ["UrlShortener"] # = MetricPublisher.NAMESPACE
    }
  }
}

resource "aws_iam_policy" "ecs_task_least_privilege" {
  name        = "url-shortener-ecs-task-least-privilege"
  description = "Least-privilege policy for ecsTaskRole-urlshortener (Day 28). Replaces AmazonDynamoDBFullAccess + AmazonSQSFullAccess + inline cloudwatch-put-metric."
  policy      = data.aws_iam_policy_document.ecs_task_least_privilege.json
}

resource "aws_iam_role_policy_attachment" "ecs_task_least_privilege" {
  role       = data.aws_iam_role.ecs_task.name
  policy_arn = aws_iam_policy.ecs_task_least_privilege.arn
}