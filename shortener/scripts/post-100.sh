#!/usr/bin/env bash
# Day 10 · 100 POST 不同 longUrl 壓力測試
# 用途：驗證 ConditionExpression + retry 在實務量下都成功（冪等性實證）
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
ENDPOINT="$BASE/api/v1/shorten"
LOG="/tmp/post-100.log"
> "$LOG"

success=0
fail=0
for i in $(seq 1 100); do
  url="https://www.example.com/day10-load-$i"
  http_code=$(curl -s -o /tmp/last-resp.json -w "%{http_code}" \
    -X POST "$ENDPOINT" \
    -H "Content-Type: application/json" \
    -d "{\"longUrl\":\"$url\"}")
  if [[ "$http_code" == "201" ]]; then
    success=$((success + 1))
    echo "[$i] OK $(jq -r .shortCode /tmp/last-resp.json)" >> "$LOG"
  else
    fail=$((fail + 1))
    echo "[$i] FAIL $http_code $(cat /tmp/last-resp.json)" >> "$LOG"
  fi
  sleep 0.05    # 50 ms 間隔避免 DynamoDB on-demand 短期 throttle
done

echo "Total: 100 | Success: $success | Fail: $fail"
echo "Log: $LOG"