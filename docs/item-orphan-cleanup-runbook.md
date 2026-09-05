# 이미지 고아 객체 정리

이슈 #49의 정리는 기본 비활성이다. 코드·LocalStack 검증과 운영 활성화는 별도 단계다.
결정과 동시성 전제는 [ADR-0038](adr/0038-periodic-item-orphan-cleanup.md)에 있다.

## 설정

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `FILE_CLEANUP_ENABLED` | `false` | DB 고아 정리와 S3 목록 탐색을 함께 활성화 |
| `FILE_CLEANUP_CRON` | `0 0 * * * *` | 매시 정각, 기존 JVM 시간대 기준 |
| `FILE_CLEANUP_GRACE_PERIOD` | `24h` | 최근 업로드/수정 보호. 최소 1초 |
| `FILE_CLEANUP_MANAGED_SINCE` | 빈 값 | ISO-8601 Instant. 활성화 시 필수, 이 시각 전 기존 데이터 보호 |
| `FILE_CLEANUP_BATCH_SIZE` | `100` | DB 후보·S3 한 페이지 크기, 1~1000 |

`managed-since`는 배포 시각 중 **새 업로드 순서가 모든 인스턴스에 적용된 뒤의 시각**으로 고정한다.
예시 값은 `2026-09-05T12:00:00Z` 형태다. 배포·재시작마다 현재 시각으로 자동 갱신하면
실패 객체가 관리 대상에서 빠지므로 바꾸지 않는다. `now - gracePeriod`와 같은 경계의 데이터는 다음 주기까지 보존한다.
랭킹의 `RANKING_BATCH_ENABLED`와는 독립적이다. 이미지 정리는 전용 스레드를 사용하므로
긴 S3 순회가 랭킹 스케줄러 스레드를 점유하지 않는다. 한 정리 실행이 오래 걸리면 다음 정리 주기는 밀릴 수 있다.

현재 실행 주체는 `item.infra.ItemCleanupScheduler`와 `point.infra.RankingScheduler`다.
각 스케줄러가 저장소 포트를 직접 호출하며, 실행기는 각각 `itemCleanupTaskScheduler`와 `taskScheduler`로 명시한다.
S3 객체의 DB 참조는 페이지의 키를 묶어 한 번에 확인한다. 컨테이너 삭제는 부착 경합과 개별 실패를 격리하도록
컨테이너마다 별도 트랜잭션을 유지한다.

## 운영 활성화 전 별도 작업

1. V13을 포함한 애플리케이션을 정리 비활성 상태로 배포한다. 기존 업로드 요청까지 종료된 뒤 관리 시작 시각을 정한다.
2. 별도 인프라 변경에서 이미지 버킷의 `s3:ListBucket`을 준비한다. 목록은 `product-images/`·`comment-images/`만 필요하다.
   버킷 목록 권한과 객체 `s3:DeleteObject`의 리소스 ARN은 다르다. 실제 AWS 권한 검증은 그 작업에서 수행한다.
3. 배포 환경의 비밀값 이외 설정 전달 경로에 위 변수를 공급한다. EC2 compose는 허용 목록 방식이므로
   현재 파일에 변수를 추가하지 않고 `.env`에만 쓰는 것으로는 앱에 전달되지 않는다.
   이 이슈에서는 Terraform·IAM·실제 배포 설정을 변경하지 않는다.
4. 정리 시작 시각과 유예시간을 검토한 뒤 활성화한다. 기존 운영 객체의 일괄 정리를 위해 시작 시각을 과거로 낮추지 않는다.

## 실패와 재시도

- 컨테이너는 별도 DB 트랜잭션에서 미부착을 재확인하고 삭제한다. 커밋 뒤 S3 삭제 실패 시 메타데이터는 이미 없을 수 있다.
  다음 S3 목록 탐색이 객체를 다시 발견하므로 별도 삭제 명령을 만들지 않아도 재시도된다.
- `고아 컨테이너 정리 실패`는 해당 컨테이너만 보류한다. FK 경합·잠금 timeout은 다음 주기에 다시 판정한다.
- `고아 객체 참조 확인 실패`는 묶음의 DB 존재 확인 실패다. 해당 묶음을 보존하고 다음 페이지를 처리한다.
- `고아 객체 삭제 실패`는 해당 키의 S3 삭제 실패다. 다음 목록 탐색을 기다린다.
- `고아 객체 목록 조회 실패`의 403은 우선 `ListBucket` 권한과 접두어 조건을 확인한다.
  이 상태에서는 S3-only 고아 회수가 완료되지 않는다.
- 스케줄러 실행 로그와 실제 DB·객체 수를 확인한다. 일반 HTTP health 응답만으로 정리 성공을 판단하지 않는다.

## 중단·복구

`FILE_CLEANUP_ENABLED=false`로 재시작하면 자동 정리가 멈춘다. 이미 삭제된 DB/S3 데이터는 되살리지 않는다.
V13은 비유니크 인덱스 추가여서 애플리케이션만 이전 버전으로 돌아갈 때 남겨도 호환된다.
인덱스 제거가 필요하면 별도 순방향 Flyway 마이그레이션으로 `DROP INDEX idx_resource_key ON item_resource`를 적용한다.
운영 비버저닝 버킷의 삭제는 복구할 수 없으므로 기존 데이터 일괄 회수는 이 작업으로 수행하지 않는다.

## 로컬 검증

Docker 실행 후 Java 25에서 다음을 실행한다. 실제 AWS 자격증명 없이 Testcontainers가 MySQL과 LocalStack을 구성한다.

```powershell
.\gradlew.bat test --tests 'app.pickple.item.*' --tests 'app.pickple.comment.*' --tests 'app.pickple.config.SchedulingConfigIT' --tests 'app.pickple.architecture.ArchitectureTest'
```

현재 이미지 HTTP 경로는 `/images`다(ADR-0033). #49 본문의 `/api/images`는 이전 경로 표기다.
