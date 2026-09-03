# IAM role 2개.
#
#   1. EC2 instance profile  — 인스턴스가 자기 비밀을 스스로 조회하고 SSM 으로 관리된다
#   2. GitHub OIDC role      — CI 가 장기 자격증명 없이 배포한다
#
# 사람이 쓰는 IAM User·그룹은 여기서 관리하지 않는다(ADR-0012 "작업 경계").
# 워크로드 자격증명만 코드와 수명을 같이한다.

# ══ 1. EC2 instance profile ════════════════════════════════

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${local.name_prefix}-ec2"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json

  tags = { Name = "${local.name_prefix}-ec2" }
}

# SSM Session Manager + Run Command 의 전제.
# 이게 있어야 인스턴스가 SSM 에 등록되고, 그래야 22번 포트를 닫을 수 있다.
resource "aws_iam_role_policy_attachment" "app_ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "app_runtime" {
  # 자기 비밀만 읽는다. 다른 secret 은 못 본다.
  statement {
    sid       = "ReadOwnSecret"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = [aws_secretsmanager_secret.app.arn]
  }

  # ECR 이미지 pull.
  statement {
    sid       = "PullImage"
    actions   = ["ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage", "ecr:BatchCheckLayerAvailability"]
    resources = [aws_ecr_repository.app.arn]
  }

  # 인증 토큰 발급은 리소스를 특정할 수 없다(AWS 사양).
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # 이미지 객체 쓰기 (ADR-0021).
  #
  # 앱이 실제로 하는 일만 넣는다. S3ImageObjectStorage 는 putObject 와 deleteObject
  # 두 가지만 호출한다.
  #
  # - s3:GetObject 는 넣지 않는다. accessUrl() 의 utilities().getUrl() 은 네트워크 호출이
  #   아니라 URL 문자열을 조립할 뿐이다. 최종 사용자의 읽기는 CloudFront 가 버킷 정책으로
  #   처리하지 이 역할이 아니다(ADR-0027).
  # - s3:ListBucket 도 넣지 않는다. 앱에 목록 조회가 없다. 부수효과로 없는 키에 대한
  #   DeleteObject 가 404 대신 204 를 반환하는데, 보상 삭제가 best-effort 라 무해하다.
  # - s3:PutObjectAcl 은 BucketOwnerEnforced 가 ACL 자체를 거부하므로 주면 안 된다.
  #
  # ⚠️ 객체 연산이므로 ARN 은 "버킷/*" 형태다. 버킷 ARN(슬래시 없음)을 쓰면
  # 조용히 AccessDenied 가 난다.
  statement {
    sid       = "WriteImageObjects"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.images.arn}/*"]
  }
}

resource "aws_iam_role_policy" "app_runtime" {
  name   = "${local.name_prefix}-ec2-runtime"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app_runtime.json
}

resource "aws_iam_instance_profile" "app" {
  name = "${local.name_prefix}-ec2"
  role = aws_iam_role.app.name
}

# ══ 2. GitHub OIDC ═════════════════════════════════════════
#
# 계정에 OIDC provider 가 0개임을 확인하고 무조건 생성한다.
# (계정당 issuer 하나만 존재할 수 있어, 이미 있으면 EntityAlreadyExists 가 난다.)
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # AWS 가 2023년부터 GitHub 인증서를 자체 신뢰하므로 thumbprint 는 형식상 값이다.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = { Name = "github-actions" }
}

data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # ⚠️ 여기가 이 파일에서 가장 중요한 조건이다.
    #    와일드카드(repo:owner/repo:*)를 쓰면 **fork 의 PR 에서도 AssumeRole 이 된다.**
    #    브랜치까지 고정해 develop 푸시에서만 열리게 한다.
    #
    #    GitHub 이 2026-06-18 부터 신규 저장소에 immutable subject claims 를 적용하고 있어
    #    형식이 다를 수 있다. 실패하면 var.github_oidc_subject 로 덮어쓴다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [local.github_oidc_subject]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${local.name_prefix}-github-deploy"
  description        = "GitHub Actions deploy role (OIDC, no long-lived keys)"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json

  tags = { Name = "${local.name_prefix}-github-deploy" }
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # 이 저장소의 레포지토리에만 push 한다.
  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:DescribeImages",
    ]
    resources = [aws_ecr_repository.app.arn]
  }

  # ⚠️ SendCommand 를 Resource "*" 로 주면 계정 내 **모든 인스턴스**에 명령을 보낼 수 있다.
  #    인스턴스 ARN 과 문서 ARN 을 모두 명시해 범위를 좁힌다.
  statement {
    sid     = "SsmDeploy"
    actions = ["ssm:SendCommand"]
    resources = [
      "arn:aws:ec2:${var.region}:${local.account_id}:instance/${aws_instance.app.id}",
      "arn:aws:ssm:${var.region}::document/AWS-RunShellScript",
    ]
  }

  # 배포 결과를 폴링해 성공/실패를 판정한다.
  statement {
    sid       = "SsmPoll"
    actions   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations", "ssm:ListCommands"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "${local.name_prefix}-github-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}
