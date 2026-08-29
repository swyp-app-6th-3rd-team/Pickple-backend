locals {
  # 모든 리소스 이름의 접두어. 예: pickple-dev-app
  name_prefix = "${var.project}-${var.env}"

  account_id = data.aws_caller_identity.current.account_id

  # EC2 는 첫 번째 AZ 에만 배치한다. subnet 은 2개지만 인스턴스는 1대다(ADR-0012).
  azs = slice(data.aws_availability_zones.available.names, 0, length(var.public_subnet_cidrs))

  # 컨테이너 포트. SG 에는 열지 않는다 — Caddy 가 compose 내부 네트워크로 프록시한다.
  app_port   = 8080
  mysql_port = 3306
  http_port  = 80
  https_port = 443

  # 백엔드 develop 서버의 FQDN. 예: dev-api.pickple.app (ADR-0022)
  api_fqdn = "${var.api_subdomain}.${var.domain_name}"

  # 데이터 EBS 가 인스턴스에 붙는 경로.
  #
  # ⚠️ Nitro 기반 인스턴스(t4g 포함)는 /dev/sdf 로 요청해도 커널에서 NVMe 로 보인다.
  #    user_data 가 이 경로를 그대로 믿으면 마운트에 실패하므로, 스크립트는
  #    /dev/disk/by-id 심링크로 실제 장치를 찾는다.
  data_device_name = "/dev/sdf"
  data_mount_point = "/data"

  # 배포 산출물이 EC2 에 놓이는 위치.
  app_dir = "/opt/pickple"

  # OIDC subject. 변수로 덮어쓰지 않으면 표준 형식을 만든다.
  github_oidc_subject = coalesce(
    var.github_oidc_subject,
    "repo:${var.github_repository}:ref:refs/heads/${var.github_deploy_branch}"
  )

  # Secrets Manager JSON 의 키 목록.
  # .env.example 에서 도출했다. fetch-secrets.sh 가 이 키들을 .env 로 펼친다.
  secret_keys = [
    "mysql_root_password",
    "mysql_password",
    "jwt_secret_key",
    "oauth_google_client_id",
    "oauth_google_client_secret",
    "oauth_kakao_client_id",
    "oauth_kakao_client_secret",
    "oauth_naver_client_id",
    "oauth_naver_client_secret",
    "oauth_apple_enabled",
    "oauth_apple_team_id",
    "oauth_apple_key_id",
    "oauth_apple_client_id",
    "oauth_apple_private_key_base64",
    "oauth_apple_token_encryption_keys",
    "oauth_apple_token_active_key_id",
  ]
}
