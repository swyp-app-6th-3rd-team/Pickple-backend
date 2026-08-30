# ADR-0015 — Apple은 네이티브 credential을 서버에서 검증하고 서비스 JWT를 발급한다

**상태**: Accepted

## 맥락

iOS의 Sign in with Apple은 기존 Google·Kakao·Naver 브라우저 리다이렉트와 다르다.
앱이 `authorizationCode`, `identityToken`, nonce를 받은 뒤 백엔드가 Apple 서버와 통신해
신원을 다시 검증해야 한다. 키를 주입하지 않는 로컬·테스트 환경도 있으므로 서버는 Apple 로그인을
비활성화한 상태로 정상 기동할 수 있어야 한다.

이 ADR은 [ADR-0006](0006-auth-hardening.md)의 `Apple 로그인을 지원하지 않는다`는 범위 결정을 대체한다.

## 결정

- `POST /api/auth/apple`은 `authorizationCode`, `identityToken`, `rawNonce`, 선택적 `name`을 받는다.
- iOS는 로그인마다 암호학적으로 안전한 새 `rawNonce`를 만들고 재사용하지 않는다.
  Apple 요청에는 `lowercase hex SHA-256(rawNonce)`를 넣는다. 백엔드는 같은 인코딩을 만든 뒤
  ID token의 `nonce`와 상수 시간 비교한다. 백엔드는 nonce를 발급하거나 사용 여부를 저장하지 않는다.
  동일 credential 묶음의 재전송 방어는 authorization code의 일회성 교환에 의존한다. 따라서 묶음을
  탈취한 공격자가 정상 앱보다 먼저 code를 교환하는 선점 공격은 현재 범위에서 방어하지 않는다.
  이를 막으려면 서버 발급 nonce를 로그인 세션 또는 기기에 바인딩한 일회용 저장소가 필요하다.
- 백엔드는 앱이 보낸 ID token을 먼저 검증하고, code를 Apple `/auth/token`에서 교환한 뒤
  응답의 ID token도 검증한다. 두 token의 `sub`가 같아야 한다.
- 사용자 키는 이메일이 아니라 `(APPLE, sub)`다. 이메일은 검증된 ID token 값만 사용한다.
  이름은 Apple이 최초 동의 때만 앱에 줄 수 있어, 인증 성공 뒤 길이를 검증해 저장한다.
- Apple token endpoint의 `client_secret`은 `.p8` P-256 키로 만든 ES256 JWT다.
  헤더 `kid`, 클레임 `iss=Team ID`, `sub=Bundle ID`, `aud=https://appleid.apple.com`,
  `iat`, `exp`를 넣는다. 이 서비스는 요청 시 10분 유효 client secret을 생성한다.
- Apple ID token은 Apple JWKS로 **RS256만** 허용하고 `iss`, `aud`, `exp`, `nonce`, `sub`를 검증한다.
- `.p8` 파일 전체는 한 줄 Base64로 Secrets Manager에 보관한다. 원문·Apple code/token·서비스 JWT는
  설정 파일, Git, URL, 로그, 예외 메시지에 남기지 않는다.
- `app.oauth.apple.enabled=false`가 기본이다. 키가 없는 환경의 서버는 정상 기동하고
  Apple endpoint만 `503 APPLE_LOGIN_UNAVAILABLE`을 반환한다.
- 모바일은 서비스 access/refresh token을 HTTPS JSON으로 받고 iOS Keychain에 저장한다.
  `POST /api/auth/mobile/refresh`가 refresh token을 회전한다. 웹의 HttpOnly 쿠키 계약은 유지한다.
- `/auth/token`의 provider refresh token은 서비스 JWT refresh token과 분리한다. 서비스 토큰처럼
  해시만 저장하면 Apple `/auth/revoke`에 쓸 수 없으므로, 별도의 32바이트 keyring으로 AES-256-GCM
  암호화한다. 각 암호화는 새 12-byte IV를 쓰고 사용자 ID·키 ID를 AAD로 인증한다.
- 외부 Apple 검증/교환은 DB 트랜잭션 밖에서 수행하고, 성공 뒤 사용자 저장·provider token 저장·
  서비스 token 저장만 하나의 로컬 트랜잭션으로 완료한다.
- Apple code 교환으로 provider refresh token을 받은 뒤 ID token 불일치나 로컬 완료가 실패하면 해당 token을
  즉시 보상 revoke한다. 보상 revoke 실패가 원래 로그인 실패를 덮지는 않는다. 실패 횟수는
  `pickple.auth.apple.login.compensation.revoke.failures` counter로 기록하고 WARN 로그의 `correlationId`로
  요청을 추적한다. token·sub·외부 응답은 로그에 남기지 않는다.
- 인증된 `DELETE /api/auth/me`에서 Apple 사용자의 provider refresh token을 복호화해 `/auth/revoke`를
  먼저 호출한다. 성공 뒤 계정 비활성화와 두 종류의 refresh token 삭제를 원자적으로 반영한다.
  외부 장애 시 로컬 상태와 token을 보존하고 503을 반환해 재시도할 수 있게 한다. revoke 성공 뒤
  로컬 트랜잭션이 실패하면 저장 token이 남으므로 같은 탈퇴 요청이 revoke부터 다시 시도할 수 있다.
- provider token이 저장되기 전에 존재한 Apple 사용자는 로컬 탈퇴를 막지 않는다. 대신 성공 응답 코드
  `APPLE_MANUAL_REVOCATION_REQUIRED`로 Apple 계정 설정에서 Pickple 연결을 직접 해제해야 함을 알린다.

## 결과

- 테스트용 EC 키와 가짜 decoder로 키 발급 전에도 client secret, claim, nonce, 로그인 조율을 검증할 수 있다.
- 실제 `.p8` 형식, 실 Bundle ID 설정 기동, Apple JWKS와 token/revoke endpoint 연결은 확인했다.
  유효한 iOS authorization code와 provider refresh token을 이용한 로그인·revoke 종단간 검증은 남아 있다.
- 현재 refresh token 저장소는 사용자당 한 행이라 웹과 iOS에서 재로그인하면 이전 기기의 refresh token이
  무효화된다. 다중 기기 세션이 필요하면 token-family 스키마를 별도로 도입한다.
- 서비스 refresh token의 동시 회전 정책은 [ADR-0016](0016-refresh-token-rotation-cas.md)을 따른다.
- provider token 암호화·복호화, keyring 교체 호환, revoke HTTP 계약과 탈퇴 조율은 자동 테스트로 검증한다.
  실제 Apple revoke 종단간 검증에는 iOS에서 발급한 유효한 credential이 필요하다.
- 현재 탈퇴는 `users.state=INACTIVE`인 소프트 탈퇴다. 개인정보 익명화/삭제와 재가입 정책은 제품·법무
  기준을 정한 뒤 별도로 확장해야 한다.
- 회원 활성 여부의 정본은 `users.state`다. 도메인 V3에서 `deleted_at`이 추가되면 탈퇴 시각 감사값으로
  사용하고 `state=INACTIVE`와 같은 트랜잭션에서 갱신한다. 활성 닉네임 제약도 `state=ACTIVE`를 기준으로 한다.
- 보상 revoke 실패 counter와 `correlationId`는 탐지 수단이지 자동 복구 수단은 아니다. 완전한 자동 복구에는
  provider refresh token을 별도 키로 암호화한 compensation outbox와 재시도 worker가 필요하다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| Spring OAuth2 브라우저 흐름에 Apple 추가 | 네이티브 앱 credential 전달·nonce·JSON token 응답 계약과 맞지 않는다 |
| 앱이 보낸 ID token만 신뢰 | authorization code를 서버에서 교환하지 않아 credential injection 방어가 약해진다 |
| 이메일로 사용자 조회 | Apple relay/이메일 공개 선택 및 변경 때문에 안정적인 식별자가 아니다 |
| `.p8` PEM 원문을 `.env`에 저장 | 개행으로 dotenv 형식이 깨지고 비밀 노출 위험이 커진다 |
| provider refresh token을 해시 저장 | Apple revoke 요청에는 원문 token이 필요해 사용할 수 없다 |
| revoke HTTP 호출을 DB 트랜잭션 안에서 수행 | 외부 지연 동안 DB transaction/connection을 점유한다 |
| Apple 장애여도 먼저 로컬 token 삭제 | revoke 재시도에 필요한 유일한 credential을 잃는다 |

운영 적용·키 교체·iOS 요청 계약은 [Apple 로그인 Runbook](../apple-sign-in-runbook.md)을 따른다.
