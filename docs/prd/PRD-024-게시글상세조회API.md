# PRD-024 — 게시글 상세 조회 API

**이슈**: #20 · **명세**: [기능명세서 v0.4](../requirement/기능명세서%20v0.4.md) §6.2 · §6.3 · **작성**: 2026-09-06

## 무엇을 왜

목록(§4.2)에서 카드를 탭하면 게시글 상세로 간다. 상세는 작성자 정보와 게시물 정보를
유형별로 다르게 보여주고, 투표 게시글이면 상품 정보와 투표 영역을 함께 보여준다.
목록 API 는 카드 한 줄에 필요한 필드만 주므로 상세 화면을 그릴 수 없다.

게스트도 커뮤니티를 둘러보다 상세까지 들어오므로 인증을 요구하지 않는다.
다만 "이미 투표했는가" 는 신원이 있어야 답할 수 있어, **인증을 선택적으로 받아
있으면 개인화하고 없으면 미투표로 답한다.**

이슈는 기능명세서 v0.2 §6.2 를 인용하지만 **정본은 v0.4** 다(사이클 도중 #121 로 올라왔다).
v0.3·v0.4 의 §6.2·§6.3 을 차례로 대조했고 **상세 조회 관련 내용은 세 판본이 동일**했다 —
조회 데이터·"0분전…통일" 문구·"일반 게시글은 존재하지 않는 기능"·"진입 시점부터 버튼 대신
득표율 게이지 바" 가 그대로다. v0.3 이 바꾼 것은 §6.4 댓글의 게스트 처리
("로그인 후 열람할 수 있어요")이며 이 사이클의 범위가 아니다.

## API 계약

| Method | Path | 인증 | 성공 |
|---|---|---|---|
| GET | `/posts/{id}` | — (게스트 허용) | `200 OK`, 상세 응답 |

없거나 삭제된 게시글은 `404 NOT_FOUND` 다.

응답 모양은 [ADR-0041](../adr/0041-post-detail-single-type-with-nested-vote-section.md) 이 정한다.
요약하면:

- **단일 응답 타입.** 유형별로 쪼개지 않는다. 클라이언트 분기는 `vote == null`(투표 영역을
  그리는가)과 `vote.voted`(버튼인가 게이지인가) 두 불리언뿐이다.
- **작성자는 평면**(`authorId`·`authorNickname`·`authorProfileImageUrl`·`authorGradeLevel`·
  `authorGradeName`·`authorRanking`), **투표 섹션만 중첩 nullable**(`vote`).
  일반 게시글은 `vote: null` 이다 (R-04).
- **`products` 는 `vote` 안에 둔다.** `productCount() > 0` 과 `hasVoting()` 이 일치하므로
  같은 조건으로 나타나고 사라진다.
- **작성 시간은 `createdAt`(시각)과 `createdAgo`(`"3시간 전"`)를 함께** 준다.
  §6.2·§6.4 가 같은 화면에서 "통일" 을 요구하므로 `CommentResponse` 선례를 따른다.
- **미투표자·게스트에게는 선택지별 `voteCount` 와 `percentage` 가 둘 다 없다.**
  하나만 빼면 R-04 + R-09 로 나머지가 역산된다. 총 인원 `voterCount` 는 준다.
- `vote.options[]` 는 `VoteController.OptionResponse` 와 같은 모양이되 `productId` 를 더 갖는다
  (A/B 에서 버튼과 상품 카드를 연결해야 한다).

## 범위

**포함**

- `GET /posts/{id}` 엔드포인트와 유형별 응답 조립 (R-01·R-04)
- 게스트 허용: `SecurityConfig` permitAll 등록 + ArchUnit `PUBLIC_ENDPOINTS` 두 번째 정본 등록
- 선택적 인증 주체로 "이미 투표했는가" 판정 (미투표·게스트는 집계 부재)
- 삭제된 게시글 404 (소프트 삭제라 `deleted_at IS NULL` 로 가른다)
- 상품 대표 사진 1장 노출 (찬반=가장 처음 등록한 사진, A/B=상품별 1장)
- 쿼리 수를 게시글 유형·상품 수·사진 수와 무관하게 고정하는 읽기 경로
- `@Tag`·전 필드 `@Schema(description=)` 을 포함한 OpenAPI 문서 표면
- 통합 테스트(응답 스키마·404·쿼리 수·부재 검증)

**제외**

- **게시글 수정·삭제 API** — #31 이며 #32 결정 대기 중이다. 이 사이클은 읽기만 한다.
- **댓글 목록** — §6.4 는 별도 엔드포인트(`GET /posts/{id}/comments`)로 이미 있고
  v0.3 에서 게스트 정책이 바뀌었다. 상세 응답에 댓글 배열을 싣지 않고 `commentCount` 만 준다.
- **투표 참여** — `POST /posts/{id}/votes` 로 이미 있다. 이 API 는 현황만 읽는다.
- **게스트의 3표 게이지** — R-11 로 게스트 투표는 서버에 남지 않고, 투표 API 가 인증 필수라
  게스트는 애초에 서버에서 집계를 받지 못한다. 기존 제약이지 이 사이클이 만든 문제가 아니다
  (ADR-0041 열린 질문).
- **신고·차단** — §6.1 이 "기능 없이 존재" 로 명시한다.
- **조회수** — 명세에 없다. 지어내지 않는다.

## 완료 판정

이슈 #20 의 완료 판정 5줄을 그대로 옮기고 측정 방법을 구체화했다.
**이 표가 `goal-spec` 의 GOAL.md 역할을 겸한다.** 검증 위치는 구현하며 채운다.

| 판정 | 검증 방법 | 검증 위치 |
|---|---|---|
| 일반 게시글 응답에 투표 영역이 없음 (R-04) | 일반 게시글 조회 후 `$.returnObject.vote` 가 `null` 이고 상품·선택지 키가 없음을 확인 | `PostDetailIT.generalPostHasNoVoteSection` |
| 이미 투표한 사용자는 응답에 득표율이 포함됨 | 투표 API 호출 후 같은 토큰으로 재조회 → `vote.voted == true`, `vote.selectedOptionId` 일치, 두 선택지 모두 `percentage` 존재하고 합이 100±1 | `PostDetailIT.votedUserSeesPercentage` · `PostDetailIT.votedUserSeesProductIdAndPercentageOnAbPost`(A/B) |
| 아직 투표하지 않은 사용자는 득표율이 노출되지 않음 | 미투표 회원·게스트 각각 조회 → `voteCount`·`percentage` 키가 **둘 다** `doesNotExist()`. 역산 방지를 위해 둘 다 본다 | `PostDetailIT.unvotedUserSeesNoTally` · `PostDetailIT.guestSeesNoTally` · `PostDetailWithdrawnUserIT.withdrawnUserTokenIsDemotedToGuestResponse`(탈퇴자) |
| 삭제된 게시글 조회 시 404 | 소프트 삭제 후 조회 → HTTP 404, `code == "NOT_FOUND"` | `PostDetailIT.deletedPostReturns404` · `PostDetailIT.missingPostReturns404` · `PostDetailIT.deletedPostWithVoteHistoryStillReturns404` |
| 조회 시 N+1 쿼리가 발생하지 않음 | Hibernate `Statistics.getPrepareStatementCount()` 로 실행 문장 수를 세고, **상품 수(1↔2)와 사진 수(1↔3)를 바꿔도 값이 변하지 않음**을 확인. 절대값을 PR 에 병기 | `PostDetailIT.queryCountIsFlat` · `PostDetailIT.viewerVoteAddsNoQuery` · `PostDetailIT.queryCountIsFlatForAuthenticatedViewer` |

> 쿼리 **횟수**만으로는 부족하다는 것을 이 저장소가 이미 실측했다
> (`PostListRepository` javadoc: 조인 순서만 바꿔 454ms → 0.23ms, 둘 다 statement 1개).
> 횟수를 고정하는 것은 팬아웃이 없다는 증거일 뿐이므로, 절대 실행 시간도 함께 기록한다.

### 실측값

| 항목 | 값 |
|---|---|
| 문장 수 (게스트) | **3** — 본문+작성자+내 투표 / 상품+사진 / 선택지 |
| 문장 수 (일반 게시글) | **1** — 상품·선택지 조회를 건너뛴다 |
| 문장 수 (인증) | **4** — 위 3 + 탈퇴 신원 강등 관문의 계정 상태 확인(ADR-0035). 요청당 상수 |
| 사진 1장 → 3장 | 3 → **3** (불변) |
| 상품 1개 → 2개 | 3 → **3** (불변) |
| `EXPLAIN ANALYZE` 본문 | `Rows fetched before execution` (PK 상수 폴딩) |
| `EXPLAIN ANALYZE` 상품 | `Index lookup on pp using uk_product_post_order` · 0.016ms |
| `EXPLAIN ANALYZE` 사진 서브쿼리 | `Index lookup on ir using idx_resource_container` · 0.025ms |
| `EXPLAIN ANALYZE` 선택지 | `Index lookup on po using uk_option_post_order` · 0.008ms |
| filesort · 풀스캔 | **0건** |

### 문서 표면 실측 (스펙 JSON 직접 계수)

| 항목 | 결과 |
|---|---|
| `components.securitySchemes` | `bearerAuth` 존재 |
| 인증 / 공개 개수 | 21 / 12 — `PUBLIC_ENDPOINTS` 와 1:1 일치 |
| `/posts/{id}` 의 `security` | **부재** (게스트 허용이므로 정상) |
| 태그 `*-controller` 잔재 | **0건** |
| 응답 DTO 필드 설명 결손 | **0건** (4개 DTO 33필드) |

### 문서 표면 판정 (openapi-documentation 룰)

| 판정 | 검증 방법 |
|---|---|
| 게스트 허용이므로 `@SecurityRequirement` 가 없다 | 스펙 JSON 에서 `paths./posts/{id}.get.security` 부재 확인 |
| ArchUnit 두 번째 정본과 `SecurityConfig` 가 일치한다 | `ArchitectureTest` 통과 (`PUBLIC_ENDPOINTS` 에 `"GET /posts/{id}"` 등록) |
| 태그에 `*-controller` 잔재가 없다 | 스펙 JSON 태그 목록 확인 |
| 응답 DTO 전 필드에 `description` 이 있다 | 스펙 JSON 스키마에서 결손 필드 수 0 을 계수 |

## 열린 질문

- **게스트 게이지.** §6.3 은 게스트에게 3표를 허용하지만 R-11 로 서버에 남지 않는다.
  기본값은 "게스트에게 집계를 주지 않는다" 로 두고, 기획이 게스트 게이지를 요구하면
  별도 사이클에서 다룬다 (ADR-0041).
- ~~**`authorRanking` 의 null 표현.**~~ **해소됨** — 목록에 맞춰 `null` 을 그대로 싣는다.
  `PostDetailIT.authorRankingIsNullWhenNotYetComputed` 가 키는 있고 값이 null 임을 실측했다.
- **작성자 등급 명칭.** `Grade.displayName()` 은 `"LV.2"` 형태의 레벨 표기라
  ADR-0031 이 뱃지 이름에서 경계한 "마케팅 명칭 변경 시 배포 동반" 위험이 없다.
  `authorGradeLevel` 과 함께 실어도 안전하다고 판단했다.
