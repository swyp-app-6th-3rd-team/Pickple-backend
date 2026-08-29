package app.pickple.auth.controller;

import app.pickple.auth.apple.AppleAuthService;
import app.pickple.auth.config.AuthProperties;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.AccountWithdrawalService;
import app.pickple.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private AppleAuthService appleAuthService;
    @Mock
    private AccountWithdrawalService accountWithdrawalService;

    private MockMvc mockMvc;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties(
                new AuthProperties.Jwt(
                        "test-secret-key-for-controller-tests-32bytes+",
                        Duration.ofMinutes(30), Duration.ofDays(14), "test"),
                new AuthProperties.Auth("http://localhost/callback", List.of("localhost"), false),
                new AuthProperties.Cors(List.of("http://localhost")));
        controller = new AuthController(
                authService, appleAuthService, accountWithdrawalService, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsServiceTokensForAppleLoginWithoutCaching() throws Exception {
        given(appleAuthService.login(
                "authorization-code", "identity-token",
                "raw-nonce-at-least-16-characters", "홍길동"))
                .willReturn(new AuthService.TokenPair("service-access", "service-refresh"));

        mockMvc.perform(post("/api/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authorizationCode": "authorization-code",
                                  "identityToken": "identity-token",
                                  "rawNonce": "raw-nonce-at-least-16-characters",
                                  "name": "홍길동"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.accessToken").value("service-access"))
                .andExpect(jsonPath("$.returnObject.refreshToken").value("service-refresh"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void rotatesMobileRefreshTokenInJson() throws Exception {
        given(authService.refresh("old-refresh"))
                .willReturn(new AuthService.TokenPair("new-access", "new-refresh"));

        mockMvc.perform(post("/api/auth/mobile/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"old-refresh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.accessToken").value("new-access"))
                .andExpect(jsonPath("$.returnObject.refreshToken").value("new-refresh"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void malformedAppleJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void withdrawsAuthenticatedUserAndExpiresWebCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.withdraw(7L, response);

        verify(accountWithdrawalService).withdraw(7L);
        assertThat(response.getCookie("refresh_token")).isNotNull();
        assertThat(response.getCookie("refresh_token").getMaxAge()).isZero();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
