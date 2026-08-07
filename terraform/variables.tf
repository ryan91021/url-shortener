variable "alert_email" {
  description = "接收 DLQ 告警的 email。★ 用 terraform.tfvars（已被 .gitignore 的 *.tfvars 擋住）或 TF_VAR_alert_email 提供，絕不寫進 .tf"
  type        = string
  sensitive   = true # ★ plan/apply 輸出會顯示成 (sensitive value)，不會把你的 email 印在畫面上
}
