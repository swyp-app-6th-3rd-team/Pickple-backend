# ADR-0045 — 게시글 목록도 키 확정과 행 조립 두 문장으로 읽는다

**상태**: Accepted

**관련**: [ADR-0043](0043-activity-list-two-statement-split.md)이 내 활동 목록에 대해 **같은 분할**(키 문장 + 행 문장 ·
한 스냅샷 · `hasNext` 는 키 문장이 정함 · 행 문장은 게시글 기본 키에서 시작)을 이미 결정했다. 이 문서는 그 결정을
반복하지 않는다 — 게시글 목록(`PostListRepository`)에 **고유한 차이**만 다룬다.
[ADR-0041](0041-read-only-mapping-for-derived-columns.md)이 `popularity_score` 의 읽기 전용 매핑으로 선행 조건을 풀었다.

## 맥락

`PostListRepository` 는 [#130](https://github.com/swyp-app-6th-3rd-team/Pickple-backend/issues/130) 이 지적한 구조의
**원본**이다. 이 파일의 javadoc 이 "왜 네이티브 SQL 인가" 를 두 가지 이유로 설명했고 나머지 조회 저장소가 그것을 복제했다.

1. `popularity_score` 는 생성 컬럼이라 매핑할 수 없다 → ADR-0041 로 해소됐다.
2. JPQL 은 튜플 비교를 지원하지 않는다 → Hibernate 7 HQL 은 지원하고, #133 이 MySQL 행 값 비교로 내려가는 것을 실측했다.

두 이유가 모두 사라진 뒤에도 이 파일은 남아 있었다. 그래서 이 파일이 갖는 문제가 가장 심하다 — 정렬 컬럼명을
`sortColumn(sort)` 로 **세 군데**에 이어붙이고, 바깥 질의에서는 `sortColumn(sort).substring(2)` 로 별칭 접두사를
잘라 다시 끼운다. `Object[]` 2곳과 `JpaPostStore` 의 컬럼 상수 13개, 드라이버 타입 방어 함수(`toLocalDateTime`·
`toCreatedAt`)가 딸려 있다.

ADR-0043 과 같은 뼈대(파생 테이블 `FROM (SELECT … LIMIT) page`)이므로 같은 방법으로 나눈다. 차이는 셋이다.

## 결정

### 1. 정렬 키가 둘이다 — 경로 객체 하나가 정렬·커서·두 문장을 관통한다

최신순은 `post.created_at`, 인기순은 `post.popularity_score` 다. `sortKey(PostSort)` 가
`ComparableExpressionBase<?>`(`DateTimePath` 또는 `NumberPath<Integer>`)를 돌려주고, **키 문장의 `ORDER BY` ·
keyset 튜플 · 행 문장의 `ORDER BY` 가 같은 객체를 쓴다.** 옛 코드가 세 군데에서 문자열을 이어붙이던 자리가 한 객체로
접힌다. 별칭 접두사를 잘라내던 `substring(2)` 는 별칭이 QueryDSL 의 것이므로 존재 이유가 없다.

**인기순 커서는 컬럼 매핑과 같은 `Integer` 로 돌린다.** 컬럼은 `INT UNSIGNED` → `Integer` 매핑이고 Hibernate 는
튜플 비교의 파라미터를 좌변 속성 타입으로 강제(coerce)한다 — SQL 에 `cast` 를 내리지 않는 대신 **Java 쪽에서
좁힌다**. 초안은 #133 처럼 `Long` 을 바인딩했고 실측 SQL 에 `cast` 가 없어 그대로 두려 했으나, 이종 리뷰가 그 강제
변환이 범위 밖 값(`INT UNSIGNED` 상한 4,294,967,295 나 조작된 커서)에서 쿼리 안의 산술 예외로 터져 400 이어야 할
요청이 500 이 되는 것을 짚었다. 그래서 `PostListCursor` 가 인기 점수를 `Integer` 로 복원하며 범위 밖은 400 으로
거르고, 프로젝션도 인기 점수는 `longValue()` 로 넓히지 않아 커서가 처음부터 끝까지 한 타입이다.
이 사실은 컴파일러가 지켜주지 못하므로(`PostListCursor.sortValue` 가 `Object`) **실행계획 테스트가 캡처한 키 문장에
`cast(` 가 없음을 고정**한다. 좌변에 `cast` 가 붙는 순간 `idx_post_popular_all` 이 범위로 접히지 않는다.
카운터의 `longValue()` 는 행 문장의 프로젝션에만 있다(ADR-0043 과 같은 규칙).

### 2. 행 문장은 작성자 조인을 품는다 — 그래도 게시글 기본 키에서 시작한다

활동 목록의 행 문장은 활동 테이블을 붙였고, 여기서는 `users` 를 붙인다. `WHERE` 가 전부 `post` 쪽이고 `users` 쪽에는
조인 조건뿐이라 옵티마이저가 회원을 진입점으로 고를 근거가 없다 — 활동 목록의 `idx_*_user_activity` 같은 그럴듯한 대안
진입점이 없다. 그래도 방향을 테스트가 고정한다. 옛 한 문장의 실측에서 바깥 질의가 실제로 `Table scan on u` 로
시작했기 때문이다(회원이 1명인 시드라 무해했지만, 그것이 PRD-024 가 예고한 함정의 모양이다).

계획 테스트는 인덱스 **이름**이 아니라 **접근 방식의 짝**을 본다 — `post` 는 `Index range scan … using PRIMARY over (id = …)`,
`users` 는 `Single-row index lookup … using PRIMARY (id=…)`, 그리고 `Table scan` 부재. 회원에서 시작하는 계획이면 두
단언이 **동시에** 뒤집히므로(회원이 스캔, 게시글이 단건 조회) 짝이 방향을 유일하게 결정한다.

작성자 표시명의 폴백 `COALESCE(NULLIF(nickname, ''), NULLIF(name, ''), '알 수 없음')` 은 **쿼리에 남긴다.**
Java 로 옮기면 저장소가 닉네임·이름 두 값을 따로 내고 `JpaPostStore` 가 뷰를 조립해야 한다 — ADR-0043 이 없앤
"행을 저장소 밖에서 조립" 구조로의 후퇴다. 비식별 표기는 개인정보처리방침 제3조가 요구하는 정책이라(ADR-0040)
정본이 하나여야 한다. 폴백 문자열은 리터럴이 아니라 파라미터로 바인딩된다.

### 3. 요청당 문장이 1 → 2 — 세 테스트가 고정하던 숫자를 성질로 바꾼다

`PostControllerIT` 두 건과 `PopularPostsIT` 한 건이 "슬라이스당 prepared statement 1개" 를 절대값으로 박아 두었다.
지키려던 성질은 "1" 이 아니라 **행 수에 비례하지 않는다**(N+1 없음)다. 세 테스트를 ADR-0043 의 선례
(`statementCountDoesNotGrowWithSliceSize`)와 같은 **이중 구조**로 고친다 — 크기가 달라도 문장 수가 같다는 것이
주 단언, 그 상수가 무엇으로 이루어졌는지(① 키 ② 행)를 열거한 부 단언. 상수를 버리지 않는 이유는 왕복이 조용히
3·4 로 느는 회귀도 실제 회귀이기 때문이다. 값이 활동 목록의 3 이 아니라 **2** 인 것은 `GET /posts` 가 게스트 허용이라
탈퇴 차단 관문 문장이 없기 때문이다.

`rankingAddsNoQuery` 는 조각 크기 변주 없이 절대값만 재고 있어 이름이 말하는 성질을 잡지 못했다. 작성자를 여럿으로
나누고 `size=1` 과 `size=N` 을 비교하는 형태로 재조준한다 — "작성자 수에 비례하지 않음" 을 재는 테스트가 된다.

### 실측 — 게시글 20,000건 · MySQL 8.4 · `EXPLAIN ANALYZE`

같은 시드에서 옛 네이티브 한 문장과 새 두 문장을 나란히 쟀다. 커서는 정렬의 한가운데(10,000번째)를 가리킨다.
문장은 손으로 베끼지 않고 `StatementInspector` 로 붙잡아 같은 값을 바인딩했다.

| 경로 | 전 (한 문장) | 후 ① 키 | 후 ② 행 | 계획 |
|---|---|---|---|---|
| 최신순 첫 조각 | 0.137 ms | 0.039 ms | 0.109 ms | `idx_post_latest_all` 11행. 인덱스 동일 |
| 최신순 둘째 조각 | 5.12 ms | 0.783 ms | 0.043 ms | 같은 인덱스, 행 값 비교는 전후 모두 `Filter`(커서 앞 10,012행) |
| 인기순 둘째 조각 | 5.45 ms | 1.39 ms | 0.091 ms | `idx_post_popular_all`. 인덱스 동일 |
| 카테고리 최신순 둘째 조각 | 1.10 ms | 0.255 ms | 0.059 ms | `idx_post_latest`(카테고리 선행). 인덱스 동일 |

```
-> Limit: 11 row(s)
   -> Filter: ((pe1_0.deleted_at is null) and ((pe1_0.created_at,pe1_0.id) < ('2026-09-07 05:24:43',10001)))
      -> Covering index lookup on pe1_0 using idx_post_latest_all (deleted_at=NULL)  rows=10012
```

**인덱스를 잃거나 `Sort:` 가 새로 생긴 경로가 없다.** 둘째 조각이 전보다 빨라진 것은 이 결정의 목표가 아니라
부산물이다 — 옛 안쪽 질의는 파생 테이블에 실을 열 컬럼을 전부 골라 커서 앞 10,012개 인덱스 항목마다 클러스터 행을
읽었고, 새 키 문장은 `id` 만 고르므로 **커버링**으로 끝난다(`Covering index lookup`). 행 문장은 네 경로 모두
`Index range scan on PRIMARY (10 ids)` 이고 대표 사진 서브쿼리는 정확히 10번 돈다. 측정 시드는 작성자가 한 명이라
행 문장의 `users` 가 1행 해시 조인으로 나왔다 — 실행계획 테스트는 작성자 20명을 심어 기본 키 단건 조회(`eq_ref`)를
고정한다.

부수 관찰 — 옛 한 문장의 바깥 질의는 파생 테이블 11행을 `Sort:` 하고 `Table scan on u` 에서 시작했다. 새 행 문장은
`post` 기본 키 범위에서 시작하고 `users` 는 기본 키 단건 조회다. 둘째 조각부터 커서 앞의 인덱스 항목을 지나쳐 읽는
Θ(커서 위치)는 옛 SQL 도 같았고 이 결정으로 바뀌지 않았다.

### 회귀 방지

`PostControllerIT.QueryPlan` 이 Hibernate 가 실제로 내보낸 SQL 을 붙잡아 `EXPLAIN FORMAT=TREE` 한다. `PopularPostsIT` 의
실행계획 검사도 손으로 베낀 SQL 대신 캡처한 문장을 쓴다. 위반 주입 결과:

| 주입 | 결과 |
|---|---|
| 정렬 튜플의 `id` 방향을 `ASC` 로 (`order()`) | **4건 실패** — 최신순·인기순·카테고리 키 문장과 `PopularPostsIT` 가 `Sort: … limit input to 11 row(s)` 를 보고 실패 |
| 행 문장을 `FROM users JOIN post` 로 뒤집기 | **실패 없음** — 옵티마이저가 그대로 게시글 기본 키 범위에서 시작했다. `FROM` 이 조인 순서를 강제하지 않는다는 위 판단의 실측이고, 이 테스트는 문장 모양이 아니라 **계획**을 고정하므로 계획이 나빠지지 않는 변경은 잡지 않는다 |

## 결과

- `PostListRepository`(158줄 네이티브 SQL 조립)가 QueryDSL 저장소로 바뀐다. `Object[]` 0건 · 컬럼 상수 0개 ·
  `.append(sortColumn` · `.formatted(` · `.substring(2)` 0건. 저장소·스토어의 드라이버 타입 방어
  `PostListRepository.toLocalDateTime`/`JpaPostStore.toCreatedAt`/`toRanking` 이 사라진다 — #130 의 중복 방어 함수 중
  마지막이었다. 커서 복원의 `PostListCursor.toLocalDateTime`(JSON 문자열 → 시각)은 다른 일을 하는 함수라 남는다.
- 요청당 SQL 이 1 → 2. 행 수에 비례하는 문장은 여전히 없다.
- 호출자가 트랜잭션을 열어야 한다는 전제가 생겼다. 격리 수준은 **가장 바깥 트랜잭션**이 정하고 Spring 은 참여
  트랜잭션의 격리를 검증하지 않으므로, `REPEATABLE_READ` 선언은 실제 경계인 `PostService.findSlice`·`findPopularTop`
  에 둔다 — `JpaPostStore` 의 같은 선언은 거기에 참여할 뿐이다(이종 리뷰가 짚었다. ADR-0043 은 이 한계를 적고
  넘어갔는데, 여기서는 선언을 경계로 올리고 저장소가 **격리 수준까지** 단언한다 — 명시되지 않았으면 MySQL 기본값
  REPEATABLE READ 로 보고, 명시됐다면 그 이상이어야 한다. `JpaPostStoreIT` 가 READ COMMITTED 로 열어 거부를 확인한다).
  인기 점수는 투표·댓글이 실시간으로 올리는 값이라 활동 시각보다 자주 바뀐다 — 스냅샷 전제는 순서뿐 아니라
  **커서 값의 정합성**(마지막 행의 점수가 키 문장이 자른 좌표계와 같다)까지 떠받친다.
- 남은 네이티브 조회 저장소(`RandomPostRepository`, #5)는 "선례가 네이티브" 라는 명분을 잃는다.

## 검토한 대안과 기각 사유

ADR-0043 이 기각한 대안(네이티브 유지 + 프로젝션만 교체 · JPQL 한 문장 · Blaze-Persistence · 정렬을 Java 에서 ·
keyset 풀어쓰기)은 같은 이유로 여기서도 기각이다. 이 파일 고유의 것만 적는다.

| 대안 | 기각 사유 |
|---|---|
| **`PostSort` 가 정렬 `Expression` 을 직접 들기** | 도메인 enum 이 infra 의 Q타입을 알게 된다. 분기는 `sortKey` 한 곳이면 충분하다 |
| **인기순 커서를 `Long` 으로 바인딩** (#133 과 같은 방식) | 초안이 택했다가 뒤집었다. 캡처한 SQL 에 `cast` 는 없지만 Hibernate 의 Java 쪽 강제 변환이 범위 밖 값에서 터진다 — 조작된 커서가 400 이 아니라 500 이 된다. 위 결정 1 |
| **닉네임 폴백을 Java 로** | 저장소 밖 조립으로의 후퇴. 정책 정본이 둘이 된다 |
| **행 문장에서 `deleted_at`·카테고리 재확인** | 두 문장 사이에 지워진 글이 조용히 빠져 조각이 짧아지고 스냅샷 전제가 깨졌다는 사실이 가려진다. ADR-0043 과 같이 키 문장에만 둔다 |
| **문장 수 단언을 없애고 성질만** | 왕복 예산이 사라진다. 상수는 부 단언으로 남긴다 |
| **`Column` 상수를 activity 처럼 QueryDSL 없이 프로젝션만 교체** | 문자열 조립이 그대로 남는다 — 이 파일의 최대 이득을 버리는 안이다 |
