# ADR-0053: 출시 데이터 초기화는 V19 마이그레이션으로 하고, 실행 시점은 머지 시점으로 통제한다

- 상태: Accepted
- 날짜: 2026-09-30
- 관련: #187, #185(QA 계정)
- 참조: ADR-0023(외부 DB 접근), ADR-0052(업로드 객체의 수동 정리)
- 적용 범위: 정식 출시 컷오버 1회. develop DB와 이미지 버킷의 `product-images/`·`profile-images/`

## 맥락

별도 prod 환경은 없다(PRD-007 범위 제외). 정식 출시 뒤에는 develop EC2의 MySQL이 그대로 운영 DB가 된다.
그 DB에는 개발·테스트 기간의 데이터가 쌓여 있다(2026-09-30 실측: users 64, post 118, vote 465, item_resource 239).
출시 시점에는 이 데이터를 비우되 스토어 심사에 쓰는 QA 계정(#185)은 로그인할 수 있는 상태로 남아야 한다.

deploy-develop은 develop 푸시마다 배포하고 앱은 기동할 때 Flyway migrate를 실행한다.
따라서 develop에 들어간 마이그레이션은 곧바로 운영 DB에서 실행된다.

## 결정

- 초기화는 `V19__reset_data_for_release.sql` 하나로 한다. 이 PR은 Draft로 두고 출시 컷오버 직전에 머지한다.
  실행 시점은 머지 시점이 정한다.
- 보존 대상은 실행 시점에 `qa_account.user_id`가 가리키는 users 행이다. SQL에 ID를 적지 않고
  `NOT EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = ...)`로 판정한다. 컷오버 전에 QA 계정이 늘거나
  줄어도 그 시점의 qa_account를 따른다.
- QA 사용자는 계정·인증 정보만 남긴다. users 행, qa_account, refresh 토큰, 약관 동의, 본인 소유 PROFILE 컨테이너가
  대상이다. 게시글·투표·댓글·원픽·포인트·뱃지·일일 활동은 QA 것도 지우고, users의 파생 컬럼
  (point, vote_count, ranking, highest_grade)은 초기값으로 되돌린다. 원장이 비었으므로 캐시도 비어야 맞다.
  highest_grade를 1로 되돌리는 것은 R-16(등급은 내려가지 않는다)의 예외다. R-16은 원장이 유지되는 동안의 규칙이고
  이번 작업은 원장 자체를 비우는 1회성 운영 작업이다.
- 마스터 데이터는 남긴다. badge(V9 시드), terms(운영자가 등록하는 약관), flyway_schema_history가 해당한다.
- 삭제는 FK 역위상 순서로 한다. `FOREIGN_KEY_CHECKS=0`은 쓰지 않는다. 순서가 틀리면 고아 행을 남기지 않고
  마이그레이션이 실패하게 둔다. 문장은 전부 DML이라 Flyway가 한 트랜잭션으로 감싼다.
- S3 객체는 `scripts/purge-orphan-upload-objects.sh`가 정리한다. 두 업로드 접두어의 객체 중 DB가 참조하지 않는 것만
  지운다. 기본은 dry-run이고 버킷 버저닝이 꺼져 있으므로 백업 경로 없이는 삭제하지 않는다.

## 결과와 트레이드오프

머지 한 번으로 운영 DB가 출시 상태가 된다. 대신 머지를 서두르면 되돌릴 수 없다.
Flyway 이력에 V19가 남으므로 롤백은 컷오버 직전에 뜬 mysqldump를 복원하는 방법뿐이다.

qa_account가 비어 있으면 users 전원이 지워진다. 이 조건을 마이그레이션 안에서 막지 않는 이유는
CI·로컬·Testcontainers가 빈 DB에서 V19를 실행하기 때문이다. 가드를 넣으면 새 환경이 모두 깨진다.
그래서 아래 런북의 사전 점검으로 막는다.

V19는 빈 DB에서는 아무 행도 지우지 않는다. 데이터가 있는 팀원의 로컬 DB는 pull 뒤 첫 기동에서 비워진다.
삭제된 사용자가 가진 액세스 토큰은 `ActiveAccountAuthorizationManager`가 401로 막고 refresh 토큰은 users 삭제와 함께
CASCADE로 지워진다.

중간에 실패하면 트랜잭션 전체가 롤백된다. 다만 Flyway가 V19를 `success = 0`으로 이력에 남긴다.
이 행이 있으면 validate가 막아서 새 코드와 이전 코드 모두 기동하지 못한다. 이미지만 되돌려서는 복구되지 않는다.
dev 덤프 리허설(2026-09-30)에서 users를 참조하는 RESTRICT FK를 일부러 넣어 실패시켜 확인했다.
이 경우 실패 이력 행을 지우고 원인을 고친 뒤 다시 배포한다(런북 3-1).

머지 전에 다른 마이그레이션이 V19를 먼저 차지하면 이 파일의 번호만 바꾼다.
어느 환경에도 적용되지 않은 파일이라 이름을 바꿔도 안전하다.

### 컷오버 런북

1. 사전 점검: `SELECT COUNT(*) FROM qa_account` ≥ 1, 각 user_id의 users 행 존재, 최종 약관 등록 여부,
   develop의 최신 마이그레이션 번호가 V18인지 확인한다.
2. EC2에서 `mysqldump --single-transaction` 전체 백업을 뜬다.
3. Draft를 해제하고 머지한다. deploy-develop 완료 후 users 집합이 1번에서 본 qa_account.user_id 집합과 같은지,
   도메인 테이블이 비었는지 확인한다.
   1. 헬스 체크가 실패하고 앱 로그에 V19 실패가 있으면, 데이터는 롤백된 상태다.
      `DELETE FROM flyway_schema_history WHERE version = '19' AND success = 0;`으로 실패 이력을 지운 뒤
      원인을 고쳐 다시 배포한다. 이전 이미지로 되돌릴 때도 이 행을 먼저 지워야 기동한다.
4. `SELECT item_key FROM item_resource;` 결과를 한 줄에 하나씩 파일로 받는다. 반드시 V19 적용 후에 받는다.
   QA가 프로필 사진을 올리지 않았다면 파일이 비므로 `--allow-empty-referenced`를 명시한다.
5. `scripts/purge-orphan-upload-objects.sh --bucket <버킷> --referenced-keys <파일>`을 dry-run으로 돌려 후보를 검토한 뒤
   `--execute --backup-dir <경로>`로 실행한다.

## 대안

- 플래그나 placeholder로 조건부 실행: 플래그가 꺼진 채 한 번 돌면 `flyway_schema_history`에 적용됨으로 기록돼
  나중에 켜도 다시 실행되지 않는다.
- 별도 location(`db/release`)을 설정으로 켜기: 그 사이 V20이 먼저 적용되면 V19가 미적용으로 남아
  `validate-on-migrate`가 기동을 막는다. 피하려면 운영에서 out-of-order를 켜야 한다.
- 운영자가 SQL을 직접 실행: 리뷰와 테스트를 거친 문장과 실제로 실행한 문장이 같다는 보장이 없고 이력도 남지 않는다.
- QA 활동까지 보존: QA 게시글에 달린 타인의 투표·댓글이 지워지면서 post와 post_option의 집계 컬럼을
  재계산해야 한다. 출시 피드에 테스트 게시글이 남는 문제도 있다.
