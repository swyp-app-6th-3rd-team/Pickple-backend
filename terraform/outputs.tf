output "app_url" {
  description = "애플리케이션 접속 주소"
  value       = "http://${aws_eip.app.public_ip}"
}

output "public_ip" {
  description = "EIP. 인스턴스를 교체해도 유지된다"
  value       = aws_eip.app.public_ip
}

output "instance_id" {
  description = "EC2 인스턴스 ID. GitHub vars.EC2_INSTANCE_ID 에 등록한다"
  value       = aws_instance.app.id
}

output "ecr_registry" {
  description = "ECR 레지스트리. GitHub vars.ECR_REGISTRY 에 등록한다"
  value       = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

output "ecr_repository" {
  description = "ECR 레포지토리 이름"
  value       = aws_ecr_repository.app.name
}

output "github_deploy_role_arn" {
  description = "GitHub Actions 가 assume 할 역할. vars.AWS_ROLE_ARN 에 등록한다"
  value       = aws_iam_role.github_deploy.arn
}

output "secret_arn" {
  description = "런타임 비밀 ARN"
  value       = aws_secretsmanager_secret.app.arn
}

output "data_volume_id" {
  description = "MySQL 데이터 볼륨. prevent_destroy 로 보호된다"
  value       = aws_ebs_volume.data.id
}

# ── 다음 단계 안내 ──────────────────────────────────────────

output "next_steps" {
  description = "apply 직후 해야 할 일"
  value       = <<-EOT

    1) 비밀 실값 주입 (자리표시자 상태로는 앱이 뜨지 않는다)

       aws secretsmanager put-secret-value \
         --secret-id ${aws_secretsmanager_secret.app.arn} \
         --region ${var.region} --profile ${var.aws_profile} \
         --secret-string '{
           "mysql_root_password": "...",
           "mysql_password": "...",
           "jwt_secret_key": "<32바이트 이상>",
           "oauth_google_client_id": "...",  "oauth_google_client_secret": "...",
           "oauth_kakao_client_id": "...",   "oauth_kakao_client_secret": "...",
           "oauth_naver_client_id": "...",   "oauth_naver_client_secret": "..."
         }'

    2) GitHub 저장소 Settings > Secrets and variables > Actions > Variables 에 등록
       (Secrets 아님 — 비밀이 아니다)

       AWS_ROLE_ARN     = ${aws_iam_role.github_deploy.arn}
       AWS_REGION       = ${var.region}
       ECR_REGISTRY     = ${local.account_id}.dkr.ecr.${var.region}.amazonaws.com
       ECR_REPOSITORY   = ${aws_ecr_repository.app.name}
       EC2_INSTANCE_ID  = ${aws_instance.app.id}

    3) develop 브랜치에 push → 배포

    4) 접속 확인
       curl ${aws_eip.app.public_ip == "" ? "<EIP>" : "http://${aws_eip.app.public_ip}"}/actuator/health

    셸 접속 (SSH 키 불필요):
       aws ssm start-session --target ${aws_instance.app.id} --region ${var.region} --profile ${var.aws_profile}
  EOT
}
