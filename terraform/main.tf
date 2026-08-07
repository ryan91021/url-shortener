resource "aws_dynamodb_table" "url_mappings" {
  name         = "UrlMappings"     # ★ AWS 上的真實表名（駝峰大寫；import ID 就用它）
  billing_mode = "PAY_PER_REQUEST" # ＝ Console 的 On-demand（Day 7 建表就選這個）
  hash_key     = "shortCode"       # 分割鍵（PK）

  attribute {
    name = "shortCode"
    type = "S" # String（shortCode 是字串）
  }
  # ★★ 只宣告【鍵】屬性！longUrl / createdAt / clickCount 是非鍵欄位、
  #    DynamoDB schemaless、不寫進 attribute{}；多宣告 → validate/plan 報
  #    "all attributes must be indexed. Unused attribute: longUrl"（卡點 2）

  tags = {
    "Project=url-shortener" = "Env=dev"
  }
}

resource "aws_dynamodb_table" "click_analytics" {
  name         = "ClickAnalytics"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "shortCode"
  range_key    = "date" # ★ Sort Key（UrlMappings 沒有、這張有）

  attribute {
    name = "shortCode"
    type = "S"
  }

  attribute {
    name = "date"
    type = "S"
  }
  # ★★ 有 PK+SK ⇒ 要宣告【兩個】鍵屬性（昨天 UrlMappings 只有 PK 所以只宣告一個）
  #    clickCount 是非鍵欄位 → 絕不宣告（DynamoDB schemaless，卡點 6）
  # ★★ 這張表【沒有任何 tag】（已用 list-tags-of-resource 查證）→
  #    不要把 url_mappings 那個 tags 區塊複製過來，否則 plan 出現 + tags（卡點 3）
}

resource "aws_sqs_queue" "click_events_dlq" {
  name = "url-click-events-dlq"

  visibility_timeout_seconds = 30      # SQS 預設
  message_retention_seconds  = 345600  # 4 天（SQS 預設）
  delay_seconds              = 0       # SQS 預設
  receive_wait_time_seconds  = 0       # 0 = short polling（SQS 預設）
  max_message_size           = 1048576 # ★★ 真值 1 MiB；provider 預設是 262144，不寫必 diff（卡點 2）
  sqs_managed_sse_enabled    = true    # SSE-SQS（AWS 代管加密，建立時預設開啟）

  # ★ DLQ 自己【沒有】redrive_policy——它是被指向的那一端，不是指向別人的那一端
  # ★ 這個佇列【沒有任何 tag】（已查證）→ 不要加 tags 區塊（卡點 3）
}

resource "aws_sqs_queue" "click_events" {
  name = "url-click-events"

  visibility_timeout_seconds = 30     # ★ 必須 ≥ 6 × Lambda timeout(5s)；30 剛好符合
  message_retention_seconds  = 345600 # 4 天
  delay_seconds              = 0
  receive_wait_time_seconds  = 0
  max_message_size           = 1048576 # ★★ 同上，真值 1 MiB（卡點 2）
  sqs_managed_sse_enabled    = true

  # ★★ 死信佇列設定：用 jsonencode + 【參照】DLQ 的 .arn
  #    絕不硬貼 "arn:aws:sqs:ap-east-2:<ACCOUNT_ID>:url-click-events-dlq"
  #    —— 硬貼 = Account ID 進 git，而且 Terraform 不知道兩者的依賴關係（卡點 5）
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.click_events_dlq.arn
    maxReceiveCount     = 3 # ★ 數字，不是 "3" 字串；意思是「收第 3 次還失敗就丟 DLQ」
  })
}