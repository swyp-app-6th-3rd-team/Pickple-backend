# Apple 로그인 적용·키 관리 Runbook

백엔드 구현은 키 없이도 빌드·단위 테스트할 수 있다. 실제 Apple 계정 왕복은 아래 값을 받은 뒤
`OAUTH_APPLE_ENABLED=true`로 전환해야 한다.

## 1. Apple Developer에서 받을 값

| 설정 | 넣을 값 | 주의 |
|---|---|---|
| `OAUTH_APPLE_TEAM_ID` | Apple Developer Team ID | 조직 식별자 |
| `OAUTH_APPLE_KEY_ID` | Sign in with Apple 키의 Key ID | APNs Key ID와 혼동하지 않는다 |
| `OAUTH_APPLE_CLIENT_ID` | iOS 앱 Bundle ID | 네이티브 로그인에서는 Services ID가 아니다 |
| `OAUTH_APPLE_PRIVATE_KEY_BASE64` | `AuthKey_XXXX.p8` 파일 전체의 Base64 | PEM 원문·경로가 아니다 |
| `OAUTH_APPLE_TOKEN_ENCRYPTION_KEYS` | `key-id=Base64 32-byte AES key` keyring | Apple에서 받는 값이 아니다 |
| `OAUTH_APPLE_TOKEN_ACTIVE_KEY_ID` | 새 암호화에 사용할 key ID | keyring 안에 반드시 있어야 한다 |

Apple Developer의 Identifiers에서 해당 Bundle ID에 **Sign in with Apple capability**가 켜져 있고,
Keys에서 그 App ID에 연결된 **Sign in with Apple 키**인지 확인한다. APNs 기능이 붙은 `.p8`과
Sign in with Apple 기능이 붙은 `.p8`은 같은 확장자를 쓰더라도 용도가 다르다.

`.p8`은 내려받을 때만 안전한 비밀 저장소로 옮긴다. Git, 이슈, 채팅, CI 로그, Notion에 붙이지 않는다.

### APNs 키와 혼동하지 않기

이 브랜치가 사용하는 것은 **Sign in with Apple** capability의 키다. 푸시 발송용 APNs capability와
같은 `.p8` 형식을 써도 인증 프로토콜과 설정값이 다르며, 이 코드는 APNs endpoint를 전혀 호출하지 않는다.
따라서 푸시 키의 sandbox/production 선택 문제와 Apple 로그인 code 교환 문제를 분리해서 처리한다.

참고로 APNs 환경은 Spring Boot 서버를 로컬/develop/production 어디에 띄웠는지가 아니라 앱 설치 방식과
device token이 결정한다. 일반적으로 Xcode Debug 설치는 sandbox, TestFlight·Ad Hoc·App Store 빌드는
production APNs를 사용한다. 로그인 키를 받는 요청과 푸시 키 정책 문의도 별도 항목으로 남긴다.

## 2. `.p8`을 한 줄 Base64로 변환

PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('AuthKey_XXXX.p8'))
```

출력값만 `OAUTH_APPLE_PRIVATE_KEY_BASE64`로 저장한다. 애플리케이션은 외부 Base64를 풀어 PEM을 얻고,
PEM 내부 PKCS#8 EC 키를 다시 파싱한다. 원본 파일은 접근 권한이 제한된 팀 비밀 저장소에 보관한다.

### Provider refresh token 암호화 키는 지금 생성 가능

이 키는 Apple이 발급하지 않는다. `.p8`, 서비스 `JWT_SECRET_KEY`와 별도로 환경마다 32바이트를 생성한다.
PowerShell에서 값만 만든 뒤 비밀 저장소에 바로 보관하고 채팅·Git·로그에 붙이지 않는다.

```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

처음에는 `OAUTH_APPLE_TOKEN_ENCRYPTION_KEYS=k1=<출력값>`,
`OAUTH_APPLE_TOKEN_ACTIVE_KEY_ID=k1`로 둔다.

## 3. 환경별 설정

### 로컬

`.env`는 커밋하지 않는다. 모든 값을 먼저 채운 뒤 마지막에 활성화한다.

```text
OAUTH_APPLE_ENABLED=true
OAUTH_APPLE_TEAM_ID=...
OAUTH_APPLE_KEY_ID=...
OAUTH_APPLE_CLIENT_ID=com.example.ios
OAUTH_APPLE_PRIVATE_KEY_BASE64=...
OAUTH_APPLE_TOKEN_ENCRYPTION_KEYS=k1=...
OAUTH_APPLE_TOKEN_ACTIVE_KEY_ID=k1
```

`.env`는 `docker compose --profile app`이 변수 보간에 사용할 때만 자동 적용된다. IntelliJ나
`gradlew bootRun`으로 Spring Boot를 직접 실행할 때는 위 다섯 값을 Run Configuration의
환경변수(또는 현재 셸 환경변수)로 넣어야 한다. Spring Boot가 저장소의 `.env` 파일을 직접 읽지는 않는다.

### AWS develop

1. 현재 Secrets Manager JSON 전체를 먼저 조회한다.
2. MySQL, JWT, Google/Kakao/Naver 값을 그대로 보존한다.
3. Apple 필드를 병합한다. `put-secret-value`는 일부 수정이 아니라 전체 JSON 교체다.
4. `sudo systemctl restart pickple`로 `.env`를 다시 만들고 컨테이너를 재시작한다.
5. `sudo systemctl status pickple`과 관리 포트 `/actuator/health`를 확인한다.

Terraform은 `secret_string`을 `ignore_changes`하므로 `terraform apply`만으로 실값이 바뀌지 않는다.
정확한 AWS 절차는 [terraform/README.md](../terraform/README.md)를 따른다.

## 4. iOS와 백엔드 계약

매 로그인 시 iOS가 수행한다.

1. `SecRandomCopyBytes` 등 암호학적으로 안전한 난수로 새 `rawNonce`를 만든다. 재사용하지 않는다.
2. `SHA-256(rawNonce)`를 **lowercase hexadecimal 문자열**로 인코딩해 Apple 요청의 `nonce`에 넣는다.
3. 성공 credential의 `authorizationCode`와 `identityToken` 데이터를 UTF-8 문자열로 바꾼다.
4. 다음 HTTPS 요청을 보낸다. `name`은 Apple이 최초 동의 때 준 경우만 보낸다.

```http
POST /api/auth/apple
Content-Type: application/json

{
  "authorizationCode": "...",
  "identityToken": "...",
  "rawNonce": "...",
  "name": "홍길동"
}
```

성공 응답의 두 토큰은 URL, UserDefaults, 일반 로그가 아니라 iOS Keychain에 저장한다.

```json
{
  "code": "OK",
  "returnObject": {
    "accessToken": "...",
    "refreshToken": "..."
  }
}
```

액세스 토큰은 `Authorization: Bearer ...`로 사용한다. 만료 시 Keychain의 refresh token으로
`POST /api/auth/mobile/refresh`를 호출하고, 응답의 access/refresh token **둘 다** 새 값으로 교체한다.
이전 refresh token은 회전 즉시 무효다. 서버는 제출된 해시를 조건으로 CAS 회전하므로 동시 요청 중
하나만 성공한다. 늦은 요청은 401이지만 먼저 성공한 응답의 새 refresh token은 삭제되지 않는다.

## 5. 백엔드가 검증하는 것

- 앱이 보낸 ID token을 Apple JWKS의 RS256으로 검증
- `iss=https://appleid.apple.com`, `aud=Bundle ID`, `exp`, `sub`, nonce 검증
- `.p8` P-256 키로 ES256 client secret 생성
- authorization code를 Apple `/auth/token`에서 1회 교환
- 교환 응답의 ID token도 동일하게 검증
- 두 ID token의 `sub` 일치 확인
- code 교환 뒤 신원 불일치나 로컬 저장 실패 시 새 provider refresh token 보상 revoke
- 이메일이 아닌 `(APPLE, sub)`로 사용자 조회·생성
- 우리 서비스 access/refresh JWT 발급 및 refresh 원문 대신 해시 저장
- Apple provider refresh token을 AES-256-GCM으로 암호화 저장
- 회원 탈퇴 시 저장 token으로 Apple `/auth/revoke` 호출

Apple authorization code는 한 번만 쓸 수 있고 약 5분 동안 유효하다. 이 구현의 client secret은
요청 시 생성하며 10분 동안 유효하다. 5분 제한과 client secret 수명을 혼동하지 않는다.

백엔드는 nonce를 직접 발급하거나 사용 여부를 저장하지 않는다. 따라서 동일 credential 묶음의 재전송은
authorization code의 일회성 교환으로 막지만, 탈취자가 정상 앱보다 먼저 교환하는 선점 공격은 현재 범위 밖이다.
서버 발급 nonce를 로그인 세션이나 기기에 묶는 저장소가 필요해지면 별도 설계한다.

## 6. 키 없이 가능한 테스트와 키 수령 뒤 테스트

키·iPhone 없이 가능한 것:

- 테스트용 P-256 키로 ES256 client secret 헤더/클레임/서명 확인
- 가짜 RS256 decoder로 issuer/audience/expiry/sub/nonce 처리 확인
- Apple code 교환 form과 오류 매핑 확인
- code 교환 뒤 신원 불일치·로컬 완료 실패 시 provider refresh token 보상 revoke 확인
- provider refresh token AES-GCM 왕복·랜덤 IV·AAD/위변조 거부·keyring 교체 호환
- `/auth/revoke` form/오류 매핑과 Apple/비-Apple 탈퇴 분기 확인
- `(APPLE, sub)` 가입/로그인, 자체 JWT 발급, 모바일 refresh 회전 확인
- 동시 refresh 중 하나만 CAS 회전에 성공하고, 늦은 요청이 현재 token을 삭제하지 않는지 확인
- provider token 누락 사용자의 로컬 탈퇴 완료와 수동 Apple 연결 해제 응답 확인

키 수령 뒤 반드시 할 종단간 테스트:

1. 신규 Apple 계정 로그인과 서비스 사용자 생성
2. 같은 계정 재로그인 시 같은 사용자 조회
3. `이메일 가리기` 선택 시 relay 이메일 처리
4. 이름이 첫 로그인 뒤 다시 오지 않아도 기존 이름 유지
5. 틀린 nonce 거부
6. 같은 authorization code 재사용 거부
7. access token으로 보호 API 호출
8. mobile refresh 회전 후 옛 refresh token 거부
9. TestFlight 또는 실기기 배포 빌드에서 다시 확인
10. `DELETE /api/auth/me`가 Apple 연결을 revoke하고 재로그인을 거부하는지 확인
11. provider token이 없는 기존 계정은 `APPLE_MANUAL_REVOCATION_REQUIRED`를 받고 iOS가 수동 해제를 안내하는지 확인

iPhone 없이도 백엔드 구현 대부분은 검증 가능하지만, Apple credential 발급부터 서버 교환까지의
진짜 종단간 검증은 iOS 앱 실행 환경과 Apple 계정이 필요하다. 시뮬레이터 확인만으로 배포 빌드를
검증했다고 보지 않는다.

## 7. 키 교체 순서

1. 기존 키를 먼저 폐기하지 않는다.
2. 새 Sign in with Apple 키를 만들고 `.p8`, Key ID를 안전하게 확보한다.
3. Secrets Manager JSON의 `oauth_apple_key_id`와 `oauth_apple_private_key_base64`를 함께 교체한다.
4. 서버를 재시작하고 실제 Apple 로그인 1회를 확인한다.
5. 새 키 성공을 확인한 뒤 Apple Developer에서 이전 키를 폐기한다.

키 파일을 잃어버렸거나 노출이 의심되면 복구하려 하지 말고 위 순서로 교체한다. 이 서버는 client secret을
저장하지 않고 매 요청 새로 만들므로 서버 재시작 뒤 새 키를 즉시 사용한다.

### Provider token AES 키 교체

1. 기존 keyring에서 이전 키를 제거하지 않는다.
2. 새 32바이트 키를 만들어 `k2=NEW,k1=OLD`처럼 keyring에 추가한다.
3. active key ID를 `k2`로 바꾸고 재시작한다. 새 로그인은 k2, 기존 행은 k1로 복호화된다.
4. 기존 행 재암호화 도구는 아직 없으므로 k1 행이 남아 있는 동안 이전 키를 제거하면 안 된다.
5. 이전 키 제거가 필요하면 DB의 `encryption_key_id` 잔존 건수를 확인하고 재암호화 작업을 별도 수행한다.

## 8. 보상 revoke 실패 관측

로그인 로컬 완료 실패 뒤 보상 revoke까지 실패하면
`pickple.auth.apple.login.compensation.revoke.failures`가 증가한다. 같은 시각의 WARN 로그는
`correlationId`로 조회한다. `trace_id`와 `span_id`는 OTel agent가 활성화된 환경에서만 채워지므로
현재 develop 환경의 복구 식별자로 가정하지 않는다. token, sub, Apple 응답 본문은 기록하지 않는다.

EC2에서는 관리 포트에서 다음처럼 확인한다.

```bash
cd /opt/pickple
sudo docker compose -f docker-compose-ec2.yml exec -T app \
  wget -qO- http://localhost:9090/actuator/metrics/pickple.auth.apple.login.compensation.revoke.failures
```

이 counter는 프로세스 재시작 시 초기화되는 수동 진단 지표다. 영속 보관·자동 알림·실패 token 자동 정리는
metrics 수집기와 암호화 compensation outbox/재시도 worker를 도입하는 후속 범위다.

## 9. 현재 범위 밖이지만 출시 전에 결정할 것

- `user_refresh_token`은 사용자당 한 행이라 새 로그인은 기존 웹/다른 기기의 refresh token을 무효화한다.
  다중 기기 동시 로그인이 필요하면 세션/token-family 스키마가 필요하다.
- 현재 회원 탈퇴는 계정을 `INACTIVE`로 만드는 소프트 탈퇴다. 이메일·이름 등 개인정보의 익명화/삭제,
  게시물 보존, 재가입 허용 정책은 출시 전에 제품·법무 기준으로 확정해야 한다.
- 활성 여부의 정본은 `users.state`다. 향후 `deleted_at`은 탈퇴 시각 감사값으로 추가하고 탈퇴 트랜잭션에서
  `state=INACTIVE`와 함께 기록한다. 활성 닉네임 유일성은 `state=ACTIVE`를 기준으로 계산한다.
- Apple 장애 시 동기 revoke가 실패하면 503으로 탈퇴를 완료하지 않는다. 장애와 무관하게 즉시 로컬 탈퇴를
  허용하려면 암호화 token을 보존하는 outbox와 재시도 worker를 후속 도입해야 한다.
- Apple provider token이 없는 기존 사용자는 로컬 탈퇴를 완료하되 `APPLE_MANUAL_REVOCATION_REQUIRED`를
  반환한다. iOS는 Apple 계정 설정에서 Pickple 연결을 직접 해제하도록 안내한다.
- 이미 발급된 stateless access token은 최대 30분 동안 남을 수 있다. 즉시 차단이 필요하면 별도 정책이 필요하다.
- 실제 `.p8` 형식·실 Bundle ID 설정 기동·Apple JWKS와 token/revoke endpoint 연결은 확인했다.
- AWS 반영과 유효한 iOS authorization code를 사용한 TestFlight/실기기 로그인·탈퇴 결과는 아직 증명하지 않는다.

## 10. Apple 공식 문서

- [Generate and validate tokens](https://developer.apple.com/documentation/signinwithapplerestapi/generate-and-validate-tokens)
- [Revoke tokens](https://developer.apple.com/documentation/signinwithapplerestapi/revoke-tokens)
- [TN3194: Handling account deletions and revoking tokens](https://developer.apple.com/documentation/technotes/tn3194-handling-account-deletions-and-revoking-tokens-for-sign-in-with-apple)
