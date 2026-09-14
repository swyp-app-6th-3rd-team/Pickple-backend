# F02 (#174). 기존 S3 + CloudFront + OAC를 그대로 사용한다.
# 제공받은 기본 이미지 한 장을 기존 네 후보 경로에서 동일하게 제공한다.
# 별도 디자인이 생기면 해당 키의 원본만 교체할 수 있다.
resource "aws_s3_object" "default_profile_images" {
  for_each = toset(["profile-1.png", "profile-2.png", "profile-3.png", "profile-4.png"])

  bucket        = aws_s3_bucket.images.id
  key           = "defaults/${each.value}"
  source        = "${path.module}/assets/default-profile.png"
  source_hash   = filemd5("${path.module}/assets/default-profile.png")
  content_type  = "image/png"
  cache_control = "public, max-age=3600"
}
