# ADR-0029 — API 경로에서 `/api` prefix 를 걷어내고, 과도기는 리버스 프록시가 흡수한다

**상태**: Superseded by [ADR-0033](0033-drop-api-prefix-implemented.md) (2026-09-04)

> 아래 "열린 질문" 3개가 모두 문제없음으로 닫혀 구현에 들어갔다(#91).
> 결정의 방향(`api.` 서브도메인 위에 `/api` path 를 다시 얹지 않는다)은 유지되지만,
> **과도기 브릿지는 두지 않는 것으로 뒤집혔다** — 이 문서가 스스로 적어둔 "브릿지를
> 아예 생략할 수 있다"는 조건이 성립했다. 구현 결과와 뒤집힌 항목은 ADR-0033 에 있다.
> 아래 본문은 결정 당시의 기록이므로 고치지 않는다.

## 맥락

배포 도메인이 이미 `dev-api.pickple.app` 로 `api.` 서브도메인을 쓴다. 그 위에 다시 `/api`
path prefix 를 얹으면 같은 말을 두 번 하게 된다 — `dev-api.pickple.app/api/posts`.

prefix 가 **어디에 사는지**를 먼저 측정했다. 세 곳 중 두 곳은 비어 있었다.

| 자리 | 실측 결과 |
|---|---|
| `server.servlet.context-path` | **미설정.** `src/main/resources` · `docker/` 전수 grep 에 없다 |
| `docker/Caddyfile` | **`/api` 를 라우팅 키로 쓰지 않는다.** `handle { reverse_proxy app:8080 }` 로 전량 전달하고, 분기하는 것은 `/actuator/*` 뿐이다 |
| 컨트롤러 애노테이션 | **여기가 전부다.** 클래스 레벨 `@RequestMapping` 4개 + 메서드에 직접 박힌 1개 |
| `springdoc.paths-to-match` | ⚠️ **`/api/**` 다**(`application.yml:165`). 아래 참조 — 이슈의 범위 조사에 없던 항목이다 |

즉 prefix 는 설정도 인프라도 아니고 **컨트롤러에 손으로 반복해 적은 문자열**이다.
그래서 제거 비용은 낮은데, 제거의 **파급**은 낮지 않다 — 이게 이 문서가 존재하는 이유다.

현재 prefix 를 갖는 표면은 다음과 같다.

| 클래스 | 현재 매핑 | 형태 |
|---|---|---|
| `AuthController` | `@RequestMapping("/api/auth")` | 클래스 레벨 |
| `UserProfileController` | `@RequestMapping("/api/users")` | 클래스 레벨 |
| `CommentController` | `@RequestMapping("/api")` | 클래스 레벨 (메서드가 `/posts/…`·`/comments/…` 로 갈린다) |
| `PostController` | `@RequestMapping("/api/posts")` | 클래스 레벨 |
| `ImageUploadController` | `@PostMapping("/api/images")` | **메서드에 직접** — 클래스 레벨 매핑이 없다 |

여기에 `SecurityConfig` 의 `/api/**` 매처 7개가 붙는다. 이 매처들은 컨트롤러 매핑에서
**파생되지 않는다** — 독립적인 문자열 패턴이다. 이 사실이 아래 "결과" 의 위험 항목을 만든다.

### 이건 API 계약 변경이다

프론트가 지금 `/api/...` 로 호출하고 있다면, 서버만 바꾸는 순간 **전부 404** 다.
백엔드가 단독으로 밀 수 있는 변경이 아니다. 되돌리기 비싼 결정이므로 ADR 이 먼저다.

## 결정

**대안 1 을 채택한다 — 컨트롤러에서 `/api` prefix 를 제거해 계약을 실제로 바꾼다.
다만 프론트 전환이 끝날 때까지는 대안 3(리버스 프록시 strip)을 과도기 브릿지로 함께 켠다.**

두 대안은 배타가 아니다. 하나는 **정본을 어디에 둘 것인가**를 정하고, 다른 하나는
**전환 기간의 실패를 무엇이 흡수할 것인가**를 정한다.

- **정본은 서버 코드다.** 컨트롤러 매핑이 `/posts` · `/auth/me` · `/users/me` 로 바뀌고,
  이것이 이후 모든 문서·클라이언트가 따르는 경로가 된다.
- **과도기에는 Caddy 가 `/api/*` → `/*` 를 rewrite 한다.** 구 경로 호출이 계속 살아 있으므로
  프론트와 백엔드가 **동시에 배포될 필요가 없다.**
- **브릿지는 한시적이다.** 프론트 전환 완료가 확인되면 Caddy 의 rewrite 블록을 지운다.
  지우는 시점에 구 경로는 404 가 되고, 그것이 의도된 최종 상태다.

### 왜 굳이 둘 다인가

대안 1 단독의 실패 모드는 "합의가 어긋나면 서비스 전면 404" 다. 되돌리려면 코드를 되돌리고
재배포해야 한다. 대안 3 을 브릿지로 두면 **롤백이 Caddy 설정 한 블록**으로 내려간다 —
최악의 경우에도 rewrite 를 남겨두면 구 경로가 계속 동작한다.

반대로 대안 3 단독은 "중복" 을 풀지 못한다. 문제를 인프라로 옮길 뿐, 서버가 아는 자기 경로는
여전히 `/api/posts` 이고 로컬 개발(프록시 없음)과 배포의 경로가 갈린다. **정본이 두 개가 된다.**

### 함께 하는 구조 변경 — 클래스 레벨 `@RequestMapping` 을 두지 않는다

prefix 를 걷어내는 김에 매핑 구조도 바꾼다. **클래스 레벨 `@RequestMapping` 을 제거하고
각 핸들러 메서드가 전체 경로를 직접 갖는다.**

```java
// before — 경로를 알려면 클래스와 메서드 애노테이션을 머릿속에서 합쳐야 한다
@RestController
@RequestMapping("/api/posts")
class PostController {
    @GetMapping                       // 최종 경로가 여기 안 보인다
    ApiResponse<…> findAll(…) { … }
}

// after — 메서드만 봐도 최종 경로를 안다
@RestController
class PostController {
    @GetMapping("/posts")
    ApiResponse<…> findAll(…) { … }
}
```

이유는 **읽는 사람의 작업기억**이다. 지금은 핸들러 하나의 경로를 알려면 두 애노테이션을
합성해야 하고, `CommentController` 처럼 클래스 레벨이 `/api` 한 조각뿐이면 그 합성이 더 헷갈린다.
`ImageUploadController` 는 이미 이 형태로 되어 있어 새로 도입하는 관행도 아니다.

부수 효과가 하나 있는데, 이쪽이 오히려 본질에 가깝다. **bare `@GetMapping`(경로 없는 매핑)이
사라진다.** 경로 없는 매핑은 클래스 레벨 매핑이 바뀌는 순간 조용히 다른 경로로 옮겨간다 —
컴파일도 통과하고 git 도 충돌 없이 머지된다. 아래 "결과" 의 첫 번째 위험이 정확히 이것이다.

이 관행은 `ArchitectureTest` 가 강제한다(이 PR 에 포함). 규칙이 먼저 들어가면, prefix 제거가
나중에 이뤄지더라도 **그 사이에 새로 생기는 컨트롤러가 옛 관행을 다시 심지 못한다.**

## 결과

**얻는 것.** 경로에서 중복이 사라진다(`api.pickple.app/posts`). 핸들러 메서드 한 줄만 보면
최종 경로를 알 수 있다. 프론트·백엔드 배포 순서 제약이 없어진다(브릿지 덕분).

**치르는 비용.**

- **경로가 두 벌 존재하는 기간이 생긴다.** 브릿지가 켜져 있는 동안 `/api/posts` 와 `/posts` 가
  모두 200 이다. 이 기간이 길어지면 "어느 쪽이 정본인지" 가 다시 흐려진다.
  브릿지 제거를 별도 이슈로 끊어 두고, 이 ADR 이 `Accepted` 가 되는 시점에 기한을 적는다.
- **Caddy 가 라우팅 로직을 갖게 된다.** 지금까지 `docker/Caddyfile` 은 전량 전달 + actuator
  차단만 했다. rewrite 가 들어가면 "요청이 앱에 닿기 전에 무슨 일이 일어나는가" 를
  한 군데 더 봐야 한다. 한시적이라는 전제로 감수한다.
- **로컬 개발에는 브릿지가 없다.** `docker-compose-local.yml` 은 Caddyfile 을 쓰지 않는다
  (ADR-0022 · ADR-0024). 로컬에서는 신 경로만 동작한다. 이건 오히려 정본을 분명히 하므로
  의도된 비대칭으로 둔다.

**가장 큰 위험 — `SecurityConfig` 매처가 조용히 어긋난다.**

Spring Security 매처는 컨트롤러 매핑에서 파생되지 않는 **독립 문자열**이다. 컨트롤러만 바꾸고
매처를 놓치면 **양방향으로 조용히 깨진다.**

| 놓친 방향 | 결과 | 왜 조용한가 |
|---|---|---|
| 매처에 구 경로가 남음 | 신 경로가 `anyRequest().authenticated()` 로 떨어져 **게스트 진입 화면이 401** | 컴파일 통과. 인증된 요청으로 테스트하면 200 이라 안 보인다 |
| 매처가 지나치게 넓음 | 보호돼야 할 경로가 **permitAll 로 열림** | 200 이 나오므로 "동작한다" 로 보인다. 실패가 아니라 성공처럼 보이는 게 최악이다 |

그래서 구현 시 **매처 7개를 하나씩 새 경로와 대조하고, 인증이 필요한 경로가 실제로 401 을
주는지 통합 테스트로 증명한다.** "테스트 통과" 는 대리지표다 — 보호가 실제로 걸렸는지는
401 을 눈으로 확인해야 안다.

**전환 여지.** 브릿지가 있으므로 결정을 되돌리는 비용이 낮다. 프론트 합의가 뒤집히면
컨트롤러를 되돌리는 대신 브릿지를 영구화하는 선택지도 남는다(그 경우 이 ADR 을 대체하는
새 ADR 을 쓴다 — 이 문서를 고치지 않는다).

## 검토한 대안과 기각 사유

| 대안 | 내용 | 기각 사유 |
|---|---|---|
| **1. 컨트롤러에서 prefix 제거** | 계약이 실제로 바뀐다. 프론트 동시 변경 필요 | **채택.** 단독으로는 "합의 어긋나면 전면 404" 라는 실패 모드가 남아, 대안 3 을 과도기 브릿지로 덧댄다 |
| **2. `server.servlet.context-path: /api` 유지** | prefix 를 설정 한 곳으로 모은다 | prefix 가 **없어지지 않는다.** 이슈가 제기한 문제는 "prefix 가 여러 곳에 흩어져 있다" 가 아니라 "`api.` 서브도메인과 의미가 중복된다" 다. 이 대안은 중복을 그대로 둔 채 위치만 옮긴다. 게다가 context-path 는 actuator·swagger·`/llms.txt` 같은 **비 API 경로까지 함께 밀어버려** 부작용이 더 크다 |
| **3. 리버스 프록시에서 `/api` strip** | 서버 코드 그대로, Caddy 가 rewrite. 롤백이 제일 싸다 | **단독으로는 기각, 브릿지로는 채택.** 단독 채택 시 서버가 아는 자기 경로는 여전히 `/api/posts` 라 정본이 인프라와 코드로 쪼개진다. Caddyfile 을 쓰지 않는 로컬 개발과 배포의 경로가 갈리고(ADR-0024), OpenAPI·`/llms.txt` 가 렌더하는 경로도 구 경로 그대로다 — 문서가 거짓말을 하게 된다 |
| **4. 구 경로를 컨트롤러에 함께 남긴다** (`@GetMapping({"/posts", "/api/posts"})`) | 브릿지를 코드로 구현 | 매핑이 두 배가 되고 `SecurityConfig` 매처도 두 배가 된다. 위에서 "가장 큰 위험" 으로 짚은 매처 동기화 문제를 **두 배로 키운다.** 제거할 때도 코드 변경·재배포가 필요해 브릿지의 존재 이유(싼 롤백)가 사라진다 |
| **5. 아무것도 하지 않는다** | `/api/posts` 유지 | 동작에는 문제가 없다. 다만 공개 API 경로는 한번 굳으면 바꾸기 더 비싸지므로, 클라이언트가 늘기 전인 지금이 가장 싸다. 6주 MVP 라 지금 정리하지 않으면 사실상 영구화된다 |

## 열린 질문 — 프론트 합의 미완 (⚠️ 이 문서가 `Proposed` 인 이유)

**아직 합의되지 않았다.** 아래는 합의 결과에 따라 **뒤집힐 수 있는** 항목이다.
합의 없이 구현을 진행하지 않는다.

1. **프론트가 실제로 `/api/...` 를 하드코딩하고 있는가, 아니면 base URL 로 주입하는가.**
   후자면 브릿지 없이도 전환이 싸다 — 그 경우 대안 3 브릿지를 아예 생략할 수 있다.
   이 답에 따라 이 ADR 의 "브릿지를 함께 켠다" 절반이 불필요해진다.
2. **iOS 클라이언트의 배포 주기.** 앱은 강제 업데이트 전까지 구 버전이 남는다.
   웹만 있으면 브릿지 기간이 짧고, 앱이 끼면 **브릿지가 앱 릴리스 주기만큼 길어진다.**
   이 경우 "한시적" 이라는 전제 자체를 재검토해야 한다.
3. **브릿지 제거 기한.** 위 2번이 정해져야 정할 수 있다. `Accepted` 로 넘어갈 때 명시한다.
4. **경로 네이밍을 함께 정리할 것인가.** 예컨대 `/users/me` 와 `/auth/me` 가 공존한다.
   prefix 제거와 별개의 결정이므로 **이 ADR 의 범위 밖**으로 두었다. 필요하면 별도 ADR.

합의가 끝나면 이 문서를 고치지 않고 상태만 `Accepted` 로 올리며, 합의 내용이 위 결정을
바꾸면 **새 번호의 ADR 로 대체한다**(ADR 은 불변).

## 구현 순서 — 이 PR 이 하는 것과 하지 않는 것

이 결정은 **컨트롤러 전부를 건드리는 전역 리팩터링**이라, 진행 중인 작업과 정면으로 부딪힌다.
작업 시점에 `feature/#17-post-creation`(PR #76)이 `PostController` 에 핸들러를 추가하고 있었고,
`#21`(새 `VoteController`) · `#24`(`CommentController` 핸들러 추가)도 열려 있었다.

**여기서 git 이 도와주지 않는다.** 지금 클래스 레벨 `@RequestMapping("/api/posts")` 를 지우고,
나중에 bare `@PostMapping` 을 추가하는 PR #76 을 머지하면 — 두 변경은 **다른 줄**을 건드리므로
**충돌 없이 깨끗하게 머지되고**, `create()` 핸들러는 빈 경로에 매핑된다. 텍스트 충돌이라면
사람이 한 번은 볼 텐데, 이 경우는 아무도 보지 않는다. **조용한 손상이 충돌보다 위험하다.**

그래서 이 PR 의 범위를 이렇게 나눈다.

| 단계 | 이번 PR | 비고 |
|---|---|---|
| 결정 기록 (이 ADR) | ☑ | 충돌 없음 |
| `ArchitectureTest` 규칙 — 핸들러 경로 비어있지 않음 | ☑ **활성** | 경로 명시는 URL 을 바꾸지 않아 합의를 기다릴 이유가 없다. `PostController.findAll` 의 bare `@GetMapping` 을 `@GetMapping("/api/posts")` 로 바꿔 통과시켰다 — **URL 은 그대로다** |
| `ArchitectureTest` 규칙 — 클래스 레벨 매핑 금지 | ☑ 코드는 들어감 / `@Disabled` | 켜면 `AuthController`·`UserProfileController`·`CommentController`·`VoteController` **4개**가 잡힌다(2026-09-03 재측정 — `VoteController` 는 #21 이 머지되며 뒤늦게 합류했다). 이들을 고치는 것이 곧 계약 변경이라 합의 전에는 켤 수 없다. **규칙을 미리 넣어두면 일괄 변경 PR 이 `@Disabled` 한 줄만 지우면 된다** |
| 문서 갱신(SPEC · PRD) | ☑ | 계획된 경로를 정본에 반영 |
| 컨트롤러 5개 + `SecurityConfig` 매처 7개 + 통합 테스트 경로 | ☐ **후속 PR** | 위 브랜치들이 머지된 **뒤**에 일괄로. 그때 `grep -rn '@RequestMapping' src/main/` 으로 새로 들어온 클래스 레벨 매핑을 재확인한다 |
| Caddy 과도기 rewrite | ☐ **후속 PR** | 프론트 합의(열린 질문 1·2) 후 |

ArchUnit 규칙을 먼저 넣으면 **후속 PR 의 안전망이 미리 깔린다.** 위에서 말한 조용한 손상
— bare `@PostMapping` 이 살아남는 경우 — 이 머지 직후 **테스트 실패로 드러난다.**
규칙이 뒤에 오면 그 창(window)이 열린 채로 남는다.

### 위반 대상은 고정된 목록이 아니다 (실측)

이 ADR 작성 시점의 위반 컨트롤러는 3개였다. **#21(투표 참여 API)이 머지되며
`VoteController` 가 `@RequestMapping("/api")` 를 들고 합류해 4개가 됐다.**

`@Disabled` 를 잠시 풀어 확인한 실제 출력(2026-09-03 재측정):

```
Rule 'no classes that are annotated with @RestController should be annotated with
@RequestMapping' was violated (4 times):
  app.pickple.auth.controller.AuthController
  app.pickple.auth.controller.UserProfileController
  app.pickple.comment.controller.CommentController
  app.pickple.vote.controller.VoteController      ← #21 로 새로 들어옴
```

그러므로 **후속 일괄 변경 PR 은 이 목록을 다시 세어야 한다.** 여기 적힌 숫자는
그 시점의 관측값이지 고정된 명세가 아니다. 세는 방법은 `@Disabled` 를 지우고
`./gradlew test --tests '*ArchitectureTest*'` 를 돌려 규칙이 지목하게 하는 것이다 —
손으로 grep 해 세면 새로 들어온 것을 놓친다.

### 후속 일괄 변경 PR 이 손대야 할 것 (2026-09-03 실측 전수)

| 대상 | 개수 | 위치 |
|---|---|---|
| 컨트롤러 클래스 레벨 `@RequestMapping` | **4** | `AuthController`(`/api/auth`) · `UserProfileController`(`/api/users`) · `CommentController`(`/api`) · `VoteController`(`/api`) |
| 메서드에 직접 박힌 `/api` | **1** | `PostController#findAll` — `@GetMapping("/api/posts")` (이 PR 에서 클래스 레벨을 걷으며 옮겨 놓은 것) |
| `SecurityConfig` 매처 | **7** | `GET /api/posts` · `GET /api/posts/{postId}/comments` · `GET /api/users/nickname/availability` · `POST /api/auth/{apple,refresh,mobile/refresh,logout}` |
| `springdoc.paths-to-match` | **1** | `application.yml` — `/api/**`. **이슈의 범위 조사에 없던 항목이다.** 바꾸지 않으면 경로 변경 후 OAS 에서 엔드포인트가 통째로 사라진다 |
| 테스트의 `/api` 경로 | **12 파일** | 통합 테스트 전반 |

`ImageUploadController`·`LlmsTxtController`·`PostController` 는 클래스 레벨 매핑이 이미 없다.

### ⚠️ 머지 순서 — 이 PR 은 마지막에 머지해야 한다

실측으로 확인한 구체적 사례다. PR #76(`feature/#17-post-creation`)이 `PostController` 에
**경로 속성이 없는** `@PostMapping` 을 추가한다.

```java
@PostMapping                      // ← 경로 없음. @RequestMapping("/api/posts") 에 100% 의존
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<PostCreateResponse> create(…)
```

이 핸들러는 클래스 레벨 매핑이 있어야만 `/api/posts` 로 간다. 클래스 레벨 매핑이 걷힌 뒤에
이 코드가 들어오면 **루트(`""`)로 떨어진다** — 컴파일 통과, 머지 충돌 없음, CI 초록.

따라서 순서가 규칙의 값어치를 가른다.

| 머지 순서 | 결과 |
|---|---|
| #76 · #21 · #24 **먼저** → 이 PR **나중** | ✅ 새로 들어온 bare 매핑을 ArchUnit 규칙이 **즉시 잡는다** |
| 이 PR **먼저** → #76 나중 | ❌ 규칙은 머지 시점의 코드만 봤다. 나중에 들어온 bare 매핑은 **조용히 통과** |

**이 PR 은 진행 중인 컨트롤러 추가 브랜치(#21 · #24 · #76)가 모두 머지된 뒤 마지막에 머지한다.**
머지 직전 `git merge origin/develop` 후 아래 둘을 재확인한다.

```bash
grep -rn '@RequestMapping' src/main/java --include='*Controller*.java'
./gradlew test --tests '*ArchitectureTest*'
```

> #76 의 코드는 이 PR 이 고치지 않는다(남의 PR 이다). 규칙이 잡아 **드러나게** 하는 것이
> 이 PR 의 몫이고, 고치는 것은 컨트롤러 일괄 변경 PR 의 몫이다.

## 관련

- [ADR-0022](0022-route53-and-caddy-tls.md) — 도메인은 Route53, TLS 는 Caddy 가 종단한다
  (브릿지 rewrite 가 들어갈 자리가 여기다)
- [ADR-0024](0024-local-run-environment.md) — 로컬 compose 는 Caddyfile 을 쓰지 않는다
  (그래서 로컬에는 브릿지가 없다)
- [ADR-0007](0007-scalar-manual-registration.md) · [ADR-0011](0011-llms-txt-runtime-rendering.md) —
  문서 경로는 API 가 아니므로 prefix 제거 대상이 아니다. context-path 대안을 기각한 근거이기도 하다
- Issue [#75](https://github.com/swyp-app-6th-3rd-team/6th-buy-or-pass-backend/issues/75)
