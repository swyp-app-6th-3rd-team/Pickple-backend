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
├── docs/            LlmsTextController · OpenApiMarkdownRenderer · DocsConfig
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
> `item`(이미지 업로드·컨테이너) · `post` · `vote` · `comment` · `point` · `grade` 가 있다.

---

## 3. API

모든 응답은 `ApiResponse<T>` 로 감싼다.

```json
{ "code": "OK", "message": "정상 처리되었습니다.", "returnObject": { } }
```

> ### `/api` prefix 는 걷어냈다 (2026-09-04)
>
> 배포 도메인이 이미 `dev-api.pickple.app` 라 경로의 `/api` 는 의미가 중복됐다.
> [ADR-0033](adr/0033-drop-api-prefix-implemented.md) 이 제거를 확정했고(ADR-0029 대체),
> **과도기 브릿지는 두지 않았다** — 프론트 합의에서 불필요로 확인됐다(#91).
>
> 아래 표의 경로가 현재 동작이다. 구 경로 `/...` 는 살아 있지 않다.
>
> 문서 노출은 `springdoc.paths-to-exclude` 로 가른다 — 새 API 경로는 자동으로 실리고,
> **공개하면 안 되는 경로를 만들면 그 목록에 더해야 한다.**

### 3.1 인증

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/oauth2/authorization/{google\|kakao\|naver}` | — | 소셜 로그인 시작 |
| GET | `/login/oauth2/code/{provider}` | — | 콜백 (Spring 이 처리) |
| POST | `/auth/apple` | — | iOS Apple credential 검증 + 서비스 JWT 발급 |
| GET | `/auth/me` | 필요 | 내 정보 |
| POST | `/auth/refresh` | 쿠키 | 토큰 재발급 (회전) |
| POST | `/auth/mobile/refresh` | 본문의 refresh token | 모바일 토큰 재발급 (회전) |
| POST | `/auth/logout` | 선택 | 리프레시 폐기 + 쿠키 만료 |
| DELETE | `/auth/me` | 필요 | provider 연결 해제 + 회원 탈퇴. Apple은 로컬 탈퇴 완료 시 identity를 분리하고, token 누락 시 수동 해제 코드 반환 |
| GET | `/users/nickname/availability?value=` | — | 닉네임 사용 가능 여부. 형식 위반은 400 |
| GET | `/users/me` | 필요 | 내 프로필 (닉네임·프로필 이미지) |
| POST | `/users/profile` | 필요 | 프로필 등록. 이미지 생략 시 랜덤 기본 프로필 |
| PATCH | `/users/profile` | 필요 | 프로필 수정. 이미지 생략 시 쓰던 이미지 유지 |

**토큰 전달 규약**
- 웹 액세스 토큰 — 로그인 성공 시 리다이렉트 **쿼리파라미터**, 이후 `Authorization: Bearer`
- 웹 리프레시 토큰 — **HttpOnly 쿠키**. URL이나 본문에 담지 않는다
- iOS 토큰 — HTTPS JSON으로 access/refresh를 받고 Keychain에 저장한다. URL·로그에 담지 않는다
- iOS nonce — 로그인마다 안전한 새 `rawNonce`를 만든다. Apple 요청에는
  `lowercase hex SHA-256(rawNonce)`를 넣고 `/auth/apple`에는 원문 `rawNonce`를 보낸다
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
| POST | `/posts` | 필요 | 게시글 작성 |
| GET | `/posts?category=&sort=&cursor=&size=` | 선택 (게스트 허용) | 게시글 목록 |
| GET | `/posts/popular` | 선택 (게스트 허용) | 인기 게시글 Top 10 (홈 화면) |

- 작성 요청은 `type`, `category`, `title`, `description`, `products[]`를 사용한다.
  `products[]`의 각 항목은 `itemContainerId`, `name`, `price`, `linkUrl`을 가진다.
- `AGREE`는 상품이 정확히 1개이고 `title`을 따로 요구하지 않는다. 저장되는 제목은 상품명이며,
  서버가 `사자`·`말자` 선택지를 정확히 2개 만든다. 상품 사진은 1~3장이어야 한다.
- `A_B`는 30자 이내 주제인 `title`과 상품이 정확히 2개 필요하다. 상품마다 사진은 정확히
  1장이고, 서버가 A·B 상품을 각각 가리키는 선택지를 만든다.
- `GENERAL`은 30자 이내 `title`이 필요하며 상품과 선택지가 없어야 한다.
- 설명은 선택이고 300자 이내, 상품명은 필수이고 30자 이내, 가격은 선택이고
  0~999,999,999다. 상품 URL은 선택 텍스트로 형식·업무 길이 제한을 두지 않으며, 서버는
  해당 값에 접속하지 않고 MySQL `LONGTEXT`의 물리 한계 안에서 그대로 저장한다.
- 이미 다른 게시글 상품에 연결된 이미지 컨테이너는 사전 검사와 DB 유일성 충돌 모두
  `ITEM_CONTAINER_ALREADY_IN_USE`(409)로 응답한다.
- 상품의 `itemContainerId`는 작성자 본인이 업로드한 `PRODUCT` 용도여야 하며 한 상품에만
  붙일 수 있다. 존재하지 않거나 타인 소유이거나 이미 사용된 컨테이너는 거부한다.
- 작성 성공은 201 `CREATED`와 `{ "postId": number }`를 반환한다. `전체`는 저장 카테고리가
  아니라 목록에서 `category`를 생략한 상태이므로 작성 값으로 받지 않는다.
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
- **작성자 랭킹(`authorRanking`)은 사전 계산 값이다**([ADR-0028](adr/0028-author-ranking-precompute.md)).
  전역 순위라 조회 시점에 구하면 회원 전체를 정렬해야 한다(200k 실측 102ms/조각).
  배치가 미리 매겨둔 `users.ranking` 을 작성자 조인에서 함께 읽으므로 조각 비용이
  랭킹 없던 때와 같다(0.117ms → 0.232ms).
  - **순위 정의**: 포인트 내림차순, 동점이면 가입이 빠른 쪽. 동점을 공동 순위로 묶지 않는다.
  - **지연 상한 5분.** 포인트가 바뀌면 다음 배치(기본 `0 */5 * * * *`)에서 반영된다.
    지금 이 순간의 정확한 등수가 아니다.
  - 아직 산정되지 않은 회원과 탈퇴 회원은 **`null`** 이다. 0 이나 꼴찌 순위를 지어내지 않는다 —
    지어낸 값은 실제 꼴찌와 구분되지 않는다.

**`GET /posts/popular` — 인기 Top 10 (홈 화면, §2.4)**

- **목록 조회와 같은 쿼리를 탄다.** "커서 없는 인기순 첫 조각" 이 곧 상위 10건이라
  `GET /posts?sort=POPULAR&size=10` 과 실행되는 SQL 이 같다. 전용 쿼리를 새로 짜지 않는다 —
  짜면 위의 실측(454ms → 0.28ms)과 `idx_post_popular_all` 검증을 처음부터 다시 세워야 한다.
- **파라미터가 없다.** 카테고리 필터도 커서도 크기 조절도 받지 않는다. 홈 화면의 계약은
  "전체에서 인기 상위 열 건" 하나뿐이고, 조절 손잡이를 열면 목록 API 와 구별되지 않는다.
- **응답은 커서 봉투 없이 배열이다.** 항목 스키마는 목록과 **같은 것을 공유**한다
  (`PostListItem`). `nextCursor`·`hasNext` 는 싣지 않는다 — Top 10 은 그 열 건이 전부인데
  `hasNext: true` 를 주면 11번째 글이 있을 때 클라이언트가 이어받을 수 있다고 읽고,
  그 커서로 다시 부르면 이 엔드포인트가 정의하지 않은 동작이 된다. 더 보기는 목록 API 로 간다.
- 10건보다 적으면 **있는 만큼만** 준다. 상한이지 정원이 아니다.
- **0건이면 빈 배열이다.** 기능명세서 §2.4 의 "더미 데이터 2개" 는 화면의 빈 상태이지
  서버 응답이 아니다 — 서버가 지어내면 그 카드를 탭했을 때 갈 곳이 없다(위 목록과 같은 판단).
- 실행 계획으로 확인한 정렬 경로: `idx_post_popular_all` / `Using where; Using index`.
  `Using filesort` 가 없다 — 조회 시점에 집계하지 않는다는 증거다.

### 3.4 댓글

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/posts/{postId}/comments` | 필요 | 활성 댓글 전체 목록 |
| POST | `/posts/{postId}/comments` | 필요 | 댓글 작성 |
| PATCH | `/comments/{id}` | 필요 (작성자) | 댓글 내용 수정 |
| DELETE | `/comments/{id}` | 필요 (작성자) | 댓글 소프트 삭제 |
| POST | `/comments/{commentId}/pick` | 필요 | 댓글 원픽 |

- 목록은 `(created_at, id)` 오름차순이며 현재 계약에는 페이징이 없다.
- 댓글·작성자 프로필·원픽 수를 단일 조회로 읽는다. `nickname`이 아직 설정되지 않은
  기존 사용자는 소셜 `name`을 대체 표시값으로 사용한다.
- 각 항목은 원본 `createdAt`과 화면용 `createdAgo`, 현재 요청자의 댓글인지 나타내는
  `mine`을 함께 제공한다.
- 차단·신고와 게스트 로그인 유도 모달은 서버 댓글 CRUD가 아니라 후속 기능/UI 범위다.

**원픽** (ADR-0018·ADR-0020)

- **한 사람은 한 게시글에서 댓글 하나만 원픽한다**(R-05). 유일성 범위가 댓글이 아니라
  **게시글**이라 같은 글의 다른 댓글을 고르는 것도 거부된다. 판정은 `UNIQUE(user_id, post_id)` 가
  원자적으로 한다 — 확인 후 삽입은 동시 요청에서 뚫린다.
- **원픽은 게시글 작성자만의 권한이 아니다.** 댓글 작성자 본인만 아니면 누구나 픽한다.
  행위자를 한정하던 R-08 은 규칙 목록에서 제외됐다.
- 취소·변경 경로가 없다(R-06). 요청 본문도 없다 — 대상은 경로가, 픽하는 사람은 토큰이 정한다.
- 성공 시 `201 CREATED`, `returnObject` 는 `{ id, commentId }`. `id` 는 포인트 지급의 멱등키다.
- 원픽 1건이 **두 사람**에게 지급한다 — 댓글 작성자 `+10P`, 픽한 사람 `+5P`(R-12).
  같은 픽으로 재지급되지 않는다 — 멱등키 `UNIQUE(comment_pick_id, reason)`(R-13).

| 실패 | 상태 | `code` |
|---|---|---|
| 이 게시글에서 이미 원픽함 (R-05) | 409 | `ALREADY_PICKED` |
| 자기 댓글 원픽 (R-07) | 400 | `INVALID_REQUEST` |
| 없는 댓글 · 삭제된 댓글 · 삭제된 게시글 | 400 | `INVALID_REQUEST` |
| 미인증 | 401 | `UNAUTHORIZED` |

> 중복 원픽이 409 인 이유는 요청이 아니라 **상태가 충돌**했기 때문이다. 요청을 고쳐 다시 보낼 것이
> 없으므로 400 이 아니다. 반대로 자기 댓글 원픽은 요청 자체가 무효라 400 이며, 인가 실패가
> 아니므로 403 도 아니다.
>
> 다른 게시글의 댓글을 픽하는 경우는 **API 로 표현되지 않는다** — `postId` 를 댓글에서 끌어오므로
> 어긋난 쌍을 만들 수 없다. 복합 FK `(comment_id, post_id)` 는 그 경로의 버그를 막는
> 최종 방어선이고, 검증은 `JpaOnePickStoreIT` 가 위조한 `OnePick` 으로 한다.
### 3.5 투표

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| POST | `/posts/{postId}/votes` | 필요 | 투표 참여·선택 변경 |

- 본문은 `{ "optionId": 1 }` 이다. 응답은 갱신된 **선택지별 득표 수와 득표율**이라
  투표 직후 화면이 다시 조회하지 않고 게이지로 전환할 수 있다(기능명세 §2.2).
  따로 조회하게 만들면 그 사이 들어온 다른 표까지 섞여 내 표의 결과가 아닌 값을 보여준다.
- **게스트의 최대 3회 선택은 클라이언트 로컬에서만 처리하고 서버에는 기록하지 않는다(R-11).**
  따라서 서버 투표 API는 별도 매처 없이 `anyRequest()` 의 인가 관문으로 401 이다.
  그 관문은 인증 여부에 더해 **계정이 활성인지**까지 본다 — 탈퇴자도 여기서 401 이다(ADR-0035).
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
| POST | `/images` | 필요 | 이미지 업로드 후 부착용 `itemContainerId` 반환 |

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

### 3.7 등급

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/users/me/grade` | 필요 | 내 등급·누적 포인트·투표 횟수·다음 등급까지 달성률 |
| GET | `/grades` | 필요 | 전체 등급의 승급 필요 조건 |

- **승급은 AND 조건이다(R-15).** 누적 포인트와 누적 투표 횟수를 **모두** 충족해야 오른다.
  임계값은 정책 요약표 §2 다 — LV.1 0P / LV.2 200P·20회 / LV.3 1,000P·100회 /
  LV.4 3,500P·300회 / LV.5 10,000P·1,000회. 정본은 `Grade` enum 이고 기준 테이블을
  두지 않는다(ADR-0030). DB 에 두면 정책 요약표와 두 곳이 정본이 된다.
- **판정 입력값은 캐시가 아니라 원장에서 읽는다(ADR-0030).** `users.point` 는 랭킹 배치가
  5분마다 채우는 캐시라 실시간 게이지(기능명세 §7.3)를 만족하지 못하고, `users.vote_count` 는
  **아무도 채우지 않아** 읽으면 전원 0 이다. 정본은 `point_history` 와 `vote` 다(R-14).
  랭킹이 배치인 근거(전역 집계)는 여기 적용되지 않는다 — 등급의 입력값은 `user_id` 로
  좁혀지는 로컬 집계이고 `idx_point_user_created`·`idx_vote_user_created` 가 그 선행 컬럼을 갖는다.
- **재투표는 누적 투표 횟수를 늘리지 않는다(R-22).** 쿼리에 별도 조건이 없다 —
  `UNIQUE (post_id, user_id)` 라 한 사람이 한 게시글에 가질 수 있는 행이 최대 1개이고
  선택 변경은 UPDATE 라, `COUNT(*)` 가 행을 세는 것만으로 이미 사람 단위다.
- **등급은 내려가지 않는다(R-16).** 도달한 최고 등급을 `users.highest_grade` 에 남기고
  계산값과 저장값 중 높은 쪽을 쓴다. 오늘은 포인트가 줄어들 경로가 없어 둘이 항상 같지만,
  저장하지 않으면 R-16 이 "회수 경로가 없어서 저절로 참" 인 상태로 남는다.
- **조회에 쓰기가 있다.** 원장 판정이 저장된 등급보다 높으면 그 자리에서 올린다 —
  갱신은 오를 때만 일어나고, 조건부 UPDATE(`WHERE highest_grade < :level`)라
  동시 요청에서 낮은 값이 높은 값을 덮지 못한다.
- **달성률은 두 조건 중 덜 채운 쪽이다.** 승급이 AND 라 그쪽이 병목이다 — 평균을 내면
  포인트 90%·투표 10% 인 사람에게 "50% 왔다" 고 말하게 된다. 기준선은 0 이 아니라
  현재 등급의 조건이라 승급 직후 0 에서 시작한다. 최고 등급이면 100 이다.
- **등급 일러스트는 응답에 없다.** 이미지의 정본은 화면설계서인데 확정 전이라
  서버가 URL 을 지어내면 그것이 계약이 된다. 프론트가 `level` 로 매핑한다.
- 두 경로 모두 `anyRequest().authenticated()` 에 걸린다. 등급 화면은 마이페이지 하위라
  게스트 진입 경로가 없다(기능명세 §11.2 트리거).

---

### 3.8 피커 랭킹

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/rankings/top` | — (게스트 허용) | 인기 피커. 기본 5명 |
| GET | `/rankings` | — (게스트 허용) | 전체 랭킹. 무한 스크롤 10개 단위 |
| GET | `/users/me/points` | 필요 | 내 포인트와 순위 |

- 응답 항목은 세 화면이 같다 — `userId` · `nickname` · `profileImageUrl` · `ranking` · `point`.
  명세의 조회 데이터에는 **등급명칭**도 있으나 판정 정본 `Grade` 가 이슈 #25 에 있어
  이 계약에서는 아직 빠져 있다. #25 머지 후 필드를 더한다.
- **순위는 사전 계산 값을 읽기만 한다**([ADR-0028](adr/0028-author-ranking-precompute.md)).
  조회 시점에 세지 않으므로 **지연 상한 5분**이 그대로 이 응답의 계약이다.
- **정렬·커서 키는 `users.ranking` 하나다**([ADR-0032](adr/0032-ranking-read-path.md)).
  `ROW_NUMBER()` 가 만든 전순서라 중복이 없어 동률 처리가 필요 없다
  (200k 시드에서 `COUNT(DISTINCT ranking) = 200,000`).
  `idx_users_ranking_order` 가 이 순서를 제공해 조각 비용이 깊이와 무관하다
  (200k 실측: 인덱스 없음 43.5ms → 있음 0.070ms).
- **순위가 산정되지 않은 회원(`ranking IS NULL`)은 목록에 오르지 않는다.** 가입 직후와
  탈퇴자가 그렇다. 순위 없는 사람을 순위 목록에 넣을 자리가 없기 때문이다.
  탈퇴자는 다음 배치까지 최대 5분간 목록에 남는다 — ADR-0028 의 지연과 같은 계약이다.
- **본인 조회는 다르다.** 순위가 없어도 포인트는 존재하므로 행을 주고 `ranking` 필드만
  응답에서 뺀다(`@JsonInclude(NON_NULL)`). 0 으로 채우지 않는다 — 지어낸 순위는
  실제 꼴찌와 구분되지 않는다.
- **포인트 보유자가 없으면 빈 배열이다.** "아직 TOP 피커가 존재하지 않아요" 는 화면의
  빈 상태 문구이므로 서버가 만들지 않는다.
- **게스트는 목록을 보되 본인 랭킹을 받지 못한다.** 목록 응답 모양이 로그인 여부에 따라
  갈리지 않게 본인 순위는 `/users/me/points` 로 분리했고, 그 경로는 인증을 요구한다.
  화면이 "본인 순위에 도달하면 합쳐서 스크롤" 하는 동작은 클라이언트 표현이다.
- `size` 상한은 50 이다. 없으면 top 은 5, 목록은 10.
  상한이 없으면 한 번의 요청으로 목록 전체가 나가 무한 스크롤이 무의미해진다.

### 3.9 뱃지

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/users/me/badges` | 필요 | 내 뱃지 현황(획득·미획득) + 수집 개수 |
| GET | `/users/me/badges/missions` | 필요 | 미해제 미션 진행률 |

- **둘 다 인증이 필요하다.** 게스트에게는 미션을 보여주지 않고 "로그인하고 뱃지를
  획득해보세요" 를 띄우는 것이 명세다(§2.3) — 그 문구는 화면의 몫이고 서버는 401 이다.
  별도 매처 없이 `anyRequest().authenticated()` 에 걸린다.
- **뱃지 8종은 `badge` 테이블의 행이다**([ADR-0031](adr/0031-badge-daily-activity-aggregate.md)).
  정책 정본이 "뱃지명은 추후 수정됩니다" 를 명시하므로 이름은 데이터이고,
  식별자는 조건에서 딴 `code`(`TOTAL_VOTE_10` …)다. 이름이 바뀌면 행을 UPDATE 하면 되고
  코드도 마이그레이션도 손대지 않는다. 반대로 조건 유형 셋(R-18)은 안정 계약이라 enum 이다.
  → 클라이언트는 일러스트를 고를 때 `name` 이 아니라 **`code`** 를 쓴다.
- 현황 응답은 **미획득 뱃지도 포함**한다. 3X3 목록이 미획득의 이름은 보여주고 일러스트만
  가리기 때문이다(§12.2). 서버가 빼면 화면이 빈 칸을 그릴 수 없다.
- `collectedCount` 는 §12.1 의 조회 데이터가 이 값 하나라 별도 엔드포인트 없이 여기 실었다.
- **미션은 계열마다 하나씩**이다(§2.3). 누적 계열과 일일 계열에서 각각 **아직 못 넘은 가장 낮은
  임계값**을 고른다 — "하위 미션 먼저 표시" 다. 다 채운 계열은 빠지고, 8종을 모두 얻으면 빈 배열이다.
  **연속 계열은 슬롯에 넣지 않는다** — 명세가 슬롯을 둘로 못박았고 `<조회 데이터>` 에도 없다.
- **진행률은 `current`·`goal` 두 수**다. 명세의 표기가 `누적 투표 10회 달성 (0/10)` 이라
  퍼센트로 환산해 내리면 클라이언트가 `0/1000` 을 렌더할 수 없다. `current` 는 목표를 넘지 않는다.
- **투표하면 그 자리에서 반영된다.** 판정이 투표와 같은 트랜잭션에서 돌기 때문이다 —
  커밋 후로 미루면 투표는 커밋되고 활동 기록만 유실되어 집계가 조용히 어긋난다.

**판정 규칙**

- **일일·연속·누적을 모두 `user_daily_activity` 에서 유도한다**(R-19). `vote` 에서 직접 구하면
  `DATE(created_at)` 이 인덱스를 무력화하고 연속 판정은 그 회원의 투표를 전부 훑는다 —
  1,000회 투표한 사람의 "7일 연속" 에 1,000행이다. 집계 테이블은 활동한 날 수만큼만 행을 가져
  연속은 최근 31행, 일일은 행 하나로 끝난다(실측: `range` + `Using index; Backward index scan`).
- **누적 투표 횟수는 `users.vote_count` 가 아니라 일별 합계다.** 그 컬럼은 V3 에 있지만
  아무도 채우지 않아 값이 전부 0 이다. 같은 사실을 두 곳이 표현하면 어긋나므로
  별도 카운터도 두지 않는다(V5 가 같은 이유로 컬럼 하나를 걷어냈다).
- **재투표는 일별 활동을 늘리지 않는다**(R-22). 판정이 `VoteService` 의 첫 투표 경로에만
  붙어 있어 선택 변경으로는 "하루 20개" 가 채워지지 않는다.
- **같은 뱃지를 두 번 주지 않는다**(R-17). `UNIQUE(user_id, badge_id)` 가 최종 방어선이다 —
  확인과 삽입 사이에 동시 투표가 끼어들 수 있다. 지급은 유일성을 사전 확인하고 저장하며
  무결성 예외를 삼키지 않는다(삼키면 트랜잭션이 rollback-only 가 되어 투표가 통째로 실패한다).
- **연속의 기준일은 오늘 행의 유무가 정한다.** 오늘 투표했으면 오늘부터, 아직 안 했으면
  어제부터 거슬러 센다 — 어제까지 6일을 채운 사람이 오늘 아침에 `0/7` 을 보면 끊긴 줄 안다.
  획득 판정은 투표 직후에만 돌아 그 시점엔 오늘 행이 반드시 있으므로 느슨해지지 않는다.
- 날짜는 애플리케이션이 `Asia/Seoul` `Clock` 으로 계산해 넘긴다. SQL 의 `CURRENT_DATE` 를 쓰면
  DB 세션 타임존이 하루를 정해, 자정 근처에서 사용자가 보는 하루와 갈린다.

---

### 3.10 내 활동 (마이페이지)

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| GET | `/users/me/activities/summary` | 필요 | 활동 갯수 요약 |
| GET | `/users/me/activities?type=&sort=&cursor=&size=` | 필요 | 활동 목록 (무한 스크롤) |
| GET | `/users/me/posts/recent` | 필요 | 7일 이내 올린 투표 |

- **셋 다 인증이 필요하다.** 명세는 게스트에게 "모든 갯수 표시가 0개로 고정" 이라 적었지만,
  같은 문단이 게스트에게 **"클릭 제한"** 도 함께 걸고 프로필 자리를 "로그인해주세요" 로 바꾼다(§7.2).
  게스트는 활동 영역을 눌러 목록 화면에 도달하지 못하므로 **0 은 로그인하지 않은 화면의
  플레이스홀더이지 서버 응답 규약이 아니다.** 서버가 0 을 주면 "활동 없는 회원" 과
  "비로그인" 이 같은 응답이 되어 화면이 둘을 구분하지 못한다.
- **목록의 항목은 활동이 아니라 게시글이다**([ADR-0036](adr/0036-my-activity-list-reads-posts-through-activity-index.md)).
  §9.2 의 조회 데이터가 세 유형 모두 게시글 카드이고 탭하면 게시글 상세로 간다.
  그래서 `type` 은 결과의 모양이 아니라 **게시글을 좁히는 조건**만 바꾼다 —
  세 테이블 UNION 이 필요 없고 커서가 언제나 `(정렬키, post.id)` 다.
- `type` 은 `VOTE`(기본) · `COMMENT` · `POST`. **"전체" 가 없다** — §9.1 상
  "메인화면에서 어떤 유형을 탭해서 들어왔는지에 따라 기본 칩이 변경" 이라 칩은 항상 하나가 활성이다.
- `sort` 는 `LATEST`(기본) · `OLDEST` · `POPULAR`. **모르는 값은 400 이 아니라 기본값으로
  되돌린다** — `type` 도 같다(§5.2 와 §3.3 이 세운 규칙). `size` 는 기본 10, 상한 50.
- **최신순의 정렬 키는 내가 활동한 시각이다.** 게시글 작성 시각이 아니다 —
  어제 올라온 글에 방금 단 댓글이 맨 위에 와야 다시 찾을 수 있다. 응답의 `activityAt` 이 그 값이고,
  내가 올린 글은 활동이 곧 작성이라 `createdAt` 과 같다.
- **인기순 = 투표 인원 + 댓글 인원**(R-24·R-25). `GET /posts` 와 **같은 컬럼 하나**
  (`post.popularity_score` 생성 컬럼)를 읽는다 — 양쪽이 각자 집계하면 같은 화면에서 다른 값이 나온다.
- **댓글 활동은 `comment` 가 아니라 `post_commenter` 를 읽는다.** 그 테이블은
  `UNIQUE(post_id, user_id)` 라 게시글당 한 행이어서 한 글에 댓글을 여러 개 달아도
  목록에 한 번만 나온다(R-25). `comment` 로 읽고 `DISTINCT` 를 걸면 커서가 가리키는 행이 사라진다.
- **요약의 세 값에 `DISTINCT` 가 없다.** 스키마가 이미 인원으로 세고 있다 —
  `vote` 는 `UNIQUE(post_id, user_id)` 라 재투표가 UPDATE 이고(R-22),
  `post_commenter` 도 같은 키다. 등급 판정(`JpaGradeStore`)이 같은 셈법을 쓰므로
  여기서 다시 세면 마이페이지의 두 숫자가 어긋난다.
- **투표·댓글의 정렬은 활동 인덱스가 통째로 맡는다.** 조각을 활동 테이블에서 확정한 뒤 대표 사진을 붙이며,
  정렬 튜플의 두 번째 자리를 `p.id` 가 아니라 `v.post_id` 로 읽는다 —
  값은 같지만 어느 테이블에서 읽느냐가 실행계획을 가른다(활동 500건 실측 4.29ms → 0.070ms,
  읽는 행 500 → 11). V11 이 `(user_id, created_at DESC, post_id DESC)` 를 두 테이블에 추가한다.
- **`type=POST` 최신순과 인기순에는 정렬이 남는다.** 전자는 `idx_post_user` 뒤에 붙는 PK 가
  오름차순이라 `created_at DESC, id DESC` 와 어긋나기 때문이다(내 글 500건 0.168ms).
  `id ASC` 로 바꾸면 인덱스로 끝나지만(0.011ms) 커서 튜플의 두 키 방향이 갈려
  행 값 비교가 성립하지 않는다 — 한 유형의 0.16ms 보다 규약의 일관성을 택했다(ADR-0036).
- **인기순은 Θ(내 활동 수)로 남는다.** 정렬 키가 활동 테이블에 없어 조인해야 알 수 있다.
  활동 5,000건에서 5,011행·10.5ms 이며, 활동 수는 사람이 손으로 만드는 값이라 상한이 낮아 받아들였다.
  비정규화는 게시글 갱신마다 활동 행 전체를 고치게 되어 기각했다(ADR-0036).
- **§7.4 의 기준 시각은 요청 시각이고 경계는 반열린 구간** `(now - 7일, now]` 다.
  정확히 7일이 지난 글은 빠진다. 날짜로 끊으면 같은 글이 자정을 지나며 사라져
  "방금 봤는데 없어졌다" 가 된다. 가로 스크롤 캐러셀이라 커서가 없고 최대 10건이며,
  투표가 없는 일반 게시글은 대상이 아니다.
- **활동이 0건이면 빈 배열이다.** "아직 참여한 활동이 없어요" 는 화면의 빈 상태이므로
  서버가 존재하지 않는 활동을 지어내지 않는다.
- **삭제된 게시글은 목록에서 빠진다** — 탭했을 때 갈 곳이 없기 때문이다.
  요약은 `vote` 행을 세므로 삭제된 글의 투표도 포함되어 **요약과 목록 길이가 어긋날 수 있다.**
  요약은 "내가 한 활동", 목록은 "지금 볼 수 있는 글" 이라 세는 대상이 다르다.

## 4. 스키마

### 4.1 인증 3개

```sql
users(id, provider, provider_id NULL, email, name, role, state, created_at, updated_at,
      UNIQUE KEY uk_users_provider (provider, provider_id),
      CONSTRAINT ck_users_active_provider_id
        CHECK (state <> 'ACTIVE' OR provider_id IS NOT NULL))

user_refresh_token(id, user_id, token_hash CHAR(64), expires_at, created_at,
      UNIQUE KEY uk_refresh_user (user_id),
      UNIQUE KEY uk_refresh_token_hash (token_hash),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)

apple_provider_token(user_id, encryption_format_version, encrypted_refresh_token,
      encryption_iv, encryption_key_id, created_at, updated_at,
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)
```

도메인에서 `provider_id = NULL`은 **Apple 비활성 회원**에만 허용한다. DB의
`ck_users_active_provider_id`는 활성 회원의 누락만 막는 최소 보장이며, Apple 이외 provider까지
구분하는 더 좁은 규칙은 애플리케이션이 지킨다. MySQL unique key는 여러 `NULL`을 허용하므로
identity를 분리한 과거 Apple 행이 동일 `sub`의 신규 회원 생성을 막지 않는다.

### 4.2 마이그레이션

| 파일 | location | 로드 시점 |
|---|---|---|
| `V1__auth_tables.sql` | `db/migration` | 항상 |
| `V3__pickple_domain.sql` | `db/migration` | 항상 |
| `V4__apple_provider_tokens.sql` | `db/migration` | 항상 |
| `V5__active_nickname_follows_state.sql` | `db/migration` | 항상 |
| `V7__users_ranking_precompute.sql` | `db/migration` | 항상 |
| `V8__grade.sql` | `db/migration` | 항상 |
| `V9__badge.sql` | `db/migration` | 항상 |
| `V10__users_ranking_order_index.sql` | `db/migration` | 항상 |
| `V11__activity_list_indexes.sql` | `db/migration` | 항상 |
| `V12__detach_withdrawn_apple_identity.sql` | `db/migration` | 항상 |
| `V13__post_product_unbounded_link_url.sql` | `db/migration` | 항상 |

> **V2·V6 은 결번이다.** V2 는 develop 에 머지되지 않은 브랜치가 잡고 있었고,
> 번호를 메우지 않는다 — 단조 증가만 유지하면
> out-of-order 를 켜지 않고도 적용된다.

### 4.3 뱃지 3개 (V9)

```sql
badge(id, code, name, description, condition_type, threshold, display_order,
      created_at, updated_at,
      UNIQUE KEY uk_badge_code (code),
      UNIQUE KEY uk_badge_condition (condition_type, threshold),
      CHECK condition_type IN ('TOTAL_VOTE','DAILY_VOTE','STREAK_VOTE'))

user_badge(id, user_id, badge_id, acquired_at,
      UNIQUE KEY uk_user_badge (user_id, badge_id),      -- R-17 최종 방어선
      FOREIGN KEY (user_id) REFERENCES users(id),
      FOREIGN KEY (badge_id) REFERENCES badge(id))

user_daily_activity(id, user_id, activity_date, vote_count, created_at, updated_at,
      UNIQUE KEY uk_daily_user_date (user_id, activity_date),   -- UPSERT 충돌 지점
      FOREIGN KEY (user_id) REFERENCES users(id))
```

- `badge` 8행은 마이그레이션이 넣는다. **애플리케이션은 이 테이블에 쓰지 않는다** —
  이름이 바뀌면 운영 DB 를 UPDATE 한다(적용된 마이그레이션은 다시 돌지 않으므로
  V9 를 고쳐도 반영되지 않고 체크섬만 어긋난다).
- `uk_daily_user_date` 의 컬럼 순서 `(user_id, activity_date)` 가 계약이다. 뒤집으면
  연속 판정이 그 회원의 행을 모으지 못해 전체를 훑는다.
- V9 는 기존 `vote` 를 일별 집계로 **백필**한다. 빈 채로 두면 배포 직후 모든 회원의
  누적 투표가 0 이 되는데 에러가 나지 않아 사용자가 신고해야 발견된다.
- `user_badge` 에 UPDATE·DELETE 경로가 없다. 뱃지는 한 번 얻으면 남고 회수 정책이 없다.

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

- 액세스 토큰은 클레임만으로 **신원**을 세운다(무상태 파싱). 다만 **계정이 살아 있는지는
  요청마다 확인한다** — 신원이 붙은 요청에 한해 `users.id = ? AND state = 'ACTIVE'` 존재 확인
  1회다(ADR-0035). 게스트·정적 경로는 조회가 0회다.
  ADR-0006 의 "요청마다 DB 를 조회하지 않는다" 를 이 항목이 대체한다 —
  뒤집은 근거는 성능이 아니라 탈퇴자가 댓글·투표·원픽을 만들 수 있던 데이터 오염 증거다
- **비활성 계정은 어디서든 익명으로 강등된다.** 그 하나의 규칙이 경로에 따라 두 결과를 낸다:
  보호 경로는 401, `permitAll` 경로는 200 이되 개인화가 사라진다(`mine=false`).
  탈퇴자가 게스트보다 권한이 많은 상태가 이로써 닫힌다
- **계정 상태를 확인하지 못하면 401 이 아니라 503** `ACCOUNT_STATE_UNAVAILABLE` 이다.
  DB 장애를 인증 실패로 내보내면 전 클라이언트 재로그인 폭주를 부르고 장애가 모니터링에서 감춰진다
- 관문은 **진입 장벽이지 트랜잭션 불변식이 아니다.** `open-in-view: false` 라 인가 시점 조회는
  서비스 트랜잭션 밖에서 끝나므로 탈퇴 커밋과 쓰기가 겹치는 창은 남는다(ADR-0035 수용 한계).
  또 이 시큐리티 체인을 지나는 HTTP 요청만 보호하므로 서비스별 `isActive()` 확인을 일괄 제거하지 않는다.
  `/auth/refresh` 는 `permitAll` 이고 액세스 토큰 없이도 불리므로 자체 확인을 유지한다
- 리프레시 토큰은 SHA-256 해시로 저장. 사용자당 한 행, 제출 해시를 조건으로 CAS 회전
- 동시 회전의 패자나 옛 토큰은 401로 거부하되 현재 저장된 승자 token은 삭제하지 않는다
- `typ` 클레임으로 액세스/리프레시를 구분해 혼용을 막는다
- 리다이렉트 URI 는 호스트 화이트리스트 검증 (오픈 리다이렉트 방지)
- 활성 Apple 사용자는 이메일이 아닌 `(APPLE, ID token의 sub)`로 식별한다
- Apple client secret은 `.p8`로 ES256 서명하고, Apple ID token은 JWKS의 RS256 서명을 검증한다
- Apple provider refresh token은 별도 AES-256-GCM keyring으로 암호화해 저장한다. 랜덤 12-byte IV와
  사용자 ID·키 ID를 묶은 AAD를 사용하며 DB에는 평문을 저장하지 않는다
- Apple code 교환 뒤 ID token 불일치나 로컬 로그인 완료가 실패하면, 새로 발급된 provider refresh token을
  보상 revoke해 로컬에서 소유하지 않는 Apple 세션이 남지 않게 한다
- Apple 회원 탈퇴는 provider refresh token으로 `/auth/revoke`에 성공한 뒤 로컬 계정을 비활성화하고
  `provider_id`를 분리하며 서비스/provider refresh token을 같은 로컬 트랜잭션에서 삭제한다.
  Apple 일시 장애 시 503으로 재시도하고 로컬 상태를 바꾸지 않는다. token이 없는 기존 계정은
  로컬 탈퇴와 identity 분리를 완료한 뒤 `APPLE_MANUAL_REVOCATION_REQUIRED`로 수동 연결 해제를 안내한다
- 탈퇴 뒤 같은 Apple `sub`로 로그인하면 과거 비활성 행을 되살리지 않고 새 `userId`를 만든다.
  과거 행과 콘텐츠는 보존하지만 프로필·포인트·뱃지·투표·댓글 등 이력은 새 회원에게 승계하지 않는다
- V12는 기존 `APPLE + INACTIVE` 행의 `provider_id`만 `NULL`로 백필한다. 다른 provider의 재가입과
  개인정보 익명화·삭제, 재가입 초기화 악용 정책은 이 변경 범위가 아니며 Issue #45에 남긴다
- 로그인 보상 revoke 실패는 counter와 `correlationId` WARN으로 관측한다. 자동 복구 outbox는 후속 범위다
- 회원 활성 여부의 정본은 `users.state`다. 향후 `deleted_at`은 탈퇴 시각 감사값으로 같은 트랜잭션에서 기록한다
- authorization code·identity token·`.p8`·access/refresh token은 로그에 남기지 않는다
- 근거: [ADR-0006](adr/0006-auth-hardening.md), [ADR-0015](adr/0015-native-sign-in-with-apple.md),
  [ADR-0016](adr/0016-refresh-token-rotation-cas.md),
  [ADR-0037](adr/0037-apple-withdrawal-detaches-provider-identity.md)
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
| 상태 충돌 — 닉네임 선점 · 이미 원픽함 · 이미지 컨테이너 재사용 | `NICKNAME_ALREADY_IN_USE` / `ALREADY_PICKED` / `ITEM_CONTAINER_ALREADY_IN_USE` | 409 |
| Apple 키 미설정·Apple 서버 일시 장애 | `APPLE_LOGIN_UNAVAILABLE` | 503 |
| Apple 회원 탈퇴 연결 해제 일시 장애 | `APPLE_ACCOUNT_REVOCATION_UNAVAILABLE` | 503 |
| Apple token 없는 기존 계정의 로컬 탈퇴 완료 | `APPLE_MANUAL_REVOCATION_REQUIRED` | 200 |
| 그 외 | `SYSTEM_ERROR` | 500 |

> 게시글을 삭제해도 연결된 이미지 컨테이너는 점유 상태를 유지한다. 삭제한 게시글에 사용된 이미지를
> 다른 게시글에서 재사용하면 `ITEM_CONTAINER_ALREADY_IN_USE`(409)로 응답한다.

---

## 6. 아키텍처 규칙

`ArchitectureTest` 24개(전부 활성). 규칙을 추가할 때는 **일부러 위반하는
코드를 넣어 해당 규칙만 실패하는지 확인한 뒤** 커밋한다 — 통과만으로는 그 규칙이 무언가를
지킨다는 증거가 되지 않는다([ADR-0008](adr/0008-domain-entity-separation.md)).

| 그룹 | 규칙 수 | 내용 |
|---|---|---|
| 테스트 네이밍 | 1 | 통합 테스트 클래스 이름은 `IT` 로 끝난다 |
| 도메인 순수성 | 6 | JPA·검증·Lombok·infra·web·부동소수점 의존 금지 |
| 계층 경계 | 9 | Store 인터페이스는 domain / 구현은 infra / Entity 는 infra / 서비스·컨트롤러가 리포지토리 직접 의존 금지 / infra→service 금지 / 컨트롤러가 Entity 노출 금지 / 설정 클래스는 루트 config |
| API 응답 계약 | 2 | 컨트롤러가 `Page`·`Window` 를 그대로 반환 금지 |
| API 문서 | 1 | permitAll 이 아닌 핸들러는 `@SecurityRequirement` 를 갖는다([ADR-0034](adr/0034-security-requirement-on-authenticated-endpoints.md)) |
| 컨트롤러 매핑 | 2 | 핸들러 매핑 경로 비어있음 금지 / 클래스 레벨 `@RequestMapping` 금지([ADR-0033](adr/0033-drop-api-prefix-implemented.md) 적용과 함께 활성) |
| 탈퇴 회원 차단 관문 | 2 | `SecurityConfig` 가 관문과 두 필터를 배선한다 / 상태 조회가 저장소 인터페이스를 지난다([ADR-0035](adr/0035-withdrawn-user-central-authorization.md)) |
| DI | 1 | `@Autowired` 필드 주입 금지 |

> **이 표는 합계가 규칙 수와 맞아야 한다.** 어긋나면 규칙을 추가하고 문서를 안 고친 것이다.
> 실제로 그랬다 — `API 문서` 그룹이 통째로 빠지고 계층 경계가 8 로 적혀 있어,
> 본문은 21 인데 표 합계는 20 이고 실제는 22 였다(#106 의 2개를 더해 지금 24).
> 세는 방법: `ArchitectureTest` 의 `@Nested` 그룹별 `@Test` 개수.

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
| 2026-09-05 | 탈퇴 회원 차단을 인가 계층 한 곳으로 집중(ADR-0035). 액세스 토큰 경로에 계정 상태 확인 1회를 더하고, 비활성 신원은 어디서든 익명으로 강등한다. 상태 확인 불가는 401 이 아니라 503 | Issue #106. 탈퇴 전 발급 토큰(TTL 30분)으로 댓글 201·투표 200·원픽 201 이 실서버에서 재현됐다. 확인 지점이 `vote`·`comment`·`point` 에 하나도 없어 **탈퇴자가 게스트보다 권한이 많았다.** 원픽은 포인트를 지급하므로 랭킹 원장까지 오염됐다 |
| 2026-09-05 | Apple 탈퇴 완료 시 `provider_id`를 분리하고, 동일 `sub` 재로그인을 이력 미승계의 새 회원으로 처리(ADR-0037) | Issue #103. Issue #40의 연결 해제 후 재로그인 계약이 비활성 행 조회로 403이 되던 회귀 수정 |
| 2026-09-04 | 이미지 컨테이너 재사용을 입력 오류가 아닌 상태 충돌(409)로 통일 | Issue #17 리뷰. 사전 검사와 DB 경합이 같은 API 계약을 가져야 함 |
| 2026-09-04 | 최신 게스트 정책에 맞춰 댓글 목록 조회를 인증 필수로 변경 | 게스트는 게시글 탐색은 가능하지만 댓글 열람은 제한 |
| 2026-09-04 | `GET /posts/popular` 추가. 목록 조회 경로를 재사용하고 커서 봉투만 벗긴다 | Issue #29. 홈 화면이 커서 없는 고정 10건을 요구. 전용 쿼리를 새로 만들면 `idx_post_popular_all` 검증이 두 벌이 된다 |
| 2026-09-04 | 등급 도메인 신설. 승급 판정 입력값을 캐시가 아니라 원장에서 읽고, 도달 등급만 `users.highest_grade` 에 저장(ADR-0030) | Issue #25. `users.vote_count` 는 선언만 있고 **쓰는 코드가 0건**이라 읽으면 전원 0 — 아무도 승급하지 못하는데 테스트는 초록인 상태가 됐을 것 |
| 2026-09-04 | 엔드포인트의 `/api` prefix를 제거하고 메서드에 전체 경로를 선언 | Issue #91. ADR-0033으로 ADR-0029의 과도기 결정을 대체 |
| 2026-09-03 | 게시글 작성 계약과 상품 URL `LONGTEXT` 마이그레이션 추가 | Issue #17. 유형별 상품·사진·선택지 규칙과 업로드 컨테이너 소유권·재사용 방지 |
| 2026-09-03 | ArchitectureTest 표에 테스트 네이밍·config 위치 규칙 반영(당시 19개) | 새 규칙이 표에 반영되지 않았음 |
| 2026-09-03 | 이미지 저장소 추상화를 `File*` 계열로 개명, 설정 접두어 `app.image` → `app.file` | 이미지 외 파일도 담을 수 있는 이름으로(#63). 환경변수도 `FILE_*` 로 |
| 2026-09-03 | 도메인별 `config` 하위 패키지를 루트 `config` 로 통합 | 설정이 흩어져 부트스트랩 전체를 한눈에 못 봄(#63). ArchitectureTest 로 재발 차단 |
| 2026-09-03 | 탈퇴 정본을 `users.state` 로 통일하고 `deleted_at` 제거 | 생성 컬럼이 `deleted_at` 을 보는데 코드는 `state` 만 써서 탈퇴해도 닉네임이 잠겼다(#16) |
| 2026-09-03 | `POST /images` 에 `attachType` 필수 파라미터 추가 | 상품 전용에서 범용으로 전환(#62). 기존 호출자는 400 |
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
| 2026-09-03 | 원픽 API 계약 추가. 중복 원픽을 `ALREADY_PICKED`(409) 로 매핑 | Issue #24. 매핑이 없어 정책 위반이 500 으로 나가고 있었음 |
| 2026-09-03 | 목록 응답에 `authorRanking` 추가. 5분 주기 배치로 사전 계산 | Issue #73. 조회 시점 계산은 200k 기준 102ms/조각이라 배치로 옮김(ADR-0028) |
| 2026-09-03 | 투표 참여 계약 추가. 선택지별 집계 카운터와 투표 시 락 순서 규정 | Issue #21. 동시 투표에서 `post` 행 락 승격으로 교착이 재현돼 순서를 못박음 |
| 2026-09-04 | 피커 랭킹 조회 계약 추가(§3.8). 정렬·커서 키를 `users.ranking` 으로 확정하고 인덱스 추가 | Issue #26. 인덱스 없이는 조각마다 회원 전체를 정렬해(200k 43.5ms) 사전 계산의 이득이 사라짐(ADR-0032) |
| 2026-09-04 | 랭킹 배치에 `users.vote_count` 동기화 단계 추가 | Issue #26. 이 컬럼을 채우는 코드 경로가 없어 등급 판정 입력이 영원히 0이었음 |
| 2026-09-04 | 뱃지 현황·미션 계약 추가. 판정을 `user_daily_activity` 집계로 (V9) | Issue #27. `vote` 직접 조회는 연속 판정이 회원의 투표 전체를 훑고 그 판정이 투표마다 일어난다(ADR-0031). 뱃지 이름은 정책이 변경을 예고해 데이터로 뒀다 |
| 2026-09-04 | 내 활동 조회 계약 추가(§3.10). 목록 항목을 활동이 아니라 게시글로 확정하고 활동 인덱스 추가(V11) | Issue #30. 정렬 튜플의 두 번째 자리를 `p.id` 로 두면 인덱스가 정렬을 못 맡아 내 활동 전체를 읽는다(500건 4.29ms). 활동 테이블 쪽 `post_id` 로 맞추고 인덱스를 넓혀 11행 고정(ADR-0036) |
| 2026-09-04 | `/api` prefix 를 실제로 제거하고 문서 노출을 `paths-to-exclude` 로 전환 | Issue #91. 프론트 합의가 닫혀 착수. 브릿지는 불필요로 확인돼 두지 않았다(ADR-0033 이 ADR-0029 대체) |
