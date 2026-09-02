# ADR-0023 — MySQL 과 SSH 를 비표준 포트로 외부에 연다

**상태**: Accepted

이 ADR 은 **ADR-0012 의 §접속 경로만** 대체한다. ADR-0012 의 나머지 결정
(단일 EC2 + docker-compose, 비용 판정, `/data` 볼륨 분리)은 그대로 유효하다.

## 맥락

ADR-0012 는 접속 경로를 이렇게 정했다.

> "SSH 를 열지 않는다", "22 — 규칙 없음, SSM Session Manager 로 대체"
> "3306 은 외부에 열려 있지 않다. SSM 으로 들어가 컨테이너에서 붙는다."

이 결정의 근거는 유효했다. SSM 은 인바운드 포트를 0개로 유지하면서 셸을 주고,
접근 통제가 보안그룹이 아니라 IAM 으로 옮겨가며, 모든 세션이 CloudTrail 에 남는다.

문제는 **일상 작업의 마찰**이다. 로컬 DB 툴(DataGrip 등)로 붙으려면 매번
`aws ssm start-session --document-name AWS-StartPortForwardingSession` 으로 터널을 열어야 하고,
터널이 끊기면 다시 열어야 한다. 게다가 MySQL 은 compose 내부 전용이라 호스트 포트에
바인딩조차 되어 있지 않아, 포트 포워딩만으로는 붙지 않는다(compose 에 `ports:` 가 없다).

즉 현재 구조에서 DB 를 보려면 **SSM 셸 → `docker exec` → CLI mysql** 뿐이고,
GUI 툴은 아예 쓸 수 없다.

## 결정

### 1. MySQL 을 호스트 13307 로 매핑하고 보안그룹에서 연다

`docker-compose-ec2.yml` 의 mysql 에 `ports: ["13307:3306"]` 을 추가하고,
보안그룹에 `var.mysql_host_port` 인그레스를 만든다.

3306 을 그대로 쓰지 않는 이유는 자동 스캔 회피다. 봇은 기본 포트를 먼저 훑는다.
이건 보안 대책이 아니라 **소음 감소**다 — 포트를 옮겼다고 안전해지지 않는다.

### 2. SSH 를 124 로 열고 EC2 키페어를 발급한다

`var.ssh_port`(기본 22, 운영 124)로 sshd 포트를 바꾸고, `var.ssh_allowed_cidr` 로 규칙을 만든다.
ADR-0012 가 만들어 둔 조건부 규칙(`count = var.ssh_allowed_cidr == null ? 0 : 1`)을 재사용한다.

**AL2023 은 SELinux 가 enforcing 이라 `ssh_port_t` 라벨이 없는 포트로는 sshd 가 bind 하지 못한다.**
user_data 가 `semanage port -a -t ssh_port_t -p tcp <port>` 로 라벨을 붙이고 `sshd_config` 를 고친다.
SG 만 열고 이 단계를 건너뛰면 접속되지 않는다 — 가장 흔한 함정이다.

### 3. 두 규칙 모두 변수가 null 이면 생성되지 않는다

`mysql_allowed_cidr` · `ssh_allowed_cidr` 의 기본값은 `null` 이고, 이때 규칙 자체가 만들어지지 않는다.
**닫는 것이 기본값이고, 여는 것이 명시적 선택**이다. 되돌리려면 tfvars 에서 값을 지우고 apply 하면 된다
(인스턴스 replace 없이 규칙만 사라진다).

### 4. SSM 은 그대로 둔다

SSH 를 열었다고 SSM 을 걷어내지 않는다. sshd 설정이 깨졌을 때의 복구 경로이고,
user_data 에서 SSH 블록을 SSM 재시작보다 **먼저** 두는 이유도 이것이다.

## 결과

**얻는 것**
- GUI DB 툴로 `54.116.14.198:13307` 에 직접 붙는다. 터널 유지 불필요
- `ssh -i ~/.ssh/pickple-dev.pem -p 124 ec2-user@54.116.14.198` 로 바로 들어간다
- MySQL·SSH 모두 EIP 하나(`54.116.14.198`)를 쓴다. 인스턴스가 replace 돼도 주소가 유지되므로
  클라이언트 설정을 다시 만질 일이 없다

**잃는 것 (수용한 위험)**
- **DB 가 인터넷에 노출된다.** `users`(소셜 로그인)와 `apple_provider_token`(암호화된 refresh token)이
  담긴 DB 다. EIP 는 고정 주소라 한 번 발견되면 계속 스캔 대상이 된다.
  방어선은 계정 비밀번호 하나뿐이므로 **remote root 를 막고 앱 계정으로만 붙는다.**
- **키페어 도입이 인스턴스 replace 를 유발했다.** `key_name` 은 launch 시점에만 설정되기 때문이다.
  `/data` 는 별도 EBS(`prevent_destroy`)라 보존되고 EIP 도 자동 재연결되지만,
  **인스턴스 ID 가 바뀌므로 `vars.EC2_INSTANCE_ID` 를 갱신해야 한다.** 빠뜨리면 이후 배포가 전부 실패한다.
- 비표준 포트는 security by obscurity 다. 스캔 소음은 줄지만 표적 공격은 막지 못한다.

**되돌리는 법**
tfvars 에서 `mysql_allowed_cidr` · `ssh_allowed_cidr` 를 지우고 apply → 규칙이 `count=0` 으로 사라진다.
compose 의 `ports:` 도 제거하면 완전히 ADR-0012 상태로 돌아간다. 인스턴스 replace 는 필요 없다.

## 검토한 대안

**SSM 포트 포워딩 유지 (기각)**
포트를 하나도 열지 않는 가장 안전한 안이다. 기각 사유는 보안이 아니라 사용성 —
터널을 매번 열어야 하고 끊기면 재연결해야 한다. 다만 **compose 에 `ports:` 만 추가하면
포트 포워딩으로도 GUI 툴이 붙는다**(호스트 바인딩이 생기므로). 보안을 우선한다면 이 조합이 최선이다.

**CIDR 을 `/32` 로 제한 (기각)**
자기 IP 만 허용하면 노출 위험이 거의 사라진다. 기각 사유는 가정용 인터넷의 IP 가 바뀌면
그때마다 tfvars 를 고치고 apply 해야 한다는 점. 팀원 추가도 매번 apply 가 필요하다.
**위험 대비 비용이 가장 낮은 안이었으나 운영 편의를 우선했다.**

**로컬 `ssh-keygen` + `authorized_keys` 주입 (기각)**
인스턴스 replace 없이 SSH 를 열 수 있었다. 개인키가 네트워크를 타지 않는 이점도 있다.
기각 사유는 키 관리 주체를 AWS 로 통일하고 싶다는 판단.

**MySQL TLS(`require_secure_transport`) (보류)**
공개 개방이므로 전송 구간 암호화가 필요하다. 이번 범위에서는 제외하고 후속으로 남긴다.

## 참고

- ADR-0012 §접속 경로 — 이 ADR 이 대체하는 결정
- PRD-009 — 적용 범위와 완료 판정
- `terraform/vpc.tf` — 조건부 인그레스 규칙
- `terraform/templates/user-data.sh.tftpl` §4.5 — SELinux·sshd 포트 변경
