# ADR-0018 — 원픽을 행위로 모델링한다

**상태**: Accepted

## 맥락

`comment_pick` 테이블이 있다. 컬럼은 `(id, post_id, comment_id, user_id, created_at)`이고
`UNIQUE(user_id, comment_id)`가 걸려 있다. 이대로 클래스를 만들면 `CommentPick` 엔티티가 나온다.

그런데 화면에서 사용자가 보는 이름은 **"원픽"**이고, 이건 사용자가 하는 **동작**이다.
용어사전도 원픽의 품사를 `동사 · 명사` 둘 다로 등재해뒀다.
테이블 이름을 그대로 클래스로 옮기면 **동사가 사라지고 명사만 남는다.**
"픽한다"가 어디에도 없고 "픽 레코드"만 있는 모델이 된다.

이게 왜 문제인가. 원픽에는 지켜야 할 규칙이 있다 — **자기 댓글은 픽할 수 없다**(R-07).
`CommentPick`이 단순 데이터 홀더면 이 검증이 갈 곳이 없어 서비스 계층으로 새어나간다.
서비스가 커질수록 같은 검증이 여러 곳에 복제되고, 어느 하나가 빠져도 컴파일은 통과한다.

한편 원픽 모델 자체가 바뀌었다. 도메인 문서의 R-05·R-08은
"게시글 작성자가 베스트 댓글 하나를 채택한다"였는데,
[ERD 2차](../erd/ERD-2차.md)는 **모든 사용자가 각자 픽하는** 모델을 채택했다.
누가 픽하느냐가 열렸으므로 "누가 픽할 수 없느냐"(R-07)를 지킬 자리가 더 중요해졌다.

## 결정

**행위는 애그리거트의 메서드가 갖고, 결과는 값 객체로 표현한다.**

```java
// domain/Comment.java — 동사
public OnePick pick(Long pickerId) {
    if (pickerId == null) {
        throw new IllegalArgumentException("픽하는 사람이 필요합니다.");
    }
    if (pickerId.equals(this.authorId)) {          // R-07
        throw new IllegalArgumentException("자기 댓글은 원픽할 수 없습니다.");
    }
    return new OnePick(this.id, this.postId, pickerId);
}

// domain/OnePick.java — 명사. 행위의 결과
public record OnePick(Long commentId, Long postId, Long pickerId) { }

// infra/OnePickEntity.java — 영속화
@Entity
@Table(name = "comment_pick")
class OnePickEntity { ... }
```

클래스 이름은 **도메인 언어를 따른다**(`OnePick`). 테이블 이름은 스키마에 이미 반영된
`comment_pick`을 유지하고 `@Table`로 잇는다. 이름이 갈리는 대가보다 스키마를 바꾸는 대가가 크다.

**중복 픽은 도메인이 아니라 DB가 막는다.** `Comment.pick()`은 `UNIQUE(user_id, comment_id)`를
알지 못한다. 애그리거트를 통째로 로드해 기존 픽 목록을 확인하려면 픽이 몰릴 때 경합이 커지고,
확인과 삽입 사이의 틈에서 동시 요청이 뚫린다.
판정을 유니크 키에 맡기고 `Store`가 위반을 도메인 예외로 옮긴다.
[ERD 2차 §2.4](../erd/ERD-2차.md)의 `post_commenter` ODKU 집계와 같은 사고다.

경계는 이렇게 갈린다.

| 규칙 | 지키는 곳 | 이유 |
|---|---|---|
| R-07 자기 댓글 픽 금지 | `Comment.pick()` | 댓글 하나만 알면 판정된다 |
| R-26 같은 댓글 중복 픽 금지 | `UNIQUE(user_id, comment_id)` | 동시성이 있다. 확인-후-삽입은 뚫린다 |
| 다른 게시글 댓글 픽 금지 | 복합 FK `(comment_id, post_id)` | 두 테이블에 걸친 소유권 |

## 결과

- 원픽의 규칙을 찾으려면 `Comment.pick()` 한 곳만 보면 된다
- `OnePick`이 `record`라 불변이고, 값이 같으면 같은 것으로 취급된다
- 도메인 계층은 여전히 JPA·Lombok·Spring을 모른다(ADR-0008, ArchUnit이 강제)
- 대가: `OnePick`(도메인)과 `OnePickEntity`(인프라) 사이 변환 코드가 생긴다.
  ADR-0008이 이미 지불하기로 한 비용이고, 여기서만 새로 드는 값은 아니다
- 대가: 클래스 이름(`OnePick`)과 테이블 이름(`comment_pick`)이 다르다.
  `@Table`에 명시하고 용어사전에 대응을 적어 갈라짐을 막는다

**R-06(취소 가능 여부)은 미결로 남는다.** 취소를 허용하면 `Comment.unpick()`이 필요하고,
포인트는 원장 특성상 삭제가 아니라 음수 보상 행이어야 한다.
그때 멱등키 `(comment_pick_id, reason)`은 재픽 후 재지급을 막지 못하므로 키를 다시 봐야 한다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| `CommentPick` 엔티티를 도메인에 그대로 노출 | 행위가 사라진다. R-07 검증이 서비스로 새고, 같은 검증이 여러 곳에 복제된다 |
| 행위를 `Post` 애그리거트에 배치 | 픽 하나 하려고 게시글과 댓글 전체를 로드해야 한다. 경합이 커지고 애그리거트가 비대해진다 |
| 도메인 서비스 `OnePickService`에 배치 | 검증 하나 때문에 계층을 늘린다. `Comment` 혼자 판정할 수 있는 규칙이라 근거가 약하다 |
| 중복 픽도 도메인에서 검사 | 확인과 삽입 사이에 틈이 있어 동시 요청에서 뚫린다. 유니크 키가 원자적이다 |

## 검증

- `CommentTest` — `pick()`에 **작성자 본인 ID를 주입해** 거부되는지 확인한다.
  통과만으로는 증거가 되지 않으므로 위반을 넣어 본다
- `JpaOnePickStoreIT` — 같은 `(user, comment)`로 두 번 저장해 유니크 위반이
  도메인 예외로 바뀌는지, 다른 게시글의 댓글을 픽할 때 복합 FK가 거부하는지 확인한다
- ArchUnit — `OnePick`이 `..domain..`에 있으므로 JPA·Lombok 의존이 자동으로 금지된다
  (`ArchitectureTest`의 도메인 순수성 규칙, 2026-09-01 전체 도메인으로 일반화)

## 참고

- [ADR-0008](0008-domain-entity-separation.md) — 도메인 객체와 JPA 엔티티를 분리한다
- [ERD 2차](../erd/ERD-2차.md) — `comment_pick` 스키마와 복합 FK
- [도메인 모델](../domain/도메인%20모델.md) — R-05·R-08 폐기, R-07 유지, R-26 신설
