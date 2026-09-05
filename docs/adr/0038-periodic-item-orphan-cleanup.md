# ADR-0038 — 이미지 고아 객체는 주기적으로 정리한다

**상태**: Proposed (이슈 #49 구현, PR #116 검토 중)

**관계**: [ADR-0021](0021-s3-image-object-storage.md)의 즉시 best-effort 보상 삭제를 대체한다.
[ADR-0027](0027-image-public-access-via-cloudfront.md)의 공개 접근 방식은 유지하며,
목록 권한은 별도 인프라 작업을 거쳐야 하는 운영 활성화 조건으로 보완한다.
[ADR-0019](0019-policy-belongs-above-infrastructure.md)의 계층 경계를 따르고,
[ADR-0039](0039-explicit-http-exception-boundary.md)의 요청 오류와 내부 오류 분류를 따른다.

## 맥락

기존 `ImageUploadService`의 트랜잭션 완료 콜백은 서비스에 Spring 트랜잭션 인프라를 노출한다.
프로세스 종료, 업로드 응답 유실, 보상 삭제 실패 후에는 S3 객체를 회수할 경로도 없다.
성공적으로 업로드했지만 최종 게시글·댓글에 붙이지 않은 리소스 역시 남는다.

단순한 주기 조회 뒤 S3 선삭제는 안전하지 않다. 후보 발견 뒤 부착이 완료되거나,
진행 중인 업로드가 S3를 쓴 뒤 아직 DB를 커밋하지 않았다면 정상 이미지를 삭제할 수 있다.
현재 런타임 역할에는 `ListBucket`이 없고 이슈 #49는 Terraform/IAM 변경을 제외한다.

## 결정

1. 서비스는 전체 입력을 검증하고 모든 객체 키와 안정적인 접근 URL을 생성한다.
   `ItemContainerStore.save`는 리소스까지 flush한 뒤 반환하고, 그 다음 S3에 저장한다.
   이 과정은 기존 하나의 DB 트랜잭션 안에 있다. S3 실패는 DB를 롤백시키지만 객체 즉시 삭제는 하지 않는다.
   객체 키는 서버가 새 UUID로 생성하며 재사용하지 않는다. API 요청·응답과 `/images` 경로는 유지한다.
2. `item.infra.ItemOrphanCleanup`이 `ItemOrphanStore`와 `FileObjectStorage` 포트로 정리한다.
   스케줄링은 전역 활성화하고 랭킹·이미지 스케줄러에 각각 feature flag를 둔다.
   이미지 정리는 별도 단일 스레드 scheduler를 사용해 긴 S3 순회가 랭킹 실행을 막지 않게 한다.
3. 관리 시작 시각 이후 생성되고 `updated_at < now - gracePeriod`인 컨테이너를 ID 순서로 페이지 조회한다.
   후보마다 별도 `REQUIRES_NEW`, `READ_COMMITTED` 트랜잭션에서 부모 행을 잠그고
   `post_product`·`comment` 참조를 current read로 다시 확인한다. 소프트 삭제 참조도 점유로 본다.
   미부착이면 컨테이너와 cascade 리소스를 삭제한다. FK는 부착과 삭제가 동시에 성공하는 것을 막는다.
4. DB 삭제가 커밋된 뒤, 다른 메타데이터가 같은 키를 참조하지 않는지 확인하고 S3를 삭제한다.
   DB 삭제 실패 시 객체를 건드리지 않는다. S3 삭제 실패·프로세스 중단 시 남은 객체는 목록 탐색에서 재발견한다.
   별도 전달 테이블이나 Outbox를 추가하지 않는다.
5. S3 목록은 `product-images/`·`comment-images/` 접두어만 페이지 단위로 읽는다.
   `managedSince <= LastModified < now - gracePeriod`인 객체의 키를 DB에서 `FOR SHARE`로 조회한다.
   메타데이터를 먼저 flush했으므로 진행 중인 업로드가 커밋/롤백될 때까지 기다린다.
   DB 조회가 실패하거나 잠금 대기 시간이 초과되면 삭제하지 않는다.
6. 각 후보·키의 실패는 격리하고 다음 후보를 계속 처리한다. DB 페이지 조회 실패 후에도 S3 탐색은 수행하며,
   한 S3 접두어의 조회 실패는 다른 접두어의 처리를 막지 않는다. 다음 실행은 처음부터 다시 탐색한다.
7. 정리는 기본 비활성이다. 활성화에는 명시적 `managed-since`가 필요하고 기존 운영 객체 일괄 정리는 하지 않는다.
   운영 권한과 배포 환경 전달 경로가 준비되기 전에는 활성화하지 않는다.
8. `OnePickService`의 광범위한 DB 예외 catch를 제거한다. store는 MySQL 1062와
   `uk_pick_user_post` 제약이 일치할 때만 `DuplicatePickException`으로 변환한다.
   FK·다른 유니크·알 수 없는 무결성 오류는 원래 예외로 보존한다.
   실패한 JPA 트랜잭션에서 빈 값을 반환해 rollback-only를 숨기지 않는다.
9. 서비스의 `org.springframework.dao`, `org.springframework.jdbc`,
   `org.springframework.transaction.support`, `jakarta.persistence` 의존을 ArchUnit으로 금지한다.
   선언적 `@Transactional`은 허용한다.

## 결과와 트레이드오프

- 신규 브로커·Outbox·분산 트랜잭션이 필요 없다. 스키마 변경은 `item_resource.item_key`의
  비유니크 조회 인덱스(V13)만 추가하며 기존 데이터나 제약을 변경하지 않는다.
- 업로드 동안 DB 쓰기 잠금을 유지한다. 정리의 개별 DB 쓰기/키 확인 트랜잭션에는 5초 timeout을 둔다.
  정리는 진행 중인 업로드를 확인하지 못하면 다음 주기로 넘긴다.
- 정리가 비활성이면 롤백 객체도 남는다. 권한 준비 전 배포가 객체 회수까지 완료한 것은 아니다.
  두 종류의 고아 정리가 동작하려면 `s3:ListBucket`과 기존 `DeleteObject` 권한이 필요하다.
- 페이지 크기는 메모리 사용량을 제한하며 전체 실행 시간의 상한은 아니다. 매 실행은 관리 접두어를 순회한다.
  큰 버킷에서 실행 시간·목록 비용을 측정해야 한다. 성능 향상이나 운영 규모 검증을 주장하지 않는다.
- 현재 비버저닝 버킷을 전제로 한다. S3 버전 이력·멀티파트 업로드·CDN 캐시 제거는 처리하지 않는다.
- 오래된 미부착 컨테이너를 첨부하려는 요청은 정리가 먼저 커밋되면 실패할 수 있다.
  이미 커밋된 부착 이미지는 보존한다. 새 부착 테이블을 추가하면 후보 조회·참조 재확인·테스트도 함께 갱신한다.
- 키를 다른 업로드에서 재사용하거나 S3 쓰기 전에 메타데이터를 기록하지 않는 새 경로는 이 안전 조건을 깨뜨린다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 서비스 또는 외부 콜백에서 즉시 보상 삭제 | 프로세스 종료와 삭제 실패를 회수하지 못한다 |
| S3를 먼저 삭제한 뒤 DB 삭제 | 부착 경합에서 정상 이미지를 잃을 수 있다 |
| 일반 SELECT로 객체 키 존재만 조회 | 미커밋 업로드의 메타데이터를 없다고 판단할 수 있다 |
| 메타데이터만 정리 | S3-only 고아 회수라는 #49 완료 조건을 충족하지 못한다 |
| Outbox, 브로커, XA | 현 범위에 비해 스키마·배포·운영 비용이 크고 명시적 제외 범위다 |
| S3 Lifecycle/IAM을 이번 변경에서 확장 | 별도 운영 변경이 필요하며 #49 범위를 벗어난다 |

## 검증과 운영

`ItemOrphanCleanupIT`는 MySQL 8.4와 LocalStack으로 유예시간 경계, 페이지 순회, 두 고아 유형,
소프트 삭제 참조 보존, 부분 실패 재시도, 상위 트랜잭션 롤백, 부착 경합, 미커밋 업로드 키 조회를 검증한다.
기존 이미지 HTTP 통합 테스트와 원픽·랭킹 회귀 테스트를 함께 실행한다.
실행 결과와 미검증 범위는 PR 초안에 기록하며, 실제 AWS 검증은 이 결정의 범위 밖이다.

운영 절차: [이미지 고아 정리 runbook](../item-orphan-cleanup-runbook.md).

근거: [MySQL locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html),
[FK 검사와 행 잠금](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html),
[S3 ListObjectsV2 권한과 페이지 계약](https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html).
