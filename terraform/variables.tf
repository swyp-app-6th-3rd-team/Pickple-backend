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
    22번 포트를 열 CIDR. 기본은 null 이며 이 경우 **SSH 규칙 자체를 만들지 않는다**.
    접속은 SSM Session Manager 로 한다(ADR-0012). SSM 이 동작하지 않는 상황의
    비상 수단으로만 임시 지정한다.
  EOT
  type        = string
  default     = null
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

variable "github_oidc_subject" {
  description = <<-EOT
    OIDC trust policy 의 sub 조건. null 이면 표준 형식으로 자동 생성한다:
      repo:<owner>/<repo>:ref:refs/heads/<branch>

    ⚠️ GitHub 이 2026-06-18 부터 신규 저장소에 immutable subject claims 를 적용하고 있어
    형식이 다를 수 있다. AssumeRole 이 실패하면 워크플로 로그의 실제 claim 을 보고
    이 변수로 덮어쓴다(ADR-0013 "포기한 것" 참조).
  EOT
  type        = string
  default     = null
}

variable "ecr_keep_image_count" {
  description = "ECR lifecycle policy 가 유지할 이미지 개수. 빌드마다 불변 태그가 쌓이므로 정리가 필요하다"
  type        = number
  default     = 10
}

# ── 상태 저장소 ─────────────────────────────────────────────

variable "state_bucket" {
  description = "tfstate S3 버킷 이름. backend.tf 와 값을 맞춰야 한다(backend 는 변수를 못 쓴다)"
  type        = string
  default     = "buyorpass-tfstate-251128835262"
}
