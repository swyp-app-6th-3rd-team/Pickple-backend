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
docker compose --env-file .env -f docker/docker-compose-local.yml up -d
```

앱은 컨테이너로 띄우지 않는다. DB 만 컨테이너, 앱은 IDE 나 Gradle 로 실행한다.

> `--env-file .env` 를 빼면 `MYSQL_PASSWORD ... missing a value` 로 실패한다.
> Compose 는 변수 보간용 `.env` 를 **실행 위치가 아니라 compose 파일과 같은 디렉터리**에서
> 찾는데, compose 파일은 `docker/` 에 있고 `.env` 는 루트에 있기 때문이다.
> Spring 의 `spring.config.import` 는 작업 디렉터리 기준이라 이 플래그가 필요 없다.

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
| `MYSQL_PASSWORD ... missing a value` | compose 명령에 `--env-file .env` 가 빠졌다 |
| Flyway checksum mismatch | 오래된 컨테이너다. `docker compose --env-file .env -f docker/docker-compose-local.yml down -v` 후 재기동 |

## ERD

문서(`/scalar`)가 싣는 ERD 는 세 파일에서 나온다.

| 소스 | 산출물 | 무엇 |
|---|---|---|
| `docs/erd/erd.mmd` | `docs/erd/erd.svg` | 물리 ERD — 자료형까지. 리뷰용 |
| `docs/erd/erd-logical.mmd` | `/docs/erd.svg` | 논리 ERD — 문서 본문에 실리는 미리보기 |
| `docs/erd/erd-logical.architecture.json` | `/docs/erd.html` | 상세 페이지 — 확대·팬·테마 전환 |

소스를 고쳤으면 다시 렌더한다. 셋 다 한 번에 갱신된다.

```bash
scripts/render-erd.sh          # 렌더
scripts/render-erd.sh --check  # 렌더 없이 "무엇이 낡았는지"만 확인
```

CI 가 실제 스키마와 ERD 를 대조한다(`erd-drift` 잡). 로컬에서 미리 보려면:

```bash
scripts/check-erd-drift.sh --local   # pickple-mysql 컨테이너가 떠 있어야 한다
```

### erd.html 을 못 만드는 경우

`erd.html` 렌더만 archify(Claude Code 스킬, `~/.claude/skills/archify`) 설치본을
요구한다. 없으면 `render-erd.sh` 가 그 단계를 건너뛰고 SVG 만 갱신한다(에러 아님).

그래서 **`erd-logical.architecture.json` 을 고쳤는데 archify 가 없으면 CI 가 막힌다.**
이때는 spec 만 고쳐 커밋하고 PR 에 "erd.html 렌더 필요" 를 적는다 — spec 은 텍스트라
누구나 고칠 수 있고, 렌더는 archify 보유자가 대신 돌려 커밋에 얹는다.

검사를 끄지 않는 이유는 `DocsConfig` 가 이 페이지를 "실제로 읽을 때 여는 정본" 으로
안내하기 때문이다. 여기가 낡으면 독자가 옛 그림을 본다.

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
