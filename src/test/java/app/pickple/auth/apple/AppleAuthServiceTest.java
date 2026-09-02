package app.pickple.auth.apple;

import app.pickple.auth.config.AppleProperties;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AppleAuthServiceTest {

    @Mock
    private AppleIdTokenVerifier verifier;
    @Mock
    private AppleTokenGateway tokenGateway;
    @Mock
    private AppleLoginCompletionService loginCompletionService;

    private AppleAuthService appleAuthService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        AppleProperties properties = new AppleProperties(
                true, "TEAM", "KEY", "app.pickple.ios", "base64-key",
                "k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "k1",
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10));
        meterRegistry = new SimpleMeterRegistry();
        appleAuthService = new AppleAuthService(
                properties, verifier, tokenGateway, loginCompletionService, meterRegistry);
    }

    @Test
    void exchangesCodeAndIssuesServiceTokensForAppleSub() {
        given(verifier.verify("client-id-token", "raw-nonce-value-1234"))
                .willReturn(new AppleIdentity("apple-sub", "verified@example.com", null));
        given(tokenGateway.exchangeAuthorizationCode("authorization-code"))
                .willReturn(new AppleTokenResponse("apple-access", 300L,
                        "server-id-token", "apple-refresh", "Bearer"));
        given(verifier.verify("server-id-token", "raw-nonce-value-1234"))
                .willReturn(new AppleIdentity("apple-sub", "verified@example.com", null));
        given(loginCompletionService.complete(any(), org.mockito.ArgumentMatchers.eq("apple-refresh")))
                .willReturn(new AuthService.TokenPair("access", "refresh"));

        AuthService.TokenPair result = appleAuthService.login(
                "authorization-code", "client-id-token", "raw-nonce-value-1234", " 홍길동 ");

        assertThat(result).isEqualTo(new AuthService.TokenPair("access", "refresh"));
        verify(loginCompletionService).complete(
                new AppleIdentity("apple-sub", "verified@example.com", "홍길동"),
                "apple-refresh");
        verify(tokenGateway, never()).revokeRefreshToken(any());
    }

    @Test
    void rejectsWhenClientAndExchangedSubjectsDiffer() {
        given(verifier.verify("client-id-token", "raw-nonce-value-1234"))
                .willReturn(new AppleIdentity("first-sub", null, null));
        given(tokenGateway.exchangeAuthorizationCode("authorization-code"))
                .willReturn(new AppleTokenResponse(null, null,
                        "server-id-token", "apple-refresh", null));
        given(verifier.verify("server-id-token", "raw-nonce-value-1234"))
                .willReturn(new AppleIdentity("other-sub", null, null));

        assertThatThrownBy(() -> appleAuthService.login(
                "authorization-code", "client-id-token", "raw-nonce-value-1234", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.OAUTH2_FAILED);
        verify(loginCompletionService, never()).complete(any(), any());
        verify(tokenGateway).revokeRefreshToken("apple-refresh");
    }

    @Test
    void revokesExchangedProviderTokenWhenLocalLoginCompletionFails() {
        given(verifier.verify("client-id-token", "raw-nonce-value-1234"))
                .willReturn(new AppleIdentity("apple-sub", "verified@example.com", null));
        given(tokenGateway.exchangeAuthorizationCode("authorization-code"))
                .willReturn(new AppleTokenResponse("apple-access", 300L,
                        "server-id-token", "apple-refresh", "Bearer"));
        given(verifier.verify("server-id-token", "raw-nonce-value-1234"))
                .willReturn(new AppleIdentity("apple-sub", "verified@example.com", null));
        ApiException forbidden = new ApiException(ResponseCode.FORBIDDEN, "탈퇴한 계정입니다.");
        given(loginCompletionService.complete(any(), org.mockito.ArgumentMatchers.eq("apple-refresh")))
                .willThrow(forbidden);

        assertThatThrownBy(() -> appleAuthService.login(
                "authorization-code", "client-id-token", "raw-nonce-value-1234", null))
                .isSameAs(forbidden);
        verify(tokenGateway).revokeRefreshToken("apple-refresh");
    }

    @Test
    void preservesOriginalLoginFailureWhenCompensationRevokeAlsoFails() {
        given(verifier.verify("client-id-token", "raw-nonce-value-1234"))
                .willReturn(new AppleIdentity("apple-sub", "verified@example.com", null));
        given(tokenGateway.exchangeAuthorizationCode("authorization-code"))
                .willReturn(new AppleTokenResponse("apple-access", 300L,
                        "server-id-token", "apple-refresh", "Bearer"));
        given(verifier.verify("server-id-token", "raw-nonce-value-1234"))
                .willReturn(new AppleIdentity("apple-sub", "verified@example.com", null));
        ApiException forbidden = new ApiException(ResponseCode.FORBIDDEN, "탈퇴한 계정입니다.");
        given(loginCompletionService.complete(any(), org.mockito.ArgumentMatchers.eq("apple-refresh")))
                .willThrow(forbidden);
        willThrow(new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE))
                .given(tokenGateway).revokeRefreshToken("apple-refresh");

        assertThatThrownBy(() -> appleAuthService.login(
                "authorization-code", "client-id-token", "raw-nonce-value-1234", null))
                .isSameAs(forbidden);
        verify(tokenGateway).revokeRefreshToken("apple-refresh");
        assertThat(meterRegistry.get(AppleAuthService.COMPENSATION_REVOKE_FAILURE_METRIC)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void disabledAppleLoginReturnsServiceUnavailableWithoutUsingCredentials() {
        AppleProperties disabled = new AppleProperties(
                false, "not-configured", "not-configured", "not-configured", "not-configured",
                "not-configured", "not-configured",
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10));
        AppleAuthService service = new AppleAuthService(
                disabled, verifier, tokenGateway, loginCompletionService, meterRegistry);

        assertThatThrownBy(() -> service.login("code", "token", "raw-nonce-value-1234", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        verifyNoInteractions(verifier, tokenGateway, loginCompletionService);
    }
}
