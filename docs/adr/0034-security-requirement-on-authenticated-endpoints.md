# ADR-0034 인증 표시는 인증을 요구하는 엔드포인트에 직접 붙인다

**상태**: Accepted

## 맥락

`/scalar` 와 `/llms.txt` 는 FE 가 API 계약서로 쓰는 문서다. 그런데 문서만 보고는 **어떤 API 가 인증을 요구하는지 알 수 없었다**. Swagger UI·Scalar 의 Authorize 버튼도 뜨지 않아 문서에서 인증이 필요한 요청을 시험해 볼 수 없었다.

원인은 문서가 게을러서가 아니다. **springdoc 은 Spring Security 설정을 읽지 않는다.** 컨트롤러 애노테이션만 보고 스펙을 만든다. 그래서 `SecurityConfig` 에 `.anyRequest().authenticated()` 가 있어도 스펙에는 한 글자도 나오지 않는다. 확인한 사실:

| 확인한 것 | 결과 |
|---|---|
| 코드베이스의 `SecurityScheme`·`SecurityRequirement` | **0건** |
| `SecurityConfig` 의 기본 규칙 | `.anyRequest().authenticated()` |
| 공개 엔드포인트 | `PUBLIC_GET` + `permitAll` 목록에 **명시된 것만** (9개) |
| 스펙의 인증 정보 | 없음 |

## 결정

`DocsConfig` 의 `OpenAPI` 빈에는 **스킴만 정의**한다. 어느 API 가 인증을 요구하는지는 **각 핸들러에 붙인 `@SecurityRequirement(name = "bearerAuth")`** 가 정한다.

```java
// DocsConfig — 스킴 정의. 이게 없으면 Authorize 버튼 자체가 뜨지 않는다.
.components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
```

```java
// 인증이 필요한 엔드포인트 16개에만 붙인다.
@SecurityRequirement(name = "bearerAuth")
@GetMapping("/users/me")
```

**공개 엔드포인트 9개에는 아무것도 붙이지 않는다.**

### 왜 전역 잠금이 아닌가

전역 `addSecurityItem` 으로 전부 잠그고 공개 엔드포인트에서 값 없는 `@SecurityRequirements` 로 푸는 방법도 있다. springdoc 이 1급으로 지원하고, "새 API 는 자동으로 잠긴다" 는 fail-safe 이점도 있다. 실제로 그렇게 먼저 구현했다가 되돌렸다.

**읽을 수 없기 때문이다.** 그 방식에서는

- 인증이 필요한 API 에는 **아무 표시가 없고**
- 공개 API 에만 애노테이션이 붙는다

애노테이션 이름(`@SecurityRequirements`)은 "보안이 필요하다" 로 읽히는데 값이 없으면 정반대로 **푸는** 뜻이다. 코드를 읽는 사람이 매번 반대로 이해한다. 실제로 리뷰에서 곧바로 "permitAll 인데 왜 `@SecurityRequirements` 가 붙느냐" 는 지적이 나왔다.

문서의 목적은 읽는 사람에게 사실을 전하는 것이다. **표시는 요구하는 쪽에 붙는다** — 자물쇠가 필요한 문에 자물쇠를 다는 것이지, 열린 문에 "열림" 팻말을 다는 게 아니다. 규약의 안전성보다 코드의 가독성을 택했다.

## 결과

**얻은 것**

- Scalar·Swagger UI 에 Authorize 버튼이 뜬다. 문서에서 토큰을 넣고 인증 API 를 시험할 수 있다.
- 자물쇠 표시가 실제 보안 규칙과 일치한다 — 공개 9개, 인증 16개(스펙 JSON 으로 확인).
- 애노테이션이 있는 곳 = 인증이 필요한 곳. 코드가 문자 그대로 읽힌다.

**포기한 것**

- **새 엔드포인트는 문서에서 기본 공개다.** `SecurityConfig` 는 기본 잠금이라 실제로는 401 이 나는데 문서에는 자물쇠가 없을 수 있다. 애노테이션을 빠뜨리면 조용히 틀린다.
  → 완화책은 없다. 리뷰에서 본다. 인증 API 를 추가하면 `@SecurityRequirement` 도 함께 붙인다.
- 두 정본은 여전히 둘이다(`SecurityConfig` 와 애노테이션). 이 ADR 은 정본을 합치지 않는다.
- `/llms.txt` 는 이 정보를 싣지 않는다. `OpenApiMarkdownRenderer` 가 `security` 를 읽지 않기 때문이다. LLM 은 여전히 어느 API 가 공개인지 문서로 알 수 없다 — 필요해지면 렌더러를 고치는 별도 작업이다.

**선택적 인증 한 곳**

`GET /posts/{postId}/comments` 는 `permitAll` 이면서 `@CurrentUser viewerId` 를 받는다. 로그인하면 `mine` 이 채워지고 게스트면 `false` 다(SPEC §3.4). 애노테이션은 붙이지 않되 이 사실을 `@Operation` 설명에 적었다 — 스펙에는 "선택적 인증" 을 표현할 자리가 없다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 전역 `addSecurityItem` + 공개에 값 없는 `@SecurityRequirements` | 위에 적은 대로 코드가 정반대로 읽힌다. fail-safe 이점보다 오독 비용이 크다 |
| `OpenApiCustomizer` 로 `SecurityConfig` 의 allowlist 를 읽어 자동 적용 | 정본이 하나가 되어 가장 좋다. 하지만 `PUBLIC_GET` 은 `private static` 이고 `permitAll` 목록은 람다 안에 흩어져 있어 읽어낼 수가 없다. 꺼내려면 보안 설정을 문서 때문에 리팩터링해야 하는데, 그건 꼬리가 몸통을 흔드는 것이다 |
| 스킴 이름을 `bearer` 로 | `bearerAuth` 가 OpenAPI 예제의 관용이고, 스킴 종류(`scheme: bearer`)와 이름이 같으면 헷갈린다 |
