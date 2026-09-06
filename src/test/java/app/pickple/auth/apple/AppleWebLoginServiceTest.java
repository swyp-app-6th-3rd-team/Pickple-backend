package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleClientType;
import app.pickple.auth.domain.AppleWebLoginAttemptStore;
import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.config.AppleProperties;
import app.pickple.config.AppleWebProperties;
import app.pickple.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AppleWebLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String STATE = "s".repeat(43);
    private static final String NONCE = "n".repeat(43);
    private static final String HANDOFF_CODE = "h".repeat(43);
    private static final String APP_STATE = "a".repeat(22);
    private static final String VERIFIER = "v".repeat(43);
    private static final String CHALLENGE = AppleWebLoginProof.challenge(VERIFIER);

    @Mock
    private AppleWebLoginAttemptStore attemptStore;
    @Mock
    private AppleWebRandomValueGenerator randomValues;
    @Mock
    private AppleTokenGateway tokenGateway;
    @Mock
    private AppleIdTokenVerifier verifier;
    @Mock
    private UserStore userStore;

    private AppleProviderTokenCipher cipher;
    private AppleWebLoginService service;

    @BeforeEach
    void setUp() {
        AppleProperties apple = appleProperties();
        cipher = new AppleProviderTokenCipher(apple);
        service = new AppleWebLoginService(
                apple, webProperties(true), attemptStore, randomValues, tokenGateway, verifier,
                cipher, userStore, new ObjectMapper(), new SimpleMeterRegistry(), CLOCK);
    }

    @Test
    void startsWithServicesIdServerStateAndNonce() {
        given(randomValues.next()).willReturn(STATE, NONCE);

        URI location = service.start(CHALLENGE, "S256", APP_STATE);

        var query = UriComponentsBuilder.fromUri(location).build().getQueryParams();
        assertThat(location.getScheme()).isEqualTo("https");
        assertThat(query.getFirst("client_id")).isEqualTo("app.pickple.web");
        assertThat(query.getFirst("redirect_uri"))
                .isEqualTo("https://api.pickple.app/auth/apple/web/callback");
        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(query.getFirst("response_mode")).isEqualTo("form_post");
        assertThat(query.getFirst("scope")).isEqualTo("name%20email");
        assertThat(query.getFirst("state")).isEqualTo(STATE);

        ArgumentCaptor<AppleWebLoginAttemptStore.PendingAttempt> captured =
                ArgumentCaptor.forClass(AppleWebLoginAttemptStore.PendingAttempt.class);
        verify(attemptStore).create(captured.capture());
        assertThat(captured.getValue().stateHash()).isEqualTo(AppleWebLoginProof.hash(STATE));
        assertThat(captured.getValue().nonceHash()).isEqualTo(query.getFirst("nonce"));
        assertThat(captured.getValue().appState()).isEqualTo(APP_STATE);
        assertThat(captured.getValue().codeChallenge()).isEqualTo(CHALLENGE);
        assertThat(captured.getValue().expiresAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(10));
    }

    @Test
    void callbackCreatesCredentialFreeHandoffBoundToVerifiedIdentity() {
        AppleWebLoginAttemptStore.PendingAttempt pending = pending();
        given(attemptStore.claimPending(AppleWebLoginProof.hash(STATE), now()))
                .willReturn(Optional.of(pending));
        given(tokenGateway.exchangeWebAuthorizationCode("apple-code"))
                .willReturn(new AppleTokenResponse(
                        "apple-access", 300L, "apple-id-token", "provider-refresh", "Bearer"));
        given(verifier.verifyWeb("apple-id-token", pending.nonceHash()))
                .willReturn(new AppleIdentity("apple-sub", "relay@example.com", null));
        given(userStore.findByProviderAndProviderId(SocialProvider.APPLE, "apple-sub"))
                .willReturn(Optional.of(activeAppleUser()));
        given(randomValues.next()).willReturn(HANDOFF_CODE);

        URI location = service.callback(new AppleWebLoginService.CallbackCommand(
                "apple-code", STATE,
                "{\"name\":{\"firstName\":\"길동\",\"lastName\":\"홍\"},\"email\":\"ignored@example.com\"}",
                null));

        assertThat(location.toString())
                .isEqualTo("pickple://auth/callback?app_state=" + APP_STATE + "&code=" + HANDOFF_CODE)
                .doesNotContain("apple-code", "apple-id-token", "provider-refresh", "relay@example.com");
        ArgumentCaptor<AppleWebLoginAttemptStore.VerifiedAttempt> captured =
                ArgumentCaptor.forClass(AppleWebLoginAttemptStore.VerifiedAttempt.class);
        verify(attemptStore).completeCallback(
                org.mockito.ArgumentMatchers.eq(AppleWebLoginProof.hash(STATE)), captured.capture(),
                org.mockito.ArgumentMatchers.eq(now()));
        AppleWebLoginAttemptStore.VerifiedAttempt verified = captured.getValue();
        assertThat(verified.boundUserId()).isEqualTo(7L);
        assertThat(verified.providerId()).isEqualTo("apple-sub");
        assertThat(verified.name()).isEqualTo("홍 길동");
        assertThat(verified.handoffCodeHash()).isEqualTo(AppleWebLoginProof.hash(HANDOFF_CODE));
        assertThat(cipher.decryptWebAttempt(verified.stateHash(), encrypted(verified)))
                .isEqualTo("provider-refresh");
    }

    @Test
    void validCancellationUsesOnlyTrustedStoredAppState() {
        given(attemptStore.claimPending(AppleWebLoginProof.hash(STATE), now()))
                .willReturn(Optional.of(pending()));

        URI location = service.callback(new AppleWebLoginService.CallbackCommand(
                null, STATE, null, "access_denied"));

        assertThat(location.toString())
                .isEqualTo("pickple://auth/callback?app_state=" + APP_STATE + "&error=cancelled");
        verify(attemptStore).failCallback(
                AppleWebLoginProof.hash(STATE), AppleWebLoginAttemptStore.Status.CANCELLED, now());
        verifyNoInteractions(tokenGateway, verifier, userStore);
    }

    @Test
    void invalidOrAlreadyClaimedStateStopsBeforeAppleExchange() {
        given(attemptStore.claimPending(AppleWebLoginProof.hash(STATE), now()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.callback(new AppleWebLoginService.CallbackCommand(
                "apple-code", STATE, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ResponseCode.APPLE_WEB_LOGIN_INVALID);
        verifyNoInteractions(tokenGateway, verifier, userStore);
    }

    @Test
    void providerCredentialIsCompensatedWhenVerificationFails() {
        given(attemptStore.claimPending(AppleWebLoginProof.hash(STATE), now()))
                .willReturn(Optional.of(pending()));
        given(tokenGateway.exchangeWebAuthorizationCode("apple-code"))
                .willReturn(new AppleTokenResponse(
                        "apple-access", 300L, "bad-id-token", "provider-refresh", "Bearer"));
        given(verifier.verifyWeb("bad-id-token", pending().nonceHash()))
                .willThrow(new ApiException(ResponseCode.OAUTH2_FAILED));

        URI location = service.callback(new AppleWebLoginService.CallbackCommand(
                "apple-code", STATE, null, null));

        assertThat(location.toString()).contains("error=login_failed").doesNotContain("provider-refresh");
        verify(tokenGateway).revokeRefreshToken(AppleClientType.WEB, "provider-refresh");
        verify(attemptStore).failCallback(
                AppleWebLoginProof.hash(STATE), AppleWebLoginAttemptStore.Status.FAILED, now());
        verify(attemptStore, never()).completeCallback(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private AppleWebLoginAttemptStore.PendingAttempt pending() {
        return new AppleWebLoginAttemptStore.PendingAttempt(
                AppleWebLoginProof.hash(STATE), APP_STATE, CHALLENGE,
                AppleIdTokenVerifier.sha256(NONCE), now(), now().plusMinutes(10));
    }

    private AppleProviderTokenCipher.EncryptedToken encrypted(
            AppleWebLoginAttemptStore.VerifiedAttempt verified) {
        return new AppleProviderTokenCipher.EncryptedToken(
                verified.encryptionFormatVersion(), verified.encryptedProviderRefreshToken(),
                verified.encryptionIv(), verified.encryptionKeyId());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }

    private User activeAppleUser() {
        return User.restore(7L, SocialProvider.APPLE, "apple-sub", "relay@example.com", "기존 사용자",
                Role.ROLE_USER, User.State.ACTIVE, null, null);
    }

    static AppleProperties appleProperties() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new AppleProperties(
                false, "TEAM", "KEY", "app.pickple.ios", "base64-p8", "k1=" + key, "k1",
                "https://appleid.apple.com", "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys", Duration.ofMinutes(10));
    }

    static AppleWebProperties webProperties(boolean enabled) {
        return new AppleWebProperties(
                enabled, "app.pickple.web", "https://api.pickple.app/auth/apple/web/callback",
                AppleWebProperties.PICKPLE_CALLBACK, "https://appleid.apple.com/auth/authorize",
                Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofDays(1));
    }
}
