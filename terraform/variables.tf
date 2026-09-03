variable "project" {
  description = "리소스 이름·태그 접두어"
  type        = string
  default     = "pickple"
}

variable "env" {
  description = "환경 이름. 이 사이클은 develop 단일이다(ADR-0012)"
  type        = string
  default     = "dev"
}

variable "region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "aws_profile" {
  description = "로컬 AWS CLI 프로필. SSO 가 아니라 IAM User 액세스키 프로필이다"
  type        = string
  default     = "root_habin"
}

variable "aws_account_id" {
  description = "타겟 계정 ID. provider 의 allowed_account_ids 가드에 쓰인다"
  type        = string
  default     = "251128835262"

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "계정 ID 는 숫자 12자리여야 합니다."
  }
}

# ── 네트워크 ────────────────────────────────────────────────

variable "vpc_cidr" {
  description = "VPC CIDR"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = <<-EOT
    public subnet CIDR 목록. AZ 순서대로 매핑된다.
    EC2 는 첫 번째 subnet 에만 배치하지만, subnet 은 무료이고 나중에 ALB·RDS 로
    확장할 때 subnet 부터 다시 만드는 것은 파괴적 변경이라 2개를 미리 만든다.
  EOT
  type        = list(string)
  default     = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "ssh_allowed_cidr" {
  description = <<-EOT
    SSH 포트(var.ssh_port)를 열 CIDR. 기본은 null 이며 이 경우 **SSH 규칙 자체를 만들지 않는다**.
    값을 지정하면 인터넷에서 직접 SSH 로 붙을 수 있다(ADR-0023).
    SSM Session Manager 는 그대로 살아 있으므로, 이 값을 다시 null 로 되돌려도 접속 경로는 남는다.
  EOT
  type        = string
  default     = null
}

variable "ssh_public_key" {
  description = <<-EOT
    SSH 키페어의 **공개키**(ssh-ed25519 AAAA...). 개인키는 넣지 않는다.
    개인키는 발급 시 1회만 반환되므로 로컬 ~/.ssh/pickple-dev.pem 에 chmod 400 으로 보관한다.
    공개키는 `ssh-keygen -y -f ~/.ssh/pickple-dev.pem` 로 다시 얻을 수 있다.
  EOT
  type        = string
}

variable "ssh_port" {
  description = <<-EOT
    sshd 가 listen 할 포트. 기본 22 대신 비표준 포트를 쓰면 자동 스캔 트래픽이 줄어든다.
    ⚠️ 이 값을 바꾸면 user_data 가 SELinux 포트 라벨(semanage)과 sshd_config 를 함께 고친다.
    SG 만 열고 OS 설정을 빼먹으면 접속되지 않는다.
  EOT
  type        = number
  default     = 22
}

variable "mysql_allowed_cidr" {
  description = <<-EOT
    MySQL 호스트 포트(var.mysql_host_port)를 열 CIDR. 기본은 null 이며 이 경우 **규칙 자체를 만들지 않는다**.
    ⚠️ 값을 지정하면 DB 가 인터넷에 노출된다. 방어선은 계정 비밀번호뿐이므로
    remote root 를 막고 앱 계정으로만 붙는다(ADR-0023).
  EOT
  type        = string
  default     = null
}

variable "mysql_host_port" {
  description = <<-EOT
    MySQL 컨테이너 3306 을 호스트에 매핑할 포트. docker-compose-ec2.yml 의 ports 와 반드시 같아야 한다.
    3306 을 그대로 쓰지 않는 이유는 자동 스캔 회피다.
  EOT
  type        = number
  default     = 13307
}

# ── 인스턴스 ────────────────────────────────────────────────

variable "instance_type" {
  description = <<-EOT
    EC2 인스턴스 타입. 기본 t4g.small(2vCPU/2GB/arm64).
    t4g.micro(1GB)는 JVM + MySQL 을 감당하지 못한다(OOM).
    메모리가 부족하면 t4g.medium 으로 올린다.
  EOT
  type        = string
  default     = "t4g.small"
}

variable "instance_architecture" {
  description = <<-EOT
    AMI 아키텍처. "arm64" 또는 "x86_64".
    arm64 를 쓰려면 이미지도 arm64 여야 한다 — 이 저장소는 PUBLIC 이라
    ubuntu-24.04-arm 러너가 무료이므로 네이티브 빌드가 가능하다.
    x86_64 로 바꾸면 instance_type 도 t3 계열로 함께 바꿔야 한다.
  EOT
  type        = string
  default     = "arm64"

  validation {
    condition     = contains(["arm64", "x86_64"], var.instance_architecture)
    error_message = "arm64 또는 x86_64 만 지원합니다."
  }
}

variable "root_volume_size" {
  description = "루트 EBS 크기(GB). JFR 링버퍼 512MB + 로그 여유 포함"
  type        = number
  default     = 20
}

variable "data_volume_size" {
  description = "MySQL datadir 전용 EBS 크기(GB)"
  type        = number
  default     = 10
}

# ── 배포 ────────────────────────────────────────────────────

variable "github_repository" {
  description = "OIDC 신뢰 대상 저장소 (owner/repo)"
  type        = string
  default     = "swyp-app-6th-3rd-team/Pickple-backend"
}

variable "github_deploy_branch" {
  description = "배포를 허용할 브랜치"
  type        = string
  default     = "develop"
}

variable "github_owner_id" {
  description = "GitHub 조직(owner) 의 숫자 ID. immutable sub claim 에 들어간다. `gh api orgs/<owner> --jq .id`"
  type        = string
  default     = "317244014"
}

variable "github_repository_id" {
  description = "GitHub 저장소의 숫자 ID. immutable sub claim 에 들어간다. `gh api repos/<owner>/<repo> --jq .id`"
  type        = string
  default     = "1339691515"
}

variable "github_oidc_subject" {
  description = <<-EOT
    OIDC trust policy 의 sub 조건. null 이면 immutable 형식으로 자동 생성한다:
      repo:<owner>@<owner_id>/<repo>@<repo_id>:ref:refs/heads/<branch>

    2026-06-18 이후 신규 저장소는 이 형식만 온다(첫 배포에서 CloudTrail 로 실측, PRD-008).
    저장소를 옮기거나 GitHub 이 형식을 또 바꾸면 CloudTrail 의 AssumeRoleWithWebIdentity
    AccessDenied 이벤트에 찍힌 실제 sub 를 이 변수로 덮어쓴다.
  EOT
  type        = string
  default     = null
}

variable "ecr_keep_image_count" {
  description = "ECR lifecycle policy 가 유지할 이미지 개수. 빌드마다 불변 태그가 쌓이므로 정리가 필요하다"
  type        = number
  default     = 10
}

# ── 도메인 (ADR-0022) ───────────────────────────────────────

variable "domain_name" {
  description = "Route53 hosted zone 으로 관리할 도메인. Gabia 에서 네임서버만 이쪽으로 위임한다"
  type        = string
  default     = "pickple.app"
}

variable "api_subdomain" {
  description = "백엔드 develop 서버의 서브도메인. apex 는 프론트 몫으로 비워 둔다"
  type        = string
  default     = "dev-api"
}

variable "extra_records" {
  description = <<-EOT
    프론트 등 다른 팀의 DNS 레코드. 키는 서브도메인("@" 는 apex), 값은 type/ttl/records.
    NS 가 Route53 으로 넘어오면 apex·www 도 여기서 관리해야 하므로 tfvars 한 줄로 받는다.
      extra_records = {
        "@"   = { type = "A",     ttl = 300, records = ["76.76.21.21"] }
        "www" = { type = "CNAME", ttl = 300, records = ["cname.vercel-dns.com"] }
      }
  EOT
  type = map(object({
    type    = string
    ttl     = optional(number, 300)
    records = list(string)
  }))
  default = {}

  # Route53 은 zone apex 의 CNAME 을 거부한다. apply 가 중간에 죽지 않게 plan 에서 막는다.
  # Vercel 류는 apex 에 A 레코드(76.76.21.21 등)를 안내한다.
  validation {
    condition = alltrue([
      for k, v in var.extra_records : !(k == "@" && upper(v.type) == "CNAME")
    ])
    error_message = "apex(\"@\") 에는 CNAME 을 둘 수 없습니다. A/AAAA 를 쓰거나 서브도메인으로 옮기십시오."
  }
}

# ── 상태 저장소 ─────────────────────────────────────────────

variable "state_bucket" {
  description = "tfstate S3 버킷 이름. backend.tf 와 값을 맞춰야 한다(backend 는 변수를 못 쓴다)"
  type        = string
  default     = "buyorpass-tfstate-251128835262"
}

# ── 런타임 설정 (비밀 아님) ─────────────────────────────────
#
# compose 가 보간하지만 그동안 아무도 공급하지 않아 `:-` 기본값으로 돌던 값들이다(ADR-0026).
# 비밀이 아니므로 Secrets Manager 가 아니라 fetch-secrets.sh 가 직접 .env 에 쓴다.

variable "spring_profiles_active" {
  description = "EC2 에서 활성화할 Spring 프로파일. local 이 올라가면 운영이 SQL 로그를 쏟으므로 prod 로 고정한다"
  type        = string
  default     = "prod"

  validation {
    condition     = var.spring_profiles_active != "local"
    error_message = "EC2 에 local 프로파일을 쓸 수 없습니다."
  }
}

variable "log_max_history" {
  description = "로그 보관 일수. .env.example 이 의도한 값은 30 이다"
  type        = number
  default     = 30
}

variable "log_total_size_cap" {
  description = "로그 총 용량 상한. .env.example 이 의도한 값은 3GB 다"
  type        = string
  default     = "3GB"
}

# 아래 3개는 프론트 배포 위치가 정해지기 전까지 compose 기본값과 동일하게 둔다.
# 프론트가 뜨면 terraform.tfvars 에서 실제 도메인으로 바꾼다.

variable "auth_redirect_uri" {
  description = "OAuth 로그인 후 리다이렉트할 프론트 주소"
  type        = string
  default     = "http://localhost:3000/oauth/callback"
}

variable "auth_allowed_redirect_hosts" {
  description = "리다이렉트를 허용할 호스트 목록(쉼표 구분). 오픈 리다이렉트 방어선이므로 넓히지 않는다"
  type        = string
  default     = "localhost"
}

variable "cors_allowed_origins" {
  description = "CORS 허용 오리진(쉼표 구분). 쿠키 인증이므로 와일드카드를 쓰지 않는다"
  type        = string
  default     = "http://localhost:3000"
}
