# PRD-007 — develop 배포 인프라

**상태**: 검증 완료 (실측 후 teardown)

## 무엇을 왜

지금까지 앱은 **로컬에서만 돈다.** PRD-000 에서 컨테이너 골격을, PRD-005 에서 관측성을
갖췄지만 실제로 접근 가능한 주소가 없다. 팀의 프론트엔드가 붙을 곳도, 사용자에게 보여줄
곳도 없는 상태다.

6주 사이클에서 배포가 늦어질수록 통합 비용이 뒤로 밀린다. **develop 브랜치에 머지하면
자동으로 올라가는 환경**을 먼저 확보한다.

제약이 명확하다. **매출 0, 6주 후 teardown.** 그래서 이 사이클의 설계 기준은
"가용성"이 아니라 **"월 비용"**이다. 목표는 **월 $25 이하**로, 실서비스형 구성
(ECS + ALB + RDS + NAT ≈ 월 $110~130)의 1/5 수준이다.

## 범위

**포함**

- Terraform (`terraform/`) — VPC · EC2 · EBS · EIP · Secrets Manager · ECR · IAM · S3 backend
- `docker-compose-ec2.yml` — EC2 전용 (기존 `docker-compose-prod.yml` 은 수정하지 않음)
- `Caddyfile` — `:80` → `app:8080` 리버스 프록시 (`/actuator/health` 만 `app:9090`)
- `.github/workflows/deploy-develop.yml` — OIDC 인증 + ECR push + SSM 배포
- [ADR-0012](../adr/0012-develop-infra-single-ec2.md) · [ADR-0013](../adr/0013-oidc-and-secrets-manager.md)

**제외**

- prod 환경 — 배포 결정 시점에 별도 사이클로
- HTTPS / 도메인 / ALB / ACM / Route53 — 도메인 미보유
- 관측성 백엔드 — 자체 스택은 2GB 인스턴스에 올릴 수 없다. CloudWatch 로 가되 별도 사이클 ([ADR-0012](../adr/0012-develop-infra-single-ec2.md) 참조)
- 자동 백업(DLM 스냅샷) — 옵션으로만 기술
- CI 테스트 — `ci.yml` 이 이미 담당. 배포 워크플로는 빌드·배포만 한다

## 완료 판정

**`terraform apply` 성공·에러 0건은 대리지표다.** 실제 시나리오로 검증한다.

| # | 판정 | 검증 방법 | 결과 |
|---|---|---|---|
| 1 | 과금 리소스가 계획에 없다 | `terraform plan \| grep -E "nat_gateway\|_lb\.\|db_instance\|vpc_endpoint"` | ✅ 빈 출력 (plan 27 add / 0 change / 0 destroy) |
| 2 | SSH 키 없이 셸에 진입된다 | `aws ssm start-session --target <id>` | ✅ PingStatus=Online, SendCommand 로 셸 실행 확인 |
| 3 | **인스턴스 교체 후 DB 데이터가 살아남는다** | INSERT → `terraform taint aws_instance` → `apply` → SELECT | ✅ probe 행이 원래 타임스탬프(17:25:13)로 생존. 로그에 "기존 파일시스템 발견, 포맷하지 않음" |
| 4 | Flyway 가 스키마를 만든다 | `SELECT * FROM flyway_schema_history` | ✅ V1 auth tables success=1, users·user_refresh_token 생성 |
| 5 | 비밀 재조회가 재시작만으로 된다 | `systemctl restart buyorpass` → `.env` 갱신 확인 | ✅ ExecStartPre 가 `.env` 16개 항목 재생성 (systemd 로그 확인) |
| 6 | develop push 로 배포가 완결된다 | GHA 성공 → `curl http://<EIP>/actuator/health` | ✅ SSM 배포 경로 실측 `{"status":"UP"}` (GHA 트리거는 미실행) |
| 7 | API 문서가 열린다 | `curl -so /dev/null -w '%{http_code}' http://<EIP>/swagger-ui.html` · `/scalar` | ✅ api-docs·scalar·llms.txt 200, swagger 302. auth API 3종 정상 응답 |
| 8 | 이전 태그로 롤백된다 | 이전 `develop-<run_id>` 로 SSM 재실행 | ⏸ 미측정 — 이미지 태그가 1개뿐이라 롤백 대상 없음. GHA 첫 배포 2회 후 측정 |
| 9 | **DB·앱 포트가 외부에서 막혀 있다** | 외부에서 `nc -zv <EIP> 3306` · `8080` | ✅ 3306·8080·9090·22 전부 차단, 80만 열림. actuator 도 health 외 404 |
| 10 | 2GB 안에서 메모리가 버틴다 | `docker stats` · `df -h` (JFR 512MB 포함) | ✅ available 495MB. app 37% / mysql 66% / caddy 12%. 디스크 root 18%, data 4% |
| 11 | **비용이 추정 범위 안이다** | Budgets 실제 50%($17.5) 알림 **미도달** + Cost Explorer 대조 | ⏸ 미측정 — 약 1시간 가동 후 teardown 하여 누적 비용이 유의미하지 않음. 상시 운영 시 측정 |

판정 3 이 이 사이클의 핵심이다. "MySQL 을 EC2 에 둔다"는 결정의 유일한 실질 위험이
인스턴스 replace 시 데이터 소실인데, **의도적으로 replace 를 일으켜 보지 않으면 확인할 수 없다.**

판정 11 은 SCP 가드레일이 없는 현 계정 구조에서 **유일한 자동 감지 수단**이다.

## 열린 질문

- **GitHub OIDC `sub` claim 형식은 어느 쪽인가?** 2026-06-18 부터 신규 저장소에 immutable
  subject claims 가 적용되고 있어, 기존 형식(`repo:<org>/<repo>:ref:refs/heads/develop`)과
  다를 수 있다. 첫 배포에서 판별된다 → trust policy 조건을 변수로 분리해 교정 가능하게 둔다.
- **2GB 로 충분한가?** JVM(`MaxRAMPercentage=75`) + MySQL + Caddy + JFR 상시 녹화가 함께 산다.
  판정 10 의 실측으로 답한다. 부족하면 `instance_type` 변수로 t4g.medium 전환.

## 발견한 문제

| 문제 | 원인 | 조치 |
|---|---|---|
| `/actuator/health` 가 8080 에 없다 | `application.yml` 이 관리 엔드포인트를 **의도적으로** 9090 으로 분리했다. actuator 가 JVM·DB 내부 상태를 드러내므로 서비스 포트로는 열지 않는다 | compose healthcheck 를 9090 으로 고치고, Caddy 는 `/actuator/health` 만 9090 으로 통과시킨 뒤 나머지 `/actuator/*` 는 404 로 막았다. 앱의 보안 의도를 인프라가 깨지 않게 한다 |
| Nitro 인스턴스에서 EBS 장치명이 어긋난다 | t4g 는 Nitro 라 `/dev/sdf` 로 요청해도 커널엔 `/dev/nvme<N>n1` 로 보이고, N 은 **부팅 시 응답 순서**로 정해져 매핑 이름과 무관하다 | user_data 가 (a) AL2023 udev 심링크 → (b) 볼륨 ID 시리얼 탐색 순으로 장치를 찾고, fstab 엔 UUID 로 적는다 |
| compose 오버레이로는 EC2 배포가 안 된다 | `build:` 와 `image:` 가 함께 있으면 compose 는 "그 이름으로 빌드"로 해석한다. EC2 에서 Gradle 빌드가 돌아 2GB 인스턴스가 OOM 된다 | 오버레이 대신 `docker-compose-ec2.yml` 을 완결형으로 분리. 기존 `docker-compose-prod.yml` 은 건드리지 않는다 |
| **첫 배포 전 부팅이 유닛을 영구 failed 로 만든다** (실측) | 부팅 시 compose 파일이 아직 없어 유닛이 exit 14 로 실패하면 systemd 가 "failed" 로 낙인찍는다. 이후 배포로 컨테이너가 떠도 `systemctl stop` 이 ExecStop 을 실행하지 않아(이미 죽은 것으로 봄), 컨테이너는 `restart:unless-stopped` 로 살아 있는데 systemd 제어가 안 되는 상태가 된다 | `buyorpass-up.sh` 래퍼가 "파일 없음"(exit 0, 배포 대기)과 "compose 실패"(진짜 오류)를 구분. `SuccessExitStatus=1` 로 뭉뚱그리면 진짜 실패까지 덮이므로 쓰지 않았다 |
| **EIP 연결이 SSM 등록을 깨뜨린다** (실측) | `aws_eip` 가 `aws_instance` 에 의존해 **부팅 뒤에** 붙는다. 그때 자동할당 public IP 가 EIP 로 교체되며 TCP 연결이 리셋되고, 하필 등록 중이던 SSM 에이전트가 `connection reset by peer` 로 실패한다. 에이전트는 **hibernation** 에 들어가 재시도 간격을 수십 분으로 늘려 스스로 복구하지 않는다. SG 에 22번이 없으므로 **복구 경로 상실**이 된다 | user_data 마지막에 `systemctl restart amazon-ssm-agent` 추가(EIP 연결 이후 시점이라 안정된 네트워크로 재등록). 기존 인스턴스는 재부팅으로 복구 |
| **볼륨은 부팅 뒤에 붙는다** | `aws_volume_attachment` 가 `aws_instance` 에 의존하므로, user_data 가 시작될 때 데이터 볼륨이 아직 없는 것이 정상이다. 심링크를 한 번만 확인하는 구조면 늦게 생길 때 놓친다 | 두 탐색 방법(udev 심링크 · 볼륨 ID 시리얼)을 **매 시도마다 함께** 돌리는 재시도 루프로 바꿨다(최대 2분) |
| **마운트 실패가 조용한 데이터 손실이 된다** | fstab 의 `nofail` 은 마운트 실패해도 부팅을 계속시킨다(SSM 진입을 위한 의도적 선택). 그 상태에서 `mkdir /data/mysql` 을 하면 **루트 볼륨**에 생기고, MySQL 이 정상처럼 돌다가 인스턴스 교체 때 통째로 사라진다 | `mountpoint -q` 로 마운트를 검증하고 실패 시 즉시 중단. "실패해도 진행"과 "실패를 감지"는 별개다 |
| **롤백 입력값으로 원격 명령 실행이 가능했다** | `workflow_dispatch` 의 `image_tag` 를 `${{ }}` 로 `run:` 안에 직접 펼쳤다. Actions 는 셸 실행 **전에** 텍스트를 치환하므로 따옴표로 감싸도 소용없고, 그 값이 SSM 을 타고 EC2 루트 셸까지 간다 | `env:` 로 넘겨 셸 변수로만 다루고, `^develop-[0-9]+$` 정규식으로 검증. 리뷰에서 지적받아 수정 |
| SSM 출력이 2,500자에서 잘린다 | SSM Run Command 의 출력 길이 제한 | 배포 실패 시 stdout·stderr 를 모두 찍고, 부족하면 `/var/log/user-data.log` 를 보도록 안내 |
| OAuth 변수를 빈 값으로 두면 기동이 거부된다 | compose 의 `:-` 는 변수가 "미설정"일 때만 발동한다. `.env` 에 빈 값이 있으면 빈 문자열이 그대로 전달돼 `application.yml` 의 `:not-configured` 를 덮는다 | Secrets Manager 에 안 쓰는 프로바이더도 `not-configured` 를 넣도록 README 에 명시. compose 에도 `:-not-configured` 방어를 유지 |

## 검증 실행 기록 (2026-08-22)

관리 계정 251128835262 / ap-northeast-2 에 실제 apply 후 실측하고 teardown 했다.

| 단계 | 결과 |
|---|---|
| `terraform apply` | 27 리소스 생성 |
| 인스턴스 교체 검증 | `taint` → `apply`, 볼륨 detach/재attach 정상 |
| `terraform destroy` | 27 리소스 삭제, 잔존 0 확인 (EC2·EBS·EIP·VPC·ECR·Secrets·IAM·OIDC 전부 0) |

**대리지표 아닌 실측:** 앱이 실제로 뜨고 외부에서 API 가 응답하는 것까지 확인했다.
`/actuator/health` → `{"status":"UP"}`, `/api/auth/me` → 401 구조화 응답,
`/v3/api-docs` → 200. Flyway V1 적용, MySQL 데이터 209MB 가 `/data`(별도 EBS)에 적재.

**teardown 시 `prevent_destroy` 가 볼륨에서 destroy 를 막았다** — 설계대로 동작했으며,
검증용으로 일시 해제한 뒤 즉시 복구했다.

미측정 2건(판정 8·11)은 각각 롤백 대상 이미지와 누적 청구가 필요해 이번 사이클에서는
측정하지 못했다. 상시 운영 시작 시 측정한다.
