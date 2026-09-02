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
| [0022](0022-route53-and-caddy-tls.md) | 도메인은 Route53 이 관리하고 TLS 는 Caddy 가 종단한다 | Accepted |
