package app.pickple.auth.apple;

import app.pickple.auth.config.AppleProperties;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Apple credential 검증부터 서비스 JWT 발급까지 조율한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppleAuthService {

    private static final int MAX_NAME_LENGTH = 100;
    static final String COMPENSATION_REVOKE_FAILURE_METRIC =
            "pickple.auth.apple.login.compensation.revoke.failures";

    private final AppleProperties properties;
    private final AppleIdTokenVerifier idTokenVerifier;
    private final AppleTokenGateway tokenGateway;
    private final AppleLoginCompletionService loginCompletionService;
    private final MeterRegistry meterRegistry;

    public AuthService.TokenPair login(String authorizationCode,
                                       String identityToken,
                                       String rawNonce,
                                       String name) {
        ensureEnabled();
        requireCredential(authorizationCode);
        requireCredential(identityToken);
        requireCredential(rawNonce);

        // 먼저 앱이 받은 ID token을 검증해 잘못된 요청이 Apple code를 소비하지 않게 한다.
        AppleIdentity clientIdentity = idTokenVerifier.verify(identityToken, rawNonce);
        AppleTokenResponse exchanged = tokenGateway.exchangeAuthorizationCode(authorizationCode);
        try {
            AppleIdentity serverIdentity = idTokenVerifier.verify(exchanged.idToken(), rawNonce);

            if (!clientIdentity.providerId().equals(serverIdentity.providerId())) {
                throw new ApiException(ResponseCode.OAUTH2_FAILED);
            }

            String verifiedEmail = serverIdentity.email() != null
                    ? serverIdentity.email()
                    : clientIdentity.email();
            AppleIdentity identity = new AppleIdentity(
                    serverIdentity.providerId(), verifiedEmail, normalizeName(name));
            return loginCompletionService.complete(identity, exchanged.refreshToken());
        } catch (RuntimeException loginFailure) {
            revokeExchangedToken(exchanged.refreshToken());
            throw loginFailure;
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.strip();
        if (normalized.length() > MAX_NAME_LENGTH) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
        return normalized;
    }

    private void requireCredential(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
    }

    private void ensureEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
    }

    private void revokeExchangedToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            tokenGateway.revokeRefreshToken(refreshToken);
        } catch (RuntimeException revokeFailure) {
            // 원래 로그인 실패를 보존한다. token·sub·외부 오류 본문은 로그에 남기지 않는다.
            meterRegistry.counter(COMPENSATION_REVOKE_FAILURE_METRIC).increment();
            log.warn("Apple 로그인 로컬 완료 실패 후 provider token 보상 revoke에 실패했습니다.");
        }
    }
}
