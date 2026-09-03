package app.pickple.auth.apple;

import com.nimbusds.jose.RemoteKeySourceException;
import app.pickple.config.AppleProperties;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
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
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/** Apple JWKS로 RS256 ID token의 서명과 신원 클레임을 검증한다. */
@Component
public class AppleIdTokenVerifier {

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final AppleProperties properties;
    private final Clock clock;
    private final JwtDecoder decoder;

    @Autowired
    public AppleIdTokenVerifier(AppleProperties properties, Clock clock) {
        this(properties, clock, createDecoder(properties, clock));
    }

    AppleIdTokenVerifier(AppleProperties properties, Clock clock, JwtDecoder decoder) {
        this.properties = properties;
        this.clock = clock;
        this.decoder = decoder;
    }

    public AppleIdentity verify(String identityToken, String rawNonce) {
        ensureEnabled();
        if (identityToken == null || identityToken.isBlank() || rawNonce == null || rawNonce.isBlank()) {
            throw invalidToken();
        }

        try {
            Jwt jwt = decoder.decode(identityToken);
            validateClaims(jwt, rawNonce);
            String email = isEmailVerified(jwt) ? jwt.getClaimAsString("email") : null;
            return new AppleIdentity(jwt.getSubject(), email, null);
        } catch (JwtException e) {
            if (causedByRemoteJwkFailure(e)) {
                throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
            }
            // token 원문·파싱 상세·클레임 값을 외부 메시지나 로그에 노출하지 않는다.
            throw invalidToken();
        } catch (IllegalArgumentException e) {
            // token 원문·파싱 상세·클레임 값을 외부 메시지나 로그에 노출하지 않는다.
            throw invalidToken();
        }
    }

    private void validateClaims(Jwt jwt, String rawNonce) {
        String issuer = jwt.getClaimAsString("iss");
        if (!properties.issuer().equals(issuer)) {
            throw invalidToken();
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains(properties.clientId())) {
            throw invalidToken();
        }
        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.plus(CLOCK_SKEW).isAfter(clock.instant())) {
            throw invalidToken();
        }
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw invalidToken();
        }

        String tokenNonce = jwt.getClaimAsString("nonce");
        String expectedNonce = sha256(rawNonce);
        if (tokenNonce == null || !constantTimeEquals(tokenNonce, expectedNonce)) {
            throw invalidToken();
        }
    }

    private boolean isEmailVerified(Jwt jwt) {
        Object claim = jwt.getClaims().get("email_verified");
        return Boolean.TRUE.equals(claim) || (claim instanceof String value && Boolean.parseBoolean(value));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
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

    private void ensureEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
        }
    }

    private ApiException invalidToken() {
        return new ApiException(ResponseCode.OAUTH2_FAILED);
    }

    private static JwtDecoder createDecoder(AppleProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                // client_secret은 ES256이지만 Apple ID token은 RS256이다.
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        JwtTimestampValidator timestamp = new JwtTimestampValidator(CLOCK_SKEW);
        timestamp.setAllowEmptyExpiryClaim(false);
        timestamp.setClock(clock);
        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
                timestamp,
                new JwtIssuerValidator(properties.issuer()),
                new JwtAudienceValidator(properties.clientId()));
        decoder.setJwtValidator(validators);
        return decoder;
    }
}
