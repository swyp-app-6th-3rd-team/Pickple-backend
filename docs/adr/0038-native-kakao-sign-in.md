# ADR-0038 — Kakao 네이티브 ID token을 서버에서 검증하고 탈퇴도 서버가 조율한다

**상태**: Accepted

## 맥락

기존 Kakao 로그인은 Spring Security의 브라우저 리다이렉트와 REST API 키를 사용한다.
iOS 앱은 Kakao SDK를 네이티브 앱 키로 초기화하고 SDK가 받은 OIDC ID token으로 Pickple에
로그인해야 한다. 브라우저 흐름을 네이티브 앱에 재사용하면 redirect, token 전달, nonce 계약이 맞지 않는다.

회원 탈퇴 때 iOS가 `unlink()`를 먼저 호출하고 별도로 Pickple 탈퇴를 요청하면 두 호출 사이의 앱 종료나
네트워크 실패로 Kakao와 Pickple 상태가 갈릴 수 있다. 반대로 Pickple 탈퇴를 먼저 확정하면 Kakao 연결이
남아 서버가 복구할 credential을 잃는다.

기능명세는 기가입자는 홈, 신규 사용자는 프로필 설정으로 보내야 한다. 소셜 `nickname`은 Pickple에서
유일성을 검증해 등록하는 서비스 닉네임이 아니므로, 로그인 응답에 별도 완료 상태가 필요하다.

## 결정

- 공개 `POST /auth/kakao`는 iOS SDK의 `OAuthToken.idToken`과 로그인 요청에 사용한 원문 `nonce`를 받는다.
  `/api` 구 경로는 제공하지 않는다(ADR-0033).
- 백엔드는 Kakao JWKS로 **RS256만** 허용하고 `iss=https://kauth.kakao.com`, `aud`, `exp`, `sub`, `nonce`를
  검증한다. SDK 흐름의 `aud` 기대값은 SDK 초기화에 사용한 `OAUTH_KAKAO_NATIVE_APP_KEY`다.
- Kakao nonce는 Apple nonce와 달리 해시하지 않는다. SDK 로그인 요청과 백엔드 요청에 같은 원문을 넣고
  ID token claim과 상수 시간 비교한다.
- 사용자는 이메일이 아니라 `(KAKAO, sub)`로 식별한다. `email`, `nickname`은 서명된 선택 claim만 사용하며
  누락값으로 기존 정보를 지우지 않는다. 저장 컬럼 최대 길이를 넘는 claim은 로그인 실패로 처리한다.
- 로그인 성공 응답은 Pickple `accessToken`, `refreshToken`, `profileCompleted`를 반환한다.
  `profileCompleted`는 서비스 닉네임 등록 여부로 판정하며, Kakao `nickname`이나 프로필 이미지 유무로
  대신하지 않는다. `false`이면 프로필 설정, `true`이면 홈으로 이동한다.
- 외부 검증을 끝낸 뒤 사용자 저장과 Pickple refresh token 저장을 하나의 로컬 트랜잭션으로 완료한다.
  JWKS 네트워크 처리는 이 트랜잭션 밖에서 끝낸다.
- 설정 누락·JWKS 장애는 `503 KAKAO_LOGIN_UNAVAILABLE`, 서명·claim 실패는
  `401 OAUTH2_FAILED`로 구분한다.
- 회원 탈퇴는 기존 인증된 `DELETE /auth/me` 하나가 조율한다. Kakao 사용자는 서버가 Admin 키로
  `/v1/user/unlink`를 먼저 호출하고 반환 `id`가 저장된 `providerId`와 같은지 확인한 뒤 로컬 계정을
  비활성화하고 Pickple refresh token을 폐기한다.
- Kakao 연결 해제 HTTP 계약은 Spring HTTP Interface로 선언한다. 구성 객체는 API base URL과 연결 3초·
  읽기 5초 제한을 설정하고, Gateway가 Admin 인증값 주입, 응답 검증, 외부 오류 변환을 담당한다.
- Admin 키는 배포 환경의 Secrets Manager에 두고, 로컬에서는 Git에 포함되지 않는 `.env`만 사용한다.
  연결 해제 API로 사용 범위를 제한하며 로그인에는 필요하지 않다. 키 누락·Kakao 장애 시 탈퇴만
  `503 KAKAO_ACCOUNT_REVOCATION_UNAVAILABLE`로 막고 로컬 상태는 보존한다.
- Kakao 오류 `-101`은 이미 앱과 연결되지 않은 사용자이므로 목표 상태가 충족된 멱등 성공으로 취급한다.
- 실제 ID token, nonce, Admin 키, Kakao/Pickple access·refresh token은 로그·URL·Git에 남기지 않는다.

## 결과

- 브라우저 OAuth의 REST API 키·Client Secret과 iOS 네이티브 앱 키의 역할이 분리된다.
- 올바른 RSA 서명과 JWKS `kid` 선택, 다른 키·알고리즘 거부를 로컬 JWKS 테스트로 검증할 수 있다.
- 로그인 한 번으로 앱이 홈과 프로필 설정 화면을 안정적으로 분기하고, 소셜 닉네임과 서비스 닉네임을
  혼동하지 않는다.
- provider 연결 해제 성공 전에는 로컬 탈퇴가 확정되지 않아 재시도 credential과 사용자 상태를 보존한다.
- HTTP 메소드·경로·헤더·폼 계약과 서비스 오류 변환을 각각 독립된 테스트로 고정할 수 있다.
- 외부 unlink 성공 직후 DB 장애가 나면 다음 요청에서 `-101`을 성공으로 수렴시켜 로컬 탈퇴를 재시도한다.
- 백엔드는 nonce를 발급하거나 사용 여부를 저장하지 않는다. ID token과 nonce 묶음 전체가 탈취되면
  token 만료 전 재전송될 수 있다. 더 강한 재전송 방어가 필요하면 서버 발급 일회용 nonce 저장소를 추가한다.
- 실제 Kakao 계정·실기기·배포 Secret을 사용한 종단간 검증은 Runbook의 release gate로 남는다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 기존 브라우저 `/oauth2/authorization/kakao`를 iOS에서 사용 | 네이티브 SDK token·nonce·JSON 서비스 token 계약과 맞지 않는다 |
| ID token의 audience로 REST API 키 사용 | iOS SDK 발급 token은 SDK 초기화에 사용한 네이티브 앱 키를 audience로 쓴다 |
| 앱이 준 Kakao access token으로 사용자 정보만 조회 | OIDC 서명·issuer·audience·nonce를 직접 검증하는 신원 계약보다 약하고 로그인 API에 불필요한 bearer token이 늘어난다 |
| 소셜 `nickname` 존재 여부로 프로필 완료 판정 | 서비스 닉네임의 형식·유일성 검증을 건너뛰어 실제 가입 완료 상태와 어긋난다 |
| iOS `unlink()` 후 Pickple 탈퇴를 별도 호출 | 두 호출 사이 장애로 한쪽만 탈퇴되는 불일치가 생긴다 |
| Pickple 로컬 탈퇴 후 iOS가 `unlink()` | 앱 종료·외부 장애 시 Kakao 연결이 남고 서버에서 복구하기 어렵다 |
| Admin 키로 unlink 실패해도 로컬 탈퇴 | 외부 연결 해제를 재시도할 정합성 경계를 잃는다 |

운영 설정과 iOS 요청 계약은 [Kakao 로그인 Runbook](../kakao-sign-in-runbook.md)을 따른다.

## 관련

- [ADR-0015](0015-native-sign-in-with-apple.md) — 네이티브 credential 검증과 서비스 JWT 경계
- [ADR-0016](0016-refresh-token-rotation-cas.md) — Pickple refresh token 회전
- [ADR-0026](0026-env-example-as-secret-schema-source.md) — 비밀 스키마 정본
- [ADR-0033](0033-drop-api-prefix-implemented.md) — `/api` prefix 제거
- [ADR-0034](0034-security-requirement-on-authenticated-endpoints.md) — 공개·인증 API 문서 표시
