# ADR-0040 — Android Apple 로그인은 서버 웹 OAuth와 일회용 앱 handoff로 연결한다

**상태**: Accepted

## 맥락

기존 iOS API는 앱이 받은 authorization code·ID token·raw nonce를 JSON으로 서버에 보낸다.
Apple 웹 로그인은 Services ID를 사용하고 Apple이 등록된 HTTPS Return URL에 form POST로
`code`·`state`를 보낸다. 두 흐름은 client, 입력 형식과 nonce 소유자가 다르다.

`pickple://auth/callback`은 앱을 여는 custom scheme이라 Apple Return URL로 등록할 서버 HTTPS
주소를 대신할 수 없다. 서비스 JWT를 custom scheme query에 넣으면 다른 앱의 scheme 선점,
브라우저 기록과 분석 로그를 통해 노출될 수 있다.

## 결정

### 1. 세 단계 경계를 둔다

서버가 `GET /auth/apple/web`에서 state·nonce를 만들고 Apple로 보낸다. Apple의
`POST /auth/apple/web/callback`을 서버가 받은 뒤 검증이 끝나면
`pickple://auth/callback`에 60초 일회용 handoff code만 전달한다.
`POST /auth/apple/web/exchange`가 앱이 미리 보관한 verifier를 확인한 뒤 Pickple JWT를 JSON으로
반환한다.

custom scheme은 정확히 `pickple://auth/callback`으로 고정한다. 요청에서 복귀 URL을 받지 않는다.

### 2. 앱 handoff에 S256 소유 증명을 쓴다

Android 앱은 RFC 7636 형식 verifier를 만들고, 시작 요청에는
`BASE64URL(SHA-256(verifier))`만 보낸다. 이는 Pickple 앱과 백엔드 사이의 딥링크 handoff
보호이며 Apple authorize/token 요청에 PKCE 파라미터를 추가한다는 뜻이 아니다.

### 3. 시도 상태를 MySQL에 영속화한다

state 원문과 handoff code 원문은 저장하지 않는다. state는 조건부 갱신으로 한 번만 claim하고,
handoff는 비관적 잠금 안에서 verifier 확인·로그인 완료·소비를 함께 커밋한다. 인가 시도 TTL은
10분, handoff TTL은 1분, 종료 상태 보존은 1일을 초기값으로 둔다.

교환되지 않은 채 만료된 VERIFIED 행은 정리 worker가 행 잠금을 잡고 WEB client로 provider
refresh token을 revoke한 뒤 암호문을 지운다. revoke 실패 시 암호문을 유지하고 5분 이후 다시
시도한다. 외부 revoke 성공 뒤 로컬 커밋이 유실돼도 Apple revoke의 멱등성으로 수렴한다.

기존 회원이면 callback 시점 user ID를 저장하고 exchange 때 active 상태와 같은 Apple subject인지
다시 확인한다. callback 뒤 탈퇴가 끼어든 경우 옛 handoff가 새 회원을 만들지 못한다.

### 4. Apple client를 grant 수명 전체에서 보존한다

iOS Bundle ID는 NATIVE, 웹 Services ID는 WEB으로 구분한다. code 교환, ID token audience,
client secret subject, provider token 암호화·저장과 revoke가 같은 client를 사용한다.
`apple_provider_token` 키는 `(user_id, client_type)`이고 V14가 기존 행을 NATIVE로 채운다.

새 암호문 format v2는 client type을 AAD에 넣는다. 기존 format v1은 NATIVE AAD로 계속 읽어
마이그레이션 직후 탈퇴 revoke를 보존한다.

### 5. CORS는 callback 경로에만 좁힌다

Apple Origin은 정확한 callback path의 POST에만 허용하고 `allowCredentials=false`로 둔다.
Origin은 인증 근거가 아니며, Origin 없는 탐색도 서버 state·nonce·앱 proof로 검증한다.
다른 API의 기존 CORS allowlist는 넓히지 않는다.

## 결과

- Android 앱은 Apple 웹 인증 뒤 장기 자격 증명을 URL에 노출하지 않고 Pickple JWT를 받는다.
- 서버 재시작과 다중 인스턴스에서도 state와 handoff의 일회성이 유지된다.
- iOS와 웹 token을 서로의 client로 검증하거나 revoke하는 경로가 닫힌다.
- 로그인 시도용 테이블과 정리 정책, client별 provider token 행이 추가된다.
- custom scheme을 가로챈 주체도 verifier 없이는 JWT를 받을 수 없지만, Android 앱 자체의
  verifier 보관과 app state 검증은 별도 구현 책임이다.
- 사용자당 하나인 Pickple refresh token 정책은 유지되므로 다른 기기 로그인 시 기존 refresh가
  교체될 수 있다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 전역 CORS에 Apple Origin만 추가 | form/JSON, nonce, Services ID, 앱 복귀와 revoke client 차이를 해결하지 못한다 |
| 기존 `POST /auth/apple`에 웹 입력도 혼합 | iOS와 웹의 trust boundary와 검증 client가 한 계약에 섞인다 |
| 딥링크에 Pickple JWT 직접 전달 | custom scheme 선점과 URL 기록에 장기 자격 증명이 노출된다 |
| callback 성공 즉시 cookie session 사용 | Android 앱의 cookie 추출에 의존하고 cross-site form POST 상태 관리가 복잡해진다 |
| 프로세스 메모리에 시도 저장 | 재시작·다중 인스턴스에서 state가 유실되고 중복 처리가 갈린다 |
| Redis 도입 | MySQL 조건부 갱신과 row lock으로 현재 정합성 요구를 충족할 수 있다 |

## 관련

- Issue #127
- [ADR-0015](0015-native-sign-in-with-apple.md)
- [ADR-0037](0037-apple-withdrawal-detaches-provider-identity.md)
- [PRD-023](../prd/PRD-023-Android-Apple웹로그인.md)
