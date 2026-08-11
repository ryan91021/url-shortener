# ──────────────────────────────────────────────────────────────
# Day 29 · API key 的保險箱
#   ★★ 這裡【只建盒子、不放值】：值用 AWS CLI put-secret-value 灌進去。
#      理由見下方決策表——用 aws_secretsmanager_secret_version 會把【明文 key 寫進 tfstate】。
# ──────────────────────────────────────────────────────────────
resource "aws_secretsmanager_secret" "api_key" {
  name        = "url-shortener/api-key"
  description = "X-API-Key for write endpoints (Day 29). Value is written by CLI, never by Terraform."

  # ★★★ Day 30 要做 terraform destroy → apply 的一鍵重建演練。
  #     Secrets Manager 的預設刪除是【排程刪除】（回收期 7–30 天，預設 30），
  #     名字在回收期內被佔住 → 隔天 apply 會報：
  #       InvalidRequestException: You can't create this secret because a secret with this
  #       name is already scheduled for deletion.
  #     設 0 = 立刻真刪、名字馬上釋放。今天多這一行，明天少一場災難（卡點 2）。
  recovery_window_in_days = 0
}