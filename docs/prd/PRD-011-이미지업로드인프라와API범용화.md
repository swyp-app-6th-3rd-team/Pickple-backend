# PRD-011 — 이미지 업로드 인프라 연결과 API 범용화

**이슈**: #61 · #62 · #63 · **ADR**: [0027](../adr/0027-image-public-access-via-cloudfront.md) · **작성**: 2026-09-03

## 무엇을 왜

`POST /api/images` 는 앱 코드가 완성돼 있는데 배포 환경에서 동작하지 않는다.
원인을 측정한 결과 **결손은 앱이 아니라 인프라와 설정 체인 전체**에 있었다.

`S3ImageStorageConfig` 는 `credentialsProvider` 를 **의도적으로 지정하지 않아**
AWS SDK v2 의 `DefaultCredentialsProvider` 체인(env → 프로파일 → EC2 IMDS)이 걸린다.
이건 결함이 아니라 정답이며, 코드에 키를 넣으면 오히려 그 체인이 죽는다.
EC2 인스턴스 프로파일도 `terraform/ec2.tf` 에 이미 부착돼 있어 전달 경로는 살아 있었다.

| # | 위치 | 측정 결과 |
|---|---|---|
| 1 | `terraform/*.tf` | `aws_s3_bucket` 리소스 0건 — 버킷 자체가 없음 |
| 2 | `terraform/iam.tf` `app_runtime` | `s3:` 액션 0건 — 역할에 S3 권한 없음 |
| 3 | `.env.example` (키 14개 전수) | `IMAGE_S3_BUCKET`·`IMAGE_PUBLIC_BASE_URL` 부재 |
| 4 | `docker/docker-compose-ec2.yml` | 위 키가 컨테이너에 미전달 |

**실행 순서상 첫 실패는 자격증명이 아니다.** `S3ImageObjectStorage.configuredBucket()` 이
버킷값 `not-configured` 를 먼저 감지해 예외를 던지므로 자격증명 오류에는 도달조차 못 한다.
이 순서를 잘못 잡으면 엉뚱한 곳을 고치게 된다.

여기에 두 가지가 얹힌다. 이미지 API 는 상품 전용이 아닌데 "상품" 종속이
세 층(OAS 표시·도메인 하드코딩·S3 키 접두어)에 박혀 있고, 설정 클래스가
루트와 도메인 패키지에 흩어져 일관성이 없다.

## 범위

**포함**
- (#61) `terraform/s3.tf`·`terraform/cloudfront.tf` 신규 — 버킷, OAC, 배포, 버킷 정책
- (#61) `terraform/iam.tf` — `app_runtime` 에 `s3:PutObject`·`s3:DeleteObject` 추가
- (#61) `.env.example`·`fetch-secrets.sh.tftpl`·`ec2.tf`·`outputs.tf`·`docker-compose-ec2.yml` — 설정 배선
- (#61) ADR-0027
- (#62) `AttachType` 에 `keyPrefix` 필드 추가, `ImageUploadService`·`ImageUploadController` 파라미터화
- (#63) `auth/config`·`item/config` 5개 클래스를 루트 `config` 로 이동

**제외**
- S3 고아 객체 정리와 서비스 계층 인프라 의존 분리 (#49 — 별도 이슈)
- CloudFront 커스텀 도메인 — us-east-1 ACM 인증서가 필요해 6주 MVP 범위 밖.
  `public-base-url` 이 어댑터라 나중 전환 비용이 낮다
- 이미지 리사이징·썸네일 파이프라인
- 업로드 presigned URL — ADR-0021 에서 이미 기각

## 완료 판정

측정 가능한 것만 적는다. **빌드 green·`terraform apply` 성공은 대리지표라 포함하지 않는다.**

| # | 판정 | 검증 방법 |
|---|---|---|
| 1 | `terraform output secret_keys` 가 변경 전후 동일 | 변경 전후 출력 `diff` — 0건이어야 기동 FATAL 위험이 없다 |
| 2 | 배포 컨테이너가 버킷 미설정 예외 없이 기동 | 앱 로그에 `이미지 S3 버킷이 설정되지 않았습니다` 0건 |
| 3 | 실제 이미지 업로드가 201 반환 | 인증된 `curl -F` 요청 |
| 4 | 반환 `accessUrl` 이 CloudFront 도메인이고 익명 GET 200 | 자격증명 없이 `curl -I` → `200` + `image/jpeg` |
| 5 | S3 에 객체 실존, `cache-control` 이 앱이 박은 값과 일치 | `aws s3api head-object` 의 `CacheControl` 이 `public, max-age=31536000, immutable` |
| 6 | 보상 삭제 후 객체 부재 | 같은 키로 `head-object` → 404 |
| 7 | **반증**: IAM statement 적용 전 업로드가 AccessDenied 로 실패 | 커밋 3 이전 상태에서 업로드 → 실패 확인. 성공하면 권한 출처가 다른 곳이므로 중단 |
| 8 | 최소 권한 확인 | `aws iam simulate-principal-policy` → `PutObject` allowed, `GetObject`·`ListBucket` implicitDeny |
| 9 | `COMMENT` 업로드가 `comment-images/` 키로 저장 | 신규 단위 테스트 + `aws s3 ls` |
| 10 | `PRODUCT` 키 접두어가 `product-images/` 로 불변 | 기존 테스트 |
| 11 | `app.pickple` 하위에 `config` 패키지가 루트 하나만 존재 | `find src/main -type d -name config` 결과 1줄 |

## 열린 질문

1. **PR B 의 API 파괴적 변경 시점** — `attachType` 이 필수가 되면 기존 호출자는 400 을 받는다.
   클라이언트 연동 전이라는 전제이며, 이미 붙었다면 한시적 기본값을 검토한다.
2. **CloudFront 캐시 무효화 정책** — 객체 키가 UUID 기반이라 같은 키를 덮어쓰는 일이 없어
   실무상 무효화가 필요한 경우는 드물다. 필요해지면 별도로 정한다.
3. **`config` 패키지 통합의 vertical slice 훼손** — 되돌리기가 저렴해 ADR 없이 진행하되,
   ArchUnit 규칙으로 고정할지는 #63 에서 판단한다.

## 변경 이력

- 2026-09-03 — 신규 작성(#61 구현 시점)
