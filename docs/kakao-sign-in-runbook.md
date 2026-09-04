# Kakao 네이티브 로그인 Runbook

이 문서는 iOS Kakao SDK 로그인, Pickple JWT 발급, 서버 주도 연결 해제의 연동 계약을 정리한다.
실제 키·ID token·nonce·access/refresh token은 문서, Git, 로그, 이슈에 복사하지 않는다.

## 1. 키 역할

| 환경변수 | Kakao 콘솔 값 | 사용 위치 |
|---|---|---|
| `OAUTH_KAKAO_NATIVE_APP_KEY` | 플랫폼 키 > 네이티브 앱 키 | iOS SDK 초기화, 네이티브 ID token `aud` 검증 |
| `OAUTH_KAKAO_CLIENT_ID` | 플랫폼 키 > REST API 키 | 기존 브라우저 OAuth의 `client_id` |
| `OAUTH_KAKAO_CLIENT_SECRET` | REST API 키의 Client Secret | 기존 브라우저 OAuth token 교환, 백엔드 전용 |
| `OAUTH_KAKAO_ADMIN_KEY` | 플랫폼 키 > Admin 키 | 서버 회원 탈퇴의 Kakao 연결 해제, 백엔드 전용 |
| `JWT_SECRET_KEY` | Kakao 값이 아닌 Pickple 자체 키 | Pickple access/refresh JWT 서명, 기존 값을 유지 |

Admin 키는 Kakao 콘솔에서 사용 가능 API를 **카카오 로그인 연결 해제**로 제한하고, 가능하면 서버 IP도
제한한다. 네이티브 앱 키와 REST API 키를 서로 바꾸어 넣지 않는다.

## 2. 설정과 Secret 반영

- 로컬 값은 커밋하지 않는 `.env`에 둔다. 변수 이름의 정본은 `.env.example`이다.
- `.env.example`의 `@secret` 마커를 Terraform이 읽으므로 `terraform/locals.tf`에 키 목록을 다시 적지 않는다.
- EC2 앱 컨테이너에는 `docker/docker-compose-ec2.yml`의 `environment` allowlist에 선언된 값만 전달된다.
- 실제 배포 전에는 `terraform/scripts/sync-secrets.sh --check`로 스키마를 확인한 뒤, 별도 승인된 운영
  절차에서 Secrets Manager 값을 동기화한다. PR에 실제 값을 넣지 않는다.
- 네이티브 앱 키가 없으면 네이티브 로그인만 503으로 실패한다. Admin 키가 없으면 Kakao 사용자의
  연결 해제·탈퇴만 503으로 실패하며 로컬 계정은 보존된다.

## 3. Kakao 콘솔 선행 조건

1. Kakao Login을 활성화한다.
2. OpenID Connect를 활성화한다.
3. iOS 플랫폼에 실제 Bundle ID를 등록한다.
4. iOS URL Scheme과 KakaoTalk 복귀 처리를 SDK 문서대로 등록한다.
5. Pickple 가입에 필요하지 않은 `profile_nickname`, `account_email`은 **사용 안 함**으로 유지하고
   로그인 `scope`로 요청하지 않는다. `email`, `nickname` claim이 없어도 정상 로그인해야 한다.
6. Admin 키의 허용 API를 연결 해제로 제한한다.

## 4. iOS 로그인 계약

로그인 시도마다 예측하기 어려운 새 nonce를 만들고 같은 원문을 Kakao SDK와 Pickple API에 사용한다.
Apple 방식처럼 SHA-256으로 바꾸지 않는다.

```swift
let nonce = makeSecureNonce() // 16~512자, 로그인 시도마다 새 값

let completion: (OAuthToken?, Error?) -> Void = { token, error in
    guard error == nil, let identityToken = token?.idToken else {
        // OIDC 비활성화·사용자 취소·SDK 오류를 구분해 처리
        return
    }

    // HTTPS POST /auth/kakao
    // { "identityToken": identityToken, "nonce": nonce }
}

if UserApi.isKakaoTalkLoginAvailable() {
    UserApi.shared.loginWithKakaoTalk(nonce: nonce, completion: completion)
} else {
    UserApi.shared.loginWithKakaoAccount(nonce: nonce, completion: completion)
}
```

백엔드 요청:

```http
POST /auth/kakao
Content-Type: application/json

{
  "identityToken": "<Kakao OIDC ID token>",
  "nonce": "<SDK 로그인 요청에 넣은 동일 원문>"
}
```

성공 응답의 `ApiResponse.returnObject`:

| 필드 | 의미 |
|---|---|
| `accessToken` | Pickple access token. 이후 `Authorization: Bearer`로 사용 |
| `refreshToken` | Pickple refresh token. iOS Keychain에 저장 |
| `profileCompleted` | 서비스 닉네임 등록을 마쳤으면 `true`, 아니면 `false` |

`profileCompleted=false`이면 프로필 설정 화면으로 이동해 `POST /users/profile`을 호출하고,
`true`이면 홈으로 이동한다. Kakao의 선택적 `nickname` claim은 소셜 프로필 정보일 뿐
Pickple 서비스 닉네임 등록을 대신하지 않는다. 프로필 이미지 유무만으로도 완료로 판정하지 않는다.

응답에는 `Cache-Control: no-store`, `Pragma: no-cache`가 포함된다.

## 5. 백엔드 검증

- JWKS: `https://kauth.kakao.com/.well-known/jwks.json`
- 알고리즘: RS256만 허용
- `iss`: `https://kauth.kakao.com`
- `aud`: `OAUTH_KAKAO_NATIVE_APP_KEY`
- `exp`: 존재하며 만료되지 않아야 함
- `sub`: 비어 있지 않은 Kakao 사용자 ID
- `nonce`: 요청 원문과 정확히 일치
- 사용자 키: `(KAKAO, sub)`
- 선택 claim: `email`, `nickname`

`sub`, `email`, `nickname`은 저장 컬럼 길이를 넘을 수 없다. 기존 사용자의 선택 claim이 빠진 경우
기존 값을 null로 덮어쓰지 않는다.

`401 OAUTH2_FAILED`는 서명·알고리즘·claim·nonce 등 credential 자체가 유효하지 않은 경우다.
설정 누락이나 JWKS 네트워크 장애는 `503 KAKAO_LOGIN_UNAVAILABLE`로 반환하므로 클라이언트는
잠시 후 재시도할 수 있다.

## 6. 토큰 갱신·로그아웃

- Pickple access/refresh token은 Kakao token과 별개다.
- 모바일 갱신은 `POST /auth/mobile/refresh`에 Pickple refresh token을 보내고, 응답의 두 토큰을
  원자적으로 새 값으로 교체한다.
- 로그아웃은 `POST /auth/logout` 후 Kakao SDK `logout()`을 best-effort로 호출하고 Keychain의
  Pickple token을 삭제한다.

## 7. 회원 탈퇴와 Kakao 연결 해제

iOS가 Kakao `unlink()`와 Pickple 탈퇴를 따로 조율하지 않는다. 인증된 Pickple API 하나만 호출한다.

```http
DELETE /auth/me
Authorization: Bearer <Pickple access token>
```

서버 처리 순서:

1. Pickple 사용자와 `(KAKAO, providerId)`를 조회한다.
2. `Authorization: KakaoAK <Admin key>`로 Kakao `/v1/user/unlink`를 호출한다.
3. 응답 `id`가 저장된 `providerId`와 같은지 확인한다.
4. 성공 후 로컬 계정을 비활성화하고 Pickple refresh token을 삭제한다.
5. Kakao 오류 `-101`은 이미 연결이 해제된 상태로 보고 로컬 탈퇴를 계속한다.
6. Kakao 장애·Admin 키 누락·응답 ID 불일치 시
   `503 KAKAO_ACCOUNT_REVOCATION_UNAVAILABLE`을 반환하고 로컬 상태를 보존한다.

성공 후 iOS는 Pickple token을 삭제하고 Kakao SDK `logout()`을 호출해 로컬 SDK token 저장소를 정리한다.

## 8. 검증 게이트

자동 검증:

- 정상 RS256/JWKS와 잘못된 키·알고리즘
- issuer, audience, expiry, nonce, sub, profile 길이
- 신규·기존 사용자의 `profileCompleted` 분기
- 미설정 키와 JWKS 장애의 503 응답
- HTTP Interface로 선언한 Admin 키 unlink 계약, 반환 ID 확인, `-101` 멱등 처리
- provider unlink 성공 전 로컬 탈퇴 금지
- `POST /auth/kakao` 공개 허용과 OpenAPI의 공개 엔드포인트 표시
- `terraform/scripts/sync-secrets.sh --check`

실기기·배포 검증:

1. KakaoTalk 설치 기기 로그인
2. KakaoTalk 미설치 또는 계정 로그인 fallback
3. 실제 ID token `aud`가 네이티브 앱 키인지 확인하되 token 원문은 로그에 남기지 않음
4. `profile_nickname`, `account_email`을 요청하지 않은 상태의 신규·재로그인과
   `email`, `nickname` claim 누락 처리
5. `profileCompleted=false`의 프로필 설정 진입과 완료 후 재로그인 `true`
6. Pickple access token으로 `/auth/me`, refresh 회전, 로그아웃
7. 회원 탈퇴 후 Kakao 연결 상태와 로컬 계정 상태 확인
8. 배포 환경의 401·503 분기와 서버 로그에 비밀값이 없는지 확인

공식 문서: [Kakao iOS 로그인](https://developers.kakao.com/docs/ko/kakaologin/ios),
[OIDC 검증](https://developers.kakao.com/docs/ko/kakaologin/utilize),
[REST API](https://developers.kakao.com/docs/ko/kakaologin/rest-api),
[Admin 키 허용 API](https://developers.kakao.com/docs/ko/reference/admin-key-api)
