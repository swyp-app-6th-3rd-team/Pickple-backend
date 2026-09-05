# dev QA 로그인 Runbook

QA 스크립트는 `POST /auth/dev/login`으로 Pickple JWT를 받은 뒤 기존 보호 API를 호출한다.
이 API는 테스트 계정을 새로 만들지 않는다. 기존 소셜 로그인과 동일한 JWT·refresh 저장소를 사용한다.

## 1. 활성화 조건

| 항목 | 설정 |
|---|---|
| 활성 프로필 | `dev` 포함. `prod` 또는 `production`이 하나라도 있으면 차단 |
| `DEV_LOGIN_ENABLED` | 기본 `false`. 명시적으로 `true`일 때만 활성화 |
| `DEV_LOGIN_KEY` | 별도로 생성한 QA 전용 키. 32바이트 이상, 512자 이하 |
| `DEV_LOGIN_ALLOWED_USER_IDS` | 기존 QA 계정의 양수 `users.id` 목록. 쉼표로 구분 |

활성화된 상태에서 키나 목록이 비어 있거나 유효하지 않으면 애플리케이션이 시작되지 않는다.
비활성 환경에는 컨트롤러·서비스·HTTP 매핑·OpenAPI 경로가 없다. Security 필터가 먼저 거부하므로
HTTP 응답은 익명 요청 401, 유효한 활성 계정 Bearer 요청 403이다. MVC 매핑 자체는 없다.

프로필은 실행 환경이 제공하는 값이다. 운영 런타임에는 반드시 `prod` 또는 `production`을 지정하고,
dev와 운영의 DB 및 `JWT_SECRET_KEY`를 분리한다. QA 키는 JWT 서명 키와 별도로 관리한다.

## 2. QA 계정 준비와 로컬 실행

1. dev DB에 QA용 소셜 계정을 최초 한 번 준비하고 해당 `users.id`를 확인한다.
   이후 스크립트 실행마다 소셜 로그인할 필요는 없다. 기존 팀 QA 계정도 사용할 수 있다.
2. 해당 ID만 `DEV_LOGIN_ALLOWED_USER_IDS`에 등록한다. 계정은 `ACTIVE`·`ROLE_USER`여야 한다.
   없는 계정·탈퇴 계정·관리자는 허용 목록에 있어도 401이다. 계정 상태를 이 API로 복구하지 않는다.
3. 저장소 루트의 커밋하지 않는 `.env`에 위 세 값을 설정한다. 키는 `openssl rand -hex 32`로
   생성할 수 있다. `.env.example`에는 활성화하지 않은 빈 설정만 있다.
4. 기존 로컬 DB를 실행하고 `local,dev` 프로필로 앱을 시작한다.

```powershell
# 저장소 루트, Java 25 및 Docker 사용 가능 환경
docker compose --env-file .env -f docker/docker-compose-local.yml up -d
.\gradlew.bat bootRun --args='--spring.profiles.active=local,dev'
```

`local` 프로필이 `.env`를 읽고 `dev` 프로필이 QA 기능을 제공한다. IntelliJ에서는 Active profiles를
`local,dev`로 지정한다. `dev`만 쓰는 런타임은 `.env`를 자동으로 읽지 않으므로 환경변수로 주입한다.
로컬 앱 포트 기본값은 8080이며, 변경했다면 아래 `BASE_URL`도 맞춘다.

## 3. 요청과 응답

```http
POST /auth/dev/login
Content-Type: application/json
X-QA-Login-Key: <서버와 동일한 QA 키>

{"userId":123}
```

```json
{
  "code": "OK",
  "message": "정상 처리되었습니다.",
  "returnObject": {
    "accessToken": "<Pickple access JWT>",
    "refreshToken": "<Pickple refresh JWT>"
  }
}
```

성공 응답에는 `Cache-Control: no-store`, `Pragma: no-cache`가 포함된다. 키 누락·불일치 및
허용하지 않는 계정은 같은 401로 반환한다. `userId` 누락·null·0·음수 및 깨진 JSON은 400이다.

다음은 Bash·curl·jq를 사용하는 최소 호출 예다. `QA_USER_ID`를 준비한 ID로 바꾼다.
원격 dev를 호출할 때는 `BASE_URL`에 HTTPS 주소를 사용한다.

```bash
set -euo pipefail
set +x
BASE_URL=http://localhost:8080
QA_USER_ID=123
read -rsp 'QA login key: ' QA_LOGIN_KEY
printf '\n'

LOGIN_RESPONSE=$(printf 'X-QA-Login-Key: %s\n' "$QA_LOGIN_KEY" |
  curl --silent --show-error --fail "$BASE_URL/auth/dev/login" \
    --header @- --header 'Content-Type: application/json' \
    --data "$(jq -cn --argjson id "$QA_USER_ID" '{userId:$id}')")
unset QA_LOGIN_KEY
ACCESS_TOKEN=$(jq -er '.returnObject.accessToken | select(type == "string" and length > 0)' <<<"$LOGIN_RESPONSE")
REFRESH_TOKEN=$(jq -er '.returnObject.refreshToken | select(type == "string" and length > 0)' <<<"$LOGIN_RESPONSE")
unset LOGIN_RESPONSE

printf 'Authorization: Bearer %s\n' "$ACCESS_TOKEN" |
  curl --silent --show-error --fail "$BASE_URL/auth/me" --header @- |
  jq -e --argjson id "$QA_USER_ID" '.code == "OK" and .returnObject.userId == $id'

# 이 아래에 ACCESS_TOKEN을 사용한 QA 시나리오 호출을 추가한다.
```

응답 전체나 키·JWT를 로그에 출력하지 않는다. 이 API는 Shell 호출용이며 브라우저 CORS의
허용 헤더 목록을 넓히지 않는다.

## 4. 재발급·로그아웃과 병렬 실행

- 재발급: `POST /auth/mobile/refresh`, JSON `{"refreshToken":"<현재 refresh JWT>"}`.
  새 access/refresh를 모두 저장하고 이전 refresh는 버린다. 회전된 이전 토큰은 401이다.
- 로그아웃: `POST /auth/logout`에 해당 계정의 `Authorization: Bearer <access JWT>`를 보낸다.
  저장된 refresh가 폐기되며 이후 재발급은 401이다.
- 사용자당 refresh는 하나다. 같은 QA 계정으로 다시 로그인하면 이전 refresh를 대체하므로,
  병렬 스크립트에는 서로 다른 허용 계정을 배정한다.
- 로그아웃, QA 키 교체, 기능 비활성화는 이미 발급한 access JWT를 즉시 만료시키지 않는다.
  기존 access TTL과 활성 계정 확인 정책이 적용된다. QA 종료 시 각 계정을 로그아웃하고
  `DEV_LOGIN_ENABLED=false`로 변경한 뒤 앱을 재시작한다.

## 5. 현재 EC2 develop 배포와의 구분

현재 `docker/docker-compose-ec2.yml`은 develop 배포에도 기본 프로필로 `prod`를 사용한다.
따라서 **이 코드를 배포하는 것만으로는 QA 로그인이 열리지 않는다.** 이 Compose는 위 QA
환경변수도 컨테이너에 전달하지 않으며, `.env.example`의 QA 값에는 공용 Secrets Manager
스키마 마커를 붙이지 않았다.

원격 QA에서 사용하려면 dev 전용 런타임에 `SPRING_PROFILES_ACTIVE=dev`와 위 세 환경변수를
명시적으로 전달해야 한다. Compose를 사용한다면 해당 dev 배포의 `app.environment`에도
세 변수를 등록해야 한다. 호스트 `.env`만 바꾸면 컨테이너에는 전달되지 않는다.
운영용 설정·키를 재사용하지 않으며, 실제 서버 설정·Secrets Manager 변경과 배포는 별도 작업이다.

## 6. 검증

```powershell
.\gradlew.bat test --tests app.pickple.config.DevLoginConfigurationIT --tests app.pickple.auth.service.DevLoginServiceTest --tests app.pickple.auth.controller.DevLoginControllerIT --tests app.pickple.auth.controller.DevLoginProductionIT --tests app.pickple.auth.AuthFlowIT --tests app.pickple.architecture.ArchitectureTest
```

프로필·활성화 조건별 빈/HTTP 매핑 부재, 운영 혼합 프로필에서 실제 보안 필터와 OpenAPI 차단,
잘못된 설정의 기동 실패, 계정 A·B의 실제 JWT 인증, refresh 해시 저장·회전·로그아웃,
잘못된 키·본문·계정 거부를 검증한다. DB 통합 테스트에는 Docker가 필요하다.
