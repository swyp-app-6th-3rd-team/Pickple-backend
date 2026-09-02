# PRD-008 — develop 도메인 연결과 자동 배포

**상태**: 진행 중
**이슈**: #36

## 무엇을 왜

PRD-007 이 인프라를 실측하고 걷어냈다. 지금 계정에는 아무것도 없고, `deploy-develop.yml` 의
push 트리거는 주석으로 잠겨 있다. 팀의 프론트가 붙을 주소가 여전히 없다.

이번 사이클은 그 스택을 **실제로 올리고**, `pickple.app` 을 **HTTPS 로 붙이고**,
develop push 가 **자동으로 배포**되게 한다. 셋은 순서대로 물려 있다.
DNS 가 EIP 를 가리켜야 인증서가 나오고, apply 결과가 있어야 GitHub 변수를 채워 트리거를 열 수 있다.

설계 결정은 [ADR-0022](../adr/0022-route53-and-caddy-tls.md) 에 있다.

## 범위

**포함**

- `terraform/route53.tf` — hosted zone · `dev-api` A 레코드 · 프론트용 `extra_records`
- `terraform/vpc.tf` 443 ingress, `locals`·`variables`·`outputs` 확장
- `terraform/scripts/sync-secrets.sh` — 로컬 `.env` → Secrets Manager 동기화(값 비노출)
- `Caddyfile` 도메인 사이트 블록 + `http://localhost` 헬스 블록, `/data/caddy` 인증서 영속
- `docker-compose-ec2.yml` 443 포트 · 프록시 헤더 · 인증 관련 비밀 아닌 파라미터
- `application.yml` `server.forward-headers-strategy`
- `deploy-develop.yml` push 트리거 해제(apply·변수 등록 뒤 별도 커밋)
- 실제 apply · Gabia 네임서버 변경 · GitHub Variables 5개 · 첫 배포 · 롤백 실측

**제외**

- prod 환경, ALB·ACM·CloudFront — ADR-0022 기각 대안
- 프론트 배포 URL 확정에 따른 CORS·쿠키 값 — 열린 질문. 값은 compose 파일 한 줄
- PR #41 S3 업로드의 EC2 IAM 권한 — 별도 이슈
- CloudWatch·백업 — ADR-0012 가 미룬 그대로

## 완료 판정

`terraform apply` 성공·워크플로 green 은 대리지표다. 실제 시나리오로 판정한다.
1~6 은 이슈 #36, 12·13 은 PRD-007 이 측정하지 못하고 넘긴 것, 7~11 은 architect 반증 검토에서 나온 것이다.

| # | 판정 | 검증 방법 | 결과 |
|---|---|---|---|
| 1 | develop 인프라가 apply 됨 | `terraform output` 에 instance_id·ecr·role·secret_arn·route53_name_servers 출력 | ✅ 2026-09-02 31 리소스, output 12개. SSM PingStatus=Online, user-data 완료, `/data/{mysql,caddy}` 마운트 |
| 2 | `https://dev-api.pickple.app` 이 유효한 인증서로 응답 | `curl -sI https://dev-api.pickple.app/actuator/health` 200, `openssl s_client` 로 issuer = Let's Encrypt | |
| 3 | 443 이 열리고 80 이 443 으로 리다이렉트 | `curl -sI http://dev-api.pickple.app` → 308 + `Location: https://…` (`.app` 은 HSTS preload 라 브라우저로는 판정 불가) | |
| 4 | develop push 시 자동 배포가 돈다 | 머지 후 Actions 실행 · 헬스 스텝 통과 · 배포 SHA 대조 | |
| 5 | 워크플로에 `secrets.*` 참조 0건 | `grep -c '\${{ secrets\.' .github/workflows/deploy-develop.yml` → 0 (이슈 #36 의 `grep "secrets\."` 는 주석과 `fetch-secrets.sh` 경로에 걸려 2 가 나온다 — 측정식을 표현식 문법으로 좁혔다) | ✅ 0건 |
| 6 | 롤백이 동작 | 2회째 배포 후 `workflow_dispatch` 에 이전 태그 → `docker inspect pickple-app` 이미지 태그 | |
| 7 | OAuth 인가 요청의 redirect_uri 가 https | `curl -sI https://dev-api.pickple.app/oauth2/authorization/kakao` → `Location` 안 `redirect_uri=https%3A%2F%2Fdev-api.pickple.app…` | |
| 8 | 클라이언트 `X-Forwarded-Proto` 스푸핑이 막힘 | 위 요청에 `-H "X-Forwarded-Proto: http"` 를 얹어도 결과 동일 | |
| 9 | 외부 포트가 80·443 만 열림 | `nc -zv <EIP> 22 3306 8080 9090` 전부 실패, `/actuator/info` 404 | |
| 10 | 인증서가 영속 EBS 에 있다 | SSM 에서 `ls /data/caddy/caddy/certificates/` 존재 | |
| 11 | 비밀 동기화가 멱등 | `sync-secrets.sh` 재실행 시 "변경 없음", 원격 JSON 에 `CHANGE_ME` 0건, MySQL 패스워드 불변 | ✅ 2026-09-02 1회 put(VersionId 1개) 뒤 재실행 "변경 없음", `CHANGE_ME` 0건. mysql_* 출처 generated→remote |
| 12 | 2GB 안에서 메모리가 버틴다 | `docker stats --no-stream` (Caddy TLS 추가분 포함) | |
| 13 | 비용이 추정 범위 안 | Budgets $35 의 50% 알림 미도달 + 1주 후 Cost Explorer ≈ $22.5/월 환산 | |

## 열린 질문

- **프론트 배포 origin.** `CORS_ALLOWED_ORIGINS`·`AUTH_ALLOWED_REDIRECT_HOSTS`·`AUTH_REDIRECT_URI` 의
  실값. 확정 전까지 로컬 기본값(`localhost:3000`)을 두고, `AUTH_COOKIE_SECURE=true` 라
  http 로컬 프론트에서는 쿠키가 붙지 않는다.
- **프론트 DNS 레코드 소유.** NS 가 Route53 으로 가면 apex·www 도 이 저장소 tfvars 다. 프론트 팀 공지.
- **OIDC sub claim 형식.** PRD-007 에서 넘어온 질문. 첫 배포에서 판별.
- **이슈 #36 판정 문구.** `https://pickple.app` → `https://dev-api.pickple.app`, ACM → Caddy 로 교정.

## 발견한 문제

| 문제 | 원인 | 조치 |
|---|---|---|
| `sync-secrets.sh` 가 macOS 에서 즉시 죽는다 | `declare -A`·`mapfile` 은 bash 4 문법인데 macOS 기본 `/bin/bash` 는 3.2 다 | 연관 배열·mapfile 없이 재작성. `/bin/bash -n` 과 stub 시나리오 5종으로 3.2 에서 검증 |
| 로컬 `.env` 의 MySQL 패스워드가 원격을 덮을 수 있다 | 우선순위가 `.env` → 원격이라, 오래된 값이 `.env` 에 남아 있으면 초기화된 MySQL 과 어긋난다. 경고는 put 뒤에만 나왔다 | `mysql_*` 만 원격 우선. 바꾸려면 `--generate` 명시, 경고는 put 전에 출력 |
| 비밀값이 `jq --arg` 인자로 `ps` 에 잠깐 보인다 | 값을 프로세스 인자로 넘겼다 | `--rawfile` 로 파일 경유 |
| `443/udp` 매핑이 죽은 설정 | SG 에 UDP 443 규칙이 없어 HTTP/3 이 EC2 에 닿지 않는다 | 매핑 제거. HTTP/3 은 필요해지면 SG 와 함께 연다 |
| 이슈 #36 의 "ACM" | ACM 은 EC2 에 배포 불가 | Caddy Let's Encrypt 로 교정(ADR-0022) |
| 원격 조회 실패가 "값 없음" 으로 오인돼 MySQL 패스워드가 재생성될 수 있다 (Codex 이종 리뷰) | `get-secret-value` 실패를 `{}` 로 삼켰다. 잘못된 프로필·권한 오류 하나로 초기화된 MySQL 과 어긋난다 | fail-closed: 조회 실패 시 아무것도 쓰지 않고 종료. stub 케이스 6(AccessDenied → put 0회) 추가 |
| `extra_records` 가 apex CNAME 을 받아 apply 가 중간에 죽는다 (Codex 이종 리뷰) | Route53 은 zone apex 의 CNAME 을 거부하는데 변수에 제약이 없었다 | variable validation 으로 plan 단계에서 거부. apex A + www CNAME 은 통과 확인(plan 33 add) |
| 첫 배포에서 OIDC AssumeRole 이 AccessDenied 로 12회 재시도 후 실패 | GitHub 이 2026-06-18 이후 신규 저장소에 **immutable sub claim** 을 보낸다: `repo:<owner>@<owner_id>/<repo>@<repo_id>:ref:refs/heads/<branch>`. trust policy 는 옛 형식(`repo:<owner>/<repo>:ref:…`)이었다. PRD-007 열린 질문이 이렇게 닫혔다 | CloudTrail `AssumeRoleWithWebIdentity` AccessDenied 이벤트의 `userName` 에 실제 sub 가 찍힌다 — 워크플로 로그엔 안 나온다. `locals.github_oidc_subject` 를 immutable 형식으로 바꾸고 `github_owner_id`·`github_repository_id` 변수 추가. apply 1 change 뒤 재실행 |
