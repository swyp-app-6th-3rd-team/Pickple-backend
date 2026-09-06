package app.pickple.auth;

import app.pickple.auth.apple.AppleIdTokenVerifier;
import app.pickple.auth.apple.AppleIdentity;
import app.pickple.auth.apple.AppleProviderTokenService;
import app.pickple.auth.apple.AppleTokenGateway;
import app.pickple.auth.apple.AppleTokenResponse;
import app.pickple.auth.domain.AppleClientType;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AccountWithdrawalService;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
        "app.oauth.apple.team-id=TEAM",
        "app.oauth.apple.key-id=KEY",
        "app.oauth.apple.private-key-base64=unused-in-mocked-provider-test",
        "app.oauth.apple.provider-token-encryption-keys=k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.oauth.apple.provider-token-active-key-id=k1",
        "app.oauth.apple.web.enabled=true",
        "app.oauth.apple.web.client-id=app.pickple.web",
        "app.oauth.apple.web.redirect-uri=https://api.pickple.app/auth/apple/web/callback"
})
class AppleWebLoginIT {

    private static final String APPLE_ORIGIN = "https://appleid.apple.com";
    private static final String VERIFIER = "v".repeat(43);
    private static final String CHALLENGE = challenge(VERIFIER);

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private AppleProviderTokenService providerTokenService;
    @Autowired
    private AccountWithdrawalService withdrawalService;

    @MockitoBean
    private AppleTokenGateway tokenGateway;
    @MockitoBean
    private AppleIdTokenVerifier verifier;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(tokenGateway, verifier);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void fullFlowConsumesOneHandoffExactlyOnceUnderConcurrency() throws Exception {
        String providerSub = "apple-web-concurrent-" + System.nanoTime();
        Handoff handoff = startAndCompleteCallback(providerSub, "a".repeat(22));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> exchange = () -> {
            ready.countDown();
            start.await();
            return mockMvc.perform(post("/auth/apple/web/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code":"%s","codeVerifier":"%s"}
                                    """.formatted(handoff.code(), VERIFIER)))
                    .andReturn().getResponse().getStatus();
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(exchange);
            Future<Integer> second = executor.submit(exchange);
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(200, 401);
        }

        User user = userStore.findByProviderAndProviderId(
                app.pickple.auth.domain.SocialProvider.APPLE, providerSub).orElseThrow();
        assertThat(providerTokenService.findAllDecryptedByUserId(user.id()))
                .extracting(AppleProviderTokenService.ClientToken::clientType)
                .containsExactly(AppleClientType.WEB);

        mockMvc.perform(post("/auth/apple/web/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","codeVerifier":"%s"}
                                """.formatted(handoff.code(), VERIFIER)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("APPLE_WEB_EXCHANGE_INVALID"));
    }

    @Test
    void callbackCorsIsPathScopedAndStillRequiresServerState() throws Exception {
        String firstAppState = "c".repeat(22);
        Start first = start(firstAppState);
        mockMvc.perform(post("/auth/apple/web/callback")
                        .header(HttpHeaders.ORIGIN, "https://attacker.example")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("error", "access_denied")
                        .param("state", first.state()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/auth/apple/web/callback")
                        .header(HttpHeaders.ORIGIN, APPLE_ORIGIN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("error", "access_denied")
                        .param("state", first.state()))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APPLE_ORIGIN))
                .andExpect(header().string(HttpHeaders.LOCATION,
                        "pickple://auth/callback?app_state=" + firstAppState + "&error=cancelled"));

        Start second = start("d".repeat(22));
        mockMvc.perform(post("/auth/apple/web/callback")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("error", "access_denied")
                        .param("state", second.state()))
                .andExpect(status().isSeeOther());

        Start third = start("e".repeat(22));
        mockMvc.perform(post("/auth/apple/web/callback")
                        .header(HttpHeaders.ORIGIN, "null")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("error", "access_denied")
                        .param("state", third.state()))
                .andExpect(status().isForbidden());

        mockMvc.perform(options("/auth/apple/web/callback")
                        .header(HttpHeaders.ORIGIN, APPLE_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APPLE_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

        mockMvc.perform(post("/auth/apple/web/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(get("/auth/apple/web/callback"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void nativeAndWebProviderGrantsDoNotOverwriteAndWithdrawalRevokesEachClient() throws Exception {
        String providerSub = "apple-web-withdraw-" + System.nanoTime();
        Handoff handoff = startAndCompleteCallback(providerSub, "b".repeat(22));
        mockMvc.perform(post("/auth/apple/web/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","codeVerifier":"%s"}
                                """.formatted(handoff.code(), VERIFIER)))
                .andExpect(status().isOk());

        User user = userStore.findByProviderAndProviderId(
                app.pickple.auth.domain.SocialProvider.APPLE, providerSub).orElseThrow();
        providerTokenService.store(user.id(), AppleClientType.NATIVE, "native-provider-refresh");
        assertThat(providerTokenService.findAllDecryptedByUserId(user.id()))
                .extracting(AppleProviderTokenService.ClientToken::clientType)
                .containsExactlyInAnyOrderElementsOf(Set.of(AppleClientType.NATIVE, AppleClientType.WEB));

        assertThat(withdrawalService.withdraw(user.id()))
                .isEqualTo(AccountWithdrawalService.WithdrawalOutcome.COMPLETED);

        verify(tokenGateway).revokeRefreshToken(AppleClientType.NATIVE, "native-provider-refresh");
        verify(tokenGateway).revokeRefreshToken(AppleClientType.WEB, "provider-refresh");
        assertThat(providerTokenService.findAllDecryptedByUserId(user.id())).isEmpty();
        assertThat(userStore.findById(user.id()).orElseThrow().isActive()).isFalse();
    }

    private Handoff startAndCompleteCallback(String providerSub, String appState) throws Exception {
        Start started = start(appState);
        given(tokenGateway.exchangeWebAuthorizationCode("apple-web-code"))
                .willReturn(new AppleTokenResponse(
                        "apple-access", 300L, "apple-id-token", "provider-refresh", "Bearer"));
        given(verifier.verifyWeb("apple-id-token", started.nonce()))
                .willReturn(new AppleIdentity(providerSub, "relay@example.com", null));

        MvcResult callback = mockMvc.perform(post("/auth/apple/web/callback")
                        .header(HttpHeaders.ORIGIN, APPLE_ORIGIN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("code", "apple-web-code")
                        .param("state", started.state())
                        .param("user", "{\"name\":{\"firstName\":\"길동\",\"lastName\":\"홍\"}}"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, APPLE_ORIGIN))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();

        URI deepLink = URI.create(callback.getResponse().getHeader(HttpHeaders.LOCATION));
        var query = UriComponentsBuilder.fromUri(deepLink).build().getQueryParams();
        assertThat(deepLink.getScheme()).isEqualTo("pickple");
        assertThat(deepLink.getHost()).isEqualTo("auth");
        assertThat(deepLink.getPath()).isEqualTo("/callback");
        assertThat(query.keySet()).containsExactlyInAnyOrder("app_state", "code");
        assertThat(query.getFirst("app_state")).isEqualTo(appState);
        String location = deepLink.toString();
        assertThat(location).doesNotContain(
                "apple-web-code", "apple-id-token", "provider-refresh", "relay@example.com");
        verify(tokenGateway, times(1)).exchangeWebAuthorizationCode("apple-web-code");
        return new Handoff(query.getFirst("code"));
    }

    private Start start(String appState) throws Exception {
        MvcResult started = mockMvc.perform(get("/auth/apple/web")
                        .param("code_challenge", CHALLENGE)
                        .param("code_challenge_method", "S256")
                        .param("app_state", appState))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        URI apple = URI.create(started.getResponse().getHeader(HttpHeaders.LOCATION));
        var query = UriComponentsBuilder.fromUri(apple).build().getQueryParams();
        assertThat(query.getFirst("client_id")).isEqualTo("app.pickple.web");
        assertThat(query.getFirst("redirect_uri"))
                .isEqualTo("https://api.pickple.app/auth/apple/web/callback");
        assertThat(query.getFirst("response_mode")).isEqualTo("form_post");
        return new Start(query.getFirst("state"), query.getFirst("nonce"));
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Start(String state, String nonce) {
    }

    private record Handoff(String code) {
    }
}
