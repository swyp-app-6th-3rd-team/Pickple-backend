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
| [0005](0005-sakila-schema-modifications.md) | Sakila 원본 스키마를 손봐서 마이그레이션한다 | Accepted |
| [0006](0006-auth-hardening.md) | 인증은 흔한 결함을 미리 막는 형태로 구현한다 | Accepted |
| [0007](0007-scalar-manual-registration.md) | Scalar 는 자동설정 대신 직접 등록한다 | Accepted |
| [0008](0008-domain-entity-separation.md) | 도메인 객체와 JPA 엔티티를 분리한다 | Accepted |
| [0009](0009-log-persistence.md) | 로그를 파일로 남기고 도커 볼륨에 영속화한다 | Accepted |
| [0010](0010-observability-opentelemetry.md) | 관측성은 OpenTelemetry + Grafana 스택으로 한다 | Accepted |
| [0011](0011-llms-txt-runtime-rendering.md) | API 문서를 LLM 친화 마크다운으로 런타임 렌더링한다 | Accepted |
| [0012](0012-develop-infra-single-ec2.md) | develop 인프라는 단일 EC2 + docker-compose 로 간다 | Accepted |
| [0013](0013-oidc-and-secrets-manager.md) | 배포 자격증명은 OIDC 로, 런타임 비밀은 Secrets Manager 로 가른다 | Accepted |
