# ADR-0025 — 로그를 레벨로 나누지 않고 한 파일에 남긴다

**상태**: Accepted

이 ADR 은 **ADR-0009 의 "레벨별로 디렉터리를 나눈다" 절만** 대체한다.
ADR-0009 의 핵심 결정("로그를 파일로 남기고 도커 볼륨에 영속화한다")은 그대로 유효하며,
오히려 이번에 `/data` 로 옮기면서 강화된다.

## 맥락

ADR-0009 는 레벨별 분리를 이렇게 정당화했다.

> `error`·`warn` 은 `LevelFilter` 로 **그 레벨만** 남긴다. error 파일에 info 가 섞이면
> 장애 시 찾는 의미가 없다.

의도는 타당했다. 그런데 실제로 운영해 보니 두 가지가 걸렸다.

**중복 저장.** `info` 는 `LevelFilter` 가 아니라 `ThresholdFilter` 를 쓴다(INFO 이상 전부).
"그 요청이 어떻게 흘러가다 실패했는가"를 보려면 전체 흐름이 필요하다는 판단이었는데,
그 결과 **ERROR 한 줄이 `error.log` 와 `info.log` 양쪽에 들어간다.** WARN 도 마찬가지다.
같은 내용을 두 번 쓰고 두 번 보관한다.

**조사할 때 파일을 오간다.** 장애를 볼 때 실제로 하는 일은 "ERROR 를 찾고 → 그 직전 맥락을 본다"이다.
파일이 나뉘어 있으면 `error.log` 에서 시각을 확인하고 `info.log` 에서 그 시각을 다시 찾아야 한다.
한 파일이면 `grep -n ERROR` 후 그 줄 번호 주변을 보면 끝난다.

결정적으로, **파일을 나눌 필요가 애초에 없었다.** `FILE_LOG_PATTERN` 이 이미 각 줄에 시각과 레벨을 찍는다.

```
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [correlationId] [trace_id] [span_id] [thread] logger - msg
```

레벨은 파일 이름이 아니라 **줄 안에** 있다. 파일 분리는 `grep` 이 이미 하는 일을 디렉터리 구조로 중복한 것이다.

## 결정

### 1. 파일 appender 를 하나로 합친다

`ERROR_FILE`·`WARN_FILE`·`INFO_FILE` 3개와 대응하는 async 3개를 `FILE`·`ASYNC_FILE` 각 1개로 줄인다.

```
${LOG_DIR}/app.log                 현재 파일
${LOG_DIR}/app-2026-08-15.0.log    롤링된 파일
```

### 2. 레벨 필터를 두지 않는다

`LevelFilter`·`ThresholdFilter` 를 모두 제거한다. **무엇을 남길지는 `<root level>` 하나가 정한다.**
필터가 여러 겹이면 "이 로그가 왜 안 남지"를 추적할 곳이 늘어난다.

### 3. 유지하는 것

- **콘솔 appender** — 기동 실패는 파일 appender 가 준비되기 전에 일어날 수 있다. `docker logs` 가 그때 유일한 단서다(ADR-0009 의 판단 그대로)
- **`discardingThreshold=0`** — 큐가 차도 버리지 않는다. 장애 조사용 로그를 정작 장애 때 잃으면 의미가 없다
- **`includeCallerData=true`** — 기존에는 ERROR appender 에만 켜져 있었다. 통합 appender 가 ERROR 도 담으므로 켠 채로 둔다
- **MDC(`correlationId`·`trace_id`·`span_id`)** — 한 요청을 다시 모으는 수단. 파일이 하나가 되면서 오히려 더 중요해진다

## 결과

**얻는 것**
- 같은 로그를 두 번 쓰지 않는다. 디스크와 I/O 가 줄어든다
- 한 요청의 흐름이 한 파일에 시간순으로 이어진다. 파일을 오갈 필요가 없다
- appender 6개 → 2개. 설정이 짧아지고 `<root>` 참조도 3줄 → 1줄

**잃는 것 (트레이드오프)**
- **`cat error.log` 로 에러만 즉시 보던 경로가 사라진다.** 이제 `grep -E ' (ERROR|WARN) ' app.log` 를 써야 한다.
  한 단계 늘지만 줄 안에 레벨이 있으므로 실패하지 않는 조작이다
- ERROR 만 따로 오래 보관하는 정책을 두려면 지금 구조로는 어렵다. 필요해지면 수집기(Loki 등) 쪽에서 한다
- 파일 하나가 커진다 → `maxFileSize` 100MB 와 `totalSizeCap` 으로 제어한다(기존과 동일한 상한)

## 검토한 대안

**레벨 분리 유지 (기각)**
현상 유지. 중복 저장과 파일 오가기가 그대로 남는다. "error 파일에 info 가 섞이면 안 된다"는
원래 논거는 `info.log` 가 이미 ERROR 를 포함하고 있어 실제로는 지켜지지 않고 있었다.

**ERROR 만 별도 유지 (기각)**
`app.log` + `error.log` 절충안. 중복은 여전하고(ERROR 가 양쪽에), 파일이 2개라 조사 동선도 그대로다.
얻는 것에 비해 구조가 남는다.

**JSON 구조화 로깅 (기각 — 지금은)**
`logstash-logback-encoder` 로 JSON 출력. 수집기가 있으면 최선이지만, 지금은 수집기가 없어
`tail` 로 직접 읽는다. JSON 은 사람이 읽기 나빠 **가독성만 잃는다.**
Loki 등을 붙이는 시점에 다시 판단한다.

**로그 레벨을 파일명이 아니라 디렉터리로 (기각)**
같은 문제의 변형. 근본 원인은 "레벨이 이미 줄 안에 있는데 파일로 또 나눈 것"이다.

## 참고

- ADR-0009 §레벨별 디렉터리 — 이 ADR 이 대체하는 결정
- ADR-0012 — `/data` 는 인스턴스 replace 를 견뎌야 하는 것을 담는다는 원칙.
  로그를 `/data/logs` 로 옮긴 것은 새 결정이 아니라 이 원칙의 적용이다
- `src/main/resources/logback-spring.xml`
- 이슈 #55
