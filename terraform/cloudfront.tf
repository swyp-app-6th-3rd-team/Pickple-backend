# 이미지 배포용 CDN (ADR-0027).
#
# 버킷을 공개하지 않고 CloudFront 만 읽게 한다. 앱은 업로드 응답의 access_url 을
# item_resource 에 **영속**하므로(ADR-0021), URL 은 만료되지 않아야 한다.
# presigned GET 이 기각된 이유가 이것이다.
#
# 업로드는 이 경로를 거치지 않는다 — Spring 앱이 POST /api/images 로 받아
# 인스턴스 프로파일 자격증명으로 S3 에 직접 쓴다. 따라서 CloudFront 는 읽기 전용이고
# 버킷 정책에도 s3:GetObject 만 준다(s3.tf).

resource "aws_cloudfront_origin_access_control" "images" {
  name                              = "${local.name_prefix}-images"
  description                       = "Pickple ${var.env} image bucket origin access"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# 관리형 캐시 정책을 이름으로 조회한다.
# 매직 UUID(658327ea-...)를 박으면 리뷰에서 무엇인지 검증할 수 없다.
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

resource "aws_cloudfront_distribution" "images" {
  enabled         = true
  is_ipv6_enabled = true
  comment         = "${local.name_prefix} images"

  # 커스텀 도메인을 쓰지 않는다. 붙이려면 **us-east-1** 의 ACM 인증서가 필요한데
  # (CloudFront 는 버지니아 리전 인증서만 받는다) 6주 MVP 에 그 값어치가 없다.
  # 나중에 붙일 때는 IMAGE_PUBLIC_BASE_URL 만 바꾸면 되므로 전환 비용이 낮다.
  price_class = "PriceClass_200" # 한국을 포함하는 최소 등급

  origin {
    origin_id                = "images-s3"
    domain_name              = aws_s3_bucket.images.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.images.id
  }

  default_cache_behavior {
    target_origin_id = "images-s3"

    # 이미지 조회만 한다. 쓰기 메서드를 열면 오리진으로 새는 경로가 생긴다.
    allowed_methods = ["GET", "HEAD"]
    cached_methods  = ["GET", "HEAD"]

    viewer_protocol_policy = "redirect-to-https"
    compress               = true

    # 앱이 객체에 cache-control: public, max-age=31536000, immutable 을 박으므로
    # (S3ImageObjectStorage.put) 오리진 헤더를 존중하는 관리형 정책과 정합적이다.
    cache_policy_id = data.aws_cloudfront_cache_policy.caching_optimized.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = { Name = "${local.name_prefix}-images-cdn" }
}
