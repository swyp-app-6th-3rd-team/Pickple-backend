# ADR-0012 — develop 인프라는 단일 EC2 + docker-compose 로 간다

**상태**: Accepted

> **§접속 경로**는 [ADR-0023](0023-external-db-ssh-access.md) 이 대체한다.
>
> **§compose 파일 구성**은 [ADR-0024](0024-local-run-environment.md) 가 대체한다.
> 아래 본문이 언급하는 `docker-compose-prod.yml` 은 `docker-compose-dev.yml` 과
> 바이트 단위로 동일한 사본이었고 CI 참조가 0 건이라 삭제됐다. 배포는
> `docker-compose-ec2.yml` 로 일원화되고, 로컬은 `docker-compose-local.yml` 을 쓴다.
> **본문은 당시 판단의 근거이므로 고치지 않는다.**
>
> 나머지 결정(단일 EC2 + docker-compose, 비용 판정, `/data` 볼륨 분리)은 그대로 유효하다.

## 맥락

swyp 6기는 **6주 팀 프로젝트**다. 매출이 없고, 기간이 끝나면 인프라를 걷어낸다.
이 제약이 인프라 선택의 1순위 기준을 "가용성"이 아니라 **"월 비용"**으로 바꾼다.

참조 대상이 있었다. 실서비스 스택(ECS Fargate + ALB + RDS + CloudFront, Terraform 3,017줄)을
그대로 축소 이식하는 안이다. 하지만 그 스택의 구성 요소 대부분은 **상시 시간당 과금**이라
축소해도 비용이 따라 줄지 않는다.

비용 구조를 실제로 뜯어보면 통념과 다른 지점이 나온다.

**VPC 를 만드는 것 자체는 무료다.** VPC·Subnet·Internet Gateway·Route Table·Security Group 은
과금 대상이 아니다. 요금은 "패킷을 대신 처리해 주는 관리형 박스"에서만 발생한다.

| 구성 | 월 비용 (ap-northeast-2) |
|---|---|
| NAT Gateway | ≈ $45 + 데이터 처리 $/GB |
| ALB | ≈ $20~25 |
| RDS db.t4g.micro | ≈ $15~20 |
| Interface VPC Endpoint | ≈ $7 / AZ |

즉 절감의 핵심은 "VPC 를 안 만드는 것"이 아니라 **"NAT 을 안 만드는 것"**이다.
NAT 경유 트래픽은 ① NAT 데이터 처리 ② 데이터 전송 요금을 **중첩**해서 낸다.
반면 public subnet 의 인스턴스가 IGW 로 직접 나가면 그 과금 레이어가 통째로 사라진다.
IGW 는 무료이기 때문이다.

여기에 이 저장소의 사정이 하나 더 겹친다. 앱은 이미 `Dockerfile` 과
`docker-compose-prod.yml` 로 **컨테이너 단위 실행이 완성돼 있다**(MySQL healthcheck,
`depends_on: service_healthy`, non-root 실행, 로그 볼륨). ECS 로 가면 이 자산을
task definition 으로 재작성해야 하지만, EC2 위 docker-compose 는 거의 그대로 쓴다.

## 결정

**public subnet 의 EC2 인스턴스 1대에 docker-compose 로 앱과 MySQL 을 함께 띄운다.**

![develop 인프라 구조도](../diagrams/develop-infra.light.png)

<sub>다크 버전: [`develop-infra.dark.png`](../diagrams/develop-infra.dark.png) ·
뷰를 나눠 보려면 [인터랙티브 구조도](../diagrams/develop-infra.html)
(요청 경로 / 배포 경로 / 비밀 주입 / 데이터 영속성 4개 뷰)</sub>

### 구성 요소

| 항목 | 값 | 근거 |
|---|---|---|
| VPC | 신규 `10.0.0.0/16`, public subnet ×2 AZ | 무료. default VPC 를 쓰지 않는 이유는 격리와 태깅 통제 |
| NAT / private subnet | **없음** | 최대 절감 항목 |
| EC2 | `t4g.small` (2 vCPU / 2GB / arm64) | JVM ~1GB + MySQL ~0.5GB + OS ~0.3GB → 1GB(t4g.micro)로는 OOM |
| Root EBS | gp3 20GB | JFR 링버퍼(512MB) + 로그 여유 |
| **Data EBS** | **gp3 10GB, 별도 볼륨** | 아래 "데이터 영속성" 참조 |
| EIP | 1개 | 인스턴스 교체 시 IP 고정. 자동할당 public IP 와 요금 동일 |
| HTTPS | 없음 (HTTP 80) | 도메인 미보유. Caddy 를 `:80` 모드로 넣어 도메인 확보 시 한 줄로 전환 |

### 데이터 영속성 — 이 결정의 가장 위험한 지점

"MySQL 을 EC2 에 두는 것"이 위험한 게 아니다. 진짜 위험은 **Terraform 이 인스턴스를
replace 할 때 데이터가 함께 사라지는 것**이다.

Why so 를 세 번 따라가면:

1. 데이터가 사라진다 → 왜?
2. 컨테이너/인스턴스가 교체된다 → 왜 그때 사라지나?
3. **root EBS 는 인스턴스 replace 시 함께 삭제되고, Terraform 은 AMI 나 `instance_type`
   변경만으로도 replace 를 일으킨다.**

`docker-compose-prod.yml` 의 named volume(`mysql_data`)은 root EBS 위에 얹히므로 이 경로에 노출된다.

→ **MySQL datadir 을 별도 `aws_ebs_volume`(gp3 10GB)로 분리하고 `prevent_destroy` 를 건다.**
인스턴스가 교체돼도 볼륨은 살아남는다. EC2 용 compose 는 named volume 대신 `/data/mysql` 바인드 마운트를 쓴다.

⚠️ user_data 의 파일시스템 생성은 **반드시 `blkid` 로 분기**한다. 무조건 `mkfs` 를 돌리면
재부팅마다 데이터를 스스로 지운다.

### 접속 경로 — SSH 를 열지 않는다

> **이 절은 [ADR-0023](0023-external-db-ssh-access.md) 으로 대체되었다(2026-09-02).**
> MySQL 은 호스트 13307, SSH 는 124 로 외부에 열려 있다. 아래 표는 대체 시점 이전의 결정이다.
> 이 ADR 의 나머지 결정(단일 EC2 + compose, 비용 판정, `/data` 볼륨 분리)은 **그대로 유효하다.**

Security Group 의 ingress 는 **80 번 하나뿐**이다.

| 포트 | 처리 | 이유 |
|---|---|---|
| 80 | 개방 (0.0.0.0/0, ::/0) | 서비스 공개 |
| 8080 | **규칙 없음** | Caddy 가 compose 내부 네트워크로 프록시한다 |
| 3306 | **규칙 없음** | compose 내부 전용. `ports:` 매핑도 하지 않는다 |
| 22 | **규칙 없음** | SSM Session Manager 로 대체 |

SSM 은 추가 요금이 없고, public subnet 이면 VPC Endpoint 도 필요 없다
(아웃바운드 HTTPS 만 있으면 된다). 키 페어 관리가 사라지고 IAM 으로 접근을 통제하며
CloudTrail 에 감사 로그가 남는다.

### 아키텍처 — arm64

이 저장소는 Jib 이 아니라 Dockerfile 로 이미지를 만든다. 보통 x86 러너에서 arm64 이미지를
빌드하려면 QEMU 에뮬레이션이 필요해 빌드가 몇 배 느려진다.

그런데 **이 저장소는 PUBLIC 이라 `ubuntu-24.04-arm` 러너가 무료**다. 네이티브 빌드가 되므로
QEMU 가 필요 없다. 베이스 이미지도 확인했다.

| 이미지 | arm64 매니페스트 |
|---|---|
| `amazoncorretto:25-alpine` | ✅ `arm64/v8` |
| `mysql:8.4` | ✅ `arm64` |

→ t4g(Graviton)를 쓴다. 동급 x86 대비 약 20% 저렴하다.

### 계정 축 — 시도했다가 되돌린 이력

이번 사이클에 **AWS 계정 분리를 시도했다가 되돌렸다.** 결정 자체보다 그 대가를 남긴다.

| | 시도했던 구조 | 최종 채택 |
|---|---|---|
| 계정 | 워크로드 전용 dev 계정 | **관리 계정 단일** |
| 인증 | IAM Identity Center (SSO) | **IAM User 액세스키** |
| 가드레일 | SCP 리전 잠금 | **없음** |
| 폭발 반경 | 워크로드 격리 | **조직 관리와 워크로드 공존** |

되돌린 이유는 2인 6주 프로젝트에 SSO 운영 부담이 과하다는 판단이다. 대가는 분명하다 —
**관리 계정에는 SCP 가 적용되지 않으므로 가드레일이 Budgets 알림 하나뿐이다.**
팀이 커지거나 prod 를 만들 때 계정 분리를 다시 검토한다.

### 작업 경계

| 범위 | 담당 |
|---|---|
| 조직·계정·IAM User/그룹·Budgets | 콘솔/CLI (수동) |
| VPC 이하 워크로드 + IAM **role**·OIDC provider | **Terraform** |

경계를 나눈 이유는 둘이다.

1. 콘솔로 만든 리소스는 tfstate 밖에 있어, Terraform 이 같은 것을 정의하면 "이미 존재함"
   충돌이 난다. `import` 로 편입할 수도 있으나 조직 구조는 자주 바뀌지 않으므로
   2인 팀에선 범위를 나누는 편이 단순하다.
2. 조직 리소스를 Terraform 으로 관리하려면 **상시 조직 권한**이 필요해진다.
   최소권한 원칙에 어긋난다. (`PowerUserAccess` 가 `organizations:*`·`account:*` 를
   차단하는 것도 같은 맥락이다.)

단 IAM **role** 과 OIDC provider 는 Terraform 이 소유한다. 사람이 쓰는 IAM User 와 달리
워크로드 자격증명이라 코드와 수명이 같기 때문이다.

## 결과

### 비용

| 항목 | 월 |
|---|---|
| EC2 t4g.small | ≈ $15.2 |
| EBS gp3 30GB (root 20 + data 10) | ≈ $2.7 |
| Public IPv4 | ≈ $3.6 |
| Secrets Manager (secret 1개) | $0.40 |
| ECR + S3 state | ≈ $0.1 |
| VPC·Subnet·IGW·SG·Route·SSM | **$0** |
| **합계** | **≈ $22** |

프리티어 12개월 내 계정이면 EC2·IPv4·ECR 상당 부분이 상쇄돼 **≈ $4**.
ECS + ALB + RDS + NAT 구성(≈ $110~130)의 **약 1/5**이다.

Budgets 를 $35(정상 상한의 1.5배)로 잡고 3단계 알림(실제 50% · 80% · 예측 100%)을 건다.
SCP 가 없는 지금 **이것이 유일한 자동 방어선**이므로, "실제 50% 알림이 울리지 않을 것"을
완료 판정에 포함한다.

### 포기한 것

- **가용성.** 단일 인스턴스이므로 SPOF 다. 인스턴스가 죽으면 서비스가 죽는다.
  develop 환경이라 수용하되, 실사용자를 받는 순간 재검토한다.
- **자동 백업.** RDS 의 자동 스냅샷·PITR 이 없다. EBS 분리로 인스턴스 교체에는 견디지만
  **논리적 삭제(`DROP TABLE`)에는 무방비**다. DLM 일 1회 스냅샷을 옵션으로 남긴다.
- **수직 확장 여유.** 2GB 에 JVM 과 MySQL 이 함께 산다. 관측성 백엔드
  (Grafana·Tempo·Loki·Prometheus)를 이 인스턴스에 올릴 수 없다.
- **HTTPS.** 도메인이 없어 HTTP 로 시작한다. 도메인이 생기면 Caddyfile 한 줄로 전환된다.
- **계정 격리.** 위 "계정 축" 참조.

### 관측성

**이 문단은 사후 갱신됐다.** 최초 작성 시점에는 ADR-0010(OpenTelemetry + Grafana 스택)이
유효했고, 이 ADR 은 그 스택을 "로컬·홈서버에서는 유지하되 2GB EC2 에서만 제외"하는 것으로
적용 범위를 갈랐다. 이후 **관측성 스택 자체를 저장소에서 제거**했으므로(ADR-0010 폐기,
`observability/` · `docker-compose-otel.yml` · OTel 에이전트 배선 삭제) 그 구분이 무의미해졌다.
단일 EC2 라는 이 ADR 의 결정 자체는 바뀌지 않았다.

현재 develop 의 진단 수단은 다음 둘이다.

| 수단 | 내용 |
|---|---|
| JFR 상시 녹화 | 6시간 롤링 링버퍼. JDK 내장이라 에이전트가 필요 없다 |
| 레벨별 파일 로깅 | [ADR-0009](0009-log-persistence.md). EBS 볼륨에 영속화 |

둘 다 EC2 에서 그대로 동작하므로 **사후 진단 능력은 남는다.**
헬스 상태는 관리 포트(9090)의 `/actuator/health` 로 확인한다 —
인터넷에 열려 있지 않으므로 SSM 포트 포워딩으로 붙는다(`terraform/README.md`).
**앞으로의 방향은 CloudWatch 다.** 자체 관측성 백엔드를 2GB 인스턴스에 얹는 대신
AWS 관리형으로 간다 — 인스턴스 메모리를 쓰지 않고, EC2 role 에 권한만 주면 되며,
이미 SSM·Secrets Manager 로 AWS 에 붙어 있으므로 새 자격증명이 필요 없다.

> **미구현.** 현재 저장소에 CloudWatch 배선은 없다 — 로그 그룹·IAM `logs:*` 권한·
> compose `awslogs` 드라이버 모두 미작성이다. 붙이는 작업은 별도 PR 로 진행하며,
> 그때 이 문단을 갱신하거나 새 ADR 로 대체한다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| ECS Fargate + ALB | NAT $45 + ALB $22 = 월 $65 추가. 6주·매출 0 프로젝트에 정당화 불가. 기존 compose 자산도 재작성해야 한다 |
| RDS (db.t4g.micro) | 월 $15~20. 관리형 백업·PITR 은 매력적이나 develop 단일 인스턴스에서 비용을 넘지 못한다. 백업은 EBS 스냅샷으로 대체 |
| private subnet + NAT Gateway | NAT 이 단일 최대 비용 항목(월 $45+). 게다가 데이터 처리와 전송을 중첩 과금한다. public subnet + IGW 로 그 레이어를 없앤다 |
| Interface VPC Endpoint (Secrets Manager·SSM·ECR) | AZ 당 월 $7. public subnet 이면 IGW 로 직접 호출하면 되므로 순수 추가 비용이다 |
| default VPC 사용 | 코드는 줄지만 CIDR·태깅 통제가 안 되고 다른 용도와 격리되지 않는다. VPC 생성은 무료라 얻는 것 대비 잃는 게 없다 |
| SSH (22번 포트) 개방 | 키 배포·회수 부담이 생기고 IP allowlist 는 개발자 IP 가 바뀔 때마다 깨진다. SSM 이 비용 $0 에 IAM 통제·감사 로그까지 준다 |
| MySQL 을 root EBS 의 named volume 에 | 인스턴스 replace 시 데이터가 사라진다. AMI 변경만으로도 발생하는 현실적 시나리오다 |
| t4g.micro (1GB) + swap | 월 $7.5 로 더 싸지만 JVM + MySQL 에 1GB 는 OOM Killer 를 부른다. swap 으로 버티면 성능이 불안정해져 측정 자체가 오염된다 |
| x86 (t3.small) | 동급 대비 약 20% 비싸다. PUBLIC 저장소라 arm 러너가 무료이고 베이스 이미지도 arm64 를 지원해 회피할 이유가 없다 |
