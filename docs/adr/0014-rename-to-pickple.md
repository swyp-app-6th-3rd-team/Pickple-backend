# ADR-0014 — 이름을 `pickple` 로 통일한다

**상태**: Accepted

## 맥락

도메인 `pickple.app` 을 구매해 제품명이 확정됐다. 그전까지 저장소에는 세 세대의 이름이 겹쳐 있었다.

| 세대 | 표기 | 유래 |
|---|---|---|
| 템플릿 | `com.example.sakila` · `sakila` | `sakilla-ddd-template` 에서 생성. MySQL Sakila 샘플 DB 에서 온 이름 |
| 과도기 | `buyorpass` · `Buy or Pass` · `buy-or-pass` | 제품명 확정 전 임시 표기 |
| 확정 | `pickple` | 도메인 구매로 확정 |

한 저장소에 세 이름이 공존하면 검색·grep 이 신뢰를 잃고, 새로 합류한 사람이
"어느 게 진짜 이름인가"를 매번 판단해야 한다. `AGENTS.md` 는 이 상황을 알고 있었고
*"별도 요청 없이 일괄 이름 변경을 섞지 않는다"* 는 규칙으로 **다른 작업에 번지는 것만**
막아 두었다. 이 ADR 이 그 "별도 요청" 이며, 규칙은 소임을 다해 함께 삭제했다.

## 결정

**기반 패키지를 `app.pickple` 로 옮기고, 제품을 가리키는 모든 식별자를 `pickple` 로 통일한다.**

패키지명은 도메인 `pickple.app` 의 역순 DNS 다. `com.pickple` 은 소유하지 않은
네임스페이스이므로 쓰지 않는다.

| 대상 | 이전 | 이후 |
|---|---|---|
| 자바 기반 패키지 | `com.example.sakila` | `app.pickple` |
| 진입점 클래스 | `BuyOrPassApplication` | `PickpleApplication` |
| Gradle `group` / `rootProject.name` | `com.example` / `buy-or-pass-backend` | `app.pickple` / `pickple-backend` |
| `spring.application.name` | `buy-or-pass` | `pickple` |
| **JWT issuer** | `buy-or-pass` | `pickple` |
| 컨테이너 · 볼륨 | `sakila-*` / `buyorpass-*` (파일마다 달랐다) | `pickple-*` |
| MySQL DB · 사용자 | `buyorpass` | `pickple` |
| terraform `project` | `buyorpass` | `pickple` (`name_prefix` 파생 리소스 전부) |
| 배포 경로 · systemd 유닛 | `/opt/buyorpass` · `buyorpass.service` | `/opt/pickple` · `pickple.service` |

### 지금 하는 이유 — 되돌리기 비용이 지금은 0이다

이름 변경은 보통 비싸다. **JWT issuer 는 `JwtService` 가 `requireIssuer` 로 검증하므로
바꾸는 순간 발급된 모든 토큰이 무효**가 되고(액세스 30분·리프레시 14일), ECR 리포지토리와
Secrets Manager 시크릿은 이름이 바뀌면 교체되어 기존 이미지·비밀값을 잃는다.

그런데 착수 시점에 실측한 결과는 다음과 같았다.

| 확인 | 결과 |
|---|---|
| terraform state 리소스 | **0개** (`serial: 9`) |
| ECR 리포지토리 | **없음** (`[]`) |
| Secrets Manager 시크릿 | **없음** (`[]`) |
| 배포 워크플로 실행 이력 | **0건** (자동 트리거도 주석 처리 상태) |

즉 무효화될 토큰도, 잃을 이미지도, 재주입할 비밀값도 존재하지 않는다.
**미루면 위 항목이 하나씩 진짜 breaking change 로 바뀐다.** 그래서 지금 한다.

### 함께 고친 것

`terraform/variables.tf` 의 `github_repository` 가 `swyp-app-6th-3rd-team/6th-buy-or-pass-backend`
로 남아 있었다. 저장소는 이미 `Pickple-backend` 로 개명됐으므로 **OIDC trust policy 의 subject 가
어긋나 배포 시 `AssumeRole` 이 실패하는 상태**였다(ADR-0013). 이름 통일과 무관한 결함이지만
같은 파일을 손대는 김에 바로잡았다.

## 결과

- 저장소 전체에서 `sakila` · `buyorpass` · `Buy or Pass` 가 사라진다 — 단 아래 예외가 있다.
- 로컬 개발 볼륨이 새로 생긴다. `COMPOSE_PROJECT_NAME` 이 바뀌어 기존
  `*_mysql_data` 의 데이터는 고아가 된다. 로컬 데이터라 재생성으로 충분하다.
- `dev` / `prod` / `ec2` compose 의 기본값이 서로 달랐던 것(`sakila` vs `buyorpass`)이
  `pickple` 로 정렬됐다.

**포기한 것**

- **과거 문서의 옛 이름은 그대로 둔다.** ADR-0001·0003·0004·0008 의 `Sakila` 는
  MySQL 샘플 DB 를 가리키는 사실 서술이라 바꾸면 오히려 틀린 문서가 된다.
  ADR-0009 의 `sakila-log` 볼륨 경로와 PRD-007 의 `buyorpass.service` 는
  **작성 시점의 사실** 이므로 보존한다 — ADR 은 불변이고(`docs/adr/README.md`),
  이 ADR 이 매핑을 제공하므로 옛 이름은 계속 추적 가능하다.
- **terraform state 버킷 `buyorpass-tfstate-251128835262` 는 바꾸지 않았다.**
  실재하는 S3 버킷이고 `develop/terraform.tfstate` 가 그 안에 있다. 이름을 바꾸면
  state 를 잃는다. 옮기려면 버킷 신설 → state 복사 → `backend.tf` 갱신 →
  `terraform init -migrate-state` 를 별도로 밟아야 하며, 이득이 위험보다 작다고 봤다.
- **기반 패키지 이동은 되돌리기 비싸다.** 이후 브랜치·PR 과 충돌이 나며,
  외부에서 이 패키지를 참조하는 코드가 있다면 함께 깨진다(현재는 없다).

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 패키지는 두고 표시 이름만 바꾼다 | `com.example.sakila` 가 로그·스택트레이스·IDE 에 계속 나온다. 가장 자주 보이는 곳이 안 바뀌므로 혼란이 남는다 |
| `com.pickple` 을 쓴다 | `pickple.com` 을 소유하지 않는다. 역순 DNS 관례의 취지(소유한 네임스페이스)에 어긋난다 |
| `app.pickple.api` 로 한 단계 더 둔다 | 현재 단일 모듈이라 빈 계층이 하나 늘 뿐이다. 모듈이 늘면 그때 나눈다(YAGNI) |
| 배포 후로 미룬다 | 그 시점에는 JWT 무효화·ECR 교체·시크릿 재주입이 전부 실제 비용이 된다. 지금이 가장 싸다 |
| 옛 issuer 도 함께 받아주는 전환 파서를 둔다 | 무효화할 토큰이 0건이라 불필요한 복잡도다. 실사용자가 있었다면 필요했을 것 |

## 검증

패키지 이동에는 **테스트 통과가 증거가 되지 않는 함정**이 있다.
`ArchitectureTest` 의 `BASE` 상수가 옛 패키지를 가리키면 `importPackages` 가 0개 클래스를
반환하고, ArchUnit 은 빈 집합에 대해 모든 규칙을 통과시킨다 — **17개 규칙이 공허하게 green** 이 된다.

그래서 도메인 클래스에 `@Entity` 를 일부러 붙여 위반을 주입하고,
**"도메인은 JPA 에 의존하지 않는다" 규칙 1개만 실패**하는 것을 확인한 뒤 되돌렸다.
규칙이 실제로 클래스를 읽고 있다는 증거다.
