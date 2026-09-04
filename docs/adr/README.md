# ADR (Architecture Decision Record)

되돌리기 비싼 결정만 남긴다. 국소·단기 결정이나 어댑터로 격리 가능한 것은 적지 않는다.

**불변이다.** 결정이 바뀌면 이 문서를 고치는 게 아니라 새 번호로 대체하고
이전 것은 `Superseded` 로 표시한다.

각 문서는 네 가지를 담는다: 맥락 / 결정 / 결과(트레이드오프) / **검토한 대안과 기각 사유**.
기각 사유가 곧 "왜 X를 안 썼나"에 대한 방어 근거다.

| # | 제목 | 상태 |
|---|---|---|
| [0001](0001-skeleton-plus-one-reference.md) | 골격 + 참조 구현 1개 | Accepted |
| [0002](0002-openfeign-querydsl.md) | QueryDSL 은 OpenFeign 포크를 쓴다 | Accepted |
| [0003](0003-localdatetime-over-instant.md) | 시간은 LocalDateTime 으로 다루고 Clock 을 초 단위로 끊는다 | Accepted |
| [0004](0004-spring-data-paging-types.md) | 페이징은 Spring Data 타입을 그대로 쓰고 응답 경계에서만 변환한다 | Accepted |
| [0006](0006-auth-hardening.md) | 인증은 흔한 결함을 미리 막는 형태로 구현한다 | Accepted |
| [0007](0007-scalar-manual-registration.md) | Scalar 는 자동설정 대신 직접 등록한다 | Accepted |
| [0008](0008-domain-entity-separation.md) | 도메인 객체와 JPA 엔티티를 분리한다 | Accepted |
| [0009](0009-log-persistence.md) | 로그를 파일로 남기고 도커 볼륨에 영속화한다 | Accepted |
| [0011](0011-llms-txt-runtime-rendering.md) | API 문서를 LLM 친화 마크다운으로 런타임 렌더링한다 | Accepted |
| [0012](0012-develop-infra-single-ec2.md) | develop 인프라는 단일 EC2 + docker-compose 로 간다 | Accepted |
| [0013](0013-oidc-and-secrets-manager.md) | 배포 자격증명은 OIDC 로, 런타임 비밀은 Secrets Manager 로 가른다 | Accepted |
| [0014](0014-rename-to-pickple.md) | 이름을 `pickple` 로 통일한다 | Accepted |
| [0015](0015-native-sign-in-with-apple.md) | Apple은 네이티브 credential을 서버에서 검증하고 서비스 JWT를 발급한다 | Accepted |
| [0016](0016-refresh-token-rotation-cas.md) | refresh token 회전은 CAS로 직렬화하고 현재 token을 보존한다 | Accepted |
| [0017](0017-compose-secret-environment-allowlist.md) | Compose는 `.env`를 보간하고 명시한 비밀만 컨테이너에 전달한다 | Accepted |
| [0018](0018-onepick-as-behavior.md) | 원픽을 행위로 모델링한다 | Accepted (유일성 범위는 0020 이 갱신) |
| [0019](0019-policy-belongs-above-infrastructure.md) | 정책 판단을 인프라에서 걷어낸다 | Accepted |
| [0020](0020-onepick-uniqueness-scope.md) | 원픽의 유일성 범위는 게시글이다 | Accepted |
| [0021](0021-s3-image-object-storage.md) | 이미지 객체는 S3에 저장한다 | Accepted |
| [0022](0022-route53-and-caddy-tls.md) | 도메인은 Route53 이 관리하고 TLS 는 Caddy 가 종단한다 | Accepted |
| [0023](0023-external-db-ssh-access.md) | MySQL 과 SSH 를 비표준 포트로 외부에 연다 | Accepted |
| [0024](0024-local-run-environment.md) | 로컬 실행은 `.env` 임포트로, compose 는 local 과 ec2 둘만 둔다 | Accepted |
| [0025](0025-single-log-file.md) | 로그를 레벨로 나누지 않고 한 파일에 남긴다 | Accepted |
| [0026](0026-env-example-as-secret-schema-source.md) | 비밀 스키마의 정본은 `.env.example` 이고 키 누락은 기동 시 잡는다 | Accepted |
| [0027](0027-image-public-access-via-cloudfront.md) | 이미지 공개 접근은 CloudFront + OAC 로 제공한다 | Accepted |
| [0028](0028-author-ranking-precompute.md) | 작성자 랭킹은 배치로 사전 계산하고 목록은 그 값을 읽는다 | Accepted |
| [0029](0029-drop-api-path-prefix.md) | API 경로에서 `/api` prefix 를 걷어낸다 | ~~Superseded~~ → 0033 |
| [0030](0030-grade-derives-from-ledger-and-only-rises.md) | 등급은 원장에서 판정하고, 도달한 등급만 저장한다 | Accepted |
| [0031](0031-badge-daily-activity-aggregate.md) | 뱃지 판정은 날짜별 집계 테이블에서 하고, 뱃지 이름은 데이터로 둔다 | Accepted |
| [0032](0032-ranking-read-path.md) | 랭킹 조회는 사전 계산된 `ranking` 을 인덱스로 읽는다 | Accepted |
| [0033](0033-drop-api-prefix-implemented.md) | `/api` prefix 를 걷어내고, 문서 노출은 제외 목록으로 가른다 | Accepted |
