terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.56.0"
    }
  }
}

provider "aws" {
  region = "ap-east-2" # ★★ 專案實際 region；不是 index 寫的 us-west-2（那是 Week 2 舊值，卡點 1）
}