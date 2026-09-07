# ADR-0041 — 유도·생성 컬럼은 엔티티에 읽기 전용으로 매핑한다

**상태**: Accepted

**관련**: [ADR-0028](0028-author-ranking-precompute.md)이 `users.ranking`을 배치 사전계산으로 정하면서
"애플리케이션 어디도 이 컬럼에 쓰지 않는다"를 전제로 삼았다. 이 ADR은 그 전제를 유지하는 방법을 바꾼다.

## 맥락

배치가 채우거나 DB가 생성하는 컬럼들이 엔티티에 **매핑되지 않은 채** 남아 있다.

| 컬럼 | 성격 |
|---|---|
| `users.ranking` | 배치가 `ROW_NUMBER()`로 매긴 순위 (ADR-0028) |
| `users.point` · `users.vote_count` | 원장에서 유도한 캐시. 배치가 채운다 |
| `post.popularity_score` | DB 생성 컬럼 `GENERATED ALWAYS AS (vote_count + commenter_count) STORED` |

매핑하지 않은 이유는 두 문서에 각각 적혀 있다.

- `JpaRankingQueryStore` javadoc: "유도 컬럼을 엔티티가 들고 있으면 프로필 저장 같은 평범한
  쓰기가 배치가 계산한 값을 덮어쓴다"
- `PostEntity` javadoc: "매핑하면 하이버네이트가 쓰기를 시도해 `ERROR 3105`가 난다"

**두 걱정 모두 타당하다.** 기본 매핑이라면 실제로 그렇게 된다.

그런데 이 미매핑이 조회 계층에 비용을 지우고 있다. 정렬·조회에 이 컬럼들이 필요한데 JPQL로는
접근할 수 없으니 네이티브 SQL을 쓰게 되고, 그 결과가 `List<Object[]>`로 돌아와 컬럼 인덱스
상수로 꺼내진다. 인덱스가 밀리면 **컴파일은 통과하고 런타임에 깨진다** (이슈 #130).

## 결정

**유도·생성 컬럼을 `@Column(insertable = false, updatable = false)`로 엔티티에 매핑한다.**

```java
@Column(name = "ranking", insertable = false, updatable = false)
private Integer ranking;
```

### 왜 이것이 불변식을 깨지 않는가

지켜야 할 불변식은 "이 컬럼이 엔티티에 없다"가 아니라 **"애플리케이션이 이 컬럼에 쓰지 않는다"**이다.
`insertable = false, updatable = false`는 Hibernate가 INSERT·UPDATE 문의 컬럼 목록에서 해당 컬럼을
**아예 제외**하게 만든다. 즉 이 매핑은 불변식을 깨는 것이 아니라 **타입 수준에서 강제**한다.

기존 진단은 조건이 빠져 있었다. "매핑하면 쓰기를 시도한다"가 아니라 **"기본 매핑이면"** 쓰기를
시도한다. 선례도 이미 같은 파일에 있다 — `PostEntity`의 `voteCount`·`commenterCount`·`commentCount`가
정확히 이 방식으로 매핑돼 있다.

### 실측으로 확인한 것

`popularity_score`는 진짜 생성 컬럼(`GENERATED ALWAYS AS ... STORED`)이라 별도로 검증했다.

| 확인 | 방법 | 결과 |
|---|---|---|
| INSERT 시 `ERROR 3105` | 게시글 저장이 도는 `PostControllerIT` 전체 실행 | 발생 0건, 통과 |
| QueryDSL 정렬·조회 | `q.select(post.popularityScore).orderBy(post.popularityScore.desc())` | 성공 |

Hibernate 7 / MySQL 8.4 기준이다. `@Generated` 애노테이션은 필요하지 않았다.

## 결과

- 조회 계층이 네이티브 SQL 없이 이 컬럼들을 정렬·조회할 수 있다. `Object[]` 제거의 선행 조건이 풀린다.
- 엔티티가 이 컬럼의 값을 **읽을 수 있게** 된다. 다만 쓰기 경로는 여전히 배치(네이티브 UPDATE)뿐이다.
- 값의 **정본은 바뀌지 않는다.** `point`의 정본은 여전히 `point_history` 원장이고(R-14),
  엔티티 필드는 그 캐시를 읽는 창일 뿐이다.

### 포기한 것

**"엔티티에 없으니 쓸 수 없다"는 물리적 보장을 잃는다.** 이제 보장은 애노테이션 옵션에 있다.
누가 `insertable = true`로 바꾸면 뚫린다.

그 대가로 얻는 것이 타입 안전한 조회다. 그리고 옵션을 지우는 것은 **의도적인 한 줄 변경**이라
리뷰에서 보이지만, 컬럼 인덱스가 밀려 생기는 오류는 아무 데도 보이지 않는다.

**엔티티가 커진다.** `UserEntity`에 읽기 전용 필드 3개가 늘었다. 읽기 모델을 따로 두는 방법도
있으나(별도 `@Entity`를 같은 테이블에 매핑), 그건 같은 테이블에 두 엔티티가 생겨 어느 쪽을 써야
하는지가 새 판단거리가 된다. 필드 3개가 더 싸다.

## 검토한 대안과 기각 사유

| 대안 | 기각 사유 |
|---|---|
| 현행 유지(미매핑) + 네이티브 SQL | 이슈 #130이 지적한 `Object[]` 문제가 그대로 남는다. 이 ADR의 출발점이다 |
| `@Formula("vote_count + commenter_count")` | 생성 컬럼을 애플리케이션이 다시 계산하는 셈이라 정본이 둘이 된다. 인덱스(`idx_post_popular`)도 못 탄다 |
| 읽기 전용 별도 엔티티를 같은 테이블에 매핑 | 같은 테이블에 엔티티가 둘이 되어 "어느 쪽을 쓰나"가 매번 판단거리가 된다. 필드 3개보다 비싸다 |
| `@Generated(event = {})` 사용 | 실측 결과 필요 없었다. 불필요한 애노테이션은 "왜 붙었나"를 나중에 되묻게 만든다 |
