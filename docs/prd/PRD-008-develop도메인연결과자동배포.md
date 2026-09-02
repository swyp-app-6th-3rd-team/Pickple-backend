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
| 1 | develop 인프라가 apply 됨 | `terraform output` 에 instance_id·ecr·role·secret_arn·route53_name_servers 출력 | |
| 2 | `https://dev-api.pickple.app` 이 유효한 인증서로 응답 | `curl -sI https://dev-api.pickple.app/actuator/health` 200, `openssl s_client` 로 issuer = Let's Encrypt | |
| 3 | 443 이 열리고 80 이 443 으로 리다이렉트 | `curl -sI http://dev-api.pickple.app` → 308 + `Location: https://…` (`.app` 은 HSTS preload 라 브라우저로는 판정 불가) | |
| 4 | develop push 시 자동 배포가 돈다 | 머지 후 Actions 실행 · 헬스 스텝 통과 · 배포 SHA 대조 | |
| 5 | 워크플로에 `secrets.*` 참조 0건 | `grep -c "secrets\." .github/workflows/deploy-develop.yml` → 0 | |
| 6 | 롤백이 동작 | 2회째 배포 후 `workflow_dispatch` 에 이전 태그 → `docker inspect pickple-app` 이미지 태그 | |
| 7 | OAuth 인가 요청의 redirect_uri 가 https | `curl -sI https://dev-api.pickple.app/oauth2/authorization/kakao` → `Location` 안 `redirect_uri=https%3A%2F%2Fdev-api.pickple.app…` | |
| 8 | 클라이언트 `X-Forwarded-Proto` 스푸핑이 막힘 | 위 요청에 `-H "X-Forwarded-Proto: http"` 를 얹어도 결과 동일 | |
| 9 | 외부 포트가 80·443 만 열림 | `nc -zv <EIP> 22 3306 8080 9090` 전부 실패, `/actuator/info` 404 | |
| 10 | 인증서가 영속 EBS 에 있다 | SSM 에서 `ls /data/caddy/caddy/certificates/` 존재 | |
| 11 | 비밀 동기화가 멱등 | `sync-secrets.sh` 재실행 시 "변경 없음", 원격 JSON 에 `CHANGE_ME` 0건, MySQL 패스워드 불변 | |
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
| (진행하며 기록) | | |
