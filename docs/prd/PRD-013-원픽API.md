# PRD-013 — 원픽 API

**이슈**: #24 · **ADR**: [0018](../adr/0018-onepick-as-behavior.md) · [0019](../adr/0019-policy-belongs-above-infrastructure.md) · [0020](../adr/0020-onepick-uniqueness-scope.md) (신규 ADR 없음 — 기존 셋이 정본) · **작성**: 2026-09-03

## 무엇을 왜

원픽의 도메인·서비스·스키마는 이미 완성돼 있다. `OnePickService#pick` 이 R-05·R-07·R-12·R-13 을
모두 조립하고, `OnePickServiceIT`·`JpaOnePickStoreIT` 가 그것을 증명한다.
**없는 것은 HTTP 계층 하나뿐**이라 앱에서 원픽 버튼을 누를 경로가 없다.

여기까지는 "핸들러 하나 추가"로 보인다. **원인을 하나 더 파면 그렇지 않다.**

`DuplicatePickException` 은 `ApiException` 이 아니라 `RuntimeException` 을 직접 상속한다.
`GlobalExceptionHandler` 에 이 예외를 받는 자리가 없으므로, 핸들러만 붙이면
**R-05 위반이 `Exception` 포괄 핸들러로 떨어져 `500 SYSTEM_ERROR` 로 나간다.**
"이미 원픽했습니다" 라는 정상적인 정책 거부가 서버 장애로 보고되는 것이다.

이 상속 구조는 실수가 아니다. `ApiException` 은 생성자로 `ResponseCode`(HTTP status 포함)를 받는데,
도메인 패키지가 그것을 던지면 도메인이 HTTP 를 알게 되어 ArchUnit 의 도메인 순수성 규칙과 어긋난다
(ADR-0008). 따라서 **번역은 `GlobalExceptionHandler` 가 해야 한다** — 이것이 이 작업 단위의 실질이다.

## 범위

**포함**

- `CommentController` 에 `POST /api/comments/{commentId}/pick` 핸들러 추가.
  별도 `OnePickController` 를 만들지 않는다 — 이 컨트롤러가 이미 `@RequestMapping("/api")` 아래
  `/comments/{id}` 계열(PATCH·DELETE)을 소유하고 있어 경로 소유권이 갈리지 않게 한다
- `ResponseCode.ALREADY_PICKED` (409) 신설
- `GlobalExceptionHandler` 에 `DuplicatePickException` → 409 매핑
- `OnePickApiIT` — HTTP 상태코드 계약
- `OnePickConcurrencyIT` — 동시 요청에서의 행 유일성
- `docs/SPEC.md` 원픽 계약 + 변경 이력

**제외**

- **도메인·서비스·저장소·스키마 일체.** 이미 있고 정본이다. 새로 짜지 않고 그대로 재사용한다
- **원픽 취소·변경 API.** R-06 이 금지한다. `comment_pick` 에 UPDATE·DELETE 경로를 두지 않는 것이
  그 규칙의 구현이므로, 엔드포인트를 만드는 순간 규칙이 깨진다
- **원픽 목록·상세 조회 API.** 댓글 목록의 `onePickCount` 가 이미 픽 수를 제공한다(#23).
  "누가 픽했는지" 목록은 화면 요구가 없어 만들지 않는다
- **새 ADR.** 0018·0020 이 이미 정본이며 **ADR 은 불변이라 수정하지 않는다**
- **포인트 조회·잔액 API.** 지급은 이 API 의 부수효과로 일어나지만 조회 표면은 별도 범위다
- **게시글 작성자 한정 로직.** 행위자를 한정하던 R-08 은 규칙 목록에서 제외됐다.
  작성자만 픽하게 만드는 것은 **명시적 비목표**다 (ADR-0020 이 한 번 뒤집은 판정)

## 완료 판정

측정 가능한 것만 적는다. **빌드 green·테스트 통과 자체는 대리지표라 판정으로 쓰지 않는다** —
아래는 전부 "몇 행이 남았는가 / 어떤 상태코드가 나갔는가" 라는 관측값이다.

| # | 판정 | 검증 방법 | 결과 |
|---|---|---|---|
| 1 | 같은 게시글의 **다른 댓글** 두 번째 원픽 거부, `comment_pick` 행 1개 유지 (R-05) | `OnePickApiIT#secondPickOnSamePostConflicts` — 409 `ALREADY_PICKED` 후 `SELECT COUNT(*) FROM comment_pick WHERE user_id=? AND post_id=?` | ✅ 409, `COUNT=1` |
| 2 | 같은 댓글 재픽도 거부 (R-26 은 R-05 에 흡수) | `OnePickApiIT#repickingSameCommentConflicts` — 같은 쿼리 | ✅ 409, `COUNT=1` |
| 3 | **동시 원픽 요청에서도 행이 하나만** | `OnePickConcurrencyIT` — 8스레드가 `CyclicBarrier` 로 동시에 **서로 다른 댓글**을 픽. `SELECT COUNT(*) WHERE user_id=? AND post_id=?` | ✅ `COUNT=1`. 상태코드 분포 **201×1 + 409×7** |
| 4 | 자기 댓글 원픽 거부 (R-07) | `OnePickApiIT#cannotPickOwnComment` — 400 + `COUNT=0` | ✅ 400 `INVALID_REQUEST`, `COUNT=0` |
| 5 | 원픽 1회로 **포인트 이력 정확히 2행** — 작성자 +10P, 픽한 사람 +5P (R-12) | `OnePickApiIT#pickGrantsExactlyTwoRows` — `SELECT user_id, amount, reason FROM point_history WHERE comment_pick_id=?` 의 **행 수와 각 행의 값** | ✅ 2행. `(작성자, 10, PICKED)` · `(픽커, 5, PICKING)` |
| 6 | 같은 원픽에 대한 **중복 지급 0건** (R-13) | 거부된 두 번째 시도 후 `SELECT COUNT(*) FROM point_history WHERE user_id=?` | ✅ `COUNT=1` (첫 픽분만) |
| 7 | 동시 요청에서도 지급은 성공한 1건에 대해서만 | `OnePickConcurrencyIT` — 픽 전체에 딸린 `point_history` 행 수 | ✅ 2행 (두 사람 × 성공 1건) |
| 8 | 다른 게시글의 댓글 원픽 거부 (복합 FK) | `JpaOnePickStoreIT#crossPostPickRejected` — **위조한 `OnePick`** 으로 `DataIntegrityViolationException` 확인 | ✅ 기존 테스트로 이미 충족 (아래 주석 참조) |
| 9 | 미인증 요청 거부 | `OnePickApiIT#guestCannotPick` — 401 + `COUNT=0` | ✅ 401 `UNAUTHORIZED`, `COUNT=0` |
| 10 | 삭제된 댓글·삭제된 게시글은 픽 불가 | `OnePickApiIT#deletedCommentRejected`·`#deletedPostRejected` — 400 + `COUNT=0` | ✅ 각각 400, `COUNT=0` |

### 8번이 HTTP 테스트가 아닌 이유

**API 로는 이 위반을 만들 수 없다.** `POST /api/comments/{commentId}/pick` 은 `postId` 를
댓글에서 끌어오므로 어긋난 `(comment_id, post_id)` 쌍이 구성되지 않는다.
복합 FK 가 막는 것은 악의적 요청이 아니라 **`OnePick` 을 만드는 코드 경로의 버그**이고,
그래서 검증도 위조한 값 객체로 저장소를 직접 때리는 쪽이 맞다.
HTTP 테스트를 만들면 "FK 가 막았다" 가 아니라 다른 이유로 통과해 **거짓 양성**이 된다.

### 3번의 반증 (동시성 테스트가 실제로 경합을 재현했다는 증거)

통과만으로는 증거가 되지 않는다 — 8스레드가 사실상 직렬 실행돼도 테스트는 통과한다.
`JpaOnePickStore` 의 **사전 존재 확인을 제거해** DB 유니크 키만 남긴 상태로 재실행했다.

| 관측 | 사전 확인 있음(운영 경로) | 사전 확인 제거 |
|---|---|---|
| `Duplicate entry ... for key 'uk_pick_user_post'` | **7건** | **7건** |
| `원픽 중복` warn (핸들러 진입) | 7건 | 7건 |
| 최종 `comment_pick` 행 | 1 | 1 |
| 상태코드 | 201×1 + 409×7 | 201×1 + 409×7 |

**두 경우가 동일하다는 것이 핵심 결과다.** 8스레드 전부가 사전 확인을 통과한 뒤 INSERT 를 시도했고,
막은 것은 애플리케이션이 아니라 `UNIQUE(user_id, post_id)` 였다.
ADR-0020 이 "확인 후 삽입은 뚫린다" 고 한 것을 실측으로 확인한 셈이다.
(측정 후 사전 확인은 원상 복구했으며 `git diff` 0줄로 확인)

또한 ADR-0019 가 경고한 `UnexpectedRollbackException` 은 **발생하지 않았다.**
`OnePickService` 가 `DataIntegrityViolationException` 을 삼키지 않고
`DuplicatePickException` 으로 **다시 던지기** 때문에 트랜잭션이 정상 롤백된다.

## 열린 질문

1. **`DuplicatePickException` 을 `ApiException` 으로 승격할지.**
   저장소 관행은 서비스가 `ApiException(ResponseCode.X)` 를 직접 던지는 쪽이지만
   (`UserProfileService` 의 `NICKNAME_ALREADY_IN_USE`), 이 예외는 도메인 패키지에 있어
   그 방식을 쓰면 도메인이 HTTP 를 알게 된다. 지금은 핸들러 번역으로 두되,
   **"도메인 예외는 핸들러가 번역하고, 서비스 예외는 `ApiException` 을 쓴다"** 는 기준을
   문서로 고정할지는 정하지 않았다. 도메인 예외가 하나 더 생기는 시점에 판단한다.
2. **동시성 테스트의 수동 정리.** `@Transactional` 을 못 쓰므로 `@AfterEach` 가 FK 역순으로
   직접 지운다. 지금은 안전하나, 원픽에 딸리는 테이블이 늘면 정리 순서를 같이 고쳐야 한다.
   테스트 전용 정리 유틸을 둘지는 그런 테이블이 실제로 생길 때 판단한다.
3. **중복 원픽의 응답 본문.** 현재 409 의 `returnObject` 는 `null` 이다.
   "이미 무엇을 픽했는지"(기존 `commentId`)를 함께 주면 클라이언트가 화면을 즉시 맞출 수 있으나,
   화면 요구가 확인되지 않아 넣지 않았다.

## 변경 이력

- 2026-09-03 — 신규 작성 (#24 구현 시점)
