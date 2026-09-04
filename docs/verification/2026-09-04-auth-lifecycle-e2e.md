# 인증 수명주기 E2E 실측 검증 (회원 탈퇴 #28)

통합테스트가 아니라 **실제로 기동한 서버에 실제 HTTP 요청**을 보내 확인한 기록이다.
아래 "통과" 는 전부 실제 응답 코드를 근거로 한다. 근거가 없는 항목은 미검증으로 적었다.

## 무엇을 검증했나

### 실행 환경

| 항목 | 값 |
|---|---|
| 기동 방법 | IntelliJ `idea` MCP → `execute_run_configuration("PickpleApplication")` (폴백 없음) |
| 기동한 체크아웃 | `/Users/kimhabin/harness/projects/6th-buy-or-pass-backend` (워크트리가 아님) |
| 그 체크아웃의 SHA | `4f8ddbe` (브랜치 `chore/#96-pull-rebase-guard`) |
| 검증 대상 워크트리 SHA | `3b020f1` (브랜치 `chore/wt-auth-e2e-live-verify`) |
| 두 SHA 의 관계 | `git diff HEAD 3b020f1 -- src/` 가 **비어 있다.** 제품 소스는 동일하고 차이는 도구·문서뿐이다 |
| 프로파일 | `local` |
| **서비스 포트** | **8080** (8081 이 아니다 — 아래 "발견한 결함" D-2 참조) |
| management 포트 | 9090 |
| MySQL | 기존 컨테이너 `pickple-mysql` 재사용, 포트 13307, schema version 10 |
| JVM | OpenJDK 25.0.4.1 |
| `OAUTH_APPLE_ENABLED` | `false` (기본값) |
| 기동 판정 | `GET :9090/actuator/health` → `{"status":"UP"}` **및** `GET :8080/posts` → 200 |

기동 확인을 health 하나로 끝내지 않은 이유는 아래 D-2 에 적었다. 실제로 이번에 health 가
UP 인 동안 서비스 포트가 열려 있지 않은 구간이 있었다.

### 시나리오

회원 1명(`id=1`, APPLE, 닉네임 `테스터`)을 시드한 뒤 발급 → 인증 → 회전 → 로그아웃 →
재로그인 → 탈퇴 → 탈퇴 후 접근까지 15단계를 순서대로 실행했다. 게시글 1건을 시드해
탈퇴 후 보존을 확인했다.

## 결과 표 — 이슈 #28 완료 판정

**6/6 검증, 미검증 0개.** 단 아래 두 항목은 대체 경로로 확인했으므로 그 사실을 함께 적는다.

| # | 판정 | 결과 | 근거 (실제 응답) |
|---|---|---|---|
| 1 | 탈퇴 후에도 작성한 게시글이 조회됨 | **통과** | 12번: `GET /posts` → **200**, 탈퇴 회원의 글 `id=1` 이 `authorNickname:"테스터"` 로 그대로 내려옴 |
| 2 | 탈퇴 후 닉네임을 다른 사용자가 등록 가능 | **통과** | 13번: 같은 닉네임 `테스터` 로 새 회원 INSERT **성공**. `uk_users_active_nickname` 위반 없음 |
| 3 | 탈퇴 계정의 리프레시 토큰이 폐기됨 | **통과** | 11번: `POST /auth/refresh` → **401** `INVALID_TOKEN`. DB `user_refresh_token` 행 수 **0** |
| 4 | 탈퇴 계정으로 인증 필요 API 접근 불가 | **부분 통과 — 아래 D-1** | 10번: `GET /auth/me` → **401** `UNAUTHORIZED`. 그러나 `POST /posts/{id}/comments` 는 **201** 로 통과됨 |
| 5 | 로그아웃 후 해당 RT 로 재발급 불가 | **통과** | 6번: `POST /auth/refresh` → **401** `INVALID_TOKEN` |
| 6 | 로그아웃은 계정을 비활성화하지 않는다 | **통과** | 7번: 로그아웃 직후 `users.state` 가 `ACTIVE` 로 유지. 재발급 후 `GET /auth/me` → **200**, `POST /auth/refresh` → **200** |

판정 4를 "통과" 가 아니라 **부분 통과**로 적은 이유는 D-1 이다. 이슈의 문구를 좁게
(`/auth/me` 한정) 읽으면 통과지만, 문구 그대로 "인증 필요 API" 로 읽으면 반례가 있다.

## 단계별 실행 기록 (원문)

### 0. 가입 불가 확인 — 제약의 증거
```
POST /auth/apple  {"authorizationCode":"dummy-code","identityToken":"eyJhbGciOiJSUzI1NiIsImtpZCI6ImZha2UifQ...","rawNonce":"0123456789abcdef0123","name":"E2E"}
→ HTTP 503
{"code":"APPLE_LOGIN_UNAVAILABLE","message":"Apple 로그인을 현재 사용할 수 없습니다.","returnObject":null}
```

### 1. 회원 시드 (SQL)
```
id  provider  provider_id   nickname  active_nickname  state   role
1   APPLE     e2e-sub-001   테스터     테스터            ACTIVE  ROLE_USER
```

### 2~3. 토큰 발급과 인증 확인
독립 스크립트로 HS256 서명(제품 코드 수정 없음). `GET /auth/me` 로 서버가 받아들이는지 확인:
```
GET /auth/me   Authorization: Bearer <access>
→ HTTP 200
{"code":"OK","returnObject":{"userId":1,"email":"e2e@example.com","name":"E2E User","provider":"APPLE","role":"ROLE_USER"}}
```

### 4. 리프레시 — 회전 확인 (ADR-0016)
```
POST /auth/refresh   Cookie: refresh_token=<RT1>
→ HTTP 200  {"code":"OK","returnObject":{"accessToken":"eyJhbGciOiJIUzUxMiJ9..."}}
Set-Cookie: refresh_token=...; Max-Age=1209600; Path=/; HttpOnly; SameSite=Lax

DB token_hash  before: e70d3c7fc2d38f7930aa13a08f06cbb180b6b222ce3e8aa5aaeb3520456e4ad5
DB token_hash  after : 06705502c4ac5f75714ba20007769f467069e01e44dd47280835914c7a1ac13f
```
200 만으로는 회전을 판정하지 않았다. **저장된 해시가 실제로 바뀐 것**을 근거로 삼았다.

### 5. 로그아웃
```
POST /auth/logout   Authorization: Bearer <access>
→ HTTP 200  {"code":"OK","message":"정상 처리되었습니다.","returnObject":null}

user_refresh_token 행 수: 0
users.state: ACTIVE   (계정은 살아 있다)
```

### 6. 로그아웃 후 폐기된 RT 로 재발급
```
POST /auth/refresh   Cookie: refresh_token=<RT2>
→ HTTP 401  {"code":"INVALID_TOKEN","message":"유효하지 않은 토큰입니다.","returnObject":null}
```

### 7. 재로그인 — 로그아웃이 계정을 죽이지 않았음
```
GET  /auth/me       → HTTP 200  {"userId":1,...}
POST /auth/refresh  → HTTP 200  {"code":"OK","returnObject":{"accessToken":"..."}}
```

### 8. 게시글 시드와 조회
```
GET /posts → HTTP 200
{"content":[{"id":1,"type":"GENERAL","category":"ETC","title":"탈퇴 후 보존 확인용 글",
  "authorId":1,"authorNickname":"테스터","authorRanking":1}],"hasNext":false}
```

### 9. 탈퇴
```
DELETE /auth/me   Authorization: Bearer <access>
→ HTTP 200
{"code":"APPLE_MANUAL_REVOCATION_REQUIRED","message":"회원 탈퇴는 완료되었습니다. Apple 계정 설정에서 Pickple 연결을 직접 해제해 주세요.","returnObject":null}
```
저장된 Apple provider token 을 시드하지 않았으므로 이 코드가 정상이다
(`AccountWithdrawalService` 의 `COMPLETED_REQUIRES_MANUAL_APPLE_REVOCATION` 경로).

### 10. 탈퇴 후 기존 access token 으로 접근
```
GET /auth/me   Authorization: Bearer <탈퇴 전 발급 토큰, 아직 만료 전>
→ HTTP 401  {"code":"UNAUTHORIZED","message":"인증이 필요합니다.","returnObject":null}
```

### 11. 탈퇴 후 리프레시
```
POST /auth/refresh   Cookie: refresh_token=<탈퇴 전 RT>
→ HTTP 401  {"code":"INVALID_TOKEN","message":"유효하지 않은 토큰입니다.","returnObject":null}
```

### 12. 글 보존 (R-20)
```
GET /posts → HTTP 200
{"content":[{"id":1,"title":"탈퇴 후 보존 확인용 글","authorId":1,"authorNickname":"테스터",...}]}
```

### 13. 닉네임 반납 (R-21)
```
INSERT INTO users (... nickname) VALUES ('APPLE','e2e-sub-002',...,'테스터');  → 성공

id  provider_id   nickname  active_nickname  state
1   e2e-sub-001   테스터     NULL             INACTIVE
2   e2e-sub-002   테스터     테스터            ACTIVE
```

### 14. DB 상태 (R-23, 물리 삭제 아님)
```
id=1 행이 남아 있다.  state=INACTIVE,  nickname='테스터' 보존,  active_nickname=NULL
user_refresh_token 행 수: 0
```
`active_nickname` 은 `CASE WHEN state='ACTIVE' THEN nickname END` 생성 컬럼이므로
상태만 뒤집으면 유니크 인덱스에서 빠진다. 닉네임 원문은 지우지 않는다.

## 발견한 결함

### D-1. 탈퇴한 회원이 댓글을 작성할 수 있다 (기능 결함)

탈퇴 직후, **탈퇴 전에 발급받아 아직 만료되지 않은 access token** 으로:

```
POST /posts/1/comments   Authorization: Bearer <탈퇴 전 토큰>
{"content":"withdrawn user comment"}
→ HTTP 201
{"code":"CREATED","message":"생성되었습니다.","returnObject":{"id":1,"content":"withdrawn user comment"}}
```

DB 에도 실제로 남았다 (검증 후 삭제함):
```
id  user_id  content                    author_state
1   1        withdrawn user comment     INACTIVE
```

**독립 재현됨.** 이 리포트를 쓴 뒤 별도 컨텍스트의 검증자가 같은 서버에 새로 서명한
토큰으로 다시 시도해 **201 CREATED 와 `user_id=1` 댓글 행 생성을 재현**했다. 같은 토큰의
`GET /auth/me` 는 401 이었다. 즉 "토큰이 아직 유효한 것" 과 "탈퇴한 계정을 막는 것" 이
엔드포인트마다 갈린다는 것이 두 번 확인되었다. (재현 중 만든 행과 카운터는 정리했다.)

**원인 (구조).** `JwtAuthenticationFilter` 는 의도적으로 무상태다 — 요청마다 DB 를 조회하지
않으려고 access token 클레임만으로 `SecurityContext` 를 채운다(`JwtService` 주석에 근거가 적혀
있다). 따라서 탈퇴 여부는 필터가 아니라 **각 서비스가 `isActive()` 를 직접 확인해야** 걸러진다.
그런데 `grep -rn "isActive()" src/main/java` 결과 확인 지점은 `AuthService`(3곳),
`UserProfileService`(1곳), `User.registerProfile`, `AccountWithdrawalPersistenceService` 뿐이고
**`comment` 패키지에는 상태 확인이 한 곳도 없다.**

즉 10번이 401 인 것은 Spring Security 가 막아서가 아니라 `AuthService.getById` 가
`!user.isActive()` 일 때 `UNAUTHORIZED` 를 던지기 때문이다. 같은 확인을 하지 않는 엔드포인트는
**access token 이 만료될 때까지 최대 30분**(`access-token-validity: PT30M`) 동안 열려 있다.

같은 토큰으로 확인한 다른 엔드포인트:

| 엔드포인트 | 응답 | 해석 |
|---|---|---|
| `GET /auth/me` | 401 `UNAUTHORIZED` | 막힘 |
| `POST /users/profile` | 401 `UNAUTHORIZED` | 막힘 (`UserProfileService` 가 확인함) |
| `POST /posts/{id}/comments` | **201 CREATED** | **뚫림 — 실제로 데이터가 생성됨** |
| `POST /posts/{id}/votes` | 400 `INVALID_REQUEST` | **미검증.** GENERAL 게시글이라 선택지가 없어서 난 400 이다. 인증 거부가 아니므로 안전하다고 말할 수 없다 |
| `POST /comments/{id}/pick` | 400 `INVALID_REQUEST` | **미검증.** 위와 같은 이유 |
| `GET /badges/me` | 404 `NOT_FOUND` | **미검증.** 뱃지 데이터가 없어서 난 404 이다 |

**확인된 것은 댓글 1건이지만, 검사 자체가 서비스마다 흩어져 있는 구조이므로
투표·픽 경로도 선택지를 갖춘 게시글로 다시 확인해야 한다.** 위 400/404 는 "막혔다" 가
아니라 "이 요청으로는 판정할 수 없었다" 이다.

제안(이 워크트리에서는 고치지 않았다 — 검증 전용 슬롯): 서비스마다 확인을 반복하는 대신
필터·인터셉터 한 곳에서 탈퇴 여부를 판정하거나, 탈퇴 시 해당 사용자의 access token 을
무효화할 수 있는 수단(jti 블랙리스트 등)을 둔다. 어느 쪽이든 되돌리기 비싼 설계 결정이라
별도 논의가 필요하다.

### D-2. `APP_PORT` 가 IntelliJ·`bootRun` 기동에 반영되지 않는다 (설정 함정)

`.env` 와 `.env.example` 은 `APP_PORT=8081` 을 선언하지만 **서버는 8080 에 떴다.**

```
o.s.boot.tomcat.TomcatWebServer - Tomcat started on port 8080 (http) with context path '/'
o.s.boot.tomcat.TomcatWebServer - Tomcat started on port 9090 (http) with context path '/'
```

`application.yml` 은 `management.server.port: ${MANAGEMENT_PORT:9090}` 은 두었지만
`server.port` 를 `APP_PORT` 로 묶는 줄이 없다. `grep -rn "APP_PORT" src/main/resources/` 결과도
없다. `APP_PORT` 는 Docker Compose 의 포트 매핑(`${APP_PORT}:8080`) 전용이므로 Compose 를
거치지 않는 기동(IntelliJ, `./gradlew bootRun`)에서는 Tomcat 기본값 8080 이 쓰인다.

동작상 버그는 아니지만 **`.env` 를 읽고 8081 로 붙는 사람은 연결 실패를 겪는다.** 이번 검증도
그랬다. 문서 한 줄이나 `server.port: ${APP_PORT:8080}` 한 줄 중 하나가 필요하다.

부수 효과로 **`/actuator/health` 가 UP 인데 서비스 포트가 아직 닫혀 있는 구간이 실측되었다.**
management 커넥터(9090)가 먼저 뜨고 서비스 커넥터가 뒤에 뜨기 때문이다. 기동 판정을 health
하나로 하면 이 구간을 "준비 완료" 로 오판한다.

### D-3. (결함 아님, 기록) 리프레시 쿠키 이름은 `refresh_token` 이다

`refreshToken` 으로 보내면 401 `INVALID_TOKEN` 이 난다. 정본은
`OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE = "refresh_token"`. 다음 사람이 같은 데서 막히지
않도록 적어 둔다.

## 검증하지 못한 것

1. **가입 구간 전체 — HTTP 로 검증하지 못했다.**
   유일한 가입 진입점 `POST /auth/apple` 은 Apple 이 RS256 으로 서명한 ID 토큰을 요구하고
   `AppleIdTokenVerifier` 가 Apple JWKS 로 서명을 검증한다. 개인키가 Apple 에 있으므로
   위조할 수 없다(서명 검증의 존재 이유다). 0번에서 503 `APPLE_LOGIN_UNAVAILABLE` 로
   **가입이 실제로 막혀 있다는 사실까지만** 확인했다. 회원 생성은 SQL 시드로 대체했다.
   따라서 `AppleAuthService` → `AuthService.loginOrRegister` 경로는 이번에 실행되지 않았고,
   "탈퇴한 계정으로 재로그인하면 403" 규칙(`AuthService:41`)도 **미검증**이다.

2. **탈퇴 시 Apple 연결 해제(revoke) 호출 — 미검증.**
   9번이 `APPLE_MANUAL_REVOCATION_REQUIRED` 로 끝난 것은 provider token 을 시드하지 않아
   `AppleTokenGateway.revokeRefreshToken` 이 호출되지 않았다는 뜻이다. Apple 장애 시 503 을
   주고 로컬 상태를 바꾸지 않는다는 재시도 경로도 실행하지 못했다.

3. **게시글 상세 조회 API 는 존재하지 않는다.**
   `PostController` 에 `GET /posts` 목록만 있고 `GET /posts/{id}` 상세가 없다. 그래서 12번의
   "게시글 상세 조회" 를 **목록 조회로 대체**했다. 탈퇴 회원의 글이 목록에서 사라지지 않는
   것은 확인했지만, 상세 화면의 동작은 엔드포인트가 없어 확인할 수 없었다.

4. **게시글 작성 API 도 존재하지 않는다.** 8번을 SQL 시드로 대체했다. 작성 경로에
   탈퇴 회원 차단이 있는지는 D-1 과 별개로 확인하지 못했다(엔드포인트 자체가 없다).

5. **D-1 의 투표·픽·뱃지 경로.** 위 표에 적은 대로 400/404 는 인증 거부가 아니어서 판정
   불가다. 선택지를 갖춘 A_B 게시글을 시드해 다시 확인해야 한다.

6. **동시성(ADR-0016 CAS 경합).** 회전이 일어난다는 것은 해시 변화로 확인했지만, 두 요청이
   동시에 같은 RT 를 내밀었을 때의 승자/패자 동작은 단일 순차 호출로는 확인하지 못했다.

## 재현 방법

```bash
# 0. MySQL 은 이미 떠 있다고 가정한다 (컨테이너 pickple-mysql, 포트 13307)
docker ps --filter name=pickple-mysql

# 1. 환경
cp .env.example .env
sed -i '' "s|^JWT_SECRET_KEY=$|JWT_SECRET_KEY=$(openssl rand -base64 48)|" .env
SECRET=$(grep '^JWT_SECRET_KEY=' .env | cut -d= -f2-)

# 2. 기동 (IntelliJ 실행 구성 PickpleApplication, 또는 ./gradlew bootRun)
#    ⚠️ 서비스 포트는 8080 이다. APP_PORT=8081 은 Compose 전용이라 반영되지 않는다(D-2).
until curl -sf http://localhost:9090/actuator/health | grep -q '"status":"UP"'; do sleep 2; done
until curl -sf -o /dev/null http://localhost:8080/posts; do sleep 2; done   # 서비스 포트도 함께 본다

MYSQL() { docker exec pickple-mysql mysql --default-character-set=utf8mb4 -upickple -pchange-me-app pickple -e "$1"; }

# 3. 가입이 막혀 있음을 확인 (503 APPLE_LOGIN_UNAVAILABLE)
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8080/auth/apple \
  -H 'Content-Type: application/json' \
  -d '{"authorizationCode":"dummy-code","identityToken":"eyJhbGciOiJSUzI1NiJ9.e30.sig","rawNonce":"0123456789abcdef0123","name":"E2E"}'

# 4. 회원 시드
MYSQL "INSERT INTO users (provider,provider_id,email,name,role,state,created_at,updated_at,nickname)
       VALUES ('APPLE','e2e-sub-001','e2e@example.com','E2E User','ROLE_USER','ACTIVE',NOW(),NOW(),'테스터');"

# 5. 토큰 발급 — 제품 코드를 고치지 않는 독립 서명기
#    JwtService 와 동일: HS256, iss=pickple, sub=userId, typ=access|refresh, jti=UUID
#    ⚠️ 키는 Base64 디코드하지 않는다. secretKey.getBytes(UTF_8) 원문 바이트가 그대로 키다.
mint() {  # mint <secret> <userId> <access|refresh>
  b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
  local now exp jti hdr pl si
  now=$(date +%s); [ "$3" = access ] && exp=$((now+1800)) || exp=$((now+1209600))
  jti=$(uuidgen | tr 'A-Z' 'a-z')
  hdr=$(printf '{"alg":"HS256"}' | b64url)
  if [ "$3" = access ]; then
    pl=$(printf '{"sub":"%s","iss":"pickple","role":"ROLE_USER","typ":"access","jti":"%s","iat":%s,"exp":%s}' "$2" "$jti" "$now" "$exp" | b64url)
  else
    pl=$(printf '{"sub":"%s","iss":"pickple","typ":"refresh","jti":"%s","iat":%s,"exp":%s}' "$2" "$jti" "$now" "$exp" | b64url)
  fi
  si="$hdr.$pl"
  printf '%s.%s' "$si" "$(printf '%s' "$si" | openssl dgst -sha256 -mac HMAC -macopt "key:$1" -binary | b64url)"
}

AT=$(mint "$SECRET" 1 access)
RT=$(mint "$SECRET" 1 refresh)
# 리프레시는 원문이 아니라 SHA-256 해시로 저장된다
MYSQL "INSERT INTO user_refresh_token (user_id,token_hash,expires_at,created_at)
       VALUES (1,'$(printf '%s' "$RT" | openssl dgst -sha256 -hex | awk '{print $NF}')',DATE_ADD(NOW(),INTERVAL 14 DAY),NOW())
       ON DUPLICATE KEY UPDATE token_hash=VALUES(token_hash);"

# 6. 시나리오 — ⚠️ 쿠키 이름은 refresh_token 이다 (refreshToken 아님, D-3)
curl -s http://localhost:8080/auth/me -H "Authorization: Bearer $AT"                          # 200
curl -s -D- -X POST http://localhost:8080/auth/refresh -H "Cookie: refresh_token=$RT"         # 200 + 회전
MYSQL "SELECT token_hash FROM user_refresh_token WHERE user_id=1;"                            # 해시가 바뀐다
curl -s -X POST http://localhost:8080/auth/logout -H "Authorization: Bearer $AT"              # 200
curl -s -X POST http://localhost:8080/auth/refresh -H "Cookie: refresh_token=$RT"             # 401
MYSQL "SELECT state FROM users WHERE id=1;"                                                   # ACTIVE 유지

# 7. 게시글 시드 후 탈퇴
MYSQL "INSERT INTO post (user_id,type,category,title,description,created_at,updated_at)
       VALUES (1,'GENERAL','ETC','탈퇴 후 보존 확인용 글','R-20 검증용 게시글',NOW(),NOW());"
AT2=$(mint "$SECRET" 1 access)
curl -s -X DELETE http://localhost:8080/auth/me -H "Authorization: Bearer $AT2"                # 200
curl -s http://localhost:8080/auth/me -H "Authorization: Bearer $AT2"                          # 401
curl -s http://localhost:8080/posts                                                            # 200, 글 보존
MYSQL "SELECT id,nickname,active_nickname,state FROM users;"                                   # INACTIVE, active_nickname NULL
MYSQL "INSERT INTO users (provider,provider_id,email,name,role,state,created_at,updated_at,nickname)
       VALUES ('APPLE','e2e-sub-002','e2e2@example.com','E2E User2','ROLE_USER','ACTIVE',NOW(),NOW(),'테스터');"  # 성공

# 8. D-1 재현 — 탈퇴한 회원이 댓글을 쓴다
curl -s -w '\n%{http_code}\n' -X POST http://localhost:8080/posts/1/comments \
  -H "Authorization: Bearer $AT2" -H 'Content-Type: application/json' \
  -d '{"content":"withdrawn user comment"}'                                                    # 201 CREATED

# 9. 정리
MYSQL "DELETE FROM comment_pick; DELETE FROM comment; DELETE FROM post_commenter;
       DELETE FROM post; DELETE FROM user_refresh_token; DELETE FROM users;"
```
