package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleClientType;
import app.pickple.auth.domain.AppleWebLoginAttemptStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppleWebLoginCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void revokesExpiredUnexchangedGrantAndClearsStoredCredential() {
        AppleWebLoginAttemptStore store = mock(AppleWebLoginAttemptStore.class);
        AppleProviderTokenCipher cipher = mock(AppleProviderTokenCipher.class);
        AppleTokenGateway gateway = mock(AppleTokenGateway.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AppleWebLoginAttemptStore.VerifiedAttempt attempt = attempt();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        when(store.findExpiredVerifiedForUpdate(now, now.minusMinutes(5)))
                .thenReturn(Optional.of(attempt));
        when(cipher.decryptWebAttempt(attempt.stateHash(), encrypted(attempt)))
                .thenReturn("unexchanged-refresh");
        AppleWebLoginCleanupService service =
                new AppleWebLoginCleanupService(store, cipher, gateway, meters, CLOCK);

        assertThat(service.cleanupOne()).isTrue();

        verify(gateway).revokeRefreshToken(AppleClientType.WEB, "unexchanged-refresh");
        verify(store).completeCleanup(attempt.stateHash(), now);
        assertThat(meters.counter(AppleWebLoginCleanupService.REVOKE_FAILURE_METRIC).count())
                .isZero();
    }

    @Test
    void failedRevokeKeepsCredentialForLaterRetry() {
        AppleWebLoginAttemptStore store = mock(AppleWebLoginAttemptStore.class);
        AppleProviderTokenCipher cipher = mock(AppleProviderTokenCipher.class);
        AppleTokenGateway gateway = mock(AppleTokenGateway.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AppleWebLoginAttemptStore.VerifiedAttempt attempt = attempt();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        when(store.findExpiredVerifiedForUpdate(now, now.minusMinutes(5)))
                .thenReturn(Optional.of(attempt));
        when(cipher.decryptWebAttempt(attempt.stateHash(), encrypted(attempt)))
                .thenReturn("unexchanged-refresh");
        org.mockito.Mockito.doThrow(new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE))
                .when(gateway).revokeRefreshToken(AppleClientType.WEB, "unexchanged-refresh");
        AppleWebLoginCleanupService service =
                new AppleWebLoginCleanupService(store, cipher, gateway, meters, CLOCK);

        assertThat(service.cleanupOne()).isTrue();

        verify(store).deferCleanup(attempt.stateHash(), now);
        assertThat(meters.counter(AppleWebLoginCleanupService.REVOKE_FAILURE_METRIC).count())
                .isEqualTo(1.0);
    }

    private AppleWebLoginAttemptStore.VerifiedAttempt attempt() {
        return new AppleWebLoginAttemptStore.VerifiedAttempt(
                "a".repeat(64), "app-state", "challenge", null,
                "apple-sub", "relay@example.com", "홍 길동",
                1, "ciphertext", "iv", "k1", "b".repeat(64),
                LocalDateTime.now(CLOCK).minusSeconds(1));
    }

    private AppleProviderTokenCipher.EncryptedToken encrypted(
            AppleWebLoginAttemptStore.VerifiedAttempt attempt) {
        return new AppleProviderTokenCipher.EncryptedToken(
                attempt.encryptionFormatVersion(), attempt.encryptedProviderRefreshToken(),
                attempt.encryptionIv(), attempt.encryptionKeyId());
    }
}
