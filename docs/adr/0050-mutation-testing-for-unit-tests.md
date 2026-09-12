# ADR-0050 — 커버리지는 하한선으로 고정하고, 단언 강도는 뮤테이션 점수로 판정한다

**상태**: Accepted

**관련**: [ADR-0008](0008-domain-entity-separation.md) 이 도메인 객체를 JPA 엔티티에서 분리해
도메인 판정 로직을 프레임워크 없이 실행 가능하게 만들어 두었다. 이 문서는 그 위에서
"도메인 판정이 테스트로 실제 보호되는가" 를 어떻게 측정할지 정한다.

## 맥락

JaCoCo 플러그인은 이미 적용돼 있고 `test` 가 `jacocoTestReport` 를 `finalizedBy` 로 호출한다.
그런데 **리포트를 만들 뿐 아무도 판정하지 않았다.** `jacocoTestCoverageVerification` 이 없고
CI 는 `build/reports/jacoco` 를 아티팩트로 올리기만 했다. 커버리지가 떨어져도 빌드는 초록이었다.

도입 시점 실측:

| 항목 | 값 |
|---|---|
| LINE | 91.5% |
| BRANCH | 81.5% |
| 전체 테스트 | 154 suite / 990 test / 96~105초 |
| 유닛(`*IT` 제외) | 89 suite / 477 test / 4.3초 |
| **뮤테이션 점수** | **71%** (723 mutations, 516 killed) |
| Test strength | 91% |

여기서 결핍이 둘로 갈린다.

**첫째, 하한선이 없다.** 수치는 이미 높은데 고정돼 있지 않아, 앞으로 들어오는 변경이
조용히 수치를 깎아도 신호가 없다.

**둘째, 커버리지는 단언 강도를 못 본다.** 라인 커버리지는 "그 줄이 실행됐다"까지만 말하고
"그 줄이 틀렸을 때 테스트가 잡는다"는 말하지 않는다. 판정 로직이 도메인에 몰려 있어
(`Post.verifyPublishable` 의 R-01·R-02·R-04 검증 등) **단언을 지워도 커버리지는 그대로다.**

실제로 뮤테이션을 돌리자 커버리지만으로는 안 보이던 것이 드러났다 — 아래 "무엇이 드러났나".

## 결정

**1. 커버리지는 실측 바로 아래로 하한선을 박는다.** LINE 0.90 / BRANCH 0.80.
`jacocoTestCoverageVerification` 을 `check` 에 걸어 기존 `build` 가 그대로 판정한다.
목표치(95/90)가 아니라 실측 바로 아래로 잡은 이유는, 이 게이트의 목적이 향상 강제가 아니라
**회귀 차단**이기 때문이다. 도입과 동시에 부채 상환을 강제하면 도입 자체가 막힌다.

**2. 유닛 전용 태스크 `unitTest` 를 둔다.** Docker 없이 4.3초에 477개를 돌린다.

제외는 **클래스 파일 패턴(`exclude '**/*IT.class'`)으로 한다. 이름 필터를 쓰면 안 된다** —
Gradle 의 `excludeTestsMatching` 은 패턴을 메서드 이름에도 적용해서, `*IT` 가
`ArchitectureTest.integrationTestsEndWithIT()` 를 조용히 빼버린다(실측: ArchUnit 규칙 24 → 23).
하필 유닛·통합 분리를 보장하는 그 규칙이라 분리의 근거 자체가 사라진다.
`*IT.*` 로 고치면 이번엔 `ActivityControllerIT` 가 새어 들어왔다(477 → 575).

**3. 뮤테이션 테스트(PIT)를 유닛 대상으로 돌린다.** `mutationThreshold = 60`.
대상은 도메인·서비스 한정 — 판정 로직이 거기 있다. 통합 테스트는 대상에서 뺀다.
Testcontainers 가 뮤턴트마다 뜨면 시간이 폭발한다.

**4. ArchUnit 은 `archunit-junit6` 모듈을 쓴다(1.5.0).** 이것이 PIT 도입의 전제였다.

## PIT 를 넣기 위해 무엇이 필요했나

`info.solidsoft.pitest` 를 선언만 해도(설정 블록 없이) 테스트 탐색이 깨졌다:

```
JUnitException: TestEngine with ID 'junit-jupiter' failed to discover tests
Caused by: OutputDirectoryCreator not available; probably due to unaligned versions of
           the junit-platform-engine and junit-platform-launcher jars
```

단일 변수 실험(초록 상태 + 플러그인 4줄): `test` 가 6 suite/30 test/0 실패 → 1 test/1 실패.

원인은 의존성 해석이다. Boot 4.1 은 JUnit 6.0.3 을 관리하는데 **`archunit-junit5` 는
Platform 1.x 로 빌드돼 `junit-bom:5.12.2` 제약을 들고 온다.** 평소에는
`junit-platform-launcher` 가 테스트 런타임에 해석되지 않아(`(n)`) 이 충돌이 잠들어 있었다.
pitest 가 launcher 를 클래스패스에 올리는 순간 제약이 깨어나 `launcher 6.0.3 → 1.12.2` 로
내려가고, engine 은 6.0.3 에 남아 어긋난다.

`resolutionStrategy.force`(스코프 2종)와 `junit-bom` exclude 는 **모두 실패했다**.
해답은 강제가 아니라 원인 제거였다 — **ArchUnit 1.5.0 이 JUnit 6 전용 모듈
`archunit-junit6` 을 낸다**(TNG/ArchUnit#1556). 이 모듈의 `engine-api` 는
`junit-platform-engine:6.1.2` 를 선언하므로 1.x 제약 자체가 사라진다.

교체 후 launcher 6.1.2 / engine 6.0.3 으로 정렬되고 PIT 가 정상 동작한다.
테스트 코드는 한 줄도 바꾸지 않았다 — `ArchitectureTest` 는 `@AnalyzeClasses`/`@ArchTest` 가
아니라 평범한 `@Test` + `ClassFileImporter` 를 쓰고, import 는 `com.tngtech.archunit.base/core/lang.*`
로 두 모듈이 동일하다.

## 무엇이 드러났나 (뮤테이션의 효용)

전체 71% 뒤에 **뮤턴트를 하나도 못 죽이는 클래스들**이 있다:

| 클래스 | 점수 | 비고 |
|---|---|---|
| `BadgeService` | 0% (0/16) | 유닛·통합 어디에도 직접 테스트 없음 |
| `RankingQueryService` | 0% (0/13) | 동일 |
| `ActivityQueryService` | 0% (0/8) | 동일 |
| `PointHistory` | 0% (0/10) | 통합 테스트 6건이 스치지만 단언이 잡지 못함 |
| `VoteService` | 22% (6/27) | |

`PointHistory` 가 특히 이 도입의 논거다 — **통합 테스트를 6건이나 통과하는데
뮤턴트는 하나도 안 죽는다.** 코드가 실행되지만 검증되지는 않는 상태이고,
커버리지 수치로는 영원히 보이지 않는다.

## 결과 (트레이드오프)

**얻는 것**
- 커버리지 회귀가 빌드에서 막힌다. 사람의 주의력에 맡기지 않는다.
- 단언이 빈 테스트가 점수로 드러난다. 위 표가 다음 보강 대상 목록이 된다.
- 유닛 스위트가 분리돼 Docker 없이 4.3초에 도는 피드백 경로가 생겼다(전체는 96~105초).

**포기하는 것**
- **CI 시간이 늘어난다.** PIT 는 유닛 대상에서 25초가 더 붙는다.
- **뮤테이션 대상이 좁다.** 컨트롤러·인프라의 단언 강도는 범위 밖이다.
- **임계값 60 은 바닥값이지 목표가 아니다.** 현재 71% 라 11%p 여유가 있는데,
  이는 회귀를 막되 오늘의 팀을 막지 않기 위한 간격이다. 0% 클래스들을 보강한 뒤 올린다.
- ArchUnit 을 1.4.1 → 1.5.0 으로 올렸다. 규칙 24개가 전부 그대로 통과하는 것은 확인했다.

## 검토한 대안과 기각 사유

**A. 커버리지 임계값만 목표치(95/90)로 올리고 뮤테이션은 안 넣는다.**
기각. 결핍의 둘째를 전혀 건드리지 못한다. `PointHistory` 처럼 "통합 테스트 6건을 통과하지만
뮤턴트는 0개 죽이는" 클래스는 커버리지를 아무리 올려도 안 보인다.
게다가 현재 91.5/81.5 라 95/90 은 즉시 실패해 도입 자체가 막힌다.

**B. `@Tag("unit")` 을 붙여 태그로 유닛을 가른다.**
기각. 58개 클래스에 손으로 붙여야 하고 새 테스트가 빠뜨리면 조용히 누락된다.
`*IT` 네이밍은 ArchUnit 이 이미 강제하므로 규약이 자동으로 유지된다.

**C. 뮤테이션을 전체 테스트(통합 포함) 대상으로 돌린다.**
기각. Testcontainers 가 뮤턴트마다 MySQL·LocalStack 을 띄운다. 통합 96초 × 뮤턴트 수는
CI 예산을 넘는다. 판정 로직은 도메인·서비스에 있다.

**D. `resolutionStrategy.force` 로 launcher 버전을 고정한다.**
기각(실패). `testRuntimeClasspath` 스코프와 `configureEach` 스코프 모두에서
launcher 가 1.12.2 로 남았다. ArchUnit 의 `junit-bom` exclude 도 실패했다.
증상을 누르는 대신 원인(Platform 1.x 로 빌드된 모듈)을 교체하는 것이 답이었다.

**E. 뮤테이션을 포기하고 커버리지 게이트만 넣는다.**
기각. 한때 이 결론을 내렸으나 `archunit-junit6` 의 존재를 확인하지 못한 탓이었다.
`archunit-junit5` 의 POM 만 보고 "1.5.0 도 Platform 1.14.4 라 안 된다" 고 판단했는데,
같은 릴리스에 **JUnit 6 전용 형제 모듈**이 따로 있었다. 아티팩트 하나만 보고
프로젝트 전체의 지원 여부를 단정한 것이 오판의 원인이다.

**F. gradle-pitest-plugin 을 Maven Central 최신(1.15.0)으로 고정한다.**
기각. 1.15.0 은 2023년판으로 Gradle 9.2.1 에서 깨진다. Maven Central 이 1.15.0 에서 멈춘 것은
플러그인이 Gradle Plugin Portal 자체 저장소(`plugins.gradle.org/m2`)에 배포되기 때문이고,
포털 기준 최신은 1.19.0(2026-03-29)이다.

### 기술 전제 (확인 완료)

- PIT 는 Java 25 를 지원한다 — 메인테이너, [hcoles/pitest#1439](https://github.com/hcoles/pitest/issues/1439):
  "Pitest support java bytecode versions up [to and] including Java 26."
- PIT 1.30.0, `pitest-junit5-plugin` 1.2.3, `gradle-pitest-plugin` 1.19.0(Gradle 8.4+/9.0).
  junit5 어댑터가 Platform 1.9.2 로 빌드됐지만 Platform 6 위에서 동작한다(실측).
