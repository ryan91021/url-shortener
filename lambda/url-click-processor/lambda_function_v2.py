# ─────────────────────────────────────────────────────────────
# ★★ 這個檔案【沒有被部署】，而且【不在任何一天的排定任務裡】。
#
#   它是【修法 C · TransactWriteItems】的候選實作。
#   Day 36 那一輪只上了 D（記憶體 128→512）與 B′（EMF），兩者都已經合併進
#   lambda_function.py —— 也就是說，本檔和線上版本的【唯一差異就是 C】。
#
#   ⚠️ Day 39 更正：本檔原本寫「定位是 Day 38 的第二輪優化」。那是錯的——
#      index（減載版）的 Week 8 調整說明是「只做【一輪】優化驗證（第二輪 + CDF 圖表
#      在選做清單）」⇒ 第二輪優化在【P2 選做清單】，不是排定任務。
#      執行時機是 8 月鞏固期，或拿到面試邀請之後。見 plan/week8/day38.md (B)。
#
#   ⚠️ 打包時絕對不要用 `zip function.zip *.py`，那會把這個檔一起送上去。
#      正確：cd lambda/url-click-processor && zip function.zip lambda_function.py
#
#   ★ 為什麼留著它而不是刪掉：PERFORMANCE.md §3.2 明寫「Not deployed:
#     TransactWriteItems (touches idempotency semantics)」、§5 Future Work 也指向它。
#     一份被報告點名的候選實作，刪掉會讓那句話變成空話。
#
#   評估 C 之前要重新回答的問題：
#     · D 把 client-side 工作加速了幾倍？C 能省的絕對毫秒數還剩多少？
#     · 交易寫入的 WCU 是普通寫入的 2×（Day 34 warm 窗 58,551 → 117,102）——值嗎？
#     · 它會讓 Day 30 的補償刪除整段消失（那是加分，不是扣分）
# ─────────────────────────────────────────────────────────────

import json
import time

import boto3
from botocore.exceptions import ClientError

REGION = "ap-east-2"
TABLE_NAME = "ClickAnalytics"
DEDUP_TABLE_NAME = "ProcessedEvents"
NAMESPACE = "UrlShortener"
DEDUP_TTL_DAYS = 7

# ★★ 這裡從 boto3.resource 換成 boto3.client：
#    TransactWriteItems 只有【低階 client】有，dynamodb.Table 沒有這個 API（卡點 9）。
#    代價：屬性值要自己寫成 {"S": ...} / {"N": "..."}（★ N 的值是【字串】）。
ddb = boto3.client("dynamodb", region_name=REGION)


def emit_emf(duration_ms):
    """★ 修法 B′：用 EMF 取代 put_metric_data。

    把一行結構化 JSON print 到 stdout，CloudWatch Logs 會自動把它抽成 metric，
    【不需要任何 API 呼叫】⇒ 省掉 Day 34 量到的那 58–63 ms 固定開銷。
    ⚠️ 欄位名是 "Name" 不是 "MetricName"（卡點 7）；Timestamp 是【毫秒】（卡點 8）。
    ⚠️ 必須是【單獨一行】的 JSON —— print(json.dumps(...)) 剛好滿足。
    """
    print(json.dumps({
        "_aws": {
            "Timestamp": int(time.time() * 1000),          # ★ 毫秒，不是秒
            "CloudWatchMetrics": [{
                "Namespace": NAMESPACE,
                "Dimensions": [[]],                         # ★ 無維度 —— 跟現行 put_metric_data 一致
                "Metrics": [{
                    "Name": "ClickEventProcessingDuration", # ★★ 是 Name，不是 MetricName
                    "Unit": "Milliseconds",
                }],
            }],
        },
        "ClickEventProcessingDuration": duration_ms,        # ★ key 要跟上面的 Name 一模一樣
    }))


def lambda_handler(event, context):
    start = time.time()
    records = event.get("Records", [])
    processed = skipped = 0
    batch_item_failures = []

    # ★ TTL 在整批算一次就夠（同一批訊息差不了幾毫秒），順便省 len(records) 次 time.time()
    expires_at = str(int(time.time()) + DEDUP_TTL_DAYS * 86400)   # ★ N 型別的值是【字串】

    for record in records:
        message_id = record["messageId"]
        try:
            msg = json.loads(record["body"])
            short_code = msg["shortCode"]
            date = msg["clickedAt"][:10]        # ★ 從事件切 UTC 日期（非 Lambda 時鐘）

            # ★★ 修法 C：一次 API 呼叫做完「搶 messageId」+「累加 clickCount」。
            #    兩者要嘛一起成功、要嘛一起不發生 —— 這正是 Day 30 那段補償刪除
            #    在【手工模擬】的東西，所以下面那段補償邏輯可以整段拿掉。
            ddb.transact_write_items(TransactItems=[
                {
                    "Put": {
                        "TableName": DEDUP_TABLE_NAME,
                        "Item": {
                            "messageId": {"S": message_id},
                            "expiresAt": {"N": expires_at},
                        },
                        "ConditionExpression": "attribute_not_exists(messageId)",
                    }
                },
                {
                    "Update": {
                        "TableName": TABLE_NAME,
                        "Key": {"shortCode": {"S": short_code}, "date": {"S": date}},
                        "UpdateExpression": "ADD clickCount :inc",
                        "ExpressionAttributeValues": {":inc": {"N": "1"}},
                    }
                },
            ])
            processed += 1
            print(f"aggregated click shortCode={short_code} date={date} messageId={message_id}")

        except ClientError as e:
            code = e.response["Error"]["Code"]
            # ★★ 交易失敗丟的是 TransactionCanceledException，不是
            #    ConditionalCheckFailedException（卡點 11）——真正的原因在 CancellationReasons，
            #    順序跟 TransactItems 一一對應：[0] = 那個 Put。
            if code == "TransactionCanceledException":
                reasons = [r.get("Code") for r in e.response.get("CancellationReasons", [])]
                if reasons[:1] == ["ConditionalCheckFailed"]:
                    skipped += 1
                    print(f"duplicate skipped messageId={message_id} reasons={reasons}")
                    continue                      # ★ 重投：不重複計數，直接跳下一則
            print(f"FAILED messageId={message_id} code={code} err={e}")
            batch_item_failures.append({"itemIdentifier": message_id})

        except Exception as e:
            print(f"FAILED messageId={message_id} err={e}")
            batch_item_failures.append({"itemIdentifier": message_id})

    duration_ms = (time.time() - start) * 1000.0
    emit_emf(duration_ms)                          # ★ B′：零 API 呼叫
    print(f"processed={processed} skipped={skipped} "
          f"failed={len(batch_item_failures)} in {duration_ms:.1f} ms")
    return {"batchItemFailures": batch_item_failures}
