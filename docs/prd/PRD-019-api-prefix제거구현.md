# PRD-019 — `/api` prefix 제거 구현

**이슈**: #91 · **결정**: [ADR-0033](../adr/0033-drop-api-prefix-implemented.md) (ADR-0029 대체)

## 무엇을 왜

배포 도메인이 `dev-api.pickple.app` 로 `api.` 서브도메인을 쓴다. path 에 다시 `/api` 를 얹으면
`dev-api.pickple.app/api/posts` 처럼 같은 말을 두 번 한다.

ADR-0029 가 이미 제거를 결정했으나 프론트 합의가 필요한 열린 질문 3개 때문에 `Proposed` 로
멈춰 있었다. **2026-09-04 그 3개가 문제없음으로 확인되어** 구현에 착수했다.

## 범위

### 포함
- 컨트롤러 클래스 레벨 `@RequestMapping` 4개 제거, 메서드에 전체 경로
- 메서드 애노테이션의 `/api` 제거 (8개 파일)
- `SecurityConfig` 매처 9개 동기화
- `springdoc` 문서 노출 기준을 `paths-to-match` → `paths-to-exclude` 로 전환
- `ArchitectureTest` 클래스 레벨 매핑 금지 규칙 활성화 (`@Disabled` 제거)
- 통합 테스트 경로 갱신 (14개 파일)
- ADR-0029 를 `Superseded` 로 표시하고 ADR-0033 으로 대체

### 제외
- **과도기 리버스 프록시 브릿지** — ADR-0029 가 계획했으나 열린 질문 1·2 가 닫히며
  불필요해졌다. 구 경로 `/api/...` 는 살리지 않는다.
- 신규 기능·엔드포인트 추가

## 완료 판정

| 판정 | 검증 방법 | 결과 |
|---|---|---|
| 전 엔드포인트가 `/api` 없이 응답 | 로컬 기동 후 실제 호출 | ✅ `/rankings`·`/posts` 200 |
| 인증 필요 경로가 그대로 401 | 토큰 없이 호출 | ✅ `/users/me/{grade,badges,points}` 401 |
| 게스트 허용 경로가 그대로 200 | 토큰 없이 호출 | ✅ `/rankings`·`/rankings/top`·`/posts` |
| **API 문서에 전 엔드포인트가 실린다** | `/v3/api-docs` 경로 수 대조 | ✅ **21개** |
| 문서에 내부 경로가 실리지 않는다 | 같은 응답에서 제외 대상 확인 | ✅ `/actuator`·`/scalar`·`/llms*`·`/error` 0개 |
| 클래스 레벨 매핑 금지 규칙 통과 | `@Disabled` 제거 후 실행 | ✅ |
| 전체 테스트 통과 | `./gradlew test` | ✅ **506개** |

## 열린 질문

없다. ADR-0029 의 열린 질문 3개는 닫혔고, 그 결과 브릿지가 범위에서 빠졌다.

## 주의

문서 노출이 **fail-open** 으로 바뀌었다. 새 API 경로는 자동으로 문서에 실리지만,
**공개하면 안 되는 경로(내부용·관리자용)를 만들면 `paths-to-exclude` 에 반드시 더해야 한다.**
설정 주석에 이 조건을 적어 두었다.
