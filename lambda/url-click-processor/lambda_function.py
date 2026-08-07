import json
import time
from decimal import Decimal

import boto3
from botocore.exceptions import ClientError   # ★ 捕捉條件寫入失敗

REGION = "ap-east-2"
TABLE_NAME = "ClickAnalytics"
DEDUP_TABLE_NAME = "ProcessedEvents"           # ★ 今天新增的去重表
NAMESPACE = "UrlShortener"
DEDUP_TTL_DAYS = 7                             # 去重記錄保留 7 天（TTL 自動刪）

# client/resource 放 handler 外＝跨 invocation 重用（暖啟動省初始化）
dynamodb = boto3.resource("dynamodb", region_name=REGION)
table = dynamodb.Table(TABLE_NAME)
dedup_table = dynamodb.Table(DEDUP_TABLE_NAME)  # ★ 去重表
cloudwatch = boto3.client("cloudwatch", region_name=REGION)


def lambda_handler(event, context):
    start = time.time()
    records = event.get("Records", [])
    processed = skipped = 0
    batch_item_failures = []                    # ★ partial batch：只裝「失敗那幾則」的 messageId

    for record in records:
        message_id = record["messageId"]        # ★ SQS 給的傳輸層唯一鍵（去重就靠它）
        try:
            # ① 冪等 claim：先搶 messageId。attribute_not_exists → 只有「第一次見」才寫得進
            #    重投同一則 → 這裡拋 ConditionalCheckFailedException → 略過、不再 ADD（不重複計數）
            try:
                dedup_table.put_item(
                    Item={
                        "messageId": message_id,
                        "expiresAt": int(time.time()) + DEDUP_TTL_DAYS * 86400,  # TTL：Unix epoch 秒
                    },
                    ConditionExpression="attribute_not_exists(messageId)",
                )
            except ClientError as e:
                if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
                    skipped += 1
                    print(f"duplicate skipped messageId={message_id}")
                    continue                    # ★ 重投：直接跳下一則
                raise                           # 其他錯誤（如 AccessDenied）→ 往外丟 → 進 batchItemFailures

            # ② 真正聚合（跟 Day 23 相同語義；搶到 messageId 才會執行）
            msg = json.loads(record["body"])    # body 是純 ClickEvent JSON（非 SNS 信封）
            short_code = msg["shortCode"]
            date = msg["clickedAt"][:10]        # ★ 從事件切 UTC 日期（非 Lambda 時鐘）
            table.update_item(
                Key={"shortCode": short_code, "date": date},
                UpdateExpression="ADD clickCount :inc",
                ExpressionAttributeValues={":inc": Decimal(1)},
            )
            processed += 1
            print(f"aggregated click shortCode={short_code} date={date} messageId={message_id}")

        except Exception as e:
            # ★ partial batch：只把「這一則」標記失敗 → 只有它會重投，同批成功的不受影響
            print(f"FAILED messageId={message_id} err={e}")
            batch_item_failures.append({"itemIdentifier": message_id})

    duration_ms = (time.time() - start) * 1000.0
    cloudwatch.put_metric_data(
        Namespace=NAMESPACE,
        MetricData=[{
            "MetricName": "ClickEventProcessingDuration",
            "Value": duration_ms,
            "Unit": "Milliseconds",
        }],
    )
    print(f"processed={processed} skipped={skipped} failed={len(batch_item_failures)} in {duration_ms:.1f} ms")
    # ★ 回傳失敗清單；mapping 設 ReportBatchItemFailures 才會「只重投這些、其餘視為成功刪掉」
    return {"batchItemFailures": batch_item_failures}