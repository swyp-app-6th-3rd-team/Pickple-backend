terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region  = var.region
  profile = var.aws_profile

  # 오배포 방어. 계정을 잘못 잡으면 apply 가 시작되기 전에 멈춘다.
  # 이 저장소는 조직 관리 계정에 워크로드를 올리므로(ADR-0012 "계정 축") 특히 중요하다.
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project   = var.project
      Env       = var.env
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

# EC2 를 배치할 AZ 를 고정하기 위해 사용 가능한 AZ 목록을 조회한다.
data "aws_availability_zones" "available" {
  state = "available"
}
