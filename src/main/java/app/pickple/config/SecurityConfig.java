package app.pickple.config;

import app.pickple.auth.oauth.CustomOAuth2UserService;
import app.pickple.auth.oauth.HttpCookieOAuth2AuthorizationRequestRepository;
import app.pickple.auth.oauth.OAuth2FailureHandler;
import app.pickple.auth.oauth.OAuth2SuccessHandler;
import app.pickple.auth.security.AccountStateUnavailableFilter;
import app.pickple.auth.security.ActiveAccountAuthorizationManager;
import app.pickple.auth.security.AnonymousDemotionFilter;
import app.pickple.auth.security.JwtAuthenticationFilter;
import app.pickple.auth.security.RestAccessDeniedHandler;
import app.pickple.auth.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 설정.
 *
 * <p><b>Spring Security 7 대응</b> — {@code MvcRequestMatcher}/{@code AntPathRequestMatcher} 와
 * {@code HandlerMappingIntrospector} 가 제거되어 {@link PathPatternRequestMatcher} 를 쓴다.
 * Security 6.x 의 matcher 헬퍼를 그대로 가져오면 컴파일되지 않는다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@EnableConfigurationProperties({AuthProperties.class, AppleProperties.class,
        KakaoProperties.class, ProfileProperties.class})
public class SecurityConfig {

    // 문서 경로는 한 덩어리로 둔다. 나중에 운영에서 문서를 잠글 때
    // /llms.txt 만 남아 공개되는 일이 없도록 같은 자리에서 관리한다.
    private static final String[] PUBLIC_GET = {
            "/", "/error", "/favicon.ico",
            "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/scalar/**", "/scalar",
            "/llms.txt", "/llms.md",
            // 문서가 싣는 ERD 그림. Boot 기본 정적 처리가 classpath:/static/docs 를 여기로 낸다.
            "/docs/**",
            "/actuator/health/**"
    };

    private final CustomOAuth2UserService oAuth2UserService;
    private final OAuth2SuccessHandler successHandler;
    private final OAuth2FailureHandler failureHandler;
    private final HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ActiveAccountAuthorizationManager activeAccountAuthorizationManager;
    private final AccountStateUnavailableFilter accountStateUnavailableFilter;
    private final AnonymousDemotionFilter anonymousDemotionFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final AuthProperties properties;
    private final ObjectProvider<DevLoginProperties> devLoginProperties;

    /**
     * 관리 포트(management.server.port) 전용 체인.
     *
     * <p>포트를 분리해도 Spring Security 필터 체인은 그대로 적용된다 — 그래서
     * 관리 포트로 {@code /actuator/health} 를 긁어도 401 이 난다.
     * compose healthcheck 와 배포 파이프라인이 이 경로를 쓰므로 통과시켜야 한다.
     *
     * <p>이 체인을 {@code @Order} 로 먼저 등록해 관리 포트 요청만 가로챈다.
     * 서비스 포트(8080)의 보안 규칙은 아래 {@link #filterChain} 이 그대로 담당하므로
     * {@code PUBLIC_GET} 에 actuator 경로를 넣을 때처럼 외부에 노출되지 않는다.
     *
     * <p>관리 포트는 compose 네트워크 안에만 열린다(호스트 포트 매핑 없음).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder mvc = PathPatternRequestMatcher.withDefaults();
        boolean devLoginEnabled = devLoginProperties.getIfAvailable() != null;

        return http
                .csrf(csrf -> csrf.disable())          // 토큰 기반이라 세션 CSRF 가 없다
                .httpBasic(basic -> basic.disable())   // 켜두면 401 에 브라우저 팝업이 뜬다
                .formLogin(form -> form.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // 프로필·활성화 조건을 통과한 설정 빈이 있을 때만 JWT 없이 진입한다.
                        // 진입 후에는 DevLoginService가 QA 키와 허용 계정을 검증한다.
                        .requestMatchers(mvc.matcher(HttpMethod.POST, "/auth/dev/login"))
                        .access((authentication, context) -> new AuthorizationDecision(devLoginEnabled))
                        .requestMatchers(mvc.matcher("/auth/dev/**")).denyAll()
                        .requestMatchers(mvc.matcher(HttpMethod.GET, "/")).permitAll()
                        .requestMatchers(toMatchers(mvc, PUBLIC_GET)).permitAll()
                        .requestMatchers(
                                // 커뮤니티 진입 화면이라 로그인 전에 부른다.
                                mvc.matcher(HttpMethod.GET, "/posts"),
                                // 홈 화면의 인기 게시글 Top 10. /posts 패턴은 이 경로를 덮지 않는다 —
                                // PathPattern 은 세그먼트가 정확히 맞아야 하므로 따로 적어야 한다.
                                mvc.matcher(HttpMethod.GET, "/posts/popular"),
                                // 가입 화면에서 로그인 전에 부른다. 조회만 하고 아무것도 남기지 않는다.
                                mvc.matcher(HttpMethod.GET, "/users/nickname/availability"),
                                // 홈 화면의 인기 피커와 그 [더보기] 목록이라 로그인 전에 부른다 (§2.5·§3.1).
                                // 게스트에게 감추는 것은 목록이 아니라 "본인 랭킹" 이고, 그건 아래
                                // /users/me/points 가 인증을 요구하는 것으로 갈린다.
                                //
                                // ⚠️ 기능명세서 v0.3 §6.4 가 "게스트에게 댓글 목록을 주지 않는다" 로
                                // 바뀌었지만 그것은 댓글 한정이다. 랭킹의 게스트 허용은 유효하다 —
                                // 공개 여부는 엔드포인트마다 판단한다.
                                mvc.matcher(HttpMethod.GET, "/rankings"),
                                mvc.matcher(HttpMethod.GET, "/rankings/top"))
                        .permitAll()
                        .requestMatchers(mvc.matcher("/oauth2/**"), mvc.matcher("/login/oauth2/**")).permitAll()
                        .requestMatchers(
                                mvc.matcher(HttpMethod.POST, "/auth/apple"),
                                mvc.matcher(HttpMethod.POST, "/auth/kakao"),
                                mvc.matcher(HttpMethod.POST, "/auth/refresh"),
                                mvc.matcher(HttpMethod.POST, "/auth/mobile/refresh"),
                                mvc.matcher(HttpMethod.POST, "/auth/logout")).permitAll()
                        // authenticated() 가 아니라 관문이다 — "토큰이 유효한가" 에 더해
                        // "이 신원이 아직 살아 있는가" 까지 묻는다. 새 보호 엔드포인트는
                        // 여기 걸리므로 별도 조치 없이 자동으로 차단된다 (ADR-0035).
                        .anyRequest().access(activeAccountAuthorizationManager))

                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(endpoint ->
                                endpoint.authorizationRequestRepository(authorizationRequestRepository))
                        .userInfoEndpoint(endpoint -> endpoint.userService(oAuth2UserService))
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                // 관문의 상태 조회가 DB 장애로 실패하면 503 으로 번역한다.
                // ExceptionTranslationFilter 는 AccessDeniedException/AuthenticationException 만
                // 잡고, @RestControllerAdvice 는 DispatcherServlet 안에서만 돈다 —
                // 이 필터가 없으면 DB 장애가 컨테이너 기본 500(HTML)으로 새어 나간다.
                // AuthorizationFilter 보다 바깥에 놓아야 관문의 예외가 여기를 지난다.
                .addFilterBefore(accountStateUnavailableFilter, AuthorizationFilter.class)

                // 비활성 신원을 익명으로 강등한다. AuthorizationFilter 보다 앞이어야 한다 —
                // 관문이 익명 요청을 보게 되어야 보호 경로가 403 이 아니라 401 로 나가고,
                // permitAll 경로는 관문이 돌지 않으므로 여기서만 강등된다.
                // 규칙은 하나다: 비활성 신원은 어디서든 익명이 된다.
                //
                // 기준점을 AuthorizationFilter 가 아니라 위 필터로 잡는다. 둘 다 같은 지점
                // 앞에 걸면 상대 순서가 등록 순서에 암묵적으로 기대게 되는데, 그 순서는
                // 프레임워크 계약이 아니다. 뒤집히면 여기서 던진 DataAccessException 이
                // 503 필터 바깥으로 새어 컨테이너 기본 500(HTML)이 된다 — C-9 가 조용히 무너진다.
                // 서로에게 직접 순서를 명시해 그 가능성을 없앤다.
                .addFilterAfter(anonymousDemotionFilter, AccountStateUnavailableFilter.class)
                .build();
    }

    private RequestMatcher[] toMatchers(PathPatternRequestMatcher.Builder mvc, String[] patterns) {
        return Arrays.stream(patterns)
                .map(mvc::matcher)
                .toArray(RequestMatcher[]::new);
    }

    /**
     * CORS.
     *
     * <p>{@code allowedOriginPatterns("*")} 에 {@code allowCredentials(true)} 를 함께 켜면
     * 쿠키를 아무 출처에나 실어 보낼 수 있게 되므로 위험하다.
     * 여기서는 설정으로 받은 출처만 허용한다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(true);   // 리프레시 토큰 쿠키 때문에 필요하다
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
