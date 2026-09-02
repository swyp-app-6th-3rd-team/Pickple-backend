# ADR-0022 — 도메인은 Route53 이 관리하고 TLS 는 Caddy 가 종단한다

**상태**: Accepted
**보완**: [ADR-0012](0012-develop-infra-single-ec2.md) 의 "HTTPS 없음(도메인 미보유)" 항목을 이 문서가 채운다. 단일 EC2·비용 우선 판단은 유지된다.

## 맥락

`pickple.app` 을 확보했다(ADR-0014). 이제 develop 서버에 붙여야 하는데, 도메인 쪽 상태와
TLD 특성이 선택지를 좁힌다.

**DNS 가 백지다.** `.app` TLD 는 이 도메인을 Gabia 네임서버 3개(`ns.gabia.net` 등)로
위임하고 있지만, 그 네임서버는 존을 모른다.

```
$ dig +norecurse SOA pickple.app @ns.gabia.net
;; ->>HEADER<<- opcode: QUERY, status: REFUSED     (2026-09-02 실측)
```

레코드가 없는 게 아니라 존 자체가 없다. 어디서 관리하든 첫 설정은 사람이 해야 한다.

**`.app` 은 HSTS preload TLD 다.** 브라우저가 `http://pickple.app` 을 시도조차 하지 않는다.
HTTP 로 먼저 열고 나중에 HTTPS 를 붙이는 순서가 성립하지 않는다.

**ACM 은 EC2 에 못 쓴다.** ACM 공개 인증서는 ALB·CloudFront·API Gateway 에만 배포된다.
이슈 #36 은 "Route53 · ACM" 이라고 적었지만 ACM 을 쓰려면 ALB 가 필요하고,
ALB 는 ADR-0012 가 월 $22 라는 이유로 기각한 바로 그 항목이다.

**앱이 자기 scheme 을 모른다.** `application.yml` 의 OAuth `redirect-uri` 는
`{baseUrl}/login/oauth2/code/{registrationId}` 템플릿이다(카카오·네이버 명시, 구글은 미지정이라
같은 템플릿으로 폴백). 프록시가 TLS 를 벗기고 `app:8080` 으로 평문 전달하면 `{baseUrl}` 이
`http://` 로 해석되고, 인가 요청의 `redirect_uri` 가 등록값(`https://`)과 달라
`redirect_uri_mismatch` 로 세 프로바이더 모두 실패한다. 도메인을 붙이는 것만으로는 끝나지 않는다.

## 결정

### 1. DNS 는 Route53 hosted zone 을 Terraform 이 관리한다

Gabia 에서는 네임서버를 Route53 의 4개로 **1회** 바꾼다. 그 뒤 레코드는 전부 `terraform/route53.tf` 다.

이유는 비용($0.50/월)이 아니라 **바인딩**이다. A 레코드가 `aws_eip.app.public_ip` 를 직접 참조하므로
destroy/apply 사이클에서 EIP 가 바뀌어도 사람이 콘솔에 값을 옮겨 적는 단계가 없다.
변경이 코드 리뷰와 CloudTrail 에 남고, 나중에 DNS-01 챌린지나 ACM 검증이 필요해져도
같은 자리에서 한다.

이 결정의 대가는 **apex 와 `www` 같은 프론트 레코드도 이 저장소가 관리하게 된다**는 점이다.
`extra_records` 변수로 tfvars 한 줄 추가로 받되, 프론트 팀에 이관 사실을 알린다.

### 2. TLS 는 EC2 위 Caddy 가 Let's Encrypt HTTP-01 로 종단한다

Caddy 는 이미 compose 에 있다. 사이트 블록을 `:80` 에서 `dev-api.pickple.app` 으로 바꾸면
발급·갱신·80→443 리다이렉트가 자동이다. 인프라 변경은 보안 그룹 443 과 compose 포트 매핑뿐이다.

인증서와 ACME 계정은 **영속 EBS(`/data/caddy`)** 에 둔다. 루트 볼륨의 named volume 에 두면
인스턴스 교체마다 재발급되고, Let's Encrypt 는 같은 호스트에 주 5회까지만 발급한다.
교체 검증(PRD-007 판정 3)을 두 번 돌리면 한도의 절반을 쓰는 셈이라 처음부터 EBS 로 간다.

워크플로 헬스체크(`curl http://localhost/actuator/health`)가 계속 동작하도록
`http://localhost` 사이트 블록을 따로 둔다. 도메인 블록만 있으면 localhost 요청은 어느 사이트에도
매칭되지 않아 배포마다 헬스 스텝이 실패한다.

### 3. 백엔드 호스트명은 `dev-api.pickple.app`

apex 는 프론트 몫으로 비운다. prod 가 생기면 `api.pickple.app` 을 쓴다.
환경을 이름에 넣는 이유는 지금 프론트 배포 URL 이 없고, apex 를 백엔드가 선점하면 나중에
Caddy·CORS·OAuth 콜백을 전부 되돌려야 하기 때문이다.

### 4. 앱은 `server.forward-headers-strategy=framework` 로 프록시 헤더를 신뢰한다

Spring 이 `ForwardedHeaderFilter` 를 최상위 순서로 등록해 `X-Forwarded-Proto` 로 scheme 을
다시 쓴다. 그래야 `{baseUrl}` 이 `https://dev-api.pickple.app` 이 된다.

이 설정은 **8080 이 인터넷에 닫혀 있다는 전제**에 기댄다. `framework` 는 헤더를 무조건 신뢰하므로
Caddy 를 거치지 않고 앱에 닿는 경로가 생기는 순간 scheme 스푸핑이 된다. 지금은 compose 가
app 에 `ports:` 를 주지 않고 보안 그룹에 8080 규칙이 없어 Caddy 가 유일한 입구다.
8080 을 열거나 host 네트워크로 바꾸는 변경은 이 전제를 깨므로 함께 재검토한다.

비밀이 아닌 런타임 파라미터(`AUTH_COOKIE_SECURE`, `CORS_ALLOWED_ORIGINS` 등)는
`docker-compose-ec2.yml` 의 `environment:` 에 둔다. `fetch-secrets.sh` 에 넣으면 안 되는 이유가 있다.
그 스크립트는 user_data 가 **최초 부팅 때 한 번** 써 놓는 파일이라, 템플릿을 고쳐 apply 해도
이미 떠 있는 인스턴스에는 영영 반영되지 않는다. compose 파일은 배포마다 SSM 으로 다시 실리므로
갱신 경로가 "PR + 배포" 하나로 통일된다.

## 결과

**얻은 것**

| 항목 | 결과 |
|---|---|
| HTTPS | Let's Encrypt, 자동 갱신, 추가 리소스 없음 |
| DNS 변경 | 코드 리뷰 + `terraform apply`. 콘솔 단계 0 |
| 인스턴스 교체 | 레코드·인증서 모두 유지(EIP 바인딩 + EBS) |
| 월 비용 | ADR-0012 $22 → **≈ $22.5** (Route53 zone $0.50) |

**포기한 것**

- **순서 자유.** HTTP-01 은 DNS 가 EIP 를 가리켜야 통과한다. apply → Gabia NS 변경 → 전파 확인 →
  Caddy 도메인 블록 배포 순서를 지키지 않으면 실패 검증이 Let's Encrypt 한도를 태운다.
  절차는 `terraform/README.md` 에 있다.
- **DNS 소유 경계.** 프론트 레코드가 백엔드 저장소 PR 을 타게 된다.
- **팀 밖 등록.** 카카오·네이버·구글 콘솔의 redirect URI 는 인증서가 발급된 뒤 사람이 등록한다.
  그 사이 소셜 로그인은 깨진다. develop 이라 수용한다.
- **Route53 zone 은 프로젝트가 끝나도 과금된다.** `prevent_destroy` 를 두지 않고
  teardown 체크리스트에 올린다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| ALB + ACM (Caddy 제거) | 이 계정은 2026-08-18 생성이라 12개월 프리티어(ALB 750h) 대상이 아니고, `aws freetier get-free-tier-usage` 에 Always Free 항목만 있으며 Cost Explorer 의 Credit 합계가 $0 이다(2026-09-02 확인). ALB 는 서울 $0.0225/h × 730h ≈ $16.4 + LCU ≈ 월 $20~25. 총액이 ADR-0012 의 2배가 되고 Budgets $35 를 넘는다 |
| Gabia DNS 관리에 A 레코드 직접 등록 | HTTP-01 은 A 레코드만 있으면 되므로 기술적으로 충분하고 $0 이다. 그러나 Terraform 밖·감사 없음·EIP 가 바뀔 때마다 콘솔 재입력. 6주 뒤 teardown 과 재기동을 생각하면 수동 단계가 반복된다 |
| ACM 단독 | EC2 에 배포할 수 없다. 이슈 #36 의 문구는 오기 |
| Caddy DNS-01 (Route53 플러그인) | 커스텀 Caddy 빌드가 필요하다. 80 포트를 닫아야 할 이유가 없으므로 HTTP-01 로 충분 |
| apex `pickple.app` 을 백엔드에 | 프론트가 apex 를 가져갈 때 Caddy·CORS·OAuth 콜백 전부 재작업 |
| 파라미터를 `fetch-secrets.sh` 로 주입 | user_data 는 최초 부팅 1회라 기존 인스턴스에 반영되지 않는다. 인스턴스를 갈아야 하고 그러면 인증서 재발급까지 딸려 온다 |
| `forward-headers-strategy=native` | Tomcat `RemoteIpValve` 경로. 동작은 같지만 `framework` 가 컨테이너 종류와 무관하고 Spring Boot 문서의 기본 권장이다 |

## 참고

- 이슈 #36 — develop 서버 배포 마무리
- [ADR-0012](0012-develop-infra-single-ec2.md) 단일 EC2 · 비용 우선
- [ADR-0013](0013-oidc-and-secrets-manager.md) 비밀 흐름(변경 없음)
- [ADR-0017](0017-compose-secret-environment-allowlist.md) compose `environment:` allowlist (PR #12)
- [PRD-008](../prd/PRD-008-develop도메인연결과자동배포.md) 완료 판정
