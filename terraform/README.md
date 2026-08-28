# develop 인프라 (Terraform)

단일 EC2 위에 docker-compose 로 앱과 MySQL 을 띄운다. 월 약 $22.
설계 근거는 [ADR-0012](../docs/adr/0012-develop-infra-single-ec2.md) ·
[ADR-0013](../docs/adr/0013-oidc-and-secrets-manager.md), 완료 판정은
[PRD-007](../docs/prd/PRD-007-develop배포인프라.md) 에 있다.

![develop 인프라 구조도](../docs/diagrams/develop-infra.light.png)

NAT Gateway·ALB·RDS·VPC Endpoint 를 **쓰지 않는다.** 이것이 비용 구조의 전부다.

<sub>다크 버전: [`develop-infra.dark.png`](../docs/diagrams/develop-infra.dark.png) ·
뷰별로 따라가려면 [인터랙티브 구조도](../docs/diagrams/develop-infra.html)</sub>

## 사전 준비

```bash
aws sts get-caller-identity --profile root_habin   # 251128835262 여야 한다
```

state 버킷은 닭-달걀 문제라 Terraform 밖에서 1회 만든다.
**버저닝은 필수다** — 워크로드와 같은 계정에 있어 계정이 오염되면 state 도 함께 잃는다.

```bash
BUCKET=buyorpass-tfstate-251128835262
REGION=ap-northeast-2

aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
  --create-bucket-configuration LocationConstraint="$REGION" --profile root_habin

aws s3api put-bucket-versioning --bucket "$BUCKET" \
  --versioning-configuration Status=Enabled --profile root_habin

aws s3api put-public-access-block --bucket "$BUCKET" --profile root_habin \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

aws s3api put-bucket-encryption --bucket "$BUCKET" --profile root_habin \
  --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

## 적용

```bash
terraform init
terraform plan     # 아래 "비용 사전 검증"을 먼저 돌린다
terraform apply
terraform output next_steps
```

### 비용 사전 검증 (PRD-007 판정 1)

과금 리소스가 계획에 섞이지 않았는지 확인한다. **빈 출력이어야 한다.**

```bash
terraform plan -no-color | grep -E "nat_gateway|_lb\.|db_instance|vpc_endpoint"
```

## apply 직후

### 1) 비밀 주입

자리표시자(`CHANGE_ME`) 상태로는 앱이 뜨지 않는다. `fetch-secrets.sh` 가 먼저 막는다.

```bash
aws secretsmanager put-secret-value \
  --secret-id "$(terraform output -raw secret_arn)" \
  --region ap-northeast-2 --profile root_habin \
  --secret-string "{
    \"mysql_root_password\": \"$(openssl rand -base64 24)\",
    \"mysql_password\": \"$(openssl rand -base64 24)\",
    \"jwt_secret_key\": \"$(openssl rand -base64 48)\",
    \"oauth_google_client_id\": \"not-configured\",
    \"oauth_google_client_secret\": \"not-configured\",
    \"oauth_kakao_client_id\": \"not-configured\",
    \"oauth_kakao_client_secret\": \"not-configured\",
    \"oauth_naver_client_id\": \"not-configured\",
    \"oauth_naver_client_secret\": \"not-configured\"
  }"
```

⚠️ OAuth 값을 **빈 문자열로 두지 않는다.** compose 의 `${VAR:-기본값}` 은 변수가
"미설정"일 때만 발동하므로, 빈 값이 있으면 그대로 전달돼 Spring 이
`Client id must not be empty` 로 기동을 거부한다. 안 쓰는 프로바이더는 `not-configured` 로 둔다.

⚠️ MySQL 패스워드는 **최초 기동 시에만** 적용된다. 이미 초기화된 뒤에 바꾸려면
Secrets Manager 값만이 아니라 `ALTER USER` 도 실행해야 한다.

### 2) GitHub 변수 등록

Settings → Secrets and variables → Actions → **Variables** 탭 (Secrets 아님).

```bash
terraform output next_steps   # 값이 그대로 출력된다
```

| 이름 | 출처 |
|---|---|
| `AWS_ROLE_ARN` | `terraform output -raw github_deploy_role_arn` |
| `AWS_REGION` | `ap-northeast-2` |
| `ECR_REGISTRY` | `terraform output -raw ecr_registry` |
| `ECR_REPOSITORY` | `terraform output -raw ecr_repository` |
| `EC2_INSTANCE_ID` | `terraform output -raw instance_id` |

### 3) 배포

`develop` 브랜치에 push 하면 자동으로 돈다.

## 운영

```bash
# 셸 접속 (SSH 키 불필요)
aws ssm start-session --target "$(terraform output -raw instance_id)" \
  --region ap-northeast-2 --profile root_habin

# 상태 확인
sudo systemctl status pickple
cd /opt/pickple && sudo docker compose -f docker-compose-ec2.yml ps

# 비밀 갱신 후 반영 (인스턴스 재생성 불필요)
sudo systemctl restart pickple
```

### 관리 포트(9090) 보기

`/actuator/health` 는 인터넷에 열려 있지 않다. 포트 포워딩으로 붙는다.

```bash
aws ssm start-session --target "$(terraform output -raw instance_id)" \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["9090"],"localPortNumber":["9090"]}' \
  --region ap-northeast-2 --profile root_habin
```

포워딩이 열리면 `curl -s localhost:9090/actuator/health` 로 확인한다.

### MySQL 접속

3306 은 외부에 열려 있지 않다. SSM 으로 들어가 컨테이너에서 붙는다.

```bash
sudo docker exec -it pickple-mysql mysql -u root -p
```

### 롤백

Actions → deploy-develop → Run workflow → 이전 태그(`develop-<run_id>`) 입력.
불변 태그를 SSM 으로 넘기는 구조라 재빌드 없이 즉시 되돌아간다.

## 인스턴스 교체 (AMI 갱신 · 스펙 변경 · 판정 3 검증)

MySQL 이 쓰기 중인 상태에서 인스턴스를 교체하면, EBS 강제 분리는 정전이나 `kill -9` 와
같은 unclean shutdown 이 된다. xfs 저널 + InnoDB crash recovery 가 이를 감내하도록
설계돼 있어 이론상 안전하지만, **무위험은 아니다.** 순서를 지킨다.

```bash
INSTANCE=$(terraform output -raw instance_id)

# 1) 앱과 DB 를 정상 종료한다 (이 단계를 건너뛰면 crash recovery 에 의존하게 된다)
aws ssm send-command --instance-ids "$INSTANCE" \
  --document-name AWS-RunShellScript \
  --parameters 'commands=["systemctl stop pickple"]' \
  --region ap-northeast-2 --profile root_habin

# 2) 교체
terraform taint aws_instance.app
terraform apply

# 3) 데이터 생존 확인
aws ssm start-session --target "$(terraform output -raw instance_id)" \
  --region ap-northeast-2 --profile root_habin
#   sudo docker exec pickple-mysql mysql -uroot -p -e "SELECT COUNT(*) FROM ..."
```

⚠️ 볼륨 자체는 `prevent_destroy` 로 보호되므로 교체 과정에서 삭제되지 않는다.
`force_detach = true` 는 분리를 강제할 뿐 데이터를 지우지 않는다(EBS 는 네트워크 블록
스토리지라 분리 ≠ 소거).

## 주의

- **`aws_ebs_volume.data` 는 `prevent_destroy` 로 보호된다.** MySQL 데이터가 여기 있다.
  정말 지우려면 `ec2.tf` 의 lifecycle 블록을 먼저 지워야 한다.
- **관리 계정에는 SCP 가 적용되지 않는다.** 가드레일이 Budgets 알림($35, 3단계) 하나뿐이므로
  예상 밖 리소스를 만들지 않도록 주의한다.
- `terraform destroy` 는 데이터 볼륨 때문에 그냥은 통과하지 않는다. 6주 후 정리 시
  스냅샷을 먼저 뜨고 lifecycle 블록을 제거한다.
