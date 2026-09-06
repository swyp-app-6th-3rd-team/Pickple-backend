# QA 아이디·비밀번호 로그인 Runbook

QA 자동화는 `POST /auth/login`으로 Pickple JWT를 받은 뒤 기존 보호 API를 호출한다.
URI에는 실행 환경 이름을 넣지 않으며, 컨트롤러 등록 여부만 dev 설정으로 구분한다.

## 1. 활성화 조건

| 항목 | 설정 |
|---|---|
| 활성 프로필 | `dev` 포함. `prod` 또는 `production`이 하나라도 있으면 차단 |
| `QA_LOGIN_ENABLED` | 기본 `false`. 명시적으로 `true`일 때만 활성화 |
| `QA_LOGIN_ID` | QA 요청에서 사용할 로그인 아이디 |
| `QA_LOGIN_PASSWORD_HASH` | BCrypt 해시. cost 10~14. 비밀번호 원문을 넣지 않음 |
| `QA_LOGIN_USER_ID` | JWT를 발급할 기존 활성 `ROLE_USER`의 `users.id` |

활성화된 상태에서 설정이 비거나 유효하지 않으면 애플리케이션이 시작되지 않는다. 이 기능은
계정을 생성하거나 상태·권한을 바꾸지 않는다. 대상 계정이 없거나 탈퇴했거나 관리자면 401이다.

비밀번호는 코드·DB·문서에 저장하지 않는다. 팀 비밀 관리 절차로 BCrypt 해시를 생성해 환경변수로
주입하고 원문은 QA 실행 주체만 보관한다. OAuth client secret이나 `JWT_SECRET_KEY`를 비밀번호로
재사용하지 않는다.

## 2. 로컬 실행

저장소 루트의 커밋하지 않는 `.env`에 네 설정을 넣고 `local,dev` 프로필로 실행한다.

```powershell
docker compose --env-file .env -f docker/docker-compose-local.yml up -d
.\gradlew.bat bootRun --args='--spring.profiles.active=local,dev'
```

## 3. 요청과 응답

```http
POST /auth/login
Content-Type: application/json

{"loginId":"qa-user","password":"<QA 비밀번호>"}
```

성공하면 기존 모바일 로그인과 같은 `returnObject.accessToken`·`returnObject.refreshToken`을 반환한다.
잘못된 아이디·비밀번호와 사용할 수 없는 대상 계정은 동일한 401이고, 잘못된 요청 형식은 400이다.
응답에는 `Cache-Control: no-store`, `Pragma: no-cache`가 포함된다.

```bash
set -euo pipefail
set +x
BASE_URL=http://localhost:8080
read -rp 'QA login id: ' QA_LOGIN_ID_INPUT
read -rsp 'QA password: ' QA_LOGIN_PASSWORD_INPUT
printf '\n'

LOGIN_RESPONSE=$(jq -cn --arg loginId "$QA_LOGIN_ID_INPUT" --arg password "$QA_LOGIN_PASSWORD_INPUT" \
  '{loginId:$loginId,password:$password}' | \
  curl --silent --show-error --fail "$BASE_URL/auth/login" \
    --header 'Content-Type: application/json' --data-binary @-)
unset QA_LOGIN_PASSWORD_INPUT

ACCESS_TOKEN=$(jq -er '.returnObject.accessToken' <<<"$LOGIN_RESPONSE")
REFRESH_TOKEN=$(jq -er '.returnObject.refreshToken' <<<"$LOGIN_RESPONSE")
unset LOGIN_RESPONSE

curl --silent --show-error --fail "$BASE_URL/auth/me" \
  --header "Authorization: Bearer $ACCESS_TOKEN"
```

응답 전체, 비밀번호, access/refresh token을 로그에 출력하지 않는다. 같은 사용자로 다시 로그인하면
사용자당 하나인 refresh token이 교체되므로 병렬 실행 시 충돌할 수 있다.

## 4. 배포 경계

현재 EC2 develop Compose는 기본 프로필로 `prod`를 사용하므로 이 코드를 배포하는 것만으로는
QA 로그인이 열리지 않는다. 원격 QA에서 사용하려면 dev 런타임에 위 설정을 별도로 주입해야 한다.
OAuth provider 설정·Secrets Manager 변경·배포는 이 변경 범위에 포함하지 않는다.
