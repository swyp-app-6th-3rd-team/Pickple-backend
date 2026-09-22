package app.pickple.auth.kakao;

import app.pickple.auth.domain.SocialIdentity;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ResponseCode;
import app.pickple.config.KakaoProperties;
import app.pickple.error.ApiException;
import com.nimbusds.jose.RemoteKeySourceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Kakao ID token 검증부터 Pickple 서비스 JWT 발급까지 처리한다. */
@Service
public class KakaoAuthService {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final int MAX_PROVIDER_ID_LENGTH = 255;
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final int MAX_NAME_LENGTH = 100;

    private final KakaoProperties properties;
    private final Clock clock;
    private final AuthService authService;
    private final JwtDecoder decoder;

    @Autowired
    public KakaoAuthService(KakaoProperties properties, Clock clock, AuthService authService) {
        this(properties, clock, authService, createDecoderIfConfigured(properties, clock));
    }

    KakaoAuthService(KakaoProperties properties,
                     Clock clock,
                     AuthService authService,
                     JwtDecoder decoder) {
        this.properties = properties;
        this.clock = clock;
        this.authService = authService;
        this.decoder = decoder;
    }

    public AuthService.LoginResult login(String identityToken, String nonce) {
        ensureConfigured();
        if (identityToken == null || identityToken.isBlank() || nonce == null || nonce.isBlank()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST);
        }

        KakaoIdentity identity;
        try {
            Jwt jwt = decoder.decode(identityToken);
            identity = validate(jwt, nonce);
        } catch (JwtException exception) {
            if (causedByRemoteJwkFailure(exception)) {
                throw new ApiException(ResponseCode.KAKAO_LOGIN_UNAVAILABLE);
            }
            throw invalidToken();
        } catch (IllegalArgumentException exception) {
            // token 원문과 claim 값은 오류 메시지나 로그에 포함하지 않는다.
            throw invalidToken();
        }
        return authService.completeLogin(identity);
    }

    private KakaoIdentity validate(Jwt jwt, String nonce) {
        if (!properties.issuer().equals(jwt.getClaimAsString("iss"))) {
            throw invalidToken();
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains(properties.nativeAppKey())) {
            throw invalidToken();
        }
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.plus(CLOCK_SKEW).isAfter(clock.instant())) {
            throw invalidToken();
        }
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()
                || jwt.getSubject().length() > MAX_PROVIDER_ID_LENGTH) {
            throw invalidToken();
        }
        String tokenNonce = jwt.getClaimAsString("nonce");
        if (tokenNonce == null || !constantTimeEquals(tokenNonce, nonce)) {
            throw invalidToken();
        }

        return new KakaoIdentity(
                jwt.getSubject(),
                normalize(jwt.getClaimAsString("email"), MAX_EMAIL_LENGTH),
                normalize(jwt.getClaimAsString("nickname"), MAX_NAME_LENGTH));
    }

    private void ensureConfigured() {
        if (!properties.loginConfigured() || decoder == null) {
            throw new ApiException(ResponseCode.KAKAO_LOGIN_UNAVAILABLE);
        }
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw invalidToken();
        }
        return normalized;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean causedByRemoteJwkFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof RemoteKeySourceException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ApiException invalidToken() {
        return new ApiException(ResponseCode.OAUTH2_FAILED);
    }

    private static JwtDecoder createDecoder(KakaoProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        JwtTimestampValidator timestamp = new JwtTimestampValidator(CLOCK_SKEW);
        timestamp.setAllowEmptyExpiryClaim(false);
        timestamp.setClock(clock);
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                timestamp,
                new JwtIssuerValidator(properties.issuer()),
                new JwtAudienceValidator(properties.nativeAppKey()));
        decoder.setJwtValidator(validators);
        return decoder;
    }

    private static JwtDecoder createDecoderIfConfigured(KakaoProperties properties, Clock clock) {
        if (!properties.loginConfigured()) {
            return null;
        }
        try {
            return createDecoder(properties, clock);
        } catch (IllegalArgumentException exception) {
            // 잘못된 JWKS/issuer 설정 하나 때문에 애플리케이션 전체가 기동 불능이 되지 않게 한다.
            // 로그인 시에는 ensureConfigured가 503으로 변환한다.
            return null;
        }
    }

    private record KakaoIdentity(String providerId, String email, String name) implements SocialIdentity {

        @Override
        public SocialProvider provider() {
            return SocialProvider.KAKAO;
        }
    }
}
