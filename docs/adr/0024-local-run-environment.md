# ADR-0024 — 로컬 실행은 `.env` 임포트로, compose 는 local 과 ec2 둘만 둔다

**상태**: Accepted

이 ADR 은 **ADR-0012 의 §compose 파일 구성만** 대체한다. ADR-0012 의 나머지 결정
(단일 EC2 + docker-compose, 비용 판정, `/data` 볼륨 분리)은 그대로 유효하다.

ADR-0012 본문은 **고치지 않았다**. ADR 은 불변이므로 대체 관계는 대체하는 쪽인 이 문서에만 적는다.
ADR-0012 가 언급하는 `docker-compose-prod.yml`(32·70 줄)은 이 ADR 로 삭제됐으나,
그 문장들은 당시 판단의 근거이므로 그대로 둔다.
같은 이유로 PRD-007(23·73 줄)의 "기존 `docker-compose-prod.yml` 을 수정하지 않는다"도 손대지 않는다.

## 맥락

IntelliJ 에서 `PickpleApplication` 을 실행하면 기동이 실패한다.

```
JWT_SECRET_KEY 는 32바이트 이상이어야 합니다. `openssl rand -base64 48` 로 생성하세요.
```

메시지는 "키가 짧다"고 말하지만 실제 원인은 **키가 주입되지 않은 것**이다.
`.env` 에는 128 바이트 값이 정상적으로 들어 있다.

`application.yml:83` 의 `secret-key: ${JWT_SECRET_KEY}` 는 **환경변수**를 참조한다.
`.env` 파일을 참조하는 문법이 아니다. `.env` 를 읽어 프로세스 환경에 넣어주는 주체는
**Docker Compose 뿐**이고, JVM 에도 Spring Boot 에도 `.env` 라는 개념이 없다.

`AuthProperties` 의 compact constructor 가 `secretKey == null` 과 `length < 32` 를
**한 메시지로 묶은 탓에** 오진을 유도한다. 실제로 걸린 가지는 앞쪽(null)이다.

### 결손은 IDE 한정이 아니다

`build.gradle` 에 `bootRun` 커스터마이징이 전혀 없다(`environment`·`systemProperty` 검색 0 건).
따라서 `./gradlew bootRun` 도 똑같이 실패한다. **IDE 런 컨피그만 고치면 CLI 경로가 깨진 채 남는다.**

Compose 경로만 정상인 이유는 `docker-compose-dev.yml` 이
`JWT_SECRET_KEY: ${JWT_SECRET_KEY:?...}` 로 명시 배선하기 때문이다.

### 두 번째 결손 — 프로파일 미지정

런 컨피그에 `SPRING_PROFILES_ACTIVE` 가 없다. 기동에 성공해도 이미 존재하는
`application-local.yml` 이 적용되지 않고 기본 프로파일로 돈다.

### compose 파일 이름이 역할을 감춘다

`docker-compose-dev.yml` 은 `app` 서비스에 `profiles: ["app"]` 을 걸어 기본 `up` 이
MySQL 만 띄우게 해 두었다. 즉 실제 용도는 local 인데 파일명은 dev 라고 말한다.
이 사실은 `profiles` 트릭을 알아야만 파악된다.

조사에서 더 큰 사실이 나왔다.

- `docker-compose-dev.yml` 과 `docker-compose-prod.yml` 은 **`diff` 결과 완전히 동일**하다
- 두 파일 모두 코드·CI·스크립트 참조가 **0 건**이다
- `.github/workflows/deploy-develop.yml` 이 실어 보내는 것은 `docker-compose-ec2.yml` 과 `Caddyfile` 뿐이다(99~118 줄)

"환경별 파일 분리"가 이미 이름만 남고 실체가 없었다.

## 결정

### 1. `application-local.yml` 에서 `.env` 를 프로퍼티 소스로 임포트한다

```yaml
spring:
  config:
    import: "optional:file:.env[.properties]"
```

- `optional:` 이라서 `.env` 가 없는 CI·EC2 에서도 기동을 막지 않는다
- `[.properties]` 는 확장자 없는 파일의 **타입 힌트** 문법이다. `.env` 는 이미 `key=value` 형식이라 그대로 파싱된다
- 임포트된 프로퍼티는 `JWT_SECRET_KEY` 라는 **이름 그대로** 등록된다. 플레이스홀더는 리터럴 이름으로 해석되므로
  **`application.yml` 의 기존 `${...}` 를 한 줄도 고치지 않아도 된다**

주입 지점을 **실행 경로 위층**에 두는 것이 핵심이다. IDE·Gradle·테스트가 설정을 로드하는 시점은 같으므로
한 곳에 배선하면 세 경로가 한 번에 해결된다.

### 2. compose 는 `local` 과 `ec2` 둘만 둔다

`docker-compose-local.yml` 을 신설하고(MySQL 만, `app` 서비스 없음),
동일 사본인 `docker-compose-dev.yml`·`docker-compose-prod.yml` 을 삭제한다.
배포는 `docker-compose-ec2.yml` 로 일원화한다.

```
전                              후
  docker-compose-dev.yml   ─┐    docker-compose-local.yml  ← MySQL 만, 앱은 IDE
  docker-compose-prod.yml  ─┘    docker-compose-ec2.yml    ← 배포 일원화
  docker-compose-ec2.yml
```

`app` 서비스를 빼면 `profiles: ["app"]` 같은 숨은 장치가 필요 없어지고,
`docker compose -f docker-compose-local.yml up -d` 가 의도 그대로 읽힌다.

### 3. `.run/` 에 공유 런 컨피그를 커밋한다 — 시크릿은 넣지 않는다

`.idea/` 는 `.gitignore` 대상이지만 `.run/` 은 아니다. `SPRING_PROFILES_ACTIVE=local` 만 담는다.
시크릿은 `.env` 에 남긴다.

## 결과

- IntelliJ ▶︎ 와 `./gradlew bootRun` 양쪽이 추가 수작업 없이 기동한다
- 프로파일 결손이 함께 해결되어 `application-local.yml` 이 실제로 적용된다
- compose 세 파일이 두 개로 줄고, 각 이름이 역할과 일치한다

### 트레이드오프

**`.env` 가 이중 용도가 된다.** Compose 변수 보간용이면서 Spring 프로퍼티 소스가 된다.
Compose 는 `MYSQL_*`·`COMPOSE_PROJECT_NAME` 을 쓰고 Spring 은 그것들을 무시하므로 실해는 없지만,
한 파일이 두 문법 규약을 동시에 만족해야 한다(값의 따옴표·공백 주의).

**EC2 가 아닌 환경에 배포할 일반형 compose 가 없어진다.** 현재 그런 계획이 없고,
필요해지면 `-ec2.yml` 에서 caddy·EIP 의존을 걷어내 새로 만드는 편이
**동일 사본 2 개를 방치하는 것보다 낫다**.

**`.env` 의 빈 값은 여전히 함정이다.** `:기본값` 은 변수가 *미정의* 일 때만 발동한다.
`OAUTH_GOOGLE_CLIENT_ID=` 처럼 빈 값을 두면 빈 문자열이 주입되어 Spring OAuth2 가 기동을 거부한다.

## 검토한 대안과 기각 사유

### `build.gradle` 의 `bootRun` 에서 `.env` 를 파싱해 주입 — 기각

IntelliJ 의 Spring Boot 런 컨피그는 **`bootRun` 태스크를 거치지 않는다**.
CLI 는 고쳐지지만 IDE ▶︎ 버튼은 여전히 실패한다. 빌드스크립트에 파싱 로직이라는 새 배선도 생긴다.

### `.run/` 런 컨피그에 환경변수를 직접 적기 — 기각

`.run/` 은 커밋 대상이므로 시크릿을 XML 에 적으면 저장소에 그대로 들어간다.
`application.yml:82` 주석이 명시적으로 거부한 방식이다. 시크릿 없이 프로파일만 담으면
**주입 문제는 미해결로 남는다** — 그래서 이 방식은 결정 3 에서 *보조 수단*으로만 채택했다.

### IntelliJ EnvFile 플러그인 — 기각

설치된 `dotenv` 는 `ru.adelf.idea.dotenv` 로 **문법 강조 전용**이라 주입 기능이 없다.
주입 기능이 있는 EnvFile(`ru.endlesscode.envfile`)은 미설치다.
팀원마다 플러그인 설치를 강제해야 하고, CLI 경로는 여전히 미해결로 남는다.

### `application-local.yml` 을 `.gitignore` 에 넣고 시크릿을 담기 — 기각

최초에 검토된 방향이나 두 가지 이유로 기각했다.
클래스패스 리소스라 ignore 하면 clone 한 팀원의 local 프로파일이 **깨진다**.
그리고 시크릿을 클래스패스에 두는 것은 `application.yml:82` 주석이 거부한 방식이다.
시크릿 격리는 `.env` + `.gitignore` 로 **이미 달성돼 있다**.

### `docker-compose-dev.yml` 을 남기고 `profiles` 만 제거 — 기각

dev 를 "진짜 dev 환경"으로 되돌리는 선택지인데, **되돌릴 dev 가 없다**.
prod 와 바이트 단위로 동일한 사본일 뿐이라 dev 환경을 표현한 적이 없다.
남기면 "배포에 어느 파일을 쓰나"라는 질문이 계속 재발한다.
