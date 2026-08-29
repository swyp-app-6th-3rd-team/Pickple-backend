# ADR-0006 — 인증은 흔한 결함을 미리 막는 형태로 구현한다

**상태**: Accepted

## 맥락

OAuth2 + JWT 인증은 예제가 흔하다. 문제는 널리 퍼진 예제 상당수가
**동작은 하지만 보안상 위험한 형태**라는 점이다. 그대로 옮기면 결함까지 따라온다.

여기에 더해 Spring Boot 4 / Security 7 은 이전 세대와 API 가 달라
기존 예제를 복사하면 컴파일조차 되지 않는다.

## 결정

**구조는 표준적인 형태를 따르고, 알려진 결함은 미리 막는다.**

### 채택한 구조

| 자산 | 이유 |
|---|---|
| `OAuth2UserInfo` 인터페이스 + 프로바이더별 어댑터 | Google(평평) · Kakao(중첩) · Naver(래퍼) 의 응답 구조가 실제로 달라 추상화가 값을 한다 |
| 쿠키 기반 `AuthorizationRequestRepository` | `STATELESS` 세션 정책에 필수 |
| `@CurrentUser` 메타 애노테이션 | 별도 `ArgumentResolver` 없이 `@AuthenticationPrincipal` + SpEL 로 해결 |

### Boot 4 / Security 7 대응

| 이전 세대 | 문제 | 이 템플릿 |
|---|---|---|
| `MvcRequestMatcher` / `AntPathRequestMatcher` + `HandlerMappingIntrospector` | Security 7 에서 제거됨 | `PathPatternRequestMatcher` (패키지가 `...web.servlet.util.matcher` 다) |
| `DefaultAuthorizationCodeTokenResponseClient` | deprecated | 기본 클라이언트 사용 |
| `spring-cloud-starter-openfeign` 4.x | Boot 4 비호환 | 사용하지 않음 |
| `com.fasterxml.jackson.databind.ObjectMapper` | Boot 4 는 Jackson 3 | `tools.jackson.databind.ObjectMapper` |

### 미리 막은 결함 12건

흔한 구현에서 실제로 보이는 문제들이다.

| # | 흔한 형태 | 이 템플릿 |
|---|---|---|
| 1 | JWT secret 을 설정 파일에 하드코딩 | `${JWT_SECRET_KEY}` 기본값 없이. 32바이트 미만이면 **기동 실패** |
| 2 | refresh token 을 **URL 쿼리파라미터**로 전달 | HttpOnly·SameSite 쿠키 단일 경로. URL 은 히스토리·리퍼러·접근 로그에 남는다 |
| 3 | refresh token 을 여러 곳에 중복 저장 | DB 한 곳 |
| 4 | refresh token 원문 저장 | **SHA-256 해시** 저장. DB 유출 시 재사용 불가 |
| 5 | 로그인마다 refresh 행 INSERT (누적) | 사용자당 한 행 UNIQUE, 회전 시 UPDATE |
| 6 | 소셜 식별자 단독 조회 | `(provider, provider_id)` 복합 UNIQUE. 프로바이더 간 subject 충돌 방지 |
| 7 | `AuthenticationEntryPoint`/`AccessDeniedHandler` 없음 | 둘 다 구현. 401/403 을 `ApiResponse` JSON 으로 |
| 8 | `httpBasic` 활성 → 401 에 **브라우저 팝업** | `httpBasic` 비활성. `WWW-Authenticate` 헤더 없음 |
| 9 | 필터 안에서 CORS 헤더 손수 작성 | `CorsConfigurationSource` 한 곳 |
| 10 | 매 요청 `loadUserByUsername` 으로 DB 조회 | JWT 클레임으로 principal 구성. DB 조회 없음 |
| 11 | 리다이렉트 URI 검증 없음 (오픈 리다이렉트) | 호스트 화이트리스트. 미허용 시 기본값으로 폴백 |
| 12 | CORS `allowedOriginPatterns("*")` + `allowCredentials(true)` | 설정으로 받은 출처만 허용 |

추가로 **리프레시 토큰 재사용 탐지**를 넣었다 — 제출된 토큰이 저장된 해시와 다르면
이미 회전됐거나 탈취된 상황이므로, 저장된 토큰을 **폐기하고** 재로그인을 강제한다.

## 결과

**검증한 것** (통합 테스트 11건 + 실 HTTP 호출)

| 항목 | 결과 |
|---|---|
| 인증 없이 보호 API | 401 + `ApiResponse` JSON, `WWW-Authenticate` 헤더 없음 |
| 토큰 회전 | 재발급 시 쿠키 값이 바뀌고 DB 행은 누적되지 않음 |
| 회전된 옛 토큰 재사용 | 401 + 저장 토큰 폐기 |
| 액세스↔리프레시 혼용 | `typ` 클레임으로 차단 |
| 복합키 | 같은 `providerId` 라도 provider 가 다르면 별개 사용자 |

**후속 확장**
- Apple 네이티브 로그인은 브라우저 `OAuth2UserInfo` 어댑터가 아니라 별도 credential 검증 흐름으로
  추가했다. client secret의 최대 수명은 약 6개월이며, 5분 제한은 authorization code에 적용된다.
  상세 결정은 [ADR-0015](0015-native-sign-in-with-apple.md)에 있다.
- 액세스 토큰이 stateless 라 **즉시 무효화가 불가능하다.** 로그아웃해도 이미 발급된
  액세스 토큰은 만료(30분)까지 유효하다. 즉시 차단이 필요하면 블랙리스트(Redis 등)가 필요한데,
  측정 없이 미들웨어를 들이지 않는다는 원칙에 따라 v1 범위 밖으로 둔다.

## 검토한 대안

| 대안 | 기각 사유 |
|---|---|
| 흔한 예제를 그대로 이식 | Boot 4 에서 컴파일되지 않고 위 결함들이 따라온다 |
| 세션 기반 인증 | 요구사항이 JWT 를 명시했다. 수평 확장 시 세션 공유가 필요해진다 |
| 액세스 토큰도 DB 조회로 검증 | 즉시 무효화는 얻지만 매 요청 DB 를 때린다. 인증이 부하의 주범이 된다 |
| refresh token 을 Redis 에 저장 | 만료 자동 정리는 편하지만 미들웨어가 하나 늘고, 측정 없이 도입하지 않는다는 원칙에 어긋난다 |
| Apple 포함 | 구현 비용이 나머지 셋을 합친 것보다 크다. 필요해지면 `OAuth2UserInfo` 어댑터를 추가하면 된다 |
