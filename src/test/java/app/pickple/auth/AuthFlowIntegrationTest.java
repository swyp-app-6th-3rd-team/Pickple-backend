package app.pickple.auth;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.oauth.OAuth2SuccessHandler;
import app.pickple.auth.service.AuthService;
import app.pickple.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 플로우 통합 테스트 — 실제 필터 체인·시큐리티 설정을 통과시킨다.
 *
 * <p>소셜 프로바이더 왕복은 외부 시스템이라 여기서 재현하지 않는다.
 * 대신 <b>프로바이더가 사용자를 넘겨준 다음</b> 부터를 검증한다:
 * 토큰 발급 → 인증된 요청 → 재발급(회전) → 로그아웃.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class AuthFlowIntegrationTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private AuthService authService;

    private MockMvc mockMvc;
    private User user;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        user = userStore.save(new User(SocialProvider.GOOGLE, "sub-integration", "u@example.com", "홍길동"));
    }

    @Test
    @DisplayName("인증 없이 보호 API 를 부르면 401 이고 본문이 ApiResponse 형식이다")
    void unauthenticatedReturnsJson401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").exists())
                // Basic 인증 팝업을 띄우는 헤더가 없어야 한다.
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    @DisplayName("잘못된 토큰도 401 이다")
    void invalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("액세스 토큰으로 내 정보를 읽는다")
    void readsMeWithAccessToken() throws Exception {
        AuthService.TokenPair tokens = authService.issueTokens(user);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject.userId").value(user.id()))
                .andExpect(jsonPath("$.returnObject.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.returnObject.role").value(Role.ROLE_USER.name()));
    }

    @Test
    @DisplayName("재발급 — 쿠키의 리프레시 토큰으로 새 액세스 토큰을 받는다")
    void refreshesWithCookie() throws Exception {
        AuthService.TokenPair tokens = authService.issueTokens(user);

        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE, tokens.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.accessToken").isNotEmpty())
                // 리프레시 토큰은 응답 본문에 담기지 않는다.
                .andExpect(jsonPath("$.returnObject.refreshToken").doesNotExist())
                // 쿠키는 새 값으로 교체된다(회전).
                .andExpect(cookie().exists(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE))
                .andExpect(cookie().httpOnly(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE, true))
                .andReturn();

        String rotated = result.getResponse().getCookie(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE).getValue();
        assertThat(rotated).isNotEqualTo(tokens.refreshToken());
    }

    @Test
    @DisplayName("재발급 — 회전된 옛 토큰은 거부된다")
    void rejectsRotatedOldToken() throws Exception {
        AuthService.TokenPair first = authService.issueTokens(user);
        authService.refresh(first.refreshToken());     // 회전 발생

        // 옛 토큰을 다시 내밀면 저장된 해시와 다르므로 거부된다.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE, first.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("재발급 — 쿠키가 없으면 401 이다")
    void rejectsRefreshWithoutCookie() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("로그아웃하면 쿠키가 만료되고 재발급이 막힌다")
    void logoutExpiresCookieAndBlocksRefresh() throws Exception {
        AuthService.TokenPair tokens = authService.issueTokens(user);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE, 0));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(OAuth2SuccessHandler.REFRESH_TOKEN_COOKIE, tokens.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("소셜 로그인 진입점이 프로바이더로 리다이렉트한다")
    void redirectsToProvider() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("공개 엔드포인트는 인증 없이 열린다")
    void publicEndpointsAreOpen() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("같은 소셜 계정으로 다시 로그인해도 사용자가 늘지 않는다")
    void loginIsIdempotent() {
        var info = app.pickple.auth.oauth.OAuth2UserInfo.of(
                "google", java.util.Map.of("sub", "sub-integration", "email", "new@example.com", "name", "새이름"));

        User again = authService.loginOrRegister(info);

        assertThat(again.id()).isEqualTo(user.id());
        assertThat(again.email()).isEqualTo("new@example.com");   // 프로필은 갱신된다
    }

    @Test
    @DisplayName("프로바이더가 다르면 providerId 가 같아도 다른 사용자다")
    void sameProviderIdDifferentProviderIsDifferentUser() {
        // (provider, providerId) 복합 유니크라 충돌하지 않는다.
        User kakao = userStore.save(new User(SocialProvider.KAKAO, "sub-integration", null, null));

        assertThat(kakao.id()).isNotEqualTo(user.id());
        assertThat(userStore.findByProviderAndProviderId(SocialProvider.GOOGLE, "sub-integration"))
                .get().extracting(User::id).isEqualTo(user.id());
        assertThat(userStore.findByProviderAndProviderId(SocialProvider.KAKAO, "sub-integration"))
                .get().extracting(User::id).isEqualTo(kakao.id());
    }
}
