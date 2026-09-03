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

  # OIDC subject. 변수로 덮어쓰지 않으면 GitHub 의 immutable 형식을 만든다.
  #
  # 첫 배포(2026-09-02)에서 실측한 형식이다. CloudTrail 의 AccessDenied 이벤트에 실제 sub 가 찍혔다:
  #   repo:<owner>@<owner_id>/<repo>@<repo_id>:ref:refs/heads/<branch>
  # 예전 형식(repo:<owner>/<repo>:ref:...)은 이 저장소(2026-08-19 생성)에는 오지 않는다.
  # ID 는 `gh api repos/<owner>/<repo> --jq '.id, .owner.id'` 로 확인한다.
  github_oidc_subject = coalesce(
    var.github_oidc_subject,
    format(
      "repo:%s@%s/%s@%s:ref:refs/heads/%s",
      split("/", var.github_repository)[0], var.github_owner_id,
      split("/", var.github_repository)[1], var.github_repository_id,
      var.github_deploy_branch
    )
  )

  # Secrets Manager JSON 의 키 목록.
  #
  # 정본은 .env.example 이다(ADR-0026). 키 선언 직전의 연속 주석 블록에 있는
  # @secret 마커를 읽는다 — 여기에 목록을 다시 적지 않는다.
  # sync-secrets.sh 도 같은 파일을 파싱하므로 둘이 어긋날 수 없다.
  #
  # 정규식 주의: 식별자 문자군에 숫자를 포함한다([A-Za-z0-9_]).
  # 빠뜨리면 oauth_apple_private_key_base64 가 "base64" 의 64 에서 잘린다.
  # 주석 처리된 선언(#OAUTH_GOOGLE_CLIENT_ID=)도 스키마에 포함해야 하므로 ^#? 를 허용한다.
  secret_keys = [
    for m in regexall(
      "(?m)^#[^\\n]*@secret[^\\n]*\\n(?:#[^\\n=]*\\n)*#?[ \\t]*(?:export[ \\t]+)?([A-Za-z_][A-Za-z0-9_]*)=",
      file("${path.module}/../.env.example")
    ) : lower(m[0])
  ]
}
