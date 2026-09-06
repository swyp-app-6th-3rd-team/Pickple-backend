package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleClientType;
import app.pickple.auth.domain.AppleWebLoginAttemptStore;
import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AppleWebLoginCompletionServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-07T01:00:00Z"), ZoneOffset.UTC);
    private static final String HANDOFF = "h".repeat(43);
    private static final String VERIFIER = "v".repeat(43);
    private static final String STATE_HASH = "a".repeat(64);

    @Mock
    private AppleWebLoginAttemptStore attemptStore;
    @Mock
    private AppleLoginCompletionService loginCompletionService;
    @Mock
    private UserStore userStore;

    private AppleProviderTokenCipher cipher;
    private AppleWebLoginCompletionService service;

    @BeforeEach
    void setUp() {
        cipher = new AppleProviderTokenCipher(AppleWebLoginServiceTest.appleProperties());
        service = new AppleWebLoginCompletionService(
                attemptStore, cipher, loginCompletionService, userStore, CLOCK);
    }

    @Test
    void exchangesMatchingProofOnceAndPreservesWebClient() {
        AppleWebLoginAttemptStore.VerifiedAttempt attempt = attempt(null, VERIFIER);
        given(attemptStore.findVerifiedForUpdate(
                AppleWebLoginProof.hash(HANDOFF), now())).willReturn(Optional.of(attempt));
        given(loginCompletionService.complete(
                new AppleIdentity("apple-sub", "relay@example.com", "홍 길동"),
                "provider-refresh", AppleClientType.WEB))
                .willReturn(new AuthService.TokenPair("service-access", "service-refresh"));

        AuthService.TokenPair result = service.exchange(HANDOFF, VERIFIER);

        assertThat(result).isEqualTo(new AuthService.TokenPair("service-access", "service-refresh"));
        verify(loginCompletionService).complete(
                new AppleIdentity("apple-sub", "relay@example.com", "홍 길동"),
                "provider-refresh", AppleClientType.WEB);
        verify(attemptStore).consume(STATE_HASH, now());
        verifyNoInteractions(userStore);
    }

    @Test
    void interceptedHandoffWithWrongVerifierCannotIssueTokens() {
        AppleWebLoginAttemptStore.VerifiedAttempt attempt = attempt(null, VERIFIER);
        given(attemptStore.findVerifiedForUpdate(
                AppleWebLoginProof.hash(HANDOFF), now())).willReturn(Optional.of(attempt));

        assertInvalid(() -> service.exchange(HANDOFF, "x".repeat(43)));

        verifyNoInteractions(loginCompletionService, userStore);
        verify(attemptStore, never()).consume(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void callbackCompletedBeforeWithdrawalCannotRecreateOrLoginOldIdentity() {
        AppleWebLoginAttemptStore.VerifiedAttempt attempt = attempt(7L, VERIFIER);
        given(attemptStore.findVerifiedForUpdate(
                AppleWebLoginProof.hash(HANDOFF), now())).willReturn(Optional.of(attempt));
        given(userStore.findById(7L)).willReturn(Optional.of(User.restore(
                7L, SocialProvider.APPLE, null, "relay@example.com", "탈퇴 사용자",
                Role.ROLE_USER, User.State.INACTIVE, null, null)));

        assertInvalid(() -> service.exchange(HANDOFF, VERIFIER));

        verifyNoInteractions(loginCompletionService);
        verify(attemptStore, never()).consume(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private AppleWebLoginAttemptStore.VerifiedAttempt attempt(Long boundUserId, String verifier) {
        AppleProviderTokenCipher.EncryptedToken encrypted =
                cipher.encryptWebAttempt(STATE_HASH, "provider-refresh");
        return new AppleWebLoginAttemptStore.VerifiedAttempt(
                STATE_HASH, "app-state", AppleWebLoginProof.challenge(verifier), boundUserId,
                "apple-sub", "relay@example.com", "홍 길동", encrypted.formatVersion(),
                encrypted.ciphertext(), encrypted.iv(), encrypted.keyId(),
                AppleWebLoginProof.hash(HANDOFF), now().plusMinutes(1));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ResponseCode.APPLE_WEB_EXCHANGE_INVALID);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
    }
}
