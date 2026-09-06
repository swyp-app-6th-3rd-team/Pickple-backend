package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleClientType;
import app.pickple.auth.domain.AppleWebLoginAttemptStore;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/** 앱이 소유한 verifier를 확인한 뒤 handoff를 한 번만 소비하고 Pickple JWT를 발급한다. */
@Service
@RequiredArgsConstructor
public class AppleWebLoginCompletionService {

    private static final String VERIFIER_PATTERN = "[A-Za-z0-9._~-]{43,128}";

    private final AppleWebLoginAttemptStore attemptStore;
    private final AppleProviderTokenCipher tokenCipher;
    private final AppleLoginCompletionService loginCompletionService;
    private final UserStore userStore;
    private final Clock clock;

    @Transactional
    public AuthService.TokenPair exchange(String handoffCode, String codeVerifier) {
        if (handoffCode == null || !handoffCode.matches("[A-Za-z0-9_-]{43}")
                || codeVerifier == null || !codeVerifier.matches(VERIFIER_PATTERN)) {
            throw invalidHandoff();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        AppleWebLoginAttemptStore.VerifiedAttempt attempt = attemptStore
                .findVerifiedForUpdate(AppleWebLoginProof.hash(handoffCode), now)
                .orElseThrow(this::invalidHandoff);
        String actualChallenge = AppleWebLoginProof.challenge(codeVerifier);
        if (!AppleWebLoginProof.constantTimeEquals(attempt.codeChallenge(), actualChallenge)) {
            throw invalidHandoff();
        }
        verifyBoundUser(attempt);
        String providerRefreshToken = tokenCipher.decryptWebAttempt(attempt.stateHash(),
                new AppleProviderTokenCipher.EncryptedToken(
                        attempt.encryptionFormatVersion(), attempt.encryptedProviderRefreshToken(),
                        attempt.encryptionIv(), attempt.encryptionKeyId()));
        AuthService.TokenPair tokens = loginCompletionService.complete(
                new AppleIdentity(attempt.providerId(), attempt.email(), attempt.name()),
                providerRefreshToken, AppleClientType.WEB);
        attemptStore.consume(attempt.stateHash(), now);
        return tokens;
    }

    private void verifyBoundUser(AppleWebLoginAttemptStore.VerifiedAttempt attempt) {
        if (attempt.boundUserId() == null) {
            return;
        }
        User user = userStore.findById(attempt.boundUserId()).orElseThrow(this::invalidHandoff);
        if (!user.isActive() || user.provider() != SocialProvider.APPLE
                || !attempt.providerId().equals(user.providerId())) {
            throw invalidHandoff();
        }
    }

    private ApiException invalidHandoff() {
        return new ApiException(ResponseCode.APPLE_WEB_EXCHANGE_INVALID);
    }
}
