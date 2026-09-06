package app.pickple.auth.apple;

import app.pickple.config.AppleProperties;
import app.pickple.config.AppleWebProperties;
import app.pickple.auth.domain.AppleClientType;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AppleTokenGatewayTest {

    @Mock
    private AppleClientSecretProvider clientSecretProvider;
    @Mock
    private AppleTokenClient tokenClient;

    private AppleTokenGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new AppleTokenGateway(properties(true), clientSecretProvider, tokenClient);
        lenient().when(clientSecretProvider.create("app.pickple.ios")).thenReturn("signed-client-secret");
    }

    @Test
    void suppliesAuthenticationAndGrantTypeForCodeExchange() {
        AppleTokenResponse response = new AppleTokenResponse(
                "apple-access", 300L, "apple-id-token", "apple-refresh", "Bearer");
        given(tokenClient.exchangeAuthorizationCode(
                "app.pickple.ios", "signed-client-secret", "one-time-code", "authorization_code"))
                .willReturn(response);

        assertThat(gateway.exchangeAuthorizationCode("one-time-code")).isSameAs(response);
    }

    @Test
    void rejectsIncompleteTokenResponse() {
        given(tokenClient.exchangeAuthorizationCode(
                "app.pickple.ios", "signed-client-secret", "one-time-code", "authorization_code"))
                .willReturn(new AppleTokenResponse(null, null, "apple-id-token", null, null));

        assertThatThrownBy(() -> gateway.exchangeAuthorizationCode("one-time-code"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.OAUTH2_FAILED);
    }

    @Test
    void mapsInvalidGrantToOAuthFailure() {
        RestClientResponseException invalidGrant = responseException(
                HttpStatus.BAD_REQUEST, new AppleTokenGateway.AppleTokenError("invalid_grant"));
        given(tokenClient.exchangeAuthorizationCode(
                "app.pickple.ios", "signed-client-secret", "expired-code", "authorization_code"))
                .willThrow(invalidGrant);

        assertThatThrownBy(() -> gateway.exchangeAuthorizationCode("expired-code"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.OAUTH2_FAILED);
    }

    @Test
    void mapsOtherAppleOrNetworkFailuresToLoginUnavailable() {
        RestClientResponseException invalidClient = responseException(
                HttpStatus.BAD_REQUEST, new AppleTokenGateway.AppleTokenError("invalid_client"));
        given(tokenClient.exchangeAuthorizationCode(
                "app.pickple.ios", "signed-client-secret", "first-code", "authorization_code"))
                .willThrow(invalidClient);
        given(tokenClient.exchangeAuthorizationCode(
                "app.pickple.ios", "signed-client-secret", "second-code", "authorization_code"))
                .willThrow(new ResourceAccessException("network unavailable"));

        assertThatThrownBy(() -> gateway.exchangeAuthorizationCode("first-code"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        assertThatThrownBy(() -> gateway.exchangeAuthorizationCode("second-code"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
    }

    @Test
    void disabledLoginDoesNotCreateClientSecretOrCallApple() {
        AppleTokenGateway disabled = new AppleTokenGateway(
                properties(false), clientSecretProvider, tokenClient);

        assertThatThrownBy(() -> disabled.exchangeAuthorizationCode("one-time-code"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        verifyNoInteractions(tokenClient);
        verify(clientSecretProvider, never()).create();
    }

    @Test
    void suppliesAuthenticationAndTokenHintForRevoke() {
        gateway.revokeRefreshToken("apple-provider-refresh");

        verify(tokenClient).revokeRefreshToken(
                "app.pickple.ios", "signed-client-secret", "apple-provider-refresh", "refresh_token");
    }

    @Test
    void webExchangeUsesServicesIdAndRegisteredReturnUrl() {
        AppleWebProperties web = webProperties(true);
        AppleTokenGateway webGateway = new AppleTokenGateway(
                properties(true), web, clientSecretProvider, tokenClient);
        given(clientSecretProvider.create("app.pickple.web")).willReturn("web-client-secret");
        AppleTokenResponse response = new AppleTokenResponse(
                "apple-access", 300L, "apple-id-token", "apple-refresh", "Bearer");
        given(tokenClient.exchangeWebAuthorizationCode(
                "app.pickple.web", "web-client-secret", "web-code", "authorization_code",
                "https://api.pickple.app/auth/apple/web/callback"))
                .willReturn(response);

        assertThat(webGateway.exchangeWebAuthorizationCode("web-code")).isSameAs(response);
    }

    @Test
    void webRevokeUsesServicesIdEvenWhenWebLoginIsDisabled() {
        AppleWebProperties web = webProperties(false);
        AppleTokenGateway webGateway = new AppleTokenGateway(
                properties(true), web, clientSecretProvider, tokenClient);
        given(clientSecretProvider.create("app.pickple.web")).willReturn("web-client-secret");

        webGateway.revokeRefreshToken(AppleClientType.WEB, "web-refresh");

        verify(tokenClient).revokeRefreshToken(
                "app.pickple.web", "web-client-secret", "web-refresh", "refresh_token");
    }

    @Test
    void mapsBlankTokenAndRevokeFailuresToDedicatedUnavailableCode() {
        assertThatThrownBy(() -> gateway.revokeRefreshToken(" "))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        verifyNoInteractions(tokenClient);

        org.mockito.Mockito.doThrow(new ResourceAccessException("network unavailable"))
                .when(tokenClient).revokeRefreshToken(
                        "app.pickple.ios", "signed-client-secret", "apple-provider-refresh", "refresh_token");

        assertThatThrownBy(() -> gateway.revokeRefreshToken("apple-provider-refresh"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
    }

    @SuppressWarnings("unchecked")
    private static RestClientResponseException responseException(
            HttpStatus status, AppleTokenGateway.AppleTokenError error) {
        RestClientResponseException exception = org.mockito.Mockito.mock(RestClientResponseException.class);
        given(exception.getStatusCode()).willReturn(status);
        given(exception.getResponseBodyAs(AppleTokenGateway.AppleTokenError.class)).willReturn(error);
        return exception;
    }

    private static AppleProperties properties(boolean enabled) {
        return new AppleProperties(
                enabled,
                enabled ? "TEAM" : "not-configured",
                enabled ? "KEY" : "not-configured",
                enabled ? "app.pickple.ios" : "not-configured",
                enabled ? "base64-key" : "not-configured",
                enabled ? "k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" : "not-configured",
                enabled ? "k1" : "not-configured",
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10));
    }

    private static AppleWebProperties webProperties(boolean enabled) {
        return new AppleWebProperties(
                enabled, "app.pickple.web", "https://api.pickple.app/auth/apple/web/callback",
                AppleWebProperties.PICKPLE_CALLBACK, "https://appleid.apple.com/auth/authorize",
                Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofDays(1));
    }
}
