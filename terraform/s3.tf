# 이미지 객체 저장소 (ADR-0021).
#
# 앱은 자격증명을 코드로 들고 있지 않다. S3ImageStorageConfig 가 credentialsProvider 를
# 지정하지 않아 AWS SDK 의 DefaultCredentialsProvider 체인이 걸리고, EC2 위에서는
# IMDS 를 통해 인스턴스 프로파일(iam.tf 의 aws_iam_role.app)로 해소된다(ADR-0013).
# 따라서 여기서 만들 것은 "버킷과 권한"이지 "키"가 아니다.
#
# ⚠️ 이 버킷은 CloudFront 를 통해서만 읽힌다(ADR-0027). 퍼블릭 차단 4개를 모두 켜 두고
# 버킷 정책으로 CloudFront 서비스 프린시펄에만 GetObject 를 연다.

resource "aws_s3_bucket" "images" {
  # ⚠️ S3 버킷 이름은 **전역 유일**이다. pickple-dev-images 는 남이 선점했을 수 있고
  # 그러면 apply 가 BucketAlreadyExists 로 죽는다. account_id 를 붙여 결정적으로 피한다.
  # bucket_prefix 의 랜덤 접미어를 쓰지 않는 이유는 output 으로 추적하기 어려워서다.
  bucket = "${local.name_prefix}-images-${local.account_id}"

  # 6주 후 teardown 시 객체가 남아 있어도 지울 수 있게 한다(ecr.tf 의 force_delete 와 같은 취지).
  force_destroy = true

  tags = { Name = "${local.name_prefix}-images" }
}

# ACL 을 완전히 끈다. 앱의 PutObjectRequest 는 .acl(...) 을 설정하지 않으므로
# 소유권만으로 접근을 판단하면 된다. 이 설정이 켜져 있으면 s3:PutObjectAcl 을
# 역할에 주더라도 거부되므로, iam.tf 에서도 그 권한을 넣지 않는다.
resource "aws_s3_bucket_ownership_controls" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# 퍼블릭 접근을 4개 모두 차단한 채로 둔다.
#
# 버킷 정책 공개 읽기를 골랐다면 block_public_policy 와 restrict_public_buckets 를
# false 로 내려야 했다. CloudFront + OAC 는 서비스 프린시펄로 접근하므로
# 퍼블릭 차단과 공존한다 — 이게 OAC 를 고른 실질적인 이득이다(ADR-0027).
resource "aws_s3_bucket_public_access_block" "images" {
  bucket = aws_s3_bucket.images.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ⚠️ 버저닝을 켜지 않았다(ADR-0027 "감수한 위험").
# 런타임 역할에 s3:DeleteObject 가 있으므로 앱 버그나 자격증명 탈취로 객체가 지워지면
# 복구 경로가 없다. 6주 MVP 라 감수하지만, 수명이 긴 환경에 이 구성을 재사용한다면
# aws_s3_bucket_versioning 을 먼저 켠다.

# SSE-S3. KMS 는 요청당 과금이고 역할에 kms:GenerateDataKey 를 추가로 줘야 한다.
resource "aws_s3_bucket_server_side_encryption_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 업로드 상한이 5MB(application.yml 의 app.image.max-file-size)라 SDK 가 멀티파트를
# 쓰지 않는다. 그래도 중단된 멀티파트가 생기면 요금만 먹고 보이지 않으므로 정리한다.
resource "aws_s3_bucket_lifecycle_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    id     = "abort-incomplete-multipart-upload"
    status = "Enabled"

    # provider 5.x 에서 filter 를 생략하면 "rule 에 filter 나 prefix 가 필요하다"고 경고한다.
    # 버킷 전체가 대상이므로 빈 filter 를 둔다.
    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# CloudFront 배포만 객체를 읽는다.
#
# AWS:SourceArn 조건이 없으면 "아무 CloudFront 배포나" 이 버킷을 오리진으로 삼을 수 있다.
# 배포 ARN 으로 좁혀 우리 배포만 통과시킨다.
data "aws_iam_policy_document" "images" {
  statement {
    sid       = "AllowCloudFrontRead"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.images.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.images.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "images" {
  bucket = aws_s3_bucket.images.id
  policy = data.aws_iam_policy_document.images.json

  # 퍼블릭 차단 설정과 경합하면 정책 적용이 AccessDenied 로 실패할 수 있다.
  # 차단 4개를 켠 채로 두는 구성이라 실제 충돌은 없지만, 순서를 명시해 재현성을 보장한다.
  depends_on = [aws_s3_bucket_public_access_block.images]
}
