# ADR-0039 — HTTP 예외 경계를 요청 오류와 내부 오류로 명시한다

**상태**: Accepted

## 맥락

`GlobalExceptionHandler`가 모든 `IllegalStateException`을 400 `INVALID_REQUEST`로 바꾸고
`WARN`으로 기록하고 있었다. 이 규칙에서는 서로 다른 두 실패가 같은 응답으로 보인다.

- 요청의 상품·선택지 구성을 고치면 해결되는 R-02·R-04 위반
- 이미 식별자가 발급된 객체를 갱신하는데 저장된 행이 없거나, 선행 검증 직후 내부 상태가
  사라진 경우

첫 번째는 호출자가 해결할 수 있지만 두 번째는 영속화 순서, 경합 또는 서버 상태를 조사해야 한다.
내부 오류를 400으로 응답하면 클라이언트가 잘못된 요청을 보냈다는 신호가 되고, 스택도 남지 않아
장애 원인을 찾기 어렵다. 반대로 `IllegalStateException`을 모두 500으로 바꾸면 올바른 R-02·R-04
계약까지 깨진다.

이 문제는 PR #76의 `PostPersistenceException` 검토에서 처음 드러났고, 이슈 #114에서
애플리케이션 전체에 적용할 경계를 정한다.

## 결정

### 1. 호출자가 해결할 수 있는 실패는 명시적인 요청 예외로 표현한다

요청 값이나 요청 대상의 상태를 바꾸면 해결할 수 있는 실패는 4xx 예외임을 타입 또는 서비스
경계의 번역으로 드러낸다.

- 도메인이 판정하는 규칙은 기능의 `domain` 패키지에 의미가 드러나는 예외를 둔다.
  도메인 예외는 `ResponseCode`를 알지 않는다.
- 서비스가 조회 결과까지 보고 판정하는 요청 실패는 서비스 경계에서 `ApiException`의 명시적인
  4xx 코드로 번역할 수 있다.
- 전역 핸들러는 확인된 도메인 예외 타입만 해당 4xx 응답으로 번역한다. 넓은 JDK 예외 타입으로
  요청 오류를 추측하지 않는다.

R-02 상품 구성과 R-04 선택지 구성 위반은 `PostNotPublishableException`으로 표현하고,
`GlobalExceptionHandler`가 400 `INVALID_REQUEST`로 번역한다. 이 두 규칙은 저장 경로가 마지막으로
검증하더라도 원인이 요청 구성에 있으므로 400 계약을 유지한다.

이미지 컨테이너의 용도 불일치는 `ItemContainerNotAttachableException`으로 표현한다.
`AttachableContainerGuard`는 이 구체 타입만 `ApiException(INVALID_REQUEST)`로 번역하고, 컨테이너를
직접 검증하는 요청 경로는 전역 핸들러가 같은 구체 타입만 400으로 번역한다.

### 2. 영속화와 선행 검증 뒤의 모순은 내부 예외로 표현한다

ID가 있는 도메인 객체를 갱신하려는데 대응하는 행이 없거나, 선행 검증이 통과한 직후 같은 내부
상태가 사라졌다면 호출자가 요청을 고쳐 해결할 수 없다.

- 저장 어댑터의 모순은 각 기능 `infra` 패키지의 `*PersistenceException`으로 표현한다.
- 서버가 조립하거나 영속 상태에서 복원한 도메인 객체의 내부 모순은 해당 기능 `domain`
  패키지의 `*ConsistencyException`으로 표현한다.
- 여러 포트 호출 사이의 모순은 해당 기능 `service` 패키지의 `*ConsistencyException`으로
  표현한다. 서비스가 인프라 예외 타입에 의존하지 않게 한다.
- 이 내부 예외들은 HTTP 코드를 갖지 않는다. 전역의 예상하지 못한 예외 처리로
  500 `SYSTEM_ERROR`가 되고, 내부 메시지는 응답에 노출하지 않는다.

현재 HTTP 요청 경로에서 확인한 분류는 다음과 같다.

| 발생 지점 | 분류 | 표현과 응답 |
|---|---|---|
| `Post.verifyPublishable()`의 R-02·R-04 | 요청 구성 오류 | `PostNotPublishableException` → 400 |
| `Post.verifyPhotoCount()`의 R-03 | 요청 구성 오류 | `PostNotPublishableException` → 400 |
| PR #76 `Post.verifyAbOptionTargets()`의 선택지-상품 참조 모순 | 서버 조립·복원 상태의 내부 일관성 오류 | `PostConsistencyException` → 500 |
| `ActivePostGuard`가 비활성 또는 없는 게시글을 거부 | 요청 대상 상태 오류 | `ApiException(INVALID_REQUEST)` → 400 |
| `OnePickService`가 삭제된 댓글을 `Comment.pick()` 전에 거부 | 요청 대상 상태 오류 | `ApiException(INVALID_REQUEST)` → 400 |
| `ItemContainer.verifyUsableAs()`가 잘못된 용도를 거부 | 요청 구성 오류 | `ItemContainerNotAttachableException` → 400 |
| `JpaVoteStore`의 갱신 대상 행 소실 | 영속화 오류 | `VotePersistenceException` → 500 |
| `JpaCommentStore`의 갱신 대상 행 소실 | 영속화 오류 | `CommentPersistenceException` → 500 |
| `JpaItemContainerStore`의 갱신 대상 행 소실 | 영속화 오류 | `ItemContainerPersistenceException` → 500 |
| `JpaUserStore`의 갱신 대상·활성 사용자 행 또는 갱신 후 재조회 소실 | 영속화 오류 | `UserPersistenceException` → 500 |
| `VoteService`의 선행 검증 뒤 또는 투표 반영 뒤 게시글 소실 | 서비스 내부 일관성 오류 | `VoteConsistencyException` → 500 |

`JpaPostStore`의 갱신 대상 행 소실과 새 게시글 상품 ID 생성 순서 위반은 아직 `develop`에
들어오지 않은 PR #76의 코드다. PR #76은 해당 지점을 `post.infra.PostPersistenceException`으로
표현한다. #114에서는 그 코드를 중복 도입하지 않고, PR #76이 합쳐질 때 이 ADR의 같은 규칙에 따라
500과 스택 로그를 유지한다.

PR #76의 현재 생성 API는 A/B 선택지가 가리킬 상품을 요청으로 받지 않는다. `PostService`가 두
선택지를 상품 표시 순서 1·2에 맞춰 생성하므로, `verifyAbOptionTargets()`의 다섯 실패는 호출자가
요청을 고쳐 해결하는 규칙 위반이 아니라 서버 조립 또는 영속 상태 복원의 모순이다. 따라서 병합
전에 이 다섯 원시 `IllegalStateException`을 모두 `post.domain.PostConsistencyException`으로
명시하고 500과 스택 로그를 유지한다. 향후 API가 선택지 타깃을 직접 입력받게 되면 요청 DTO와
서비스 경계에서 클라이언트가 제어하는 검증만 별도의 4xx로 번역하며, 내부 불변식 예외를 일괄
4xx로 바꾸지 않는다.

반면 같은 생성 요청의 R-03 사진 수는 요청에서 선택한 이미지 컨테이너로 해결할 수 있으므로
`PostNotPublishableException`을 사용해 400 계약을 유지한다. 이때 PR #76의 안전한 메시지 형식인
상품 표시 순서를 유지하고 요청 상품명은 오류 메시지나 로그에 포함하지 않는다.
`ItemContainer.verifyUsableAs()`를 직접 호출하는 경로도 전용 도메인 예외의 정확한 전역 번역으로
400을 유지한다.

남아 있는 원시 `IllegalStateException`도 다음처럼 전수 분류한다.

| 발생 지점 | 도달성과 분류 | 처리 |
|---|---|---|
| `CursorCodec.encode()`, `LlmsTxtController.readSpec()` | 요청 중 직렬화·문서 생성에 실패한 서버 내부 오류 | 예상하지 못한 예외 처리 → 500 |
| `JwtService.hash()`, `AppleIdTokenVerifier.sha256()` | 필수 JCA 알고리즘을 사용할 수 없는 런타임 내부 오류 | 예상하지 못한 예외 처리 → 500 |
| `Comment.pick/edit/delete()` | 서비스가 삭제 상태를 먼저 400 또는 404로 번역한 뒤 남는 도메인 방어선 | HTTP 경계까지 새면 번역 누락이므로 500 |
| `User.registerProfile/withdraw()` | 활성 사용자 확인과 멱등 탈퇴 분기 뒤 남는 도메인 방어선 | HTTP 경계까지 새면 번역 누락이므로 500 |
| `Post.delete()` | 현재 HTTP 호출 경로가 없는 도메인 방어선 | 호출 경로를 추가할 때 명시적으로 분류 |
| `ItemContainer.verifyPhotoCount()` | 현재 운영 호출자가 없는 도메인 방어선 | 호출 경로를 추가할 때 명시적으로 분류 |
| `DefaultProfileImages`, `AppleProviderTokenCipher`의 생성자 검증 | 빈 생성 중 설정 오류로 요청 처리 전에 실패 | HTTP 예외 경계 밖 |
| `AuthProperties`, `AppleProperties` | 애플리케이션 기동 중 설정 오류 | HTTP 예외 경계 밖 |

이 분류에서 근거 없이 남은 운영 코드 사용처는 없다. 따라서 #114에서 별도의 후속 분류 이슈는
만들지 않는다. 새 호출 경로가 위 도메인 방어선에 닿게 되면 그 작업에서 4xx 번역 또는 내부 예외
타입을 함께 정한다.

### 3. `IllegalStateException` 전역 변환을 두지 않는다

`GlobalExceptionHandler`의 `IllegalStateException` 전용 핸들러를 제거한다. 명시적으로 4xx로
분류되지 않은 `IllegalStateException`은 예상하지 못한 예외 처리로 500이 된다. 이것은 모든
`IllegalStateException`을 500으로 재분류한다는 뜻이 아니라, 요청 오류로 확정한 지점에 명시적인
타입 또는 번역을 요구한다는 뜻이다.

### 4. 로그 레벨은 HTTP 상태 계열과 맞춘다

- 4xx는 `WARN`으로 기록하고 예외 객체를 로그 인자로 넘기지 않는다.
- 5xx는 `ERROR`로 기록하고 예외 객체를 넘겨 스택을 보존한다.
- `ApiException`도 코드의 상태가 5xx이면 `ERROR`와 스택으로 기록한다. 예외 클래스만 보고 항상
  `WARN`으로 기록하지 않는다.

응답에는 기존과 같이 `ResponseCode`의 공개 메시지만 싣고, 영속화 식별자나 내부 상태 설명은
로그에서만 확인한다.

## 결과

**얻는 것**

- 클라이언트가 고쳐야 할 실패와 서버가 조사해야 할 실패를 상태 코드로 구분한다.
- 저장 행 소실과 내부 순서 위반의 스택이 남아 상관관계 ID로 원인을 추적할 수 있다.
- 새 예외 지점은 타입과 패키지만 보고 어느 계층이 분류 책임을 갖는지 알 수 있다.
- R-02·R-04의 기존 400 계약은 유지된다.
- 서버가 생성한 A/B 선택지 타깃의 모순은 요청 오류로 숨지 않고 500과 스택으로 드러난다.

**대가**

- 기능별 예외 타입이 늘어난다. 이름이 실패의 소유 계층을 드러내고 잘못된 전역 변환을 막는
  비용으로 받아들인다.
- 새 요청 오류를 추가할 때 도메인 예외와 전역 번역 또는 서비스의 명시적 번역을 함께 정해야 한다.
- 분류하지 않은 상태 오류는 안전하게 500으로 보이므로, 새 4xx 경로의 번역을 빠뜨리면 회귀
  테스트에서 발견해 보완해야 한다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 모든 `IllegalStateException`을 계속 400으로 처리 | 영속화 오류가 요청 오류로 위장되고 스택이 남지 않는다 |
| 모든 `IllegalStateException`을 500으로 처리 | 요청으로 고칠 수 있는 R-02·R-04의 400 계약을 깨뜨린다 |
| 도메인·인프라에서 모두 `ApiException`을 던진다 | 순수 도메인과 저장 어댑터가 HTTP `ResponseCode`에 결합된다 |
| 예외 메시지 문자열로 4xx와 5xx를 구분한다 | 문구 변경에 동작이 바뀌고 컴파일러가 미분류 지점을 잡지 못한다 |
| 하나의 공통 내부 예외만 사용한다 | 기능과 실패 소유 계층이 가려지고, 현재는 기능별 타입을 묶어 처리할 필요가 없다 |

## 검증

- `GlobalExceptionHandlerTest`가 실제 R-02·R-04 위반의 400/WARN, 분류되지 않은
  `IllegalStateException`과 대표 영속화 예외의 500/ERROR/스택을 함께 고정한다.
- 같은 테스트가 이미지 컨테이너 용도 불일치의 직접 호출도 400/WARN으로 유지하는지 확인한다.
- 같은 테스트가 4xx `ApiException`도 WARN만 남기고 예외 객체를 기록하지 않는지 확인한다.
- 같은 테스트가 5xx `ApiException`도 `ERROR`와 스택으로 기록하는지 확인한다.
- 각 저장소 테스트가 존재하지 않는 갱신 대상에 대해 기능별 `*PersistenceException`을 던지는지
  확인한다.
- `PostStoreGuardIT`가 R-02·R-04 검증이 저장 경로에서 빠지지 않는지 계속 확인한다.
- PR #76 병합 시 `verifyAbOptionTargets()`의 다섯 분기를 `PostConsistencyException`으로 고정하고,
  R-03은 상품 표시 순서를 사용하는 안전한 메시지와 `PostNotPublishableException` 400을 유지하는
  회귀 테스트를 추가한다.

## 참고

- 이슈 #114
- PR #76 리뷰 코멘트
- [ADR-0008](0008-domain-entity-separation.md) — 도메인과 JPA 엔티티 분리
- [ADR-0019](0019-policy-belongs-above-infrastructure.md) — 정책 판정과 저장 사실의 경계
- [ADR-0025](0025-single-log-file.md) — 로그 저장과 조사 방식
