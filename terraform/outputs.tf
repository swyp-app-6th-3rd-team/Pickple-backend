output "app_url" {
  description = "애플리케이션 접속 주소. Gabia 네임서버 변경과 첫 배포 뒤에 살아난다"
  value       = "https://${local.api_fqdn}"
}

output "api_fqdn" {
  description = "백엔드 develop 서버 FQDN. Caddy 사이트 주소와 OAuth 콘솔 redirect URI 에 쓴다"
  value       = local.api_fqdn
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

output "secret_keys" {
  description = "Secrets Manager JSON 의 키 스키마. scripts/sync-secrets.sh 가 정본으로 읽는다"
  value       = local.secret_keys
}

output "data_volume_id" {
  description = "MySQL 데이터 볼륨. prevent_destroy 보호된다"
  value       = aws_ebs_volume.data.id
}

output "route53_name_servers" {
  description = "Gabia 네임서버 설정에 넣을 4개. 이것이 도메인 쪽 유일한 수동 단계다(ADR-0022)"
  value       = aws_route53_zone.main.name_servers
}

output "region" {
  description = "scripts/ 가 참조한다"
  value       = var.region
}

output "aws_profile" {
  description = "scripts/ 가 참조한다"
  value       = var.aws_profile
}

# ── 다음 단계 안내 ──────────────────────────────────────────

output "next_steps" {
  description = "apply 직후 해야 할 일"
  value       = <<-EOT

    1) 비밀 동기화 — 로컬 .env 를 읽어 Secrets Manager 에 넣는다. 값은 화면에 나오지 않는다.

       terraform/scripts/sync-secrets.sh --dry-run   # 키·출처·길이 표 확인
       terraform/scripts/sync-secrets.sh

    2) Gabia 네임서버 변경 — 아래 4개를 Gabia "도메인 관리 > 네임서버 설정" 에 넣는다.

       ${join("\n       ", aws_route53_zone.main.name_servers)}

       전파 확인 (둘 다 맞아야 다음으로 간다. 보통 1시간 안, 최대 48시간):
       dig +short NS ${var.domain_name} @8.8.8.8     # awsdns 4개
       dig +short A ${local.api_fqdn}                 # ${aws_eip.app.public_ip}

    3) GitHub 저장소 Settings > Secrets and variables > Actions > Variables 에 등록
       (Secrets 가 아니라 Variables 탭이다 — 비밀이 아니다)

       AWS_ROLE_ARN     = ${aws_iam_role.github_deploy.arn}
       AWS_REGION       = ${var.region}
       ECR_REGISTRY     = ${local.account_id}.dkr.ecr.${var.region}.amazonaws.com
       ECR_REPOSITORY   = ${aws_ecr_repository.app.name}
       EC2_INSTANCE_ID  = ${aws_instance.app.id}

    4) deploy-develop.yml 의 push 트리거 주석을 풀고 develop 에 머지 → 첫 배포.
       Caddy 가 Let's Encrypt 인증서를 받는다. 2) 의 dig 가 맞기 전에는 머지하지 않는다.

    5) 접속 확인
       curl -sI https://${local.api_fqdn}/actuator/health
       curl -sI http://${local.api_fqdn}                  # 308 → https

    6) OAuth 콘솔(카카오·네이버·구글)에 redirect URI 등록
       https://${local.api_fqdn}/login/oauth2/code/{kakao,naver,google}

    셸 접속 (SSH 키 불필요):
       aws ssm start-session --target ${aws_instance.app.id} --region ${var.region} --profile ${var.aws_profile}
  EOT
}
