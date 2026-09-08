# PRD-025 — 게시글 수정·삭제 API

**이슈**: [#31](https://github.com/swyp-app-6th-3rd-team/Pickple-backend/issues/31) · **결정**: [#32](https://github.com/swyp-app-6th-3rd-team/Pickple-backend/issues/32) ·
**명세**: [기능명세서 v0.4](../requirement/기능명세서%20v0.4.md) §6.1 헤더 `[더보기]` 분기 · **작성**: 2026-09-08

## 무엇을 왜

작성자 본인이 자기 게시글을 **수정**(`PATCH /posts/{id}`)하고 **삭제**(`DELETE /posts/{id}`)한다.
§6.1 의 `[더보기]` 바텀시트가 "작성자 본인이면 수정/삭제, 남의 글이면 신고/차단" 으로 갈리는데
서버에 그 두 동작이 없다. 신고·차단(#34)은 다른 분기라 이 사이클에 넣지 않는다.

이 이슈는 #32("투표가 시작된 뒤 상품을 수정할 수 있는가")에 막혀 있었다. 기획이
**투표 유무와 무관하게 상품은 언제나 수정 불가, 카테고리·주제/제목·설명만 수정 가능**으로 답해
질문 자체가 닫혔다(R-33). 그 답이 요청 스키마를 결정하므로 착수 전에 확정이 필요했다.

> **기획문서 갱신본은 아직 없다.** 기능명세서 v0.4 §6.1 `[더보기]` 분기는 v0.2·v0.3 과 글자 그대로 같다.
> 이 사이클은 [#32 의 기획 담당자 코멘트](https://github.com/swyp-app-6th-3rd-team/Pickple-backend/issues/32#issuecomment-5570120403)(2026-09-07)를
> 근거로 구현한다. 갱신본이 다르게 나오면 갱신본을 따른다(#32 "다시 연다" 코멘트의 정본 위계).

## API 계약

| Method | Path | 인증 | 성공 |
|---|---|---|---|
| PATCH | `/posts/{id}` | 필요 (작성자) | `200 OK`, `{ postId, type, category, title, description }` |
| DELETE | `/posts/{id}` | 필요 (작성자) | `200 OK`, `returnObject: null` |

- 수정 요청 스키마는 **`category` · `title` · `description` 셋뿐**이다. 상품 필드와 `type` 은
  스키마에 없다 — 보내도 바인딩되지 않는다. 근거는 [ADR-0047](../adr/0047-post-update-whitelist-schema.md).
- **찬반 게시글은 `title` 을 바꿀 수 없다.** 찬반의 제목은 상품명이라(`PostService.resolveTitle`)
  상품 필드에 속한다. 보내면 `INVALID_REQUEST`(400).
- 필드가 없거나 `null` 이면 그대로 둔다. `description` 을 빈 문자열로 보내면 비운다(설명은 선택 입력이다).
- 없거나 삭제된 게시글은 `NOT_FOUND`(404). 작성자가 아니면 `FORBIDDEN`(403). 순서는 404 → 403 —
  댓글과 같다(`CommentService`).
- 삭제는 **소프트 삭제**다. `post.deleted_at` 을 찍고 행·상품·선택지·투표·댓글은 남긴다.
  기존 조회가 전부 `deleted_at IS NULL` 로 거르고, 투표·댓글·원픽은 `ActivePostGuard` 를 지난다.
- 수정·삭제는 게시글 행을 `PESSIMISTIC_WRITE` 로 잠그고 시작한다(댓글과 같은 장치). `deleted_at` 이 보통의
  갱신 가능 컬럼이라 잠그지 않으면 수정이 삭제와 경합할 때 낡은 `NULL` 을 되써 **지운 글이 되살아난다**(ADR-0047 결정 4).
- 내 활동 요약의 투표·댓글 수가 목록과 같이 삭제된 글을 뺀다. 삭제가 생기면 "12" 아래 11장이 뜨는 불일치가 드러나기 때문이다.

## 범위

**포함**

- `Post.edit` 에 삭제·찬반 제목 가드, `Post.clearDescription`(R-33). 빈 설명 정규화는 작성과 같이 서비스가 한다
- `PostStore.findByIdForUpdate`(비관적 쓰기 잠금) — 수정·삭제 경합에서 삭제가 되돌려지지 않게
- `PostService.update` · `PostService.delete` — 잠금 조회 → 활성 확인(404) → 작성자 확인(403) → 도메인 전이 → 저장
- 내 활동 요약(`ActivityQuerydslRepository.summarize`)의 투표·댓글 수에 삭제 게시글 제외
- `PATCH /posts/{id}` · `DELETE /posts/{id}` 컨트롤러와 요청·응답 DTO, `@SecurityRequirement`
- 도메인 문서(R-33)·ADR-0047·SPEC §3.3 갱신
- 단위 테스트(도메인·서비스)와 실제 MySQL 통합 테스트(API 수직 경로)

**제외**

- 상품 필드·사진·유형 수정 — 기획 확정으로 **영구히** 범위 밖 (R-33·R-01)
- 신고·차단 — `[더보기]` 의 다른 분기 (#34)
- 삭제 취소(복구) — 화면에 없다
- 삭제 시 이미지 객체(S3) 정리 — 지운 글의 이미지는 재사용하지 않기로 했으므로(#31 코멘트) 행을 남긴다
- 스키마 변경 — `deleted_at` 이 이미 있다. 마이그레이션 없음

## 완료 판정

이슈 #31 의 [확정 완료 판정 코멘트](https://github.com/swyp-app-6th-3rd-team/Pickple-backend/issues/31#issuecomment-5556538203)를
정본으로 하고, 계약에서 따라오는 항목을 더했다.

| # | 판정 | 검증 방법 | 검증 위치 |
|---|---|---|---|
| 1 | 작성자가 아닌 사용자의 수정·삭제 요청은 403 | 타인 토큰으로 PATCH·DELETE → `FORBIDDEN`, DB 값 불변 | `PostMutationIT.onlyAuthorCanEditOrDelete` |
| 2 | 수정으로 게시글 유형을 바꿀 수 없음 (R-01) | 본문에 `type` 을 넣어 PATCH → 200 이되 `type` 불변 (스키마에 없어 바인딩되지 않는다) | `PostMutationIT.ignoresTypeAndProductFieldsOutsideSchema` |
| 3 | 삭제된 게시글에 투표·댓글 시도 시 거부 | API 로 삭제 후 `POST /posts/{id}/votes`·`POST /posts/{id}/comments` → 400 (`ActivePostGuard` 경유) | `PostMutationIT.deletedPostRejectsVoteAndComment` |
| 4 | 상품 필드(상품명·가격·URL·사진) 수정 요청은 투표 유무와 무관하게 거절 | 투표 0건인 게시글에 `products[]` 를 넣어 PATCH → `post_product` 행 불변 | `PostMutationIT.ignoresTypeAndProductFieldsOutsideSchema` |
| 5 | 카테고리·주제/제목·설명은 수정된다 | 세 필드 각각 PATCH 후 `GET /posts/{id}` 재조회로 대조 | `PostMutationIT.editsCategoryTitleAndDescription` |
| 6 | 찬반 게시글의 제목(=상품명)은 바꿀 수 없다 | 찬반 게시글에 `title` PATCH → 400, 제목 불변 | `PostMutationIT.agreeTitleIsProductNameAndImmutable`, `PostTest` |
| 7 | 삭제된 글은 조회에서 사라지고 행은 남는다 | 삭제 후 `GET /posts/{id}` 404, 목록에서 부재, `post.deleted_at IS NOT NULL` | `PostMutationIT.softDeleteHidesPostButKeepsRows` |
| 8 | 삭제된 글은 다시 수정·삭제할 수 없다 | 삭제 후 PATCH·DELETE → 404 | `PostMutationIT.deletedPostCannotBeEditedOrDeletedAgain` |
| 9 | 미인증 요청은 401 | 토큰 없이 PATCH·DELETE | `PostMutationIT.rejectsUnauthenticated` |
| 10 | 문서 표면: 두 엔드포인트에 `security` 가 있고 공개 목록은 그대로 | `OpenApiSurfaceIT`·`ArchitectureTest` 의 공개 목록 대조 통과(개수 포함) | 기존 테스트 |
| 11 | 삭제와 경합한 수정이 삭제를 되돌리지 않고, 수정과 경합한 삭제가 그 수정을 잃지 않는다 | 한 트랜잭션이 잠금을 쥔 동안 실제 서비스 경로가 `innodb_trx` 에서 LOCK WAIT 로 관측된 뒤 풀어 줌 → 수정은 404·`deleted_at` 유지 / 삭제 뒤 제목은 먼저 고친 값. `@Lock` 을 지우면 두 케이스 모두 실패함을 주입으로 확인 | `PostMutationConcurrencyIT.editRacingDeleteDoesNotResurrect`, `deleteRacingEditKeepsTheEdit` |
| 12 | 내 활동 요약이 삭제된 글을 빼 목록과 일치한다 | 투표·댓글한 글을 삭제한 뒤 `GET /users/me/activities` 요약 대조 | `ActivityControllerIT` (추가) |
| 13 | 같은 제목을 다시 보내는 찬반 수정은 200 이다 (멱등) | 찬반 게시글에 현재 상품명을 `title` 로 PATCH | `PostMutationIT.agreeTitleIsProductNameAndImmutable` |
| 14 | `description: ""` 는 설명을 비운다 | PATCH 후 재조회에서 `description` 이 null | `PostMutationIT.editsCategoryTitleAndDescription` |

빌드 green·테스트 통과는 대리지표다. 위 표의 항목이 실제 MySQL Testcontainers 위에서 확인돼야 한다.

## 작업 단위

| # | 단위 | 의존 |
|---|---|---|
| 1 | 도메인 규칙 R-33 · ADR-0047 · 이 PRD | — |
| 2 | 도메인·저장소·서비스: `Post.edit` 가드·`clearDescription`, `findByIdForUpdate`, `PostService.update/delete`, 활동 요약 필터, 단위 테스트 | 1 |
| 3 | API: 요청·응답 DTO, 컨트롤러 PATCH/DELETE, `@SecurityRequirement` | 2 |
| 4 | 통합 테스트 `PostMutationIT` · `PostMutationConcurrencyIT` · 활동 요약 케이스 | 3 |
| 5 | SPEC §3.3 갱신, 리뷰·QA 반영, 이 PRD 완료 기록 | 4 |

순차다. 병렬 가능한 단위가 없어 워크트리를 나누지 않는다.

## 열린 질문

- **기획문서 갱신본.** #32 는 "갱신본에 반영 → 절 번호 인용 → 완료 판정 대조" 가 닫는 조건이다.
  이 PRD 는 코멘트를 근거로 쓴다. 갱신본이 나오면 §번호를 여기와 ADR-0047 에 인용한다.
- **삭제된 글의 하위 자원 코드.** 상세는 404 인데 댓글 목록·투표·댓글은 `ActivePostGuard` 의 400 이다. 이 사이클이 드러낸
  비대칭이며 범위 밖이다(ADR-0047 열린 질문).
- **설명 비우기.** `description: ""` 를 "비움" 으로 해석했다. 화면이 설명을 지우는 동작을 제공하지
  않는다면 이 해석은 쓰이지 않을 뿐 해롭지 않다.

## 변경 이력

| 날짜 | 변경 | 사유 |
|---|---|---|
| 2026-09-08 | 작성 | #31 착수. #32 기획 확정 코멘트 기준 |
