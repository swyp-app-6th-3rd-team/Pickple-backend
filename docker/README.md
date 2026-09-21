# docker/

컨테이너 관련 파일을 한곳에 모았다. 프로젝트 루트에 흩어져 있던 것을 옮긴 것이고,
**동작은 그대로다.**

| 파일 | 쓰이는 곳 | 누가 읽나 |
|---|---|---|
| `Dockerfile` | 앱 이미지 빌드 | `deploy-develop.yml` 의 `docker build -f docker/Dockerfile .` |
| `docker-compose-local.yml` | 로컬 개발 (MySQL 만) | 사람이 직접 |
| `docker-compose-ec2.yml` | 배포 (앱 + MySQL + Caddy) | 워크플로가 EC2 로 실어 보낸다 |
| `Caddyfile` | 리버스 프록시·TLS 종단 | `docker-compose-ec2.yml` 이 마운트 |

## 로컬 개발

앱은 IDE 에서 띄우고 MySQL 만 컨테이너로 쓴다(ADR-0024).

```bash
docker compose --env-file .env -f docker/docker-compose-local.yml up -d
```

### `--env-file .env` 는 생략할 수 없다

Compose 는 변수 보간(`${MYSQL_PASSWORD}`)에 쓸 `.env` 를 **실행 위치가 아니라 compose 파일과
같은 디렉터리**에서 찾는다. compose 파일이 `docker/` 로 오면서 Compose 는 `docker/.env` 를
보게 됐지만 `.env` 는 루트에 있다.

`env_file:` 로는 해결되지 않는다. 그건 **컨테이너에 주입할 환경변수**를 지정하는 것이고,
변수 보간은 그보다 이른 **파싱 시점**에 일어나기 때문이다(실험으로 확인).

EC2 배포는 영향이 없다. `deploy-develop.yml` 이 파일을 `/opt/pickple/docker-compose-ec2.yml` 로
평탄화해 배치하므로 `.env` 와 같은 디렉터리에 놓인다.

## 빌드 컨텍스트는 루트다

`Dockerfile` 이 `docker/` 안에 있어도 **빌드 컨텍스트는 저장소 루트**여야 한다.
Gradle 소스(`src/`, `build.gradle.kts`)를 `COPY` 하기 때문이다.

```bash
docker build -f docker/Dockerfile -t pickple:local .
#            ^^^^^^^^^^^^^^^^^^^^                  ^
#            Dockerfile 위치                       컨텍스트는 루트
```

`-f` 없이 `docker build docker/` 로 하면 소스를 찾지 못해 빌드가 깨진다.

## EC2 위에서는 경로가 평평하다

워크플로는 이 디렉터리의 파일을 base64 로 실어 보내 **`/opt/pickple/` 에 이름만으로 떨어뜨린다.**

```
저장소                              EC2
docker/docker-compose-ec2.yml  →   /opt/pickple/docker-compose-ec2.yml
docker/Caddyfile               →   /opt/pickple/Caddyfile
```

그래서 EC2 에서 다루는 명령에는 `docker/` 가 붙지 않는다.

```bash
cd /opt/pickple
sudo docker compose -f docker-compose-ec2.yml ps
```

systemd 유닛(`pickple.service`)도 `/opt/pickple/docker-compose-ec2.yml` 을 가리킨다.
**이 경로를 바꾸려면 `terraform/templates/user-data.sh.tftpl` 의 유닛 정의도 함께 고쳐야 한다.**

## 주의

- **`docker-compose-ec2.yml` 의 포트는 terraform 과 짝이 맞아야 한다.**
  MySQL 호스트 포트(13307)는 `terraform/variables.tf` 의 `mysql_host_port` 와 같은 값이어야
  보안그룹이 열리는 포트와 실제 바인딩이 일치한다(ADR-0023).
- **로그는 `/data/logs` 에 bind 된다**(ADR-0025). bind mount 는 named volume 과 달리
  이미지의 소유권을 복사하지 않으므로, 호스트에 `chown 100:101 /data/logs` 가 되어 있어야
  앱(uid 100)이 로그를 쓸 수 있다. `user-data.sh.tftpl` 이 처리한다.
- **`.env` 는 저장소에 없다.** EC2 에서는 `fetch-secrets.sh` 가 Secrets Manager 에서 받아 만든다(ADR-0013).

## QA·심사·테스터 계정 발급

V18이 적용된 앱 이미지에서 `QaAccountCommand`를 실행한다. 공개 회원가입 API는 없으며,
QA·심사·테스터별로 서로 다른 아이디와 닉네임을 발급한다. 운영 명령은 EC2의 `/opt/pickple`에서
실행한다. 실행 중인 `app` 컨테이너의 DB 접속 환경변수를 그대로 사용하므로 대상 서버를 먼저 확인한다.

**안전 — 실행 중인 서비스와 생성 도구 도움말 확인. DB 변경 없음.**

```bash
cd /opt/pickple
sudo docker compose -f docker-compose-ec2.yml ps app
sudo docker compose -f docker-compose-ec2.yml exec app java \
  -Dloader.main=app.pickple.auth.infra.QaAccountCommand \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher --help
```

**주의 — 해당 DB에 사용자와 자격증명 생성. 대화형 터미널에서 실행하며 `-T`를 붙이지 않는다.**

```bash
sudo docker compose -f docker-compose-ec2.yml exec app java \
  -Dloader.main=app.pickple.auth.infra.QaAccountCommand \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

입력 순서는 아이디, 닉네임, 숨김 비밀번호, 비밀번호 확인이다. 아이디는 영문·숫자·`._@+-`
1~100자이며 대소문자를 구분한다. 닉네임은 한글·영문·숫자 1~5자, 활성 회원과 중복 불가다.
비밀번호는 12자 이상·UTF-8 72바이트 이하이며 BCrypt 해시만 DB에 저장한다.
비밀번호를 CLI 인자·셸 기록·Git·로그에 남기지 않는다.

성공하면 `QA 계정을 생성했습니다`와 종료 코드 0을 확인한다. 입력 검증 실패나 DB 오류는
종료 코드 1이다. 중복 아이디·닉네임은 기존 계정을 덮어쓰지 않고 생성 트랜잭션 전체를 롤백한다.
DB 오류 시 대상 DB·V18 적용·중복 여부를 확인한다. `--help` 성공만으로 생성 성공을 판단하지 않는다.

발급 후 확인 순서:

1. API 클라이언트에서 `POST /auth/login`에 발급한 `loginId`, `password`를 전송한다.
   200 응답의 `returnObject`는 `accessToken`, `refreshToken` 두 필드다.
2. access token을 Bearer 인증으로 전달해 `GET /auth/me`의 본인 정보와 `provider=QA`를 확인한다.
3. 잘못된 비밀번호가 401인지 확인한다. 반복 실패 시험은 제출용 계정과 별도로 한다.
4. 60초 안에 같은 IP·아이디 10회 또는 같은 IP 전체 60회를 넘으면 429와 `Retry-After` 초가 나온다.
   정해진 시간 뒤 재시도한다. 이 제한은 같은 IP의 다른 ID를 무제한 바꾸는 시도도 막는다.

새 QA 로그인을 전체 중단하려면 `application.yml`의 `app.auth.qa-login.enabled`를 false로 변경하고
새 이미지를 배포한다. `/auth/login`은 미인증 요청 401, 인증 요청 403으로 차단되며 OpenAPI에서 사라진다.
이미 발급한 토큰은 이 설정만으로 폐기되지 않는다. 개별 계정을 완전히 폐기할 때는 해당 계정의
`DELETE /auth/me`를 사용한다(주의: 실제 회원 탈퇴). 자격증명·refresh token이 삭제되고 기존 access
token도 계정 상태 검사에서 차단된다. 테스트 목적으로 제출용 계정을 탈퇴시키지 않는다.

로컬 앱을 IDE/Gradle로 실행하는 경우에도 같은 클래스가 지원 경로다. 먼저 `bootJar`를 빌드하고
로컬 DB에 V18까지 적용한다. **주의 — 로컬 DB 접속용 `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`를 현재 터미널에 설정한 후** 실행한다.
도구는 `.env`를 자동으로 읽지 않는다. 비밀번호는 숨김 입력을 사용한다.

```powershell
java "-Dloader.main=app.pickple.auth.infra.QaAccountCommand" `
  -cp "build/libs/pickple-backend-0.0.1-SNAPSHOT.jar" `
  org.springframework.boot.loader.launch.PropertiesLauncher
```

IP 제한은 단일 앱 인스턴스 메모리 기준이며 재시작 시 초기화된다. 같은 회사·와이파이 사용자는
공인 IP 한도를 공유한다. 운영은 앱 포트를 외부에 공개하지 않고 Caddy를 거친다. Caddy는
클라이언트의 `Forwarded`를 제거하고 실제 접속 IP를 전달한다. 프록시를 추가하거나 앱 인스턴스를
늘릴 때는 신뢰할 IP 전달 경로와 제한 저장소를 재검토해야 한다.

## 관련 문서

- ADR-0012 — 단일 EC2 + docker-compose 구성
- ADR-0013 — 배포 자격증명(OIDC)과 런타임 비밀(Secrets Manager) 분리
- ADR-0022 — Caddy TLS 종단
- ADR-0024 — 로컬 실행 환경과 compose 파일 구성
- ADR-0025 — 로그 단일 파일·영속 EBS
- `terraform/README.md` — 인프라 운영
