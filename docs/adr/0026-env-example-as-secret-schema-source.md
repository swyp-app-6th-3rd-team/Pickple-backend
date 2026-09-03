# ADR-0026 — 비밀 스키마의 정본은 `.env.example` 이고 키 누락은 기동 시 잡는다

**상태**: Accepted
**보완**: [ADR-0017](0017-compose-secret-environment-allowlist.md) 이 "여러 목록을 함께 갱신해야 하므로 누락 가능성이 있다" 고 남긴 결과를 이 문서가 구조로 막는다. Compose `environment:` allowlist 경계는 그대로 유지된다.

## 맥락

[ADR-0013](0013-oidc-and-secrets-manager.md) 은 Terraform 이 비밀의 **그릇만 만들고 값은 소유하지 않는다**고 정했다. 값이 tfstate 에 남지 않게 하는 것이 목적이었다.

그런데 **키 집합(스키마)까지 Terraform 이 소유**하게 됐다. `local.secret_keys` 에 16개 키를 손으로 적고, `sync-secrets.sh` 가 `terraform output secret_keys` 로 그 목록을 읽어 로컬 `.env` 에서 값을 찾는다.

문제는 그 리스트의 주석이 스스로 밝히고 있다.

> `.env.example` 에서 도출했다. fetch-secrets.sh 가 이 키들을 `.env` 로 펼친다.

**정본은 실질적으로 이미 `.env.example` 인데 사람이 옮겨 적고 있었다.** 키를 추가하려면 `.env.example` 과 `locals.tf` 두 곳을 고쳐야 하고, 한쪽만 고치면 조용히 어긋난다.

### 실제로 어긋났다

Apple 7키가 2026-08-30([`cb1f5c6`](../../commit/cb1f5c6)) 에 `locals.tf` 에 추가됐지만 `sync-secrets.sh` 가 실행되지 않아, 원격 secret 은 9키에 머물렀다.

`fetch-secrets.sh` 는 원격 JSON 을 `to_entries` 로 그대로 펼치므로 원격에 없는 키는 EC2 `.env` 에도 없다. 그러면 Compose 의 `${OAUTH_APPLE_ENABLED:-false}` 가 발동한다 — **Apple 로그인이 약 4일간 꺼진 채 운영됐고 아무도 몰랐다.**

무증상이었던 이유가 중요하다. `:-` 기본값은 앱이 죽지 않게 해주지만, **동시에 누락을 감춘다.** 헬스체크는 계속 초록이었다. 즉 이 부류의 결함은 "에러가 나지 않아서" 위험하다.

## 결정

### 1. 스키마 정본은 `.env.example` 이다

`.env` 는 `.gitignore` 라 정본이 될 수 없다(팀·CI 가 못 본다). `.env.example` 은 커밋된다.

키 선언 **직전의 연속 주석 블록**에 마커를 둔다. 빈 줄이나 다른 키 선언이 블록을 끊는다.

```
# HS256 이므로 최소 256비트(32바이트) 이상이어야 한다.
# 생성: openssl rand -base64 48
# @secret @generate=48
JWT_SECRET_KEY=
```

| 마커 | 뜻 | 없을 때 |
|---|---|---|
| `@secret` | Secrets Manager 에 올린다 | 로컬 전용 — **올라가지 않는다** |
| `@generate=N` | 미설정 시 `openssl rand -base64 N` | 생성하지 않는다 |
| `@remote-wins` | 원격 값이 로컬 `.env` 보다 우선 | 로컬 우선 |
| `@default=V` | 최종 폴백 | `not-configured` |

`sync-secrets.sh` 와 `locals.tf` 가 **같은 파일을 파싱**한다. 정본이 하나이므로 둘이 어긋날 수 없다.

**값 줄이 아니라 주석 줄에 마커를 두는 이유.** `.env.example` 은 스스로 경고한다 — "값에 따옴표나 후행 공백을 넣지 않는다. 두 파서의 해석이 갈릴 수 있다." `VALUE  # @secret` 형태의 인라인 마커는 `cp .env.example .env` 시 값에 딸려 들어가 Compose 와 Spring 의 해석이 갈린다.

**주석 처리된 키도 스키마에 포함한다.** 쓰지 않는 OAuth 프로바이더는 `#OAUTH_GOOGLE_CLIENT_ID=` 형태로 주석 처리되어 있다(빈 값을 두면 Spring 이 기동을 거부하므로). 이 키들도 원격에 `not-configured` 로 존재해야 하므로, 파싱 규칙은 `#` 접두를 포착한다.

**마커가 키와 떨어지면 로컬 전용이 된다(fail-safe).** 마커와 키 사이에 빈 줄이 끼면 그 키는 올라가지 않는다. 실수의 결과가 "안 올라감" 이지 "잘못 올라감" 이 아니다 — 비밀을 다룰 때 이 방향이 맞다.

### 2. `.env` 전체를 올리지 않는다

마커가 없는 키는 로컬 전용이다. 이 경계가 필요한 이유는 구체적이다.

로컬 `.env` 에는 `SPRING_PROFILES_ACTIVE=local` 이 있다. `docker-compose-ec2.yml` 의 `${SPRING_PROFILES_ACTIVE:-prod}` 에서 `:-` 는 변수가 **미설정** 일 때만 발동하므로, 이 값이 Secrets Manager 를 거쳐 EC2 `.env` 에 들어가면 **운영 서버가 local 프로파일로 뜬다.** `MYSQL_PORT`·`APP_PORT` 같은 로컬 포트 회피 값도 마찬가지다.

### 3. 키 누락은 기동 시 잡는다

`fetch-secrets.sh` 가 기대 키 집합과 원격 JSON 을 대조해, 빠진 키가 있으면 **기동을 실패시킨다.** 기존의 `mysql_root_password == "CHANGE_ME"` 단일 키 검사를 전체 키 집합으로 넓히는 것이다.

이 자리를 고른 이유는 대안이 전부 막혀서다.

- **값 업로드 자동화는 불가능하다.** 값의 원천이 `.gitignore` 된 로컬 `.env` 다. GitHub Secrets 에 넣으면 ADR-0013 의 "워크플로에 `secrets.*` 참조가 하나도 없다" 가 깨진다.
- **CI 가 원격을 대조하는 것도 부적절하다.** `DescribeSecret` 은 키 이름을 주지 않으므로(`SecretVersionsToStages` 는 버전 정보다) 키 목록을 얻으려면 값까지 받는 `GetSecretValue` 를 써야 한다. 게다가 `github_deploy` 역할에는 secretsmanager 권한이 아예 없다 — 권한을 새로 만들고 러너에 비밀값을 노출하게 된다.
- **`fetch-secrets.sh` 는 이미 매 기동(`ExecStartPre`)마다 돌고, 이미 값을 손에 쥐고 있다.** 권한 추가도 값 노출도 없다.

출력에는 키 이름만 남기고 값은 찍지 않는다.

## 결과

- 키를 추가할 때 고치는 파일이 `.env.example` **하나**다. `locals.tf` 와 스크립트는 따라온다.
- 스키마에 키를 추가하고 동기화를 잊으면 **다음 배포가 실패한다.** 조용히 `:-` 기본값으로 도는 일이 사라진다.
- `sync-secrets.sh` 에서 키 이름 하드코딩(`case "$key" in mysql_root_password|...`)이 사라져, 새 비밀의 생성·보존 정책도 `.env.example` 에서 선언된다.
- 마커 규약을 팀이 알아야 한다. `.env.example` 상단 주석과 `terraform/README.md` 에 적는다.
- **`.env.example` 이 문서이자 스키마가 된다.** 주석을 지우다 마커를 함께 지우면 키가 조용히 로컬 전용이 될 수 있다 — `--check` 가 마커 개수와 파싱된 키 개수를 대조해 잡는다.
- Compose `environment:` allowlist 는 그대로 손으로 관리한다(ADR-0017). 비밀을 추가할 때 여전히 Compose 를 함께 봐야 한다 — 다만 이제 **누락하면 기동이 실패해서 드러난다.**

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| `.env` 의 모든 키를 올린다 | `SPRING_PROFILES_ACTIVE=local` 이 운영 서버를 local 프로파일로 띄운다. 비밀이 아닌 로컬 설정까지 Secrets Manager 에 들어간다 |
| `.env` 를 스키마 정본으로 | `.gitignore` 라 팀·CI 가 못 본다. Terraform 이 읽을 수 없다 |
| Compose `environment:` allowlist 도 자동 생성 | ADR-0017 이 지키는 최소권한 경계를 무너뜨린다. 그 ADR 이 "env_file 로 모든 값 전달" 을 이미 기각했다 |
| 별도 스키마 파일(`secrets-schema.yml`) 신설 | 정본이 하나 더 늘어 `.env.example` 과 어긋날 여지가 남는다. 지금 문제의 원인이 "목록이 둘" 이었다 |
| CI 가 원격 secret 과 대조 | `github_deploy` 에 secretsmanager 권한 신설이 필요하고, 키 목록을 얻으려면 값까지 받아 러너에 노출된다 |
| 인라인 마커(`VALUE  # @secret`) | `cp .env.example .env` 시 값에 딸려 들어간다. Compose 와 Spring 의 파싱이 갈린다 |

## 참고

- [ADR-0013](0013-oidc-and-secrets-manager.md) — Terraform 은 그릇만, 값은 소유하지 않는다
- [ADR-0017](0017-compose-secret-environment-allowlist.md) — Compose 는 명시한 비밀만 컨테이너에 전달한다
- [ADR-0015](0015-native-sign-in-with-apple.md) — 이 사고로 4일간 꺼져 있던 기능
