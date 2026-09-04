# ADR-0037 — Apple 탈퇴는 provider identity를 분리하고 재로그인은 새 회원으로 만든다

**상태**: Accepted
**관련**: [ADR-0015](0015-native-sign-in-with-apple.md)가 미뤄 둔 재가입 정책 가운데
Apple 탈퇴 후 동일 계정 재로그인 계약만 보완한다. Issue #40의 완료 조건을 복구하는
Issue #103의 결정이며, 더 넓은 탈퇴 데이터 처리 정책은 Issue #45에 남긴다.

## 맥락

Issue #40은 Apple 연결 해제 뒤에도 재로그인할 수 있어야 한다는 계약으로 완료됐다.
그러나 기존 구현은 탈퇴한 `users` 행을 `INACTIVE`로 보존하면서 Apple `sub`도
`provider_id`에 남겼다. 이후 새 Apple credential로 로그인하면 `(APPLE, sub)` 조회가
그 행을 다시 찾고, 비활성 계정이라는 이유로 403을 반환한다. 기존 행을 건너뛰어 새 행을
만들더라도 `UNIQUE(provider, provider_id)`와 충돌한다.

따라서 `Apple 로그인 → DELETE /auth/me → 새 credential로 같은 Apple 계정 로그인`이
실패한다. 이는 authorization code 재사용 문제가 아니라, 탈퇴 뒤에도 로컬 provider
identity가 예전 회원 행에 연결된 계약 회귀다.

## 결정

### 1. 로컬 Apple 탈퇴가 완료되면 provider identity를 분리한다

외부 `/auth/revoke`가 성공했거나 저장된 provider token이 없어 수동 해제를 안내하는 등,
**로컬 탈퇴가 완료되는 모든 Apple 경로**에서 기존 `users` 행은 다음 상태가 된다.

- `state = INACTIVE`
- `provider_id = NULL`
- 서비스 refresh token과 저장된 Apple provider token 삭제

이 변경은 로컬 탈퇴 트랜잭션 안에서 함께 처리한다. Apple 일시 장애로 `/auth/revoke`가
실패하면 기존 계약대로 503을 반환하고 로컬 탈퇴를 완료하지 않으므로 identity도 분리하지
않는다.

### 2. NULL 허용 범위는 도메인과 DB가 서로 다른 강도로 지킨다

애플리케이션 도메인에서 `provider_id = NULL`이 유효한 조합은 오직
`provider = APPLE AND state = INACTIVE`다. 활성 회원과 Apple 이외 provider의 회원은
비어 있지 않은 `provider_id`를 가져야 한다.

V12 마이그레이션은 다음과 같은 **DB 최소 보장**만 둔다.

```sql
CHECK (state <> 'ACTIVE' OR provider_id IS NOT NULL)
```

즉 DB는 활성 회원의 `provider_id` 누락을 막고, 더 좁은 "NULL은 Apple 비활성 회원만"
규칙은 도메인이 지킨다. V12는 `provider_id`를 nullable로 바꾸고 기존
`APPLE + INACTIVE` 행만 `NULL`로 백필한다. 기존 `UNIQUE(provider, provider_id)`는
유지한다. MySQL의 unique key는 여러 `NULL`을 허용하므로 분리된 과거 행들이 신규 가입을
막지 않는다.

### 3. 같은 Apple sub의 다음 로그인은 새 회원이다

활성 Apple 회원이 다시 로그인하면 종전과 같이 `(APPLE, sub)`로 같은 회원을 찾는다.
탈퇴 완료 뒤에는 과거 행의 `provider_id`가 분리되어 조회되지 않으므로 같은 `sub`의 다음
로그인은 **새 `userId`를 가진 신규 회원 생성 흐름**으로 들어간다.

과거 `users` 행과 그 행을 참조하는 게시글·댓글·투표·포인트·뱃지 등 콘텐츠와 활동은
보존한다. 다만 이 기록은 과거 `userId`에 남으며 새 회원에게 프로필, 닉네임, 포인트,
뱃지 또는 활동 이력을 승계하지 않는다.

### 4. 이번 결정의 경계를 좁게 유지한다

- Google·Kakao·Naver 등 Apple 이외 provider의 탈퇴와 재가입 동작은 바꾸지 않는다.
- 과거 행의 이메일·이름·닉네임·프로필 이미지 익명화 또는 삭제를 결정하지 않는다.
- 반복 탈퇴·재가입을 통한 포인트나 뱃지 초기화 악용, 재가입 대기 기간을 결정하지 않는다.
- stateless access token의 즉시 무효화 등 Issue #45의 나머지 정책 축을 결정하지 않는다.

## 결과 (트레이드오프)

- Issue #40의 "연결 해제 후 재로그인 가능" 계약을 복구하고 Issue #103의 403 회귀를 막는다.
- 소프트 탈퇴와 콘텐츠 보존 규칙을 유지하면서도 새 계정이 과거 이력을 소유하는 일을 막는다.
- 탈퇴와 재가입을 반복하면 `provider_id = NULL`인 Apple 비활성 행이 여러 개 남을 수 있다.
- 백필로 제거한 과거 Apple `sub`는 DB만으로 복구할 수 없다. 마이그레이션 롤백으로 원래
  identity 연결을 재구성할 수 없다는 점을 받아들인다.
- 과거 행에 남는 개인정보와 재가입 초기화 악용은 해결하지 않는다. 이 결정이 개인정보
  삭제 정책을 충족한다고 간주하지 않고 Issue #45에서 별도로 결정한다.

## 검토한 대안과 기각 사유

| 대안 | 기각 사유 |
|---|---|
| **비활성 행을 다시 활성화한다** | 신규 회원 계약과 다르고 과거 프로필·포인트·뱃지·활동, 해제된 닉네임 및 이전 token 의미를 되살린다 |
| **sub를 보존하고 활성 행에만 unique를 적용한다** | 탈퇴 회원과 Apple identity의 연결이 계속 남고, 같은 identity를 가진 여러 행 중 어느 것을 로그인 조회가 선택할지 별도 규칙이 필요하다 |
| **sub의 hash 또는 tombstone을 별도 보관한다** | provider identity에서 파생된 연결을 계속 보존하며 개인정보·재가입 제한이라는 Issue #45 범위로 결정이 넓어진다 |
| **기존 회원과 관련 행을 물리 삭제한다** | 소프트 탈퇴와 콘텐츠 보존 규칙을 깨고 다수 FK 및 과거 콘텐츠의 작성자 처리까지 함께 바꿔야 한다 |
| **모든 provider에 같은 분리 정책을 적용한다** | Issue #103은 Apple `/auth/revoke` 뒤 재로그인 계약 회귀만 다룬다. 다른 provider 정책까지 검증하지 않은 채 바꾸지 않는다 |
