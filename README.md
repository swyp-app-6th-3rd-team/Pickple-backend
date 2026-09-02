# Pickple Backend

살까 말까 고민되는 상품을 공유하고,
다른 사용자의 구매 판단을 받을 수 있는 서비스의 백엔드 API입니다.

## 로컬 실행

### 1. 환경변수 준비

```bash
cp .env.example .env
```

`.env` 를 열어 최소 세 값을 채운다. 나머지는 기본값으로 뜬다.

| 변수 | 비고 |
|---|---|
| `MYSQL_ROOT_PASSWORD` | 아무 값이나 |
| `MYSQL_PASSWORD` | 아무 값이나 |
| `JWT_SECRET_KEY` | `openssl rand -base64 48` 로 생성. **32바이트 미만이면 기동 실패** |

Apple 로그인을 쓰지 않는다면 `OAUTH_APPLE_ENABLED=false` 로 둔다.
`true` 면 Apple 관련 6개 값이 전부 필수가 된다.

### 2. MySQL 기동

```bash
docker compose -f docker/docker-compose-local.yml up -d
```

앱은 컨테이너로 띄우지 않는다. DB 만 컨테이너, 앱은 IDE 나 Gradle 로 실행한다.

### 3. 애플리케이션 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

IntelliJ 를 쓰면 커밋된 `PickpleApplication` 실행 구성(`.run/`)을 그대로 쓰면 된다.
프로파일이 이미 `local` 로 지정돼 있다.

`Started PickpleApplication` 과 `The following 1 profile is active: "local"` 이 보이면 성공이다.

### `.env` 는 어떻게 읽히나

`.env` 는 표준이 아니라 관례다. JVM 도 Spring Boot 도 `.env` 라는 개념이 없고,
원래는 Docker Compose 만 이 파일을 읽는다.

그래서 `application-local.yml` 에 임포트를 걸어 두었다.

```yaml
spring:
  config:
    import: "optional:file:.env[.properties]"
```

이 한 줄 덕분에 IDE·Gradle·Compose 세 경로가 모두 같은 `.env` 를 본다.
자세한 배경은 [ADR-0024](docs/adr/0024-local-run-environment.md) 참조.

### 문제가 생기면

| 증상 | 원인 |
|---|---|
| `JWT_SECRET_KEY 는 32바이트 이상이어야 합니다` | 값이 짧거나 **아예 없다**. 두 경우가 같은 메시지다 |
| `Client id of registration must not be empty` | `.env` 에 `OAUTH_..._CLIENT_ID=` 처럼 **빈 값**이 있다. 기본값은 변수가 미정의일 때만 발동한다 |
| Apple 관련 기동 실패 | `OAUTH_APPLE_ENABLED=true` 인데 6개 값이 덜 찼다 |
| Flyway checksum mismatch | 오래된 컨테이너다. `docker compose -f docker/docker-compose-local.yml down -v` 후 재기동 |

## 배포

develop 브랜치에 머지되면 `.github/workflows/deploy-develop.yml` 이
`docker/docker-compose-ec2.yml` 을 EC2 로 실어 보낸다. compose 파일은 두 개뿐이다.

| 파일 | 용도 |
|---|---|
| `docker/docker-compose-local.yml` | 로컬 — MySQL 만 |
| `docker/docker-compose-ec2.yml` | 배포 — 앱 + MySQL + Caddy |

## Template

This project was initialized from
[sakilla-ddd-template](https://github.com/swyp-app-6th-3rd-team/sakilla-ddd-template).

템플릿의 Spring Boot, 인증, 데이터베이스, 테스트 및 CI 구조를 기반으로
Pickple 제품 도메인을 개발합니다.
