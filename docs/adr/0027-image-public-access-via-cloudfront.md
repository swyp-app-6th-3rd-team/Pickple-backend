# ADR-0027 — 이미지 공개 접근은 CloudFront + OAC 로 제공한다

**상태**: Accepted
**보완**: [ADR-0021](0021-s3-image-object-storage.md) 이 "실제 공개 정책 또는 CDN 연결은 배포 인프라가 별도로 제공해야 한다"고 남긴 미결 항목을 이 문서가 채운다. 이미지 객체를 S3 에 둔다는 결정 자체는 유지된다.

## 맥락

이미지 업로드 경로는 코드가 완성돼 있다. `POST /api/images` 가 파일을 받아 S3 에 올리고,
`item_resource` 에 객체 키와 접근 URL 을 저장한다. 그런데 배포 환경에서 동작하지 않았고,
원인을 측정해 보니 앱이 아니라 인프라가 통째로 비어 있었다.

- `terraform/` 에 `aws_s3_bucket` 리소스가 없다. 버킷 자체가 존재하지 않는다.
- EC2 런타임 역할에 `s3:` 액션이 하나도 없다.
- `.env.example` 에 이미지 관련 키가 없어 배포 환경에 버킷 이름이 전달되지 않는다.

실행 순서상 첫 실패는 자격증명이 아니다. `S3ImageObjectStorage.configuredBucket()` 이
버킷값 `not-configured` 를 먼저 감지해 예외를 던지므로 자격증명 오류에는 도달조차 못 한다.

자격증명 자체는 이미 해결돼 있다. `S3ImageStorageConfig` 는 `credentialsProvider` 를
**의도적으로 지정하지 않아** AWS SDK 의 `DefaultCredentialsProvider` 체인이 걸리고,
EC2 에서는 인스턴스 프로파일로 해소된다([ADR-0013](0013-oidc-and-secrets-manager.md)).
코드에 키를 넣으면 오히려 이 체인이 죽는다.

남은 결정은 **업로드된 이미지를 클라이언트가 어떻게 읽는가**다. 앱은 업로드 응답의
`accessUrl` 을 `item_resource` 에 **영속**하므로, 그 URL 은 만료되지 않아야 한다.
이 제약이 선택지를 크게 좁힌다.

## 결정

**CloudFront 배포를 만들고 OAC(Origin Access Control)로 S3 버킷을 연결한다. 버킷은 공개하지 않는다.**

- 버킷의 퍼블릭 차단 4개(`block_public_acls`·`block_public_policy`·`ignore_public_acls`·
  `restrict_public_buckets`)를 **모두 켠 채로 둔다.**
- 버킷 정책은 `cloudfront.amazonaws.com` 서비스 프린시펄에 `s3:GetObject` 만 허용하고,
  `AWS:SourceArn` 조건으로 우리 배포에 한정한다. 조건이 없으면 아무 CloudFront 배포나
  이 버킷을 오리진으로 삼을 수 있다.
- EC2 런타임 역할에는 `s3:PutObject` 와 `s3:DeleteObject` 만 준다.
  - `s3:GetObject` 는 주지 않는다. `accessUrl()` 의 `utilities().getUrl()` 은 네트워크
    호출이 아니라 URL 문자열을 조립할 뿐이다. 읽기는 CloudFront 가 담당한다.
  - `s3:ListBucket` 도 주지 않는다. 앱에 목록 조회가 없다.
  - `s3:PutObjectAcl` 은 `BucketOwnerEnforced` 가 ACL 자체를 거부하므로 의미가 없다.
- 커스텀 도메인은 붙이지 않는다. CloudFront 기본 도메인을 쓴다.
- 버킷 이름에 계정 ID 를 접미어로 붙인다. S3 버킷 이름은 전역 유일이라
  `pickple-dev-images` 는 남이 선점했을 수 있고, 그러면 apply 가 실패한다.

## 결과

**얻는 것.** 버킷이 직접 노출되지 않는다. 앱이 객체에 박는
`cache-control: public, max-age=31536000, immutable` 이 CDN 캐시와 정합적이라
같은 이미지의 반복 조회가 오리진에 닿지 않는다. HTTPS 가 기본으로 적용된다.

**치르는 비용.**
- CloudFront 배포·전파에 15~20분이 걸린다. `terraform apply` 가 그만큼 매달린다.
- 리소스가 4~5개 늘어 인프라 표면이 커진다.
- 캐시 무효화를 고려해야 한다. 다만 객체 키가 UUID 기반이라 같은 키를 덮어쓰는 일이
  없어 실무상 무효화가 필요한 경우는 드물다.

**제약.** 이 버킷은 공개 이미지 전용이다. 로그·백업·사용자 문서를 같은 버킷에 두면
CloudFront 를 통해 새어 나간다.

**전환 여지.** `app.image.s3.public-base-url` 이 어댑터 역할을 한다. 나중에 커스텀
도메인을 붙이거나 CDN 을 바꿔도 이 값 하나만 바뀐다. 이미 저장된 `access_url` 행은
CloudFront 배포가 살아 있는 한 계속 유효하다.

## 검토한 대안과 기각 사유

| 대안 | 기각 사유 |
|---|---|
| **버킷 정책 공개 읽기** | 가장 단순하고 즉시 동작한다. 그러나 `버킷/*` 전체가 익명 공개라 버킷에 이미지 외에는 무엇도 둘 수 없고, 핫링크와 직접 노출을 막을 수단이 없다. 또한 퍼블릭 차단 4개 중 `block_public_policy` 와 `restrict_public_buckets` 를 내려야 하는데, 후자를 잘못 두면 정책은 적용되지만 익명 읽기가 조용히 거부된다 — terraform 은 초록인데 제품이 깨지는 실패 모드다 |
| **presigned GET URL** | `item_resource` 가 `access_url` 을 **영속**하는데 presigned URL 은 만료된다. 저장된 URL 이 썩어 읽기 경로를 다시 설계해야 하고, 이는 "앱 코드 무변경" 전제와 충돌한다. ADR-0021 이 업로드용 presigned 를 기각한 것과는 별개의 이유다 |
| **EC2 역할에 `s3:GetObject` 부여** | 앱이 객체를 읽지 않으므로 불필요한 권한이다. 최소 권한 원칙에 어긋나고, 나중에 읽기 코드가 생겨도 그때 명시적으로 추가하는 편이 낫다 |
| **커스텀 도메인(`images.pickple.app`)** | CloudFront 는 **us-east-1** 의 ACM 인증서만 받는다. 인증서 발급·검증 단계가 추가되는데 6주 MVP 에서 값어치가 없다. `public-base-url` 이 어댑터라 나중 전환 비용이 낮다 |

## 관련

- [ADR-0021](0021-s3-image-object-storage.md) — 이미지 객체를 S3 에 저장한다(이 결정의 전제)
- [ADR-0013](0013-oidc-and-secrets-manager.md) — 장기 자격증명을 쓰지 않는다
- [ADR-0026](0026-env-example-as-secret-schema-source.md) — 비밀 스키마의 정본은 `.env.example`
  - 이미지 설정 키에는 비밀 마커를 달지 않았다. 달면 `secret_keys` 가 커져
    `sync-secrets.sh` 실행 전까지 EC2 가 기동을 거부한다. 버킷 이름은 비밀이 아니므로
    `SITE_ADDRESS`·`CORS_ALLOWED_ORIGINS` 와 같은 "비밀 아닌 배포 파라미터" 경로로 흘린다
- `terraform/s3.tf` · `terraform/cloudfront.tf` · `terraform/iam.tf`
