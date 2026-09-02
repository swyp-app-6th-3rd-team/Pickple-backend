# ADR-0009 — 로그를 파일로 남기고 도커 볼륨에 영속화한다

**상태**: Accepted

## 맥락

이 템플릿은 **온프레미스 홈서버에 컨테이너로 배포**하는 것을 전제한다.
로컬 개발이라면 콘솔 로그로 충분하지만, 원격 서버에서는 장애가 났을 때 로그를 어떻게
확보하느냐가 조사 가능 여부를 가른다.

문제를 세 단계로 짚으면:

**1) 표준 출력만 쓰면** `docker logs` 로만 볼 수 있다. 실제 저장 위치는
`/var/lib/docker/containers/{ID}/{ID}-json.log` 이고, 한 줄이 JSON 한 객체라
읽기가 나쁘다. 레벨별로 거르기도 어렵다.

**2) Logback 으로 파일에 남겨도** 그 파일은 **컨테이너 계층에만** 존재한다.
도커 이미지는 읽기 전용이고 변경분은 컨테이너 계층에 쌓이므로,
`docker rm` 하는 순간 로그가 통째로 사라진다. 배포는 대개 컨테이너 재생성이므로
**배포할 때마다 이전 로그를 잃는다.**

**3) 컨테이너가 기동조차 못 하면** 파일 appender 가 준비되기 전에 죽을 수 있다.
이때 콘솔까지 꺼두면 원인을 볼 방법이 없다.

## 결정

**파일 로깅 + named volume 영속화**를 기본으로 한다.

### 로그 구조

> **이 절은 [ADR-0025](0025-single-log-file.md) 로 대체되었다(2026-09-03).**
> 로그는 레벨 구분 없이 `app.log` 한 파일에 시간순으로 남긴다. 아래 내용은 대체 이전의 결정이다.
> 이 ADR 의 나머지 결정(파일로 남긴다·볼륨에 영속화한다·컨테이너 권한)은 **그대로 유효하다.**

레벨별로 디렉터리를 나눈다.

```
${LOG_DIR}/error/error.log       ERROR 만
${LOG_DIR}/warn/warn.log         WARN 만
${LOG_DIR}/info/info.log         INFO 이상 전부
```

`error`·`warn` 은 `LevelFilter` 로 **그 레벨만** 남긴다. error 파일에 info 가 섞이면
장애 시 찾는 의미가 없다. 반면 `info` 는 `ThresholdFilter` 로 INFO 이상을 전부 담는다 —
"그 요청이 어떻게 흘러가다 실패했는가"를 보려면 전체 흐름이 필요하다.

### 볼륨

```yaml
volumes:
  - type: volume
    source: app_log
    target: /app/logs

volumes:
  app_log:
    name: ${COMPOSE_PROJECT_NAME:-sakila}-log
```

### 흔한 구성과 다르게 간 지점

| 항목 | 흔한 구성 | 이 템플릿 | 이유 |
|---|---|---|---|
| 볼륨 생성 | `docker volume create` 후 `external: true` | compose 가 관리, `name:` 고정 | 템플릿에서 "먼저 볼륨을 만드세요"라는 수동 절차를 없앤다. `name:` 을 고정해 프로젝트명 접두어가 붙지 않으므로 호스트에서 찾기 쉽고 `down` 후에도 재사용된다 |
| prod 콘솔 로깅 | 파일만 | **콘솔도 유지** | 기동 실패는 파일 appender 준비 전에 일어난다. 콘솔을 끄면 `docker logs` 가 비어 원인을 못 본다. 실제로 이 작업 중 OAuth 설정 오류로 기동이 실패했고, 콘솔 로그로 원인을 찾았다 |
| MDC | 없음 | `correlationId` 패턴 연동 | 이 프로젝트엔 이미 `CorrelationIdFilter` 가 있다. 로그에 싣지 않으면 그 필터가 무용지물이다 |
| 비동기 쓰기 | 없음 | `AsyncAppender` + `discardingThreshold=0` | 디스크가 느려도 요청 스레드가 붙잡히지 않는다. 기본값은 큐가 80% 차면 WARN 이하를 버리는데, 장애 때 로그가 폭증하므로 하필 그때 버려진다 |

### 컨테이너 권한

Dockerfile 이 `USER app` 으로 non-root 실행하므로, **`USER` 를 바꾸기 전에**
로그 디렉터리를 만들고 소유권을 넘긴다.

```dockerfile
RUN mkdir -p /app/logs/error /app/logs/warn /app/logs/info && \
    chown -R app:app /app/logs
USER app
```

도커는 빈 named volume 을 마운트할 때 **이미지의 해당 경로 소유권을 볼륨에 복사한다.**
디렉터리가 없으면 `root:root` 로 만들어져 앱이 쓰지 못한다
(`FileNotFoundException: ... (Permission denied)`).

## 결과

**검증한 것** (실제 컨테이너로)

| 항목 | 결과 |
|---|---|
| non-root 쓰기 권한 | ✅ `/app/logs/*` 소유자가 `app:app` |
| 레벨 분리 | ✅ error→ERROR만, warn→WARN만, info→ERROR·WARN·INFO |
| 볼륨 위치 | ✅ `/var/lib/docker/volumes/sakila-log/_data` |
| **컨테이너 삭제 후 로그 보존** | ✅ `docker rm` 후에도 1,943줄 그대로 |
| 새 컨테이너가 이어서 기록 | ✅ 1,943 → 1,985줄 (덮어쓰지 않고 누적) |
| 이전 컨테이너 기록 유지 | ✅ 이전 종료 로그가 그대로 남음 |
| MDC correlationId | ✅ 단위 테스트 3건 (`CorrelationIdLoggingTest`) |

**포기한 것**
- named volume 은 호스트 경로가 `/var/lib/docker/volumes/...` 라 **직접 tail 하려면
  root 권한이 필요하다.** 호스트에서 자주 들여다볼 계획이면 bind mount 가 편하다
  (compose 에 주석으로 대안을 남겼다). 대신 bind mount 는 호스트 디렉터리 소유권을
  직접 맞춰야 하고, UID 가 어긋나면 같은 권한 문제가 난다.
- 로그 수집기(Loki·ELK 등)를 두지 않았다. 단일 홈서버에서 파일로 충분하고,
  측정 없이 미들웨어를 도입하지 않는다는 원칙에 따라 v1 범위 밖으로 둔다.
  로그가 여러 노드로 흩어지면 그때 재검토한다.
- 구조화 로깅(JSON)을 쓰지 않았다. Boot 4 가 지원하지만, 사람이 `tail` 로 읽는 게
  주 용도인 단계에서는 평문이 낫다. 수집기를 도입하면 함께 전환한다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 표준 출력만 쓰고 `docker logs` 로 확인 | 컨테이너 삭제 시 사라지고, JSON 한 줄씩이라 읽기 나쁘며 레벨별 필터가 안 된다 |
| bind mount (`./logs:/app/logs`) | 호스트 경로는 직관적이지만 소유권을 직접 맞춰야 하고 UID 불일치 시 권한 오류가 난다. 기본값은 named volume 으로 두고 주석으로 대안 제공 |
| 로그 수집기(Loki/ELK) 도입 | 단일 홈서버에 과하다. 운영 부담과 리소스가 늘고, 지금 측정된 문제가 없다 |
| 도커 로깅 드라이버(json-file `max-size`) 설정만 | 표준 출력 한계는 그대로다. 레벨 분리·구조화된 파일명이 안 된다 |
| 파일만 남기고 콘솔 끄기 | 기동 실패 시 `docker logs` 가 비어 원인을 못 본다. 실제로 겪었다 |
