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
| 2차 검증 추가 | Kakao 프로바이더 URI 를 로컬 스텁(`127.0.0.1:9500`)으로 env override 후 재기동. **제품 코드·설정 파일 수정 없음** |

기동 확인을 health 하나로 끝내지 않은 이유는 아래 D-2 에 적었다. 실제로 이번에 health 가
UP 인 동안 서비스 포트가 열려 있지 않은 구간이 있었다.

### 시나리오

회원 1명(`id=1`, APPLE, 닉네임 `테스터`)을 시드한 뒤 발급 → 인증 → 회전 → 로그아웃 →
재로그인 → 탈퇴 → 탈퇴 후 접근까지 15단계를 순서대로 실행했다. 게시글 1건을 시드해
탈퇴 후 보존을 확인했다.

## 결과 표 — 이슈 #28 완료 판정

**6/6 검증, 미검증 0개. 5개 통과, 1개 실패(D-1).**

2차 검증에서 **가입 구간을 실제 HTTP 로 실행**했다(로컬 스텁 프로바이더 + Kakao OAuth2 흐름).
1차의 최대 공백이던 "가입 미검증" 이 해소됐고, 그 결과 D-1 의 범위도 확대 확인됐다.

| # | 판정 | 결과 | 근거 (실제 응답) |
|---|---|---|---|
| 1 | 탈퇴 후에도 작성한 게시글이 조회됨 | **통과** | 12번: `GET /posts` → **200**, 탈퇴 회원의 글 `id=1` 이 `authorNickname:"테스터"` 로 그대로 내려옴 |
| 2 | 탈퇴 후 닉네임을 다른 사용자가 등록 가능 | **통과** | 13번: 같은 닉네임 `테스터` 로 새 회원 INSERT **성공**. `uk_users_active_nickname` 위반 없음 |
| 3 | 탈퇴 계정의 리프레시 토큰이 폐기됨 | **통과** | 11번: `POST /auth/refresh` → **401** `INVALID_TOKEN`. DB `user_refresh_token` 행 수 **0** |
| 4 | 탈퇴 계정으로 인증 필요 API 접근 불가 | **실패 — 아래 D-1** | `GET /auth/me`·`POST /users/profile` 은 **401**. 그러나 **댓글 201, 투표 200, 원픽 201** 로 뚫림 (2차 검증에서 확대 확인) |
| 5 | 로그아웃 후 해당 RT 로 재발급 불가 | **통과** | 6번: `POST /auth/refresh` → **401** `INVALID_TOKEN` |
| 6 | 로그아웃은 계정을 비활성화하지 않는다 | **통과** | 7번: 로그아웃 직후 `users.state` 가 `ACTIVE` 로 유지. 재발급 후 `GET /auth/me` → **200**, `POST /auth/refresh` → **200** |

판정 4는 **실패**다. 1차 검증에서는 댓글 1건만 확인해 "부분 통과" 로 적었으나, 2차 검증에서
**투표와 원픽까지 뚫리는 것**을 확인해 판정을 내렸다. 막힌 것은 `isActive()` 를 직접 확인하는
두 엔드포인트뿐이고, 쓰기 경로 3개가 모두 통과한다.

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

## 2차 검증 — 가입 구간을 실제 HTTP 로 실행했다

1차에서 "가입은 HTTP 로 검증 불가" 로 남긴 부분을 다시 시도해 **해소했다.**

### 일반(ID/비밀번호) 회원가입은 이 저장소에 없다

먼저 확인한 사실이다. 찾지 못한 게 아니라 **존재하지 않는다**:

- `users` 테이블에 **password 컬럼이 없다** (V1~V10 전 마이그레이션)
- `PasswordEncoder`·`BCrypt`·`UserDetailsService` 빈이 없다
- 가입/로그인 엔드포인트는 `POST /auth/apple` 하나뿐이다
- `grep` 의 `UsernamePassword*` 히트 2건은 Spring Security **필터 순서 지정용**
  (`addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)`)이지 비밀번호 로그인이 아니다

이 서비스는 설계상 소셜 로그인 전용이다(ADR-0006).

### 대신 Google/Kakao/Naver OAuth2 경로를 실제로 실행했다

Apple 이 막힌 이유는 **RS256 서명**(개인키가 Apple 에 있음)이지만, Kakao/Naver 가 막히는 이유는
`DefaultOAuth2UserService` 가 `user-info-uri` 로 **실제 HTTPS 호출**을 한다는 것이다. 그런데
그 URI 는 **하드코딩이 아니라 설정값**이다. 그래서 제품 코드를 고치지 않고 **환경변수만으로**
프로바이더를 로컬 스텁으로 돌렸다:

```
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KAKAO_AUTHORIZATIONURI=http://127.0.0.1:9500/oauth/authorize
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KAKAO_TOKENURI=http://127.0.0.1:9500/oauth/token
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KAKAO_USERINFOURI=http://127.0.0.1:9500/v2/user/me
```

스텁은 `KakaoUserInfo` 가 읽는 모양(`{id, kakao_account:{email, profile:{nickname}}}`)만 돌려주는
70줄짜리 파이썬 HTTP 서버다. **`CustomOAuth2UserService` → `AuthService.loginOrRegister` →
`OAuth2SuccessHandler` 는 전부 진짜 제품 코드가 실행된다.**

```
GET /oauth2/authorization/kakao
→ 302  Location: http://127.0.0.1:9500/oauth/authorize?...&state=...&code_challenge=...

GET /login/oauth2/code/kakao?code=stub-auth-code&state=<state>
→ 302
Set-Cookie: refresh_token=eyJhbGciOiJIUzUxMiJ9...; Max-Age=1209600; HttpOnly; SameSite=Lax
Location: http://localhost:3000/oauth/callback?accessToken=eyJhbGciOiJIUzUxMiJ9...
```

**애플리케이션이 스스로 만든 회원** (SQL 시드가 아니다):

```
id  provider  provider_id        email              name    state
3   KAKAO     stub-kakao-9001    stub@example.com   스텁유저  ACTIVE

user_refresh_token: id=3  user_id=3   ← 진짜 issueTokens() 가 쓴 행
```

서버가 발급한 그 토큰으로 이어서:

```
GET  /auth/me        → 200  {"userId":3,"provider":"KAKAO","name":"스텁유저"}
POST /users/profile  → 201  {"userId":3,"nickname":"스텁","profileImageUrl":".../profile-2.png"}
DELETE /auth/me      → 200  {"code":"OK"}          ← Apple 이 아니므로 수동 해제 문구가 없다
POST /auth/refresh   → 401  {"code":"INVALID_TOKEN"}
users.id=3           → state=INACTIVE, active_nickname=NULL
```

이로써 **가입 → 프로필 등록 → 탈퇴 → 탈퇴 후 차단**이 전부 실제 HTTP 로 이어졌고,
D-1 은 SQL 로 시드한 계정이 아니라 **애플리케이션이 직접 만든 계정**에서도 재현됐다.

### 탈퇴 계정 재로그인 차단 — 403 이 아니라 `login_failed` 리다이렉트다

같은 스텁 신원(`KAKAO / stub-kakao-9001`, 현재 INACTIVE)으로 로그인을 한 번 더 돌렸다:

```
GET /login/oauth2/code/kakao?code=stub-auth-code&state=<state>
→ 302  Location: http://localhost:3000/oauth/callback?error=login_failed

users: 중복 생성 없음 (id=3 그대로, 신규 행 없음)
```

**차단은 된다.** 다만 `AuthService:41` 의 `ApiException(FORBIDDEN, "탈퇴한 계정입니다.")` 이
그대로 403 으로 나가지 않는다. `CustomOAuth2UserService` 가 그것을
`OAuth2AuthenticationException` 으로 감싸고, `OAuth2FailureHandler` 가 **원인을 감춘 채**
`?error=login_failed` 리다이렉트로 통일한다("실패 원인을 그대로 노출하지 않는다. 내부 구조가
드러날 수 있다" — 핸들러 주석).

**코드만 읽고 "403" 이라고 적었으면 틀렸을 자리다.** 실제 관측값은 `302 + error=login_failed`
이고, 탈퇴 계정이 되살아나지 않는다는 결론은 같지만 클라이언트가 보는 신호는 다르다.

### 그래도 남는 한계

스텁은 **프로바이더의 응답을 흉내낼 뿐** 진짜 Kakao 서버가 아니다. 따라서 실제 Kakao 의
토큰 검증·스코프 동의·에러 응답은 여전히 검증되지 않았다. 검증된 것은 **우리 쪽 가입 경로**
(`loginOrRegister`, 신규 생성, 토큰 발급, 프로필 등록)이지 프로바이더 연동의 정확성이 아니다.
Apple 경로(`AppleIdTokenVerifier`, provider token revoke)는 여전히 미검증이다.

## 발견한 결함

### D-1. 탈퇴한 회원이 댓글·투표·원픽을 계속 할 수 있다 (기능 결함)

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

같은 토큰으로 확인한 다른 엔드포인트. **2차 검증에서 1차의 미검증 3건을 전부 판정했다** —
1차의 400/404 는 인증 거부가 아니라 요청 자체가 성립하지 않아 난 것이었으므로, 선택지를 갖춘
찬반 게시글과 타인 댓글을 만들어 **정상 요청으로 다시** 확인했다.

| 엔드포인트 | 활성 회원(대조군) | **탈퇴 회원** | 판정 |
|---|---|---|---|
| `GET /auth/me` | 200 | **401** `UNAUTHORIZED` | 막힘 |
| `POST /users/profile` | 201 | **401** `UNAUTHORIZED` | 막힘 (`UserProfileService` 가 확인함) |
| `POST /posts/{id}/comments` | 201 | **201 CREATED** | **뚫림 — 댓글 행 생성됨** |
| `POST /posts/{id}/votes` | 200 | **200 OK** | **뚫림 — 투표 행 생성, 득표수 변동** |
| `POST /comments/{id}/pick` | — | **201 CREATED** | **뚫림 — 원픽 행 + 포인트 지급** |
| `GET /badges/me` | 404 | 404 | **판정 불가.** 활성 회원도 404 다(뱃지 데이터 없음). 인증 차이가 아니다 |

**두 번 재현했다.** 위 표는 서버를 재기동한 뒤 **다른 게시글·다른 토큰으로 다시 실행해**
같은 결과를 얻었다(`GET /auth/me` 401 / 투표 200 / 댓글 201 / 원픽 201, 포인트는 다시
INACTIVE 계정에 적립). 일회성 상태 오염이 아니다.

**대조군을 함께 둔 이유:** 1차에서 투표가 400 이었던 것은 GENERAL 게시글에 선택지가 없어서였고,
원픽이 400 이었던 것은 **R-07(자기 댓글 원픽 금지)** 때문이었다. 둘 다 인증과 무관한 거절이라
"막혔다" 로 읽으면 오판이다. 같은 요청이 활성 회원에게 성공하는 것을 먼저 확인한 뒤 탈퇴
회원으로 반복해야 **차이의 원인이 탈퇴 여부**임이 확정된다.

실제로 남은 데이터 (검증 후 삭제함):

```
vote          : id=1  user_id=3  post_option_id=2   voter_state=INACTIVE
comment       : id=4  user_id=3  "withdrawn kakao comment"  author_state=INACTIVE
post_option   : id=1 '사자' vote_count=0  /  id=2 '말자' vote_count=1   ← 득표가 실제로 옮겨감
point_history : id=1 user_id=2 PICKED  +10  (state=ACTIVE)
                id=2 user_id=3 PICKING  +5  (state=INACTIVE)   ← 탈퇴 계정에 포인트 적립
```

**포인트·랭킹 오염이 따라온다.** 원픽은 포인트를 지급하므로 탈퇴 회원이 **자신에게 +5, 상대에게
+10** 을 적립시켰다. 포인트는 TOP 피커 랭킹의 입력이다. `users.point` 집계 컬럼은 배치가 나중에
채우므로(ADR-0028) **즉시 눈에 띄지 않고 다음 재계산 때 드러난다.**

**왜 세 곳이 한꺼번에 뚫렸나.** 이 저장소에는 이미 "세 서비스가 같은 관문을 지나게 한다" 는
패턴이 있다 — `ActivePostGuard` 다. 그런데 그 관문이 보는 것은 **게시글의 생사이지 사용자의
생사가 아니다.** 주석도 "삭제된 게시글에는 새 상호작용을 만들지 않는다" 로 게시글만 말한다.
`grep -rniE "isActive|INACTIVE" src/main/java/app/pickple/{vote,comment,point}/` 결과
**사용자 상태를 보는 코드가 한 줄도 없다.** 즉 결함은 세 개가 아니라 **하나** 다 —
탈퇴 사용자를 거르는 관문이 애초에 없고, 인증 계층은 무상태라 알 수 없다.

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

2차 검증에서 해소된 항목은 아래 "해소됨" 으로 옮겼다. 남은 것만 미검증이다.

### 여전히 미검증

1. **Apple 가입 경로 (`POST /auth/apple`).**
   `AppleIdTokenVerifier` 가 Apple JWKS 로 RS256 서명을 검증한다. 개인키가 Apple 에 있어
   위조할 수 없다(서명 검증의 존재 이유다). 0번에서 503 `APPLE_LOGIN_UNAVAILABLE` 로
   **가입이 막혀 있다는 사실까지만** 확인했다. Kakao 스텁으로 `loginOrRegister` 는 실행했지만
   `AppleAuthService`·nonce 검증·authorization code 교환은 실행되지 않았다.

2. **탈퇴 시 Apple 연결 해제(revoke) 호출.**
   provider token 을 시드하지 않아 `AppleTokenGateway.revokeRefreshToken` 이 호출되지 않았다
   (9번이 `APPLE_MANUAL_REVOCATION_REQUIRED` 로 끝난 이유다). Apple 일시 장애 시 503 을 주고
   로컬 상태를 바꾸지 않는 재시도 경로도 실행하지 못했다.

3. **실제 프로바이더 연동의 정확성.**
   스텁은 응답 모양만 흉내낸다. 진짜 Kakao 의 토큰 검증·스코프 동의·에러 응답은 검증되지 않았다.

4. **게시글 상세·작성 API 는 존재하지 않는다.**
   `PostController` 에 `GET /posts` 목록만 있다. 12번의 "게시글 상세 조회" 를 **목록 조회로
   대체**했고, 8번의 게시글 작성은 SQL 시드로 대체했다. 엔드포인트가 없어 확인할 수 없다.

5. **`GET /badges/me` 의 탈퇴 회원 차단 여부.**
   활성 회원도 404 라(뱃지 데이터 없음) 인증 차이를 판정할 수 없다. 뱃지를 시드해야 한다.

6. **동시성(ADR-0016 CAS 경합).**
   회전 자체는 해시 변화로 확인했지만, 두 요청이 동시에 같은 RT 를 내밀었을 때의 승자/패자
   동작은 단일 순차 호출로는 확인할 수 없다.

### 2차 검증에서 해소됨

- ~~가입 구간 전체~~ → Kakao OAuth2 스텁으로 **실제 HTTP 가입 실행**. 회원 id=3 을
  애플리케이션이 직접 생성. 프로필 등록(201)·탈퇴(200)까지 이어서 확인.
- ~~D-1 의 투표·원픽 경로~~ → 대조군을 두고 **투표 200, 원픽 201 로 뚫림 확정.**
  1차의 400 은 각각 "선택지 없는 게시글" 과 "R-07 자기 댓글 원픽 금지" 때문이었다.
- ~~"탈퇴 계정 재로그인 차단"~~ → 스텁 로그인을 같은 신원으로 다시 돌려 **차단 확인.**
  단 관측되는 형태는 403 이 아니라 `302 + ?error=login_failed` 다("2차 검증" 절 참조).

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

## 재현 방법 — 2차 검증 (실제 가입 + D-1 전체)

제품 코드를 고치지 않는다. 프로바이더 URI 를 **환경변수로만** 로컬 스텁에 돌린다.

```bash
# 1. 스텁 프로바이더 (Kakao 응답 모양만 흉내낸다)
cat > /tmp/stub_idp.py <<'PY'
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse
class H(BaseHTTPRequestHandler):
    def _s(self, c, b):
        r = json.dumps(b).encode()
        self.send_response(c); self.send_header("Content-Type","application/json")
        self.send_header("Content-Length", str(len(r))); self.end_headers(); self.wfile.write(r)
    def do_POST(self):
        if urlparse(self.path).path == "/oauth/token":
            self._s(200, {"access_token":"stub-access-token","token_type":"bearer","expires_in":3600})
        else: self._s(404, {"error":"not_found"})
    def do_GET(self):
        if urlparse(self.path).path == "/v2/user/me":
            self._s(200, {"id":"stub-kakao-9001",
                          "kakao_account":{"email":"stub@example.com","profile":{"nickname":"스텁유저"}}})
        else: self._s(404, {"error":"not_found"})
    def log_message(self,*a): pass
HTTPServer(("127.0.0.1",9500), H).serve_forever()
PY
python3 /tmp/stub_idp.py &

# 2. 서버를 스텁으로 향하게 기동 (IntelliJ 실행 구성의 env override 또는 아래 값을 export)
#    OAUTH_KAKAO_CLIENT_ID=stub-client-id
#    OAUTH_KAKAO_CLIENT_SECRET=stub-client-secret
#    SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KAKAO_AUTHORIZATIONURI=http://127.0.0.1:9500/oauth/authorize
#    SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KAKAO_TOKENURI=http://127.0.0.1:9500/oauth/token
#    SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KAKAO_USERINFOURI=http://127.0.0.1:9500/v2/user/me
until curl -sf -o /dev/null http://localhost:8080/posts; do sleep 2; done

# 3. 실제 가입 — state 와 authorization-request 쿠키를 이어받아야 한다
LOC=$(curl -s -o /dev/null -c /tmp/jar -D- http://localhost:8080/oauth2/authorization/kakao \
      | sed -n 's/^[Ll]ocation: //p' | tr -d '\r')
STATE=$(echo "$LOC" | sed -n 's/.*[?&]state=\([^&]*\).*/\1/p')
curl -s -o /dev/null -b /tmp/jar -D /tmp/cb.txt \
  "http://localhost:8080/login/oauth2/code/kakao?code=stub-auth-code&state=$STATE"

AT=$(sed -n 's/.*accessToken=\([^&[:space:]]*\).*/\1/p' /tmp/cb.txt | tr -d '\r')
curl -s http://localhost:8080/auth/me -H "Authorization: Bearer $AT"          # 200, provider=KAKAO
curl -s -X POST http://localhost:8080/users/profile -H "Authorization: Bearer $AT" \
     -H 'Content-Type: application/json' -d '{"nickname":"스텁"}'             # 201

MYSQL() { docker exec pickple-mysql mysql --default-character-set=utf8mb4 -upickple -pchange-me-app pickple -e "$1"; }

# 4. D-1 을 판정하려면 대조군이 필요하다 — 정상 요청이 성립하는 글을 만든다
MYSQL "INSERT INTO post (user_id,type,category,title,description,created_at,updated_at)
       VALUES (3,'AGREE','ETC','D-1 검증용 찬반글','선택지 있는 글',NOW(),NOW());
       SET @p := LAST_INSERT_ID();
       INSERT INTO post_option (post_id,label,display_order,created_at)
       VALUES (@p,'사자',1,NOW()),(@p,'말자',2,NOW());"

# 대조군: 아직 ACTIVE 일 때 — 투표 200, 댓글 201 이어야 한다
curl -s -X POST http://localhost:8080/posts/2/votes    -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"optionId":1}'
curl -s -X POST http://localhost:8080/posts/2/comments -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"content":"active"}'

# 5. 탈퇴 후 같은 요청을 반복한다
curl -s -X DELETE http://localhost:8080/auth/me -H "Authorization: Bearer $AT"   # 200
curl -s http://localhost:8080/auth/me           -H "Authorization: Bearer $AT"   # 401  ← 막힘
curl -s -X POST http://localhost:8080/posts/2/votes    -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"optionId":2}'   # 200 뚫림
curl -s -X POST http://localhost:8080/posts/2/comments -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"content":"withdrawn"}'  # 201 뚫림

# 원픽은 R-07(자기 댓글 금지)에 걸리므로 타인 댓글이어야 판정된다
MYSQL "INSERT INTO comment (post_id,user_id,content,created_at,updated_at) VALUES (2,2,'다른 사람 댓글',NOW(),NOW());"
CID=$(MYSQL "SELECT id FROM comment WHERE user_id=2 LIMIT 1;" | tail -1)
curl -s -X POST "http://localhost:8080/comments/$CID/pick" -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{}'  # 201 뚫림

# 6. 피해 확인 — 탈퇴 계정에 포인트가 적립된다
MYSQL "SELECT ph.user_id, ph.reason, ph.amount, u.state FROM point_history ph JOIN users u ON u.id=ph.user_id;"

# 7. 정리
MYSQL "DELETE FROM point_history; DELETE FROM comment_pick; DELETE FROM vote; DELETE FROM comment;
       DELETE FROM post_commenter; DELETE FROM post_option; DELETE FROM post;
       DELETE FROM user_refresh_token; DELETE FROM users; UPDATE users SET point=0, vote_count=0;"
kill %1   # 스텁 종료
```
