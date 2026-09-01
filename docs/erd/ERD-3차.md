# Pickple ERD 3차 — 구현으로 검증된 스키마

**상태**: 3차 · 구현 반영 · **대상 DBMS**: MySQL 8.4 · **작성일**: 2026-09-01

[ERD 2차](./ERD-2차.md)를 실제 코드로 구현하면서 드러난 것을 반영한 문서다.
**스키마 DDL 자체는 2차에서 바뀌지 않았다**(`V3__pickple_domain.sql` 그대로).
바뀐 것은 *그 스키마를 어떻게 쓰는가* — 타입 매핑, 집계 갱신 경로, 제약과 코드의 역할 분담이다.

2차가 "제약을 어떻게 걸 것인가"였다면, 3차는 **"그 제약 위에 코드를 올렸더니 무엇이 틀렸나"**다.

---

## 1. 구현이 반증한 것

문서상 옳아 보였는데 실제로 돌려보니 틀린 것 넷. 전부 **통합 테스트가 잡았고**, 단위 테스트나 컴파일로는 드러나지 않았다.

| # | 2차의 서술 | 실제 | 잡은 방법 |
|---|---|---|---|
| 1 | `INT UNSIGNED` 카운터 | 엔티티를 `Long`으로 매핑하면 **기동 실패** | `ddl-auto: validate` |
| 2 | `TINYINT display_order` | `Integer` 매핑도 **기동 실패** | `ddl-auto: validate` |
| 3 | `ON DUPLICATE KEY UPDATE`로 첫 댓글 판정 | 영향 행 수를 **신뢰할 수 없다** | 통합 테스트 |
| 4 | 카운터를 엔티티가 들고 있으면 됨 | 일반 UPDATE가 **원자 증가를 덮어쓴다** | 이종 리뷰 + 회귀 테스트 |

### 1.1 타입은 정확히 맞아야 한다

`ddl-auto: validate`는 컬럼 타입을 대조한다. 아래는 **컴파일도 통과하고 단위 테스트도 통과**하지만 기동에서 터진다.

| 컬럼 | DDL | 엔티티 | 비고 |
|---|---|---|---|
| `post.vote_count` 외 카운터 3종 | `INT UNSIGNED` | `Integer` | `Long` 이면 `wrong column type ... expecting [bigint]` |
| `post_product.price` | `INT UNSIGNED` | `Integer` | 도메인은 `Long`, 인프라에서 변환 |
| `display_order` (상품·선택지) | `TINYINT` | `Byte` | `Integer` 면 `found [tinyint], expecting [integer]` |
| `item_resource.size` | `BIGINT` | `Long` | 이건 원래 맞음 |

> `price`는 도메인 `Long`(상한 999,999,999) ↔ 인프라 `Integer`로 갈린다.
> 지금은 `Integer.MAX_VALUE` 안이라 안전하지만, 상한을 올리면 조용히 오버플로한다.
> 올릴 일이 생기면 컬럼을 `BIGINT`로 함께 바꾼다.

### 1.2 생성 컬럼은 매핑하지 않는다

`popularity_score`·`container_type`은 `GENERATED ALWAYS AS ... STORED`다. 엔티티에 매핑하면
하이버네이트가 INSERT/UPDATE에 포함시켜 `ERROR 3105`가 난다.

**매핑하지 않아도 제약은 정상 작동한다.** 이종 리뷰가 확인해준 지점이다 —
MySQL이 행을 만들 때 `container_type`을 계산한 뒤 복합 FK를 검사하므로,
댓글용 컨테이너를 상품에 붙이면 그대로 거부된다.

대신 대가가 있다. `popularity_score`가 엔티티 속성이 아니므로
**JPQL·`Sort.by("popularityScore")`로 정렬할 수 없다.** 인기순 조회는 네이티브 쿼리를 쓰거나,
읽기 전용(`insertable=false, updatable=false`) 속성으로 따로 매핑해야 한다.
→ [§4 미결 1번](#4-미결).

### 1.3 첫 댓글 판정은 `INSERT IGNORE`다

2차는 `ON DUPLICATE KEY UPDATE post_id = post_id`의 영향 행 수로 첫 댓글을 가르자고 했다.
**JPA 경로에서 이게 깨진다** — 값이 그대로라 MySQL이 "변경 없음"으로 볼지 "갱신함"으로 볼지가
드라이버·설정에 따라 갈린다. 통합 테스트에서 두 번째 호출이 "첫 댓글"로 판정됐다.

```sql
-- 삽입되면 1, 유니크로 걸리면 0. 해석의 여지가 없다.
INSERT IGNORE INTO post_commenter (post_id, user_id, created_at)
VALUES (:postId, :userId, NOW());
```

### 1.4 카운터는 읽기 전용으로 분리한다

가장 무거운 발견이다. 카운터를 보통 컬럼으로 매핑하면 **게시글 제목 수정 한 번이
그 사이 증가한 투표 수를 되돌린다.** 하이버네이트의 일반 UPDATE가 SET 절에
카운터 컬럼을 포함하고, 그 값은 트랜잭션이 시작할 때 읽은 오래된 스냅샷이기 때문이다.

```java
// 엔티티: 읽기만 한다
@Column(name = "vote_count", nullable = false, insertable = false, updatable = false)
private Integer voteCount;
```

```sql
-- 증가는 전용 경로로. DB가 원자적으로 올린다
UPDATE post SET vote_count = vote_count + 1 WHERE id = :postId;

-- 감소는 언더플로를 막는다. INT UNSIGNED 는 0-1 에서 ERROR 1690 이다
UPDATE post SET comment_count = GREATEST(CAST(comment_count AS SIGNED) - 1, 0) WHERE id = :postId;
```

Java에서 읽고 더해 쓰면(read-modify-write) 두 요청이 같은 값을 읽고 각자 +1 해서 하나가 사라진다.

---

## 2. 제약을 어디가 지키는가

2차는 "무엇을 막을 것인가"를 정했고, 구현하면서 **"어디가 막을 것인가"**를 갈라야 했다.
기준은 **판정에 필요한 정보의 범위**다.

| 규칙 | 지키는 곳 | 이유 |
|---|---|---|
| R-01 유형 불변 | 도메인 (`Post`에 변경 수단 없음) | 바꿀 방법을 두지 않는 것이 규칙이다 |
| R-02 상품 수 0/1/2 | 도메인 (`Post.verifyPublishable()`) | 행 개수는 `CHECK` 범위 밖. 애그리거트 안에서 셀 수 있다 |
| R-03 사진 장수 | 도메인 (`ItemContainer.verifyPhotoCount()`) | 컨테이너 하나로 판정 |
| R-04 선택지 2개 | 도메인 (`Post.verifyPublishable()`) | R-02와 같은 이유 |
| R-07 자기 댓글 픽 금지 | 도메인 (`Comment.pick()`) | 댓글 하나만 알면 판정 |
| R-09 중복 투표 | **스키마** `UNIQUE(post_id, user_id)` | 동시 요청. 확인-후-삽입은 뚫린다 |
| R-10 교차 게시글 투표 | **스키마** 복합 FK | 두 테이블에 걸친 소유권 |
| R-13 중복 지급 | **스키마** `UNIQUE(comment_pick_id, reason)` | 동시성 + 멱등 |
| R-25 댓글 인원 | **스키마** `UNIQUE(post_id, user_id)` + `INSERT IGNORE` | 동시 댓글 |
| R-26 중복 픽 | **스키마** `UNIQUE(user_id, comment_id)` | 동시 픽 |

**갈림의 기준**: 애그리거트 하나로 판정되면 도메인, 동시성이 있거나 여러 행/테이블을 봐야 하면 스키마.
"확인한 뒤 삽입"하는 형태가 나오면 그건 거의 항상 스키마가 해야 할 일이다.

### 2.1 저장 경로가 마지막 관문이다

도메인이 검증 메서드를 갖고 있어도 **호출을 잊으면 소용없다.**
실측했더니 찬반 게시글에 상품 2개, 선택지 0개인 행이 그대로 들어갔다.

```java
// JpaPostStore.save()
public Post save(Post post) {
    post.verifyPublishable();   // 개수 제약은 CHECK 로 표현할 수 없다
    ...
}
```

`CHECK`가 다른 행을 셀 수 없으므로 스키마는 R-02·R-04를 막지 못한다.
검증 호출이 유일한 방어선이라면 그 호출을 **저장 경로에 붙박아야** 한다.

### 2.2 정책 판단은 저장소가 하지 않는다 — 미해결

현재 `JpaOnePickStore`·`JpaPointHistoryStore`가 유니크 위반을 잡아
`DuplicatePickException`·`DuplicateGrantException`을 던진다.
**이건 경계 위반이다.** "이미 지급됐다"는 정책 해석이고, 저장소는 그 판단을 할 위치가 아니다.

올바른 형태는 이렇다.
- 저장소: `boolean saveIfAbsent(...)` — 삽입됐는지만 알린다
- 단일 도메인 규칙: 도메인 객체가 판정 (R-07처럼)
- **여러 도메인에 걸친 규칙: 서비스** — R-12(원픽 1건 → 두 사람 지급),
  R-13(재지급 금지)은 댓글·원픽·포인트 세 도메인이 얽힌다

서비스 계층이 이번 범위 밖이라 지금은 저장소에 남아 있다. [§4 미결 2번](#4-미결).

---

## 3. 삭제 경로 — 2차에서 이월

2차 §6.1에서 확인한 결함 둘이 그대로다. 스키마 변경이 필요해 구현 범위 밖으로 뒀다.

| 시나리오 | 실측 | 상태 |
|---|---|---|
| `comment_pick`이 있는 게시글 물리 삭제 | `1451` 거부 | 미해결 |
| 게시글 삭제 후 `item_container` 고아 잔존 | 6개 중 2개 남음 | 미해결 |

소프트 삭제가 기본이라 운영에서 당장 터지지는 않지만,
CASCADE를 "물리 삭제 시의 안전망"으로 둔 의도와는 어긋난다.

`item_container`의 고아는 **부착 방향의 구조적 대가**다. 컨테이너가 부모라
자식(상품·댓글)이 사라져도 남는다. DB CASCADE로 풀 수 없고 정리 배치가 필요하다.
구현에서도 이 대가가 드러났다 — 고아 조회 쿼리가 부착 테이블을 전부 알아야 해서,
`ItemContainerStore.findOrphans()`를 넣지 못하고 뺐다.

---

## 3.1 적대적 리뷰가 잡은 것 (2026-09-01)

이종 모델(Codex) 적대적 리뷰에서 **No-ship 2건 포함 3건**이 나왔고 전부 실측으로 재현됐다.

| 지적 | 실측 | 조치 |
|---|---|---|
| 투표가 달린 게시글은 **제목조차 수정 불가** | FK 위반으로 실패 | `syncChildren()` 이 신규 자식만 추가하도록 |
| 동시 요청에서 예외 계약이 깨짐 | 타이밍에 따라 다른 예외 | 서비스가 유니크 위반도 같은 정책 예외로 통일 |
| **삭제된 게시글에 투표·댓글 가능** | 그대로 저장됨 | `ActivePostGuard` 로 세 서비스가 같은 관문 통과 |

첫 번째는 **앞선 리뷰 수정이 만든 새 결함**이다. "추가한 자식이 유실된다"는 지적을 받고
매 수정마다 컬렉션을 비우고 재생성하게 했는데, `vote` 가 선택지를 FK 로 참조하고 있어
(CASCADE 없음) 삭제가 막혔다. 그때 회귀 테스트는 일반 게시글(선택지 0개)로만 확인해 통과했다.

세 번째는 소프트 삭제의 구조적 대가다. FK 는 `post` 행의 **존재**만 보장하고 `deleted_at` 은 보지 않는다.
행이 남아 있으므로 스키마로는 막을 수 없고, 서비스가 확인해야 한다.

> `ActivePostGuard` 를 처음에 `post.domain` 에 뒀다가 **ArchUnit 이 잡았다** —
> `PostStore` 에 의존하므로 도메인이 아니라 서비스다. 일반화해둔 규칙이 실제로 작동했다.

---

## 4. 미결

| # | 항목 | 상태 | 다음 행동 |
|---|---|---|---|
| 1 | 인기순 정렬 | `popularity_score`가 엔티티 속성이 아니라 JPQL 정렬 불가 | 조회 구현 시 네이티브 쿼리 또는 읽기 전용 속성 추가 |
| 2 | 정책 판단 위치 | 중복 픽·재지급 판정이 저장소에 있다 | 서비스 계층에서 R-12·R-13 조립, 저장소는 `boolean`으로 되돌린다 |
| 3 | 고아 컨테이너 | 정리 배치 없음 | 부착 테이블이 다 생겼으므로 `findOrphans()` 추가 가능 |
| 4 | 게시글 물리 삭제 | `comment_pick` FK가 막음 | `ON DELETE CASCADE`를 줄지 정한다 |
| 5 | 원픽 취소 (R-06) | 미정 | 허용하면 포인트는 음수 보상 행. 그때 멱등키를 다시 봐야 한다 |
| 6 | A/B 선택지 생성 순서 | 상품 id가 저장 후에야 생겨 2단계 저장 필요 | 서비스에서 한 트랜잭션으로 묶는다 |
| 9 | 기존 상품·선택지 **수정·삭제** | 지원하지 않음 — 신규 추가만 반영 | 투표 시작 후 상품 변경 정책이 미정(도메인 모델 "아직 결정하지 않은 것") |
| 10 | 삭제-상호작용 경합 | `ActivePostGuard` 확인 후 삭제가 끼어들면 통과 | 창이 좁고 삭제가 드물어 감수. 필요하면 잠금 또는 조건부 쓰기 |
| 7 | `@Version` 부재 | 동시 편집에서 lost update | 편집 빈도를 보고 판단 |
| 8 | 화면설계서 COM_03 | R-05·R-08 폐기의 근거 재확인 필요 | 원본 4장 중 2장 미확인 |

---

## 5. 검증

**스키마** — clean `mysql:8.4.11`에 V1→V3 적용, 위반 주입 35건 전부 의도대로 동작(2차 §6).
3차에서 추가로 확인한 것: 테이블 12, FK 20, UNIQUE 17, CHECK 8, 생성 컬럼 4, 복합 FK 5.

**코드** — 도메인 단위 테스트 + Testcontainers 통합 테스트. 불변식은 **위반을 주입해** 확인한다.

| 대상 | 확인한 것 |
|---|---|
| `ItemContainerTest` | 빈 컨테이너, 4장째, 용도 불일치 거부 |
| `PostTest` | 유형별 상품 수·선택지 수·사진 장수, 유형 불변 |
| `PostStoreGuardIT` | 검증 없이 저장해도 막히는지, 카운터가 덮어써지는지, 언더플로 |
| `VoteTest`·`JpaVoteStoreIT` | 게스트 거부, 중복 투표, 교차 게시글, 재투표가 인원을 안 늘림 |
| `CommentTest`·`JpaOnePickStoreIT` | 자기 댓글 픽 거부, 여러 사람 픽, 첫 댓글 판정 |
| `JpaPointHistoryStoreIT` | 멱등키 `(원픽, 사유)`, 원장 합계 |

**ArchUnit** — 도메인 순수성 규칙을 `..domain..` 전체로 일반화했다.
새 도메인에 `@Entity`·Lombok·infra 참조·`Double`을 주입해 각각 실패하는 것을 확인한 뒤 되돌렸다.
일반화 전에는 `..auth.domain..`만 봐서 새 도메인이 무방비였다.

---

## 6. 참고

- [ERD 2차](./ERD-2차.md) — 제약의 도출 근거와 위반 주입 35건
- [ERD 초안](./ERD-초안.md) — 전체 범위 설계
- [ADR-0008](../adr/0008-domain-entity-separation.md) — 도메인과 JPA 엔티티 분리
- [ADR-0018](../adr/0018-onepick-as-behavior.md) — 원픽을 행위로 모델링
- [도메인 모델](../domain/도메인%20모델.md) — R-01~R-26
