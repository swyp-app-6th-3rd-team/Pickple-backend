package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleClientType;
import app.pickple.auth.domain.AppleWebLoginAttemptStore;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.config.AppleProperties;
import app.pickple.config.AppleWebProperties;
import app.pickple.error.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;

/** Apple 웹 인가 시작과 HTTPS form callback을 조율한다. */
@Service
public class AppleWebLoginService {

    static final String CALLBACK_COMPENSATION_REVOKE_FAILURE_METRIC =
            "pickple.auth.apple.web.callback.compensation.revoke.failures";
    private static final String URL_SAFE = "[A-Za-z0-9_-]+";
    private static final int MAX_USER_JSON_LENGTH = 4_096;
    private static final int MAX_NAME_LENGTH = 100;

    private final AppleWebProperties webProperties;
    private final AppleWebLoginAttemptStore attemptStore;
    private final AppleWebRandomValueGenerator randomValues;
    private final AppleTokenGateway tokenGateway;
    private final AppleIdTokenVerifier idTokenVerifier;
    private final AppleProviderTokenCipher tokenCipher;
    private final UserStore userStore;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public AppleWebLoginService(AppleProperties appleProperties,
                                AppleWebProperties webProperties,
                                AppleWebLoginAttemptStore attemptStore,
                                AppleWebRandomValueGenerator randomValues,
                                AppleTokenGateway tokenGateway,
                                AppleIdTokenVerifier idTokenVerifier,
                                AppleProviderTokenCipher tokenCipher,
                                UserStore userStore,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry,
                                Clock clock) {
        this.webProperties = webProperties;
        this.attemptStore = attemptStore;
        this.randomValues = randomValues;
        this.tokenGateway = tokenGateway;
        this.idTokenVerifier = idTokenVerifier;
        this.tokenCipher = tokenCipher;
        this.userStore = userStore;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        if (webProperties.enabled()) {
            appleProperties.validateSharedCredentials();
        }
    }

    public URI start(String codeChallenge, String codeChallengeMethod, String appState) {
        ensureEnabled();
        require(codeChallenge != null && codeChallenge.matches("[A-Za-z0-9_-]{43}"));
        require("S256".equals(codeChallengeMethod));
        require(appState != null && appState.length() >= 22
                && appState.length() <= 256 && appState.matches(URL_SAFE));

        LocalDateTime now = LocalDateTime.now(clock);
        attemptStore.deleteStaleNonVerifiedBefore(now.minus(webProperties.retention()));
        String state = randomValues.next();
        String rawNonce = randomValues.next();
        String nonceHash = AppleIdTokenVerifier.sha256(rawNonce);
        attemptStore.create(new AppleWebLoginAttemptStore.PendingAttempt(
                AppleWebLoginProof.hash(state), appState, codeChallenge, nonceHash,
                now, now.plus(webProperties.attemptValidity())));

        return UriComponentsBuilder.fromUriString(webProperties.authorizationUri())
                .queryParam("client_id", webProperties.clientId())
                .queryParam("redirect_uri", webProperties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("response_mode", "form_post")
                .queryParam("scope", "name email")
                .queryParam("state", state)
                .queryParam("nonce", nonceHash)
                .build().encode().toUri();
    }

    public URI callback(CallbackCommand command) {
        ensureEnabled();
        validateCallback(command);
        LocalDateTime now = LocalDateTime.now(clock);
        String stateHash = AppleWebLoginProof.hash(command.state());
        AppleWebLoginAttemptStore.PendingAttempt attempt = attemptStore.claimPending(stateHash, now)
                .orElseThrow(this::invalidAttempt);

        if (command.error() != null) {
            AppleWebLoginAttemptStore.Status status = "access_denied".equals(command.error())
                    ? AppleWebLoginAttemptStore.Status.CANCELLED
                    : AppleWebLoginAttemptStore.Status.FAILED;
            attemptStore.failCallback(stateHash, status, now);
            return appRedirect(status == AppleWebLoginAttemptStore.Status.CANCELLED
                    ? "cancelled" : "login_failed", attempt.appState(), null);
        }

        AppleTokenResponse exchanged = null;
        try {
            exchanged = tokenGateway.exchangeWebAuthorizationCode(command.code());
            AppleIdentity identity = idTokenVerifier.verifyWeb(exchanged.idToken(), attempt.nonceHash());
            String name = readName(command.user());
            Long boundUserId = findBoundActiveUser(identity);
            AppleProviderTokenCipher.EncryptedToken encrypted =
                    tokenCipher.encryptWebAttempt(stateHash, exchanged.refreshToken());
            String handoffCode = randomValues.next();
            LocalDateTime handoffExpiresAt = now.plus(webProperties.handoffValidity());
            attemptStore.completeCallback(stateHash, new AppleWebLoginAttemptStore.VerifiedAttempt(
                    stateHash, attempt.appState(), attempt.codeChallenge(), boundUserId,
                    identity.providerId(), identity.email(), name,
                    encrypted.formatVersion(), encrypted.ciphertext(), encrypted.iv(), encrypted.keyId(),
                    AppleWebLoginProof.hash(handoffCode), handoffExpiresAt), now);
            return appRedirect(null, attempt.appState(), handoffCode);
        } catch (RuntimeException failure) {
            if (exchanged != null) {
                compensateRevoke(exchanged.refreshToken());
            }
            markFailedBestEffort(stateHash, now);
            String error = isCredentialFailure(failure) ? "login_failed" : "temporarily_unavailable";
            return appRedirect(error, attempt.appState(), null);
        }
    }

    private Long findBoundActiveUser(AppleIdentity identity) {
        return userStore.findByProviderAndProviderId(SocialProvider.APPLE, identity.providerId())
                .map(user -> {
                    if (!user.isActive()) {
                        throw new ApiException(ResponseCode.OAUTH2_FAILED);
                    }
                    return user.id();
                })
                .orElse(null);
    }

    private String readName(String userJson) {
        if (userJson == null || userJson.isBlank()) {
            return null;
        }
        if (userJson.length() > MAX_USER_JSON_LENGTH) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
        try {
            AppleWebUser user = objectMapper.readValue(userJson, AppleWebUser.class);
            if (user.name() == null) {
                return null;
            }
            String joined = String.join(" ", nonBlank(user.name().lastName()), nonBlank(user.name().firstName())).strip();
            if (joined.isBlank()) {
                return null;
            }
            if (joined.length() > MAX_NAME_LENGTH) {
                throw new ApiException(ResponseCode.INVALID_REQUEST);
            }
            return joined;
        } catch (JsonProcessingException e) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
    }

    private String nonBlank(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    private URI appRedirect(String error, String appState, String handoffCode) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(AppleWebProperties.PICKPLE_CALLBACK)
                .queryParam("app_state", appState);
        if (error != null) {
            builder.queryParam("error", error);
        } else {
            builder.queryParam("code", handoffCode);
        }
        return builder.build().encode().toUri();
    }

    private void validateCallback(CallbackCommand command) {
        require(command != null && command.state() != null
                && command.state().length() == 43 && command.state().matches(URL_SAFE));
        require(command.code() == null || command.code().length() <= 4_096);
        require(command.error() == null || command.error().length() <= 100);
        require((command.code() == null) != (command.error() == null));
    }

    private void compensateRevoke(String refreshToken) {
        try {
            tokenGateway.revokeRefreshToken(AppleClientType.WEB, refreshToken);
        } catch (RuntimeException ignored) {
            meterRegistry.counter(CALLBACK_COMPENSATION_REVOKE_FAILURE_METRIC).increment();
        }
    }

    private void markFailedBestEffort(String stateHash, LocalDateTime now) {
        try {
            attemptStore.failCallback(stateHash, AppleWebLoginAttemptStore.Status.FAILED, now);
        } catch (RuntimeException ignored) {
            // 콜백 오류 응답까지 DB 장애에 가려지지 않게 한다. 오래 멈춘 PROCESSING 행은 retention 정리가 지운다.
        }
    }

    private boolean isCredentialFailure(RuntimeException failure) {
        if (!(failure instanceof ApiException api)) {
            return false;
        }
        return api.code() == ResponseCode.OAUTH2_FAILED
                || api.code() == ResponseCode.INVALID_REQUEST;
    }

    private void ensureEnabled() {
        if (!webProperties.enabled()) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
    }

    private void require(boolean condition) {
        if (!condition) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }
    }

    private ApiException invalidAttempt() {
        return new ApiException(ResponseCode.APPLE_WEB_LOGIN_INVALID);
    }

    public record CallbackCommand(String code, String state, String user, String error) {
        @Override
        public String toString() {
            return "CallbackCommand[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AppleWebUser(AppleWebName name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AppleWebName(String firstName, String lastName) {
    }
}
