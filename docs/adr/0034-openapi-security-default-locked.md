# ADR-0034 OpenAPI 문서는 기본 잠금이고, 공개 엔드포인트만 명시적으로 해제한다

**상태**: Accepted

## 맥락

`/scalar` 와 `/llms.txt` 는 FE 가 API 계약서로 쓰는 문서다. 그런데 문서만 보고는 **어떤 API 가 인증을 요구하는지 알 수 없었다**. Swagger UI 의 Authorize 버튼도 뜨지 않아 문서에서 인증이 필요한 요청을 시험해 볼 수 없었다.

원인은 문서가 게을러서가 아니다. **springdoc 은 Spring Security 설정을 읽지 않는다.** 컨트롤러 애노테이션만 보고 스펙을 만든다. 그래서 `SecurityConfig` 에 `.anyRequest().authenticated()` 가 있어도 스펙에는 한 글자도 나오지 않는다. 확인한 사실:

| 확인한 것 | 결과 |
|---|---|
| 코드베이스의 `SecurityScheme`·`SecurityRequirement` | **0건** |
| `SecurityConfig` 의 기본 규칙 | `.anyRequest().authenticated()` |
| 공개 엔드포인트 | `PUBLIC_GET` + `permitAll` 목록에 **명시된 것만** (9개) |
| 스펙의 인증 정보 | 없음 |

즉 인증 사실이 **보안 설정과 문서 애노테이션이라는 두 정본**에 나뉘어 있고, 둘을 잇는 장치가 없다. 문서 쪽 정본은 아예 비어 있었다.

## 결정

`DocsConfig` 의 `OpenAPI` 빈에 bearer 스킴을 **전역** 등록하고, **공개 엔드포인트에만** 값 없는 `@SecurityRequirements` 를 붙여 잠금을 푼다.

```java
.components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
.addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
```

```java
@SecurityRequirements   // 값이 없으면 스펙에 "security": [] 를 낸다
@GetMapping("/posts")
```

**방향이 결정의 전부다.** 반대로도 같은 그림을 만들 수 있다 — 인증이 필요한 16개에 `@SecurityRequirement` 를 하나씩 붙이는 것이다. 그걸 택하지 않은 이유:

`SecurityConfig` 의 기본값이 `authenticated()` 이므로 **새로 만든 엔드포인트는 아무것도 하지 않아도 인증 대상이 된다.** 문서를 "기본 공개 + 예외 잠금" 으로 만들면 새 API 를 추가할 때마다 문서에만 손을 안 대면 조용히 틀린다. 그리고 그 틀림의 방향이 나쁘다 — **잠긴 API 를 공개로 표시**한다. 반대 방향의 실수(공개 API 를 잠긴 것으로 표시)는 FE 가 401 을 안 받고 넘어가므로 금방 드러난다.

문서 모델을 보안 모델과 **같은 모양**으로 두면 드리프트가 한쪽으로만 생기고, 그 한쪽이 안전한 쪽이다.

## 결과

**얻은 것**

- Scalar·Swagger UI 에 Authorize 버튼이 뜬다. 문서에서 토큰을 넣고 인증 API 를 시험할 수 있다.
- 자물쇠 표시가 실제 보안 규칙과 일치한다 — 공개 9개, 인증 16개.
- 새 엔드포인트는 **자동으로 인증 필요**로 표시된다. 공개하려면 `@SecurityRequirements` 를 의식적으로 붙여야 하고, 그 순간 `SecurityConfig` 에도 넣었는지 되묻게 된다.

**포기한 것**

- 두 정본은 여전히 둘이다. `SecurityConfig` 에서 `permitAll` 을 지우고 애노테이션을 안 지우면 문서만 틀린다. 이 ADR 은 정본을 하나로 합치지 않고 **방향만 안전한 쪽으로 정렬**했다.
- 공개 엔드포인트가 늘 때마다 두 곳을 고쳐야 한다. 9개 정도라 감당 가능하다고 봤다.
- `/llms.txt` 는 이 정보를 싣지 않는다. `OpenApiMarkdownRenderer` 가 `security` 를 읽지 않기 때문이다(머리말에 "인증이 필요한 요청에는 Authorization 헤더를 보낸다" 는 고정 문구만 있다). LLM 은 여전히 어느 API 가 공개인지 문서로 알 수 없다 — 필요해지면 렌더러를 고치는 별도 작업이다.

**선택적 인증 한 곳**

`GET /posts/{postId}/comments` 는 `permitAll` 이면서 `@CurrentUser viewerId` 를 받는다. 로그인하면 `mine` 이 채워지고 게스트면 `false` 다(SPEC §3.4). 자물쇠는 떼되 이 사실을 `@Operation` 설명에 적었다 — 스펙에는 "선택적 인증" 을 표현할 자리가 없다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 인증 16개에 개별 `@SecurityRequirement` | 위에 적은 대로 드리프트가 위험한 방향으로 생긴다 |
| `OpenApiCustomizer` 로 `SecurityConfig` 의 allowlist 를 읽어 자동 적용 | 정본이 하나가 되어 가장 좋다. 하지만 `PUBLIC_GET` 은 `private static` 이고 `permitAll` 목록은 람다 안에 흩어져 있어 읽어낼 수가 없다. 꺼내려면 보안 설정을 문서 때문에 리팩터링해야 하는데, 그건 꼬리가 몸통을 흔드는 것이다 |
| 스킴 이름을 `bearer` 로 | `bearerAuth` 가 OpenAPI 예제의 관용이고 스킴 종류(`scheme: bearer`)와 이름이 같으면 헷갈린다 |
