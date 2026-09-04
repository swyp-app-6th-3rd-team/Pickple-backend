# PRD-017 — 포인트 조회와 피커 랭킹 API

**이슈**: #26 · **ADR**: [0028](../adr/0028-author-ranking-precompute.md)(선행) ·
[0032](../adr/0032-ranking-read-path.md) · **작성**: 2026-09-04

## 무엇을 왜

인기 피커 랭킹(§2.5) · 전체 피커 랭킹(§3.1) · 내 포인트와 순위(§7.3) 세 화면에
데이터를 내린다. 조회 데이터는 세 화면 모두 같다 —
**랭킹 순위 / 프로필 사진 / 닉네임 / 등급명칭 / 포인트**.
이 중 **등급명칭은 이 사이클에서 제외**한다(아래 "범위" 참조 — 정본이 이슈 #25 에 있다).

**이 작업은 새 도메인이 아니라 이미 있는 사전계산 위의 조회 계층이다.**
필요한 기반이 대부분 머지돼 있다.

| 이미 있는 것 | 위치 | 이 작업에서의 쓰임 |
|---|---|---|
| 포인트 원장 | `point_history` (V3) | R-14 의 정본. 응답 포인트의 근거 |
| 포인트 캐시 | `users.point` (V3) | 정렬 기준. 배치가 원장에서 채운다 |
| 순위 사전계산 | `users.ranking` (V7, ADR-0028) | 응답의 순위. 조회 시점에 세지 않는다 |
| 정렬 인덱스 | `idx_users_ranking (point DESC, created_at)` (V3) | 동점자 가입일 순이 인덱스에 이미 있다 |
| 배치 | `RankingBatchService` | 이 작업이 한 단계를 덧붙인다(아래) |

따라서 **주 작업은 읽기 모델 · DTO · 컨트롤러**다. 순위를 조회 시점에 계산하지 않는다 —
ADR-0028 이 200k 실측 97.6ms/조각 근거로 명시적으로 기각한 설계다.

### 착수 중 드러난 사실 1 — `users.ranking` 에 인덱스가 없다

배정표는 "스키마 변경 불필요" 였다. V7 이 컬럼을 이미 추가했기 때문이다.
그러나 V7 은 **컬럼만** 추가했고 인덱스는 없다 — 배치가 쓰기만 하던 동안에는
필요가 없었다(전체를 재계산하므로 어차피 전 행을 훑는다).

목록 조회가 이 컬럼을 정렬 키로 쓰기 시작하면 이야기가 달라진다.
회원 200,000 · MySQL 8.4 에 실제 쿼리로 `EXPLAIN ANALYZE` 를 걸었다.

| 조회 형태 | 한 조각(LIMIT 11) | 실행계획 |
|---|---|---|
| `ranking` 커서 · 인덱스 없음 | 43.5 ms / 33.1 ms(깊은 조각) | `Table scan` 200,000행 + `Sort` |
| `(point, created_at, id)` 튜플 커서 · 기존 인덱스 | **114 ms** | 인덱스를 타지만 149,456행을 훑는다 |
| `ranking` 커서 · `KEY (ranking)` | **0.070 ms / 0.045 ms** | `Index range scan` 11행 |

**인덱스가 없으면 사전 계산의 이득이 사라진다.** ADR-0028 이 97.6ms 를 근거로
조회 시점 계산을 기각했는데, 인덱스 없는 조회는 그 자리로 되돌아간다.
→ `V10__users_ranking_order_index.sql` 을 추가한다.
번호는 다른 워크트리를 확인해 배정했다 — #25 가 V8(`grade`), #27 이 V9(`badge`) 를
이미 쓰고 있어 V10 이 첫 빈 번호다. 스스로 세어 V8 을 골랐다면 런타임에서 깨졌다.

### 착수 중 드러난 사실 2 — `users.vote_count` 는 아무도 채우지 않는다

`users.vote_count` 는 V3 에 있지만(`INT UNSIGNED NOT NULL DEFAULT 0`)
**애플리케이션 어디도 이 컬럼에 쓰지 않는다.** 투표 경로 `VoteService.castFirst()` 가
부르는 `PostCounters` 는 `post`·`post_option` 만 올리고, `UserEntity` 는 이 필드를
매핑조차 하지 않는다. `UPDATE users` 문은 인증 경로와 랭킹 배치의 것뿐이다.

이는 ADR-0028 이 `users.point` 에서 발견한 것과 **같은 형태의 결손**이고,
해법도 같은 형태를 쓴다 — 정본(`vote` 테이블)에서 유도해 캐시 컬럼을 채운다.
등급 판정의 두 입력 중 하나이므로, 등급 필드를 붙일 때 이 컬럼이 채워져 있어야 한다.

### 착수 중 드러난 사실 3 — 등급 정본은 이슈 #25 에 있다

등급명칭을 내리려면 정책표 §2 를 판정하는 `Grade` 가 필요한데,
**병렬로 도는 이슈 #25 가 그것을 만들고 있다**(`app.pickple.grade.domain.Grade`,
ADR-0030). 그쪽은 `level`·`ordered()`·`next()` 에 더해 R-16(등급은 내려가지 않는다)을
`users.highest_grade` 로 명시화한 정본이다.

같은 정책표를 두 패키지에 옮겨 적으면 정본이 둘이 되고 복제본은 어긋난다 —
ADR-0030 이 기준 테이블을 기각한 것과 같은 이유다.
→ 등급명칭은 이 사이클에서 제외하고 #25 머지 후 후속 PR 로 붙인다.

## 범위

**포함**
- ADR-0032 — 랭킹 읽기 경로(정렬 키·커서·인덱스)의 결정과 실측
- `V10__users_ranking_order_index.sql` — `users.ranking` 인덱스
- `RankingStore.syncVoteCountsFromVotes()` — `vote` 정본에서 `users.vote_count` 를 채운다
- `RankingQueryStore`(domain) · `JpaRankingQueryStore`(infra) — 랭킹 읽기 모델
- `RankingQueryService` — 조각 크기 상한, 커서 복호
- `RankingController` — `GET /api/rankings/top` · `GET /api/rankings` · `GET /api/users/me/points`
- `SecurityConfig` — 랭킹 두 경로 게스트 허용
- 통합 테스트 — 완료 판정 표 전 항목 (`RankingControllerIT` 11 · `JpaRankingStoreIT` +3 · `RankingCursorTest` 4)

**제외 (하지 않는 것과 그 이유)**
- **랭킹 조회 시점 계산** — ADR-0028 이 실측으로 기각했다. 배치 주기(5분)만큼
  순위가 낡는 것은 결함이 아니라 **응답 계약의 일부**다
- **등급명칭 필드** — 판정 정본 `Grade` 를 이슈 #25 가 만들고 있다(ADR-0030).
  같은 정책표 §2 를 두 패키지에 옮겨 적지 않는다. #25 머지 후 후속 PR 에서
  그쪽 `Grade` 로 필드를 더한다 — 판정 입력인 `voteCount` 는 읽기 모델이
  지금부터 함께 읽어두므로 그 PR 은 필드 하나를 더하는 작업이 된다
- **뱃지** — 정책표 §3 의 8종은 확정됐으나 이 화면의 조회 데이터에 없다
- **본인 순위 도달 시 합쳐지는 스크롤 동작(§3.1 의 2번)** — 클라이언트 표현이다.
  서버는 `GET /api/users/me/points` 로 본인 순위를 주고, 합치는 시점은 화면이 정한다
- **"아직 TOP 피커가 존재하지 않아요" 문구** — 화면 문구다. 서버는 빈 배열을 준다
- **새 컬럼** — 컬럼은 전부 이미 있다. V10 이 더하는 것은 인덱스 하나뿐이다

## 완료 판정

이슈 #26 의 완료 판정 표를 그대로 옮기고, 검증 위치를 붙였다.

| # | 판정 | 검증 방법 | 검증 위치 |
|---|---|---|---|
| 1 | 동점자가 가입일 빠른 순으로 정렬됨 | 같은 포인트 3명의 순서 확인 | `RankingControllerIT.tieIsBrokenByRegistrationOrder` |
| 2 | 상위 피커가 정확히 5명 이하 | 사용자 10명 상황에서 응답 길이 = 5 | `RankingControllerIT.topIsCappedAtFive` |
| 3 | 조회된 포인트가 이력 합계와 일치 | `SUM(point_history)` 대조 (R-14) | `RankingControllerIT.pointMatchesLedgerSum` |
| 4 | 포인트 보유자가 없을 때 빈 배열을 정상 응답 | 200 + 빈 배열 | `RankingControllerIT.emptyTopReturnsEmptyArray` |
| 5 | 게스트 요청 시 본인 랭킹 필드가 없음 | 토큰 없이 요청한 응답 검증 | `RankingControllerIT.guestGetsNoMyRanking` |
| 6 | 정렬·페이징이 쿼리에서 이뤄짐 | 실행 쿼리 확인 | `RankingControllerIT.cursorWalksEveryRowExactlyOnce` + PR 의 `EXPLAIN` |
| 7 | `users.vote_count` 가 `vote` 정본에서 채워진다 | 투표 후 배치 → 컬럼 대조 | `JpaRankingStoreIT.voteCountsAreSyncedFromVotes` |
| 8 | 깊은 조각이 인덱스 범위 스캔이다 | 200k `EXPLAIN ANALYZE` | PR 본문 측정표 (43.5ms → 0.070ms) |

**대리지표 금지** — 빌드 green·테스트 통과만으로 완료를 선언하지 않는다.
6번은 테스트가 "조각 크기가 맞다"까지만 보장하므로, **실행계획을 PR 본문에 첨부**해
정렬·페이징이 애플리케이션이 아니라 쿼리에서 일어났음을 증거로 남긴다.

## 열린 질문

- **`vote_count` 를 배치로 채우는 것은 지연을 뜻한다.** 투표 직후 최대 5분간
  이 컬럼이 낡는다. 랭킹과 같은 계약이라 별도 지연 상한을 만들지 않았지만,
  등급 승급이 즉시 보여야 한다는 요구가 생기면 투표 경로에서 카운터를 올리는
  쪽으로 옮겨야 한다(그 경우 `PostCounters` 와 같은 원자적 증분 형태를 쓴다).
  참고로 이슈 #25 의 단건 조회는 `vote` 를 직접 세므로 이 지연의 영향을 받지 않는다 —
  두 경로가 다른 이유는 목록이 회원마다 상관 서브쿼리를 돌 수 없기 때문이다
- **미산정(`ranking IS NULL`) 회원의 목록 노출** — 현재 설계는 목록에서 제외한다.
  순위 없는 사람을 순위 목록에 넣을 자리가 없기 때문이다. 본인 조회(`/me/points`)에서는
  `null` 을 그대로 준다 — 그쪽은 "아직 산정되지 않음" 을 표현할 자리가 있다
