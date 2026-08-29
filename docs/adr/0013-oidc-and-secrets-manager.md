# ADR-0013 — 배포 자격증명은 OIDC 로, 런타임 비밀은 Secrets Manager 로 가른다

**상태**: Accepted

## 맥락

GitHub Actions 에서 EC2 로 배포하려면 비밀이 두 군데 필요하다. 흔히 하나로 뭉뚱그리지만
**성격이 전혀 다른 두 축**이고, 섞으면 한쪽의 약점이 다른 쪽으로 번진다.

| 축 | 무엇 | 예 |
|---|---|---|
| 1. 파이프라인 권한 | CI 가 AWS 를 조작할 자격 | ECR push, 배포 트리거 |
| 2. 런타임 비밀 | 앱이 동작하는 데 필요한 값 | DB 패스워드, JWT 키, OAuth 시크릿 |

### 축 1 — 흔한 방식의 문제

일반적 구성은 IAM User 를 만들어 액세스키를 GitHub Secrets 에 넣는 것이다.
문제는 **장기 자격증명**이라는 점이다. 유출되면 무기한 유효하고, 로테이션은 수동이며,
유출 사실을 알아채기 어렵다. 저장소가 PUBLIC 이면 사고 비용이 더 크다.

### 축 2 — "GitHub Secrets 에 다 넣으면 되지 않나"

DB 패스워드까지 GitHub Secrets 에 두고 배포할 때 `.env` 를 만들어 밀어넣는 방식이 있다.
Secrets Manager 비용($0.40/월)도 아낀다. 하지만 결정적 결함이 있다.

**EC2 가 재생성되면 스스로 복구하지 못한다.**

인스턴스가 교체되면(AMI 변경, `instance_type` 변경, 장애) 새 인스턴스는 DB 패스워드를
모른 채 부팅한다. 앱이 뜨지 못하고, 복구하려면 **매번 GitHub Actions 워크플로를 수동 트리거**해야 한다.
Terraform 으로 인프라를 선언했는데 정작 "apply 하면 동작하는 상태로 수렴한다"는
IaC 의 목적이 깨진다.

즉 판단 기준은 비용이 아니라 **"재생성 후 스스로 복구되는가"**다.

### 축 1 의 숨은 위험 — SSH 키

EC2 배포 가이드 대부분이 `EC2_SSH_KEY` 를 GitHub Secrets 에 넣으라고 한다.
이건 축 1 에서 가장 큰 유출 표면이다. 개인키 하나가 새면 서버 전체가 열린다.

## 결정

**두 축을 분리하고, 각각 다른 메커니즘을 쓴다.**

| 축 | 채택 | 저장 위치 |
|---|---|---|
| 파이프라인 권한 | **GitHub OIDC** (AssumeRoleWithWebIdentity) | 어디에도 저장하지 않음 |
| 런타임 비밀 | **AWS Secrets Manager** | Secrets Manager (EC2 가 IAM role 로 자가 조회) |

### GitHub Secrets 에는 비밀을 두지 않는다

전부 `vars` 로 충분한 값만 남는다.

```
vars.AWS_ROLE_ARN       # 역할 ARN — 비밀이 아니다
vars.AWS_REGION
vars.ECR_REGISTRY
vars.EC2_INSTANCE_ID
```

`secrets.*` 는 **하나도 쓰지 않는다.** 액세스키도, SSH 키도 없다.

### 배포 경로 — SSH 대신 SSM

```
develop push
  → OIDC 로 AssumeRole (1시간 토큰)
  → ECR push  (develop-<run_id> + develop 2태그)
  → aws ssm send-command  ← SSH 아님
  → EC2 가 이미지 pull 후 docker compose up -d
```

SSM Run Command 는 추가 요금이 없고, **SSH 개인키라는 유출 표면 자체가 사라진다.**
CloudTrail 에 "누가 언제 어느 인스턴스에 무엇을 실행했는지"가 남는 부수 효과도 있다.

⚠️ `ssm:SendCommand` 를 `Resource: "*"` 로 주면 **계정 내 모든 인스턴스에 명령을 보낼 수 있다.**
반드시 인스턴스 ARN 과 `AWS-RunShellScript` 문서 ARN 으로 스코프를 좁힌다.

### 이미지 태그 — 불변 태그를 SSM 인자로 넘긴다

빌드마다 두 태그를 붙인다.

```
develop-<run_id>   불변 — 이 커밋의 이미지
develop            이동 — 최신을 가리킴
```

배포 시 **불변 태그를 SSM 파라미터로 전달**한다. 이동 태그만 참조하는 구성은
롤백하려면 이전 이미지를 다시 push 해야 하지만, 이 방식은 **태그 지정만으로 롤백**된다.

### 런타임 비밀이 앱에 닿는 경로

```
Secrets Manager (JSON)
  → EC2 instance profile 로 GetSecretValue
  → /usr/local/bin/fetch-secrets.sh 가 .env 생성
  → docker compose 가 .env를 보간하고 environment에 명시된 값만 컨테이너로 전달
```

`fetch-secrets.sh` 를 **user_data 에 인라인으로 넣지 않는다.** user_data 는 부팅 시 1회만
실행되므로, 인라인이면 비밀을 갱신할 때 인스턴스를 재생성해야 한다.
별도 스크립트로 떨어뜨리고 systemd `ExecStartPre` 로 호출하면
`systemctl restart` 만으로 재조회된다.

앱은 이 구조를 모른다. `application.yml` 은 `${SPRING_DATASOURCE_PASSWORD}` 같은
플레이스홀더만 알고 있어 로컬 개발과 동일하게 동작한다 — **앱이 클라우드에 결합되지 않는다.**

### 비밀 값은 Terraform 이 소유하지 않는다

```hcl
resource "aws_secretsmanager_secret_version" "app" {
  secret_string = jsonencode({ mysql_root_password = "CHANGE_ME", ... })
  lifecycle { ignore_changes = [secret_string] }
}
```

컨테이너(secret 리소스)는 Terraform 이 만들되 **실제 값은 CLI 로 1회 주입**한다.
`random_password` 로 생성하면 편하지만 **tfstate 에 평문으로 남는다.**

## 결과

**얻은 것**

| 항목 | 결과 |
|---|---|
| GitHub 에 저장된 비밀 | **0건** (`secrets.*` 미사용) |
| AWS 자격증명 수명 | 1시간 (기존 방식: 무기한) |
| SSH 개인키 | **없음** — 표면 자체가 제거됨 |
| EC2 재생성 후 복구 | 자동 (부팅 시 스스로 조회) |
| 비밀 갱신 | `systemctl restart` (인스턴스 재생성 불필요) |
| 롤백 | 이전 태그 지정만으로 가능 |
| 감사 | CloudTrail 에 AssumeRole·SendCommand 기록 |

**비용**: Secrets Manager secret 1개 = 월 $0.40. 위 이점 대비 무시할 수준이다.

**포기한 것**

- **비밀 로테이션 자동화.** Secrets Manager 의 자동 로테이션을 쓰지 않는다. Lambda 로테이션
  함수가 필요하고 6주 프로젝트에 과하다. 수동 갱신 + `systemctl restart` 로 충분하다.
- **MySQL 패스워드는 반쪽만 관리된다.** `MYSQL_ROOT_PASSWORD` 는 컨테이너 **최초 기동 시에만**
  적용된다. Secrets Manager 값을 바꿔도 이미 초기화된 MySQL 내부 패스워드는 바뀌지 않는다.
  실제 변경은 `ALTER USER` 로 해야 하며, 이건 Terraform 관할 밖이다.
- **OIDC `sub` claim 형식 불확실성.** GitHub 이 2026-06-18 부터 신규 저장소에 immutable
  subject claims 를 적용하고 있어, 이 저장소가 어느 형식인지 첫 배포에서 판별된다.
  trust policy 의 조건을 변수로 분리해 교정 가능하게 둔다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| IAM User 액세스키를 GitHub Secrets 에 | 장기 자격증명이라 유출 시 무기한 유효하고 로테이션이 수동이다. PUBLIC 저장소에서 사고 비용이 크다 |
| 런타임 비밀도 GitHub Secrets 에 두고 배포 시 `.env` 주입 | **EC2 재생성 시 자가 복구가 불가**하다. 인스턴스가 교체될 때마다 워크플로를 수동 트리거해야 해 IaC 의 선언적 재현이 깨진다 |
| SSH 로 배포 (`EC2_SSH_KEY` 를 Secrets 에) | 개인키가 최대 유출 표면이다. SSM 이 비용 $0 로 대체하며 감사 로그까지 준다 |
| SSM Parameter Store (Standard, 무료) | 비용은 유리하나 JSON 키 셀렉터·로테이션 확장성이 약하다. $0.40 차이로 선택을 바꿀 이유가 없다 |
| Spring Cloud AWS 로 앱이 직접 Secrets Manager 조회 | 디스크에 평문이 안 남아 더 안전하지만 **앱이 AWS 에 결합**된다. 로컬 개발에서 동일하게 못 돌고 의존성·설정이 늘어난다 |
| `random_password` 로 Terraform 이 비밀 생성 | **tfstate 에 평문으로 남는다.** state 파일이 곧 비밀 저장소가 되어 버린다 |
| 이동 태그(`develop`)만으로 배포 | 롤백하려면 이전 이미지를 다시 push 해야 한다. 불변 태그를 넘기면 태그 지정만으로 롤백된다 |
