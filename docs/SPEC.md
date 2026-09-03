# SPEC

무엇을 만드는가 — API · 스키마 · 처리 규칙. **계속 갱신한다.**
결정이 바뀌면 항목을 고치고 맨 아래 변경 이력에 남긴다.

---

## 1. 기술 스택

| 항목 | 버전 | 비고 |
|---|---|---|
| JDK | 25 (Amazon Corretto) | Gradle toolchain 으로 고정 |
| Spring Boot | 4.1.0 | Hibernate 7.4.5 · MySQL Connector/J 9.7.0 관리 |
| Spring Security | 7.1.0 | `PathPatternRequestMatcher` (Ant/Mvc matcher 제거됨) |
| Jackson | 3.1.4 (`tools.jackson`) | groupId 가 `com.fasterxml` 이 아니다 |
| QueryDSL | 7.5 (`io.github.openfeign.querydsl`) | 본가 아님. classifier `:jpa` |
| MySQL | 8.4 LTS | `utf8mb4_0900_ai_ci`, `lower_case_table_names=0` |
| Flyway | 12.4.0 + `spring-boot-flyway` | 자동설정 모듈이 별도다 |
| springdoc | 3.1.0 | 2.x 는 Boot 4 비호환 |
| Testcontainers | 1.21.3 | `testcontainers-bom` 으로 버전 관리 |
| ArchUnit | 1.4.1 | |

---

## 2. 패키지 구조

비즈니스 축으로 먼저 자르고, 그 안에서 계층을 나눈다.

```
app/pickple/
├── common/          ApiResponse · ResponseCode · PageResponse · ScrollResponse
│                    CursorCodec · CorrelationIdFilter
├── config/          ClockConfig · QuerydslConfig · ScalarConfig
│                    SecurityConfig · AuthProperties · AppleProperties
│                    FileStorageProperties · S3FileStorageConfig
│                    ※ 설정은 여기 하나로 모은다. 도메인별 config 하위 패키지는
│                      ArchitectureTest 가 막는다(#63)
├── docs/            LlmsTxtController · OpenApiMarkdownRenderer · DocsConfig
├── error/           ApiException · GlobalExceptionHandler
│
└── auth/            OAuth2 + Apple native login + JWT
    ├── domain/      User · Role · SocialProvider · SocialIdentity · *Store
    ├── service/     AuthService · JwtService · AccountWithdrawal*Service
    ├── infra/       UserEntity · *TokenEntity · Jpa*Store
    ├── oauth/       OAuth2UserInfo(+3 어댑터) · CustomOAuth2UserService
    │                OAuth2SuccessHandler · OAuth2FailureHandler
    │                HttpCookieOAuth2AuthorizationRequestRepository
    ├── security/    JwtAuthenticationFilter · @CurrentUser
    │                RestAuthenticationEntryPoint · RestAccessDeniedHandler
    ├── apple/       client secret · code 교환/revoke · ID token 검증 · provider token 암호화
    └── controller/  AuthController
```

> 위 트리는 `auth` 만 펼친 것이다. 같은 형태(`domain`·`service`·`infra`·`controller`)로
> `item`(이미지 업로드·컨테이너) · `post` · `vote` · `comment` · `point` 가 있다.

---

## 3. API

모든 응답은 `ApiResponse<T>` 로 감싼다.

```json
{ "code": "OK", "message": "정상 처리되었습니다.", "returnObject": { } }
```

### 3.1 인증

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/oauth2/authorization/{google\|kakao\|naver}` | — | 소셜 로그인 시작 |
| GET | `/login/oauth2/code/{provider}` | — | 콜백 (Spring 이 처리) |
| POST | `/api/auth/apple` | — | iOS Apple credential 검증 + 서비스 JWT 발급 |
| GET | `/api/auth/me` | 필요 | 내 정보 |
| POST | `/api/auth/refresh` | 쿠키 | 토큰 재발급 (회전) |
| POST | `/api/auth/mobile/refresh` | 본문의 refresh token | 모바일 토큰 재발급 (회전) |
| POST | `/api/auth/logout` | 선택 | 리프레시 폐기 + 쿠키 만료 |
| DELETE | `/api/auth/me` | 필요 | provider 연결 해제 + 회원 탈퇴. Apple token 누락 시 수동 해제 코드 반환 |
| GET | `/api/users/nickname/availability?value=` | — | 닉네임 사용 가능 여부. 형식 위반은 400 |
| GET | `/api/users/me` | 필요 | 내 프로필 (닉네임·프로필 이미지) |
| POST | `/api/users/profile` | 필요 | 프로필 등록. 이미지 생략 시 랜덤 기본 프로필 |
| PATCH | `/api/users/profile` | 필요 | 프로필 수정. 이미지 생략 시 쓰던 이미지 유지 |

**토큰 전달 규약**
- 웹 액세스 토큰 — 로그인 성공 시 리다이렉트 **쿼리파라미터**, 이후 `Authorization: Bearer`
- 웹 리프레시 토큰 — **HttpOnly 쿠키**. URL이나 본문에 담지 않는다
- iOS 토큰 — HTTPS JSON으로 access/refresh를 받고 Keychain에 저장한다. URL·로그에 담지 않는다
- iOS nonce — 로그인마다 안전한 새 `rawNonce`를 만든다. Apple 요청에는
  `lowercase hex SHA-256(rawNonce)`를 넣고 `/api/auth/apple`에는 원문 `rawNonce`를 보낸다
- 백엔드는 nonce를 발급·저장하지 않는다. 재전송 방어는 Apple authorization code의 일회성 교환에 의존한다

### 3.2 문서

| Path | 설명 |
|---|---|
| `/swagger-ui.html` | Swagger UI |
| `/scalar` | Scalar (직접 등록 — [ADR-0007](adr/0007-scalar-manual-registration.md)) |
| `/v3/api-docs` | OpenAPI 스펙 |
| `/llms.txt` · `/llms.md` | LLM 프롬프트용 마크다운 — 같은 스펙을 런타임 렌더 ([ADR-0011](adr/0011-llms-txt-runtime-rendering.md)) |

### 3.3 게시글

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/posts?category=&sort=&cursor=&size=` | 선택 (게스트 허용) | 게시글 목록 |

- `category` 는 없으면 **전체**다. `sort` 는 `LATEST`(기본) · `POPULAR` 둘이며,
  **모르는 값은 400 이 아니라 기본값으로 되돌린다** — 진입 화면이 오타 하나로 비지 않게.
- `size` 는 기본 10, 상한 50. 무한 스크롤 한 조각이다.
- **인기순 = 투표 인원 + 댓글 인원**(R-24·R-25). 건수가 아니다. 두 카운터의 합인
  `post.popularity_score` 생성 컬럼이 정렬 키라 조회 시점에 집계하지 않는다.
- 정렬·필터는 전부 SQL 이 한다. **조각을 인덱스로 확정한 뒤 작성자·대표 사진을 붙인다** —
  조인을 먼저 걸면 MySQL 이 정렬 전에 조인 결과 전체를 만들어 인덱스가 무의미해진다
  (100k 게시글·200k 회원 실측: 454ms → 0.28ms).
- 응답은 세 유형이 한 스키마를 공유하고 해당 없는 필드를 비운다.
  `voteCount` 는 일반 게시글에서 `null`, `thumbnailUrl` 은 상품이 없는 유형에서 `null`.
  대표 사진은 찬반=가장 처음 등록한 1장, A/B=A 상품(display_order 1)의 사진이다.
- **게시글이 0건이면 빈 배열이다.** "아직 없는 게시글" 안내는 화면의 빈 상태이므로
  서버가 존재하지 않는 더미 게시글을 지어내지 않는다(지어내면 탭했을 때 갈 곳이 없다).
- 인기순 무한 스크롤은 **최선 노력**이다. 스크롤 도중 점수가 올라 커서 위로 이동한 글은
  그 회차에서 빠질 수 있다. 의도된 선택이며 근거는 ERD 초안 8.4.
- **작성자 랭킹은 아직 응답에 없다.** 전역 순위라 조회 시점에 구하면 회원 전체를
  정렬해야 한다(실측 154ms/조각). 사전 계산 컬럼이 생기면 추가한다.

### 3.4 댓글

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/posts/{postId}/comments` | 선택 (게스트 허용) | 활성 댓글 전체 목록 |
| POST | `/api/posts/{postId}/comments` | 필요 | 댓글 작성 |
| PATCH | `/api/comments/{id}` | 필요 (작성자) | 댓글 내용 수정 |
| DELETE | `/api/comments/{id}` | 필요 (작성자) | 댓글 소프트 삭제 |

- 목록은 `(created_at, id)` 오름차순이며 현재 계약에는 페이징이 없다.
- 댓글·작성자 프로필·원픽 수를 단일 조회로 읽는다. `nickname`이 아직 설정되지 않은
  기존 사용자는 소셜 `name`을 대체 표시값으로 사용한다.
- 각 항목은 원본 `createdAt`과 화면용 `createdAgo`, 현재 요청자의 댓글인지 나타내는
  `mine`을 함께 제공한다. 게스트 요청의 `mine`은 항상 `false`다.
- 차단·신고와 게스트 로그인 유도 모달은 서버 댓글 CRUD가 아니라 후속 기능/UI 범위다.

### 3.5 투표

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/posts/{postId}/votes` | 필요 | 투표 참여·선택 변경 |

- 본문은 `{ "optionId": 1 }` 이다. 응답은 갱신된 **선택지별 득표 수와 득표율**이라
  투표 직후 화면이 다시 조회하지 않고 게이지로 전환할 수 있다(기능명세 §2.2).
  따로 조회하게 만들면 그 사이 들어온 다른 표까지 섞여 내 표의 결과가 아닌 값을 보여준다.
- **게스트는 투표할 수 없다(R-11).** 별도 매처 없이 `anyRequest().authenticated()` 로 401 이다.
- **한 게시글에 한 사람은 한 표다(R-09).** 인원은 선택지가 아니라 게시글 단위로 센다.
  이미 투표한 사람이 다시 보내면 새 행을 만들지 않고 선택지만 바꾼다 —
  `UNIQUE(post_id, user_id)` 가 막기도 하지만 의미상 "다시 투표"가 아니라 "선택 변경"이다.
- **재투표는 표만 옮긴다(R-22).** 이전 선택지 −1, 새 선택지 +1, 투표 인원과
  누적 투표 횟수는 그대로다. 인원을 올리면 등급·뱃지가 부풀어 잘못 나간다.
- **다른 게시글의 선택지로는 투표할 수 없다(R-10).** 복합 FK `(post_option_id, post_id)` 가
  최종 방어선이지만 거기까지 가면 flush 시점 무결성 위반이라 500 이 되므로,
  서비스가 먼저 걸러 `INVALID_REQUEST`(400) 로 답한다. 투표가 없는 유형(일반)도 400 이다.
- 카운터는 전용 원자 UPDATE 로만 증감한다. **집계를 먼저 올리고 투표 행을 나중에 넣는다** —
  `vote` INSERT 가 FK 검사로 `post` 에 공유 락을 잡은 뒤 `UPDATE post` 가 배타 락을
  요구하면 락 승격이 되어 동시 요청이 교착에 빠진다(16명 동시 투표에서 재현).
  선택 변경의 두 선택지도 id 순으로 잠가 반대 방향 변경끼리 고리를 만들지 않는다.
- `percentage` 는 정수 퍼센트다. 반올림 때문에 두 값의 합이 100 이 아닐 수 있으며
  화면이 게이지 폭으로만 쓰므로 억지로 맞추지 않는다. 아무도 투표하지 않았으면 0 이다.
- `label` 은 찬반 선택지만 값이 있다. A/B 는 상품이 이름을 대신하므로 `null` 이다.

---

### 3.6 이미지

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/images` | 필요 | 이미지 업로드 후 부착용 `itemContainerId` 반환 |

- `multipart/form-data` 로 `images`(파일, 복수)와 `attachType`(폼 필드)을 받는다.
- **`attachType` 은 필수이며 기본값이 없다.** `PRODUCT`(상품 사진) 또는 `COMMENT`(댓글 사진).
  기본값을 두면 용도를 넘기지 않은 호출이 조용히 상품으로 분류되므로 두지 않는다.
  누락하거나 알 수 없는 값이면 `INVALID_REQUEST`(400)다.
- JPEG·PNG 만 허용하고 Content-Type 과 실제 파일 시그니처가 다르면 `INVALID_IMAGE`(400),
  파일당 5MB 를 넘으면 `IMAGE_TOO_LARGE`(413)다.
- 객체 키 접두어는 용도에 따라 갈린다 — `product-images/{userId}/{uuid}.{ext}`,
  `comment-images/{userId}/{uuid}.{ext}`. 접두어는 `AttachType` 상수가 소유한다.
- 응답의 `accessUrl` 은 CloudFront 도메인 기준이며 `item_resource` 에 영속된다(ADR-0021, ADR-0027).
  만료되지 않아야 하므로 presigned URL 을 쓰지 않는다.
- S3 와 DB 사이에 분산 트랜잭션이 없다. 업로드 후 저장이 실패하면 이번 요청에서 만든 객체를
  best-effort 로 보상 삭제한다.

## 4. 스키마

### 4.1 인증 3개

```sql
users(id, provider, provider_id, email, name, role, state, created_at, updated_at,
      UNIQUE KEY uk_users_provider (provider, provider_id))

user_refresh_token(id, user_id, token_hash CHAR(64), expires_at, created_at,
      UNIQUE KEY uk_refresh_user (user_id),
      UNIQUE KEY uk_refresh_token_hash (token_hash),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)

apple_provider_token(user_id, encryption_format_version, encrypted_refresh_token,
      encryption_iv, encryption_key_id, created_at, updated_at,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)
```

### 4.2 마이그레이션

| 파일 | location | 로드 시점 |
|---|---|---|
| `V1__auth_tables.sql` | `db/migration` | 항상 |
| `V3__pickple_domain.sql` | `db/migration` | 항상 |
| `V4__apple_provider_tokens.sql` | `db/migration` | 항상 |
| `V5__active_nickname_follows_state.sql` | `db/migration` | 항상 |

---

## 5. 처리 규칙

### 5.1 시간

- 모든 시간 필드는 `LocalDateTime`. `Instant` 를 쓰지 않는다.
- `Clock` 빈은 **초 단위로 끊는다** — DB 의 `datetime(0)` 과 정밀도를 맞춘다.
  나노초를 그대로 두면 keyset 스크롤에서 행이 누락된다(변경 이력 참조).
- 도메인·Store 는 `LocalDateTime.now()` 를 직접 부르지 않고 `Clock` 을 주입받는다.
- 근거: [ADR-0003](adr/0003-localdatetime-over-instant.md)

### 5.2 페이징

- 계층 내부는 Spring Data 타입 직접 사용 — `Pageable`/`Page`/`ScrollPosition`/`Window`
- **응답 경계에서만** `PageResponse`/`ScrollResponse` 로 변환
- keyset 정렬 키는 반드시 `(정렬컬럼, PK)` — 동률 구간에서 누락·중복을 막는다
- 커서는 Base64URL(JSON). **JSON 왕복 시 타입이 소실되므로** 복원 측이 문자열도 받아야 한다
- 정렬 필드는 허용 목록 방식. 모르는 필드는 무시한다
- 근거: [ADR-0004](adr/0004-spring-data-paging-types.md)

### 5.3 도메인

- 도메인은 JPA·Spring·Lombok·검증 애노테이션에 의존하지 않는다
- 생성자가 불변식을 강제. 복원은 `restore(...)` 정적 팩터리로 분리
- 변환 방향은 **엔티티 → 도메인** (`toDomain()`/`from()`)
- Store 인터페이스는 domain, 구현은 infra
- Spring Data 리포지토리는 **package-private**
- 근거: [ADR-0008](adr/0008-domain-entity-separation.md)

### 5.4 인증

- 액세스 토큰은 클레임만으로 인가 판단. **요청마다 DB 를 조회하지 않는다**
- 리프레시 토큰은 SHA-256 해시로 저장. 사용자당 한 행, 제출 해시를 조건으로 CAS 회전
- 동시 회전의 패자나 옛 토큰은 401로 거부하되 현재 저장된 승자 token은 삭제하지 않는다
- `typ` 클레임으로 액세스/리프레시를 구분해 혼용을 막는다
- 리다이렉트 URI 는 호스트 화이트리스트 검증 (오픈 리다이렉트 방지)
- Apple 사용자는 이메일이 아닌 `(APPLE, ID token의 sub)`로 식별한다
- Apple client secret은 `.p8`로 ES256 서명하고, Apple ID token은 JWKS의 RS256 서명을 검증한다
- Apple provider refresh token은 별도 AES-256-GCM keyring으로 암호화해 저장한다. 랜덤 12-byte IV와
  사용자 ID·키 ID를 묶은 AAD를 사용하며 DB에는 평문을 저장하지 않는다
- Apple code 교환 뒤 ID token 불일치나 로컬 로그인 완료가 실패하면, 새로 발급된 provider refresh token을
  보상 revoke해 로컬에서 소유하지 않는 Apple 세션이 남지 않게 한다
- Apple 회원 탈퇴는 provider refresh token으로 `/auth/revoke`에 성공한 뒤 로컬 계정을 비활성화하고
  서비스/provider refresh token을 같은 로컬 트랜잭션에서 삭제한다. Apple 일시 장애 시 503으로 재시도한다.
  token이 없는 기존 계정은 로컬 탈퇴 후 `APPLE_MANUAL_REVOCATION_REQUIRED`로 수동 연결 해제를 안내한다
- 로그인 보상 revoke 실패는 counter와 `correlationId` WARN으로 관측한다. 자동 복구 outbox는 후속 범위다
- 회원 활성 여부의 정본은 `users.state`다. 향후 `deleted_at`은 탈퇴 시각 감사값으로 같은 트랜잭션에서 기록한다
- authorization code·identity token·`.p8`·access/refresh token은 로그에 남기지 않는다
- 근거: [ADR-0006](adr/0006-auth-hardening.md), [ADR-0015](adr/0015-native-sign-in-with-apple.md),
  [ADR-0016](adr/0016-refresh-token-rotation-cas.md)
- 적용·키 교체·iOS 계약: [Apple 로그인 Runbook](apple-sign-in-runbook.md)

### 5.5 로깅

- 레벨별 디렉터리로 분리 — `${LOG_DIR}/{error,warn,info}/`
  - `error`·`warn` 은 `LevelFilter` 로 **그 레벨만**
  - `info` 는 `ThresholdFilter` 로 INFO 이상 전부 (전체 흐름 추적용)
- 롤링: 일 단위 + 파일당 100MB, 30일 보관, 전체 3GB 상한
- 패턴에 `%X{correlationId}` 포함 — `CorrelationIdFilter` 가 MDC 를 채운다
- `AsyncAppender` + `discardingThreshold=0` — 큐가 차도 로그를 버리지 않는다
- **prod 에서도 콘솔을 유지한다** — 기동 실패는 파일 appender 준비 전에 일어나므로
  콘솔을 끄면 `docker logs` 가 비어 원인을 못 본다
- 컨테이너에서는 `/app/logs` 를 named volume 에 마운트해 영속화
- 근거: [ADR-0009](adr/0009-log-persistence.md)

| 프로파일 | 콘솔 | 파일 |
|---|---|---|
| `test` | ✅ (WARN 이상) | ✗ |
| `local` | ✅ | ✅ |
| `prod` | ✅ | ✅ |

### 5.6 에러 응답

| 상황 | 코드 | HTTP |
|---|---|---|
| 성공 | `OK` / `CREATED` | 200 / 201 |
| 요청 값 오류 · 도메인 불변식 위반 | `INVALID_REQUEST` | 400 |
| 미인증 · 토큰 오류 | `UNAUTHORIZED` / `INVALID_TOKEN` / `EXPIRED_TOKEN` | 401 |
| 권한 없음 | `FORBIDDEN` | 403 |
| 대상 없음 | `NOT_FOUND` | 404 |
| Apple 키 미설정·Apple 서버 일시 장애 | `APPLE_LOGIN_UNAVAILABLE` | 503 |
| Apple 회원 탈퇴 연결 해제 일시 장애 | `APPLE_ACCOUNT_REVOCATION_UNAVAILABLE` | 503 |
| Apple token 없는 기존 계정의 로컬 탈퇴 완료 | `APPLE_MANUAL_REVOCATION_REQUIRED` | 200 |
| 그 외 | `SYSTEM_ERROR` | 500 |

---

## 6. 아키텍처 규칙

`ArchitectureTest` 17개. 규칙을 추가할 때는 **일부러 위반하는 코드를 넣어
해당 규칙만 실패하는지 확인한 뒤** 커밋한다 — 통과만으로는 그 규칙이 무언가를
지킨다는 증거가 되지 않는다([ADR-0008](adr/0008-domain-entity-separation.md)).

| 그룹 | 규칙 수 | 내용 |
|---|---|---|
| 도메인 순수성 | 6 | JPA·검증·Lombok·infra·web·부동소수점 의존 금지 |
| 계층 경계 | 8 | Store 인터페이스는 domain / 구현은 infra / Entity 는 infra / 서비스·컨트롤러가 리포지토리 직접 의존 금지 / infra→service 금지 |
| API 응답 계약 | 2 | 컨트롤러가 `Page`·`Window` 를 그대로 반환 금지 |
| DI | 1 | `@Autowired` 필드 주입 금지 |

---

## 7. 테스트

| 계층 | 컨테이너 |
|---|---|
| 단위 (도메인·어댑터·서비스) | 불필요 |
| 아키텍처 (ArchUnit) | 불필요 |
| 통합 (인프라·API) | 필요 — Testcontainers MySQL |

---

## 변경 이력

| 날짜 | 변경 | 계기 |
|---|---|---|
| 2026-09-03 | 이미지 저장소 추상화를 `File*` 계열로 개명, 설정 접두어 `app.image` → `app.file` | 이미지 외 파일도 담을 수 있는 이름으로(#63). 환경변수도 `FILE_*` 로 |
| 2026-09-03 | 도메인별 `config` 하위 패키지를 루트 `config` 로 통합 | 설정이 흩어져 부트스트랩 전체를 한눈에 못 봄(#63). ArchitectureTest 로 재발 차단 |
| 2026-09-03 | 탈퇴 정본을 `users.state` 로 통일하고 `deleted_at` 제거 | 생성 컬럼이 `deleted_at` 을 보는데 코드는 `state` 만 써서 탈퇴해도 닉네임이 잠겼다(#16) |
| 2026-09-03 | `POST /api/images` 에 `attachType` 필수 파라미터 추가 | 상품 전용에서 범용으로 전환(#62). 기존 호출자는 400 |
| 2026-08-15 | `Instant` → `LocalDateTime` | Sakila 컬럼이 `DATETIME`(타임존 없음) |
| 2026-08-15 | `PageQuery`/`PageResult` 자체 래퍼 제거 → Spring Data 타입 직접 사용 | 무한 스크롤에 `Window` 가 필요 |
| 2026-08-15 | `film.length` `Integer` → `Short` | `ddl-auto=validate` 가 `smallint unsigned` 불일치 검출 |
| 2026-08-15 | `film.rental_duration` `Short` → `Byte` | 같은 경로로 `tinyint unsigned` 검출 |
| 2026-08-15 | springdoc 2.8.13 → 3.1.0 | 2.x 가 Spring Data 4 와 비호환 (`NoClassDefFoundError`) |
| 2026-08-15 | `spring-boot-flyway` 모듈 추가 | Boot 4 는 자동설정이 모듈별로 분리됨. 마이그레이션이 조용히 실행되지 않았음 |
| 2026-08-15 | `Clock` 을 초 단위로 끊음 | keyset 스크롤에서 행 누락. `datetime(0)` vs 나노초 정밀도 불일치 |
| 2026-08-15 | 아키텍처 규칙 "컨트롤러는 엔티티를 노출하지 않는다" 에 패키지 조건 추가 | 이름만으로 필터링해 Spring `ResponseEntity` 를 오검출 |
| 2026-08-22 | `/llms.txt` · `/llms.md` 추가 | FE 가 API 계약을 LLM 프롬프트에 붙여넣을 표면이 없었음 |
| 2026-08-22 | `OpenAPI` 빈으로 스펙 제목 지정 | 기본값 "OpenAPI definition" 이 그대로 노출되고 있었음 |
| 2026-08-29 | Apple 네이티브 로그인과 모바일 JWT 회전 API 추가 | iOS Sign in with Apple 지원 |
| 2026-08-30 | Apple provider RT 암호화 저장과 회원 탈퇴 시 revoke 추가 | 계정 삭제 시 Apple 연결 해제 필요 |
| 2026-08-30 | refresh CAS·Apple 수동 해제 응답·보상 실패 관측 추가 | PR #12 리뷰 반영 |
| 2026-09-03 | 댓글 CRUD·게스트 목록·원픽 수 조회 계약 추가 | Issue #23 |
| 2026-09-03 | 게시글 목록 조회 계약 추가(카테고리·정렬·커서). 작성자 랭킹은 보류 | Issue #19. 랭킹은 요청당 154ms 라 사전 계산 과제로 분리 |
| 2026-09-03 | 투표 참여 계약 추가. 선택지별 집계 카운터와 투표 시 락 순서 규정 | Issue #21. 동시 투표에서 `post` 행 락 승격으로 교착이 재현돼 순서를 못박음 |
