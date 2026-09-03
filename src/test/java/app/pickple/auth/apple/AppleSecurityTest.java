package app.pickple.auth.apple;

import com.nimbusds.jose.RemoteKeySourceException;
import app.pickple.config.AppleProperties;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppleSecurityTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void enabledConfigurationRequiresKeyValuesAndAppleValidityLimit() {
        assertThatThrownBy(() -> new AppleProperties(
                true, "not-configured", "KEY", "app.pickple.ios", "base64-key",
                "k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "k1",
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new AppleProperties(
                true, "TEAM", "KEY", "app.pickple.ios", "base64-key",
                "k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "missing-key-id",
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("활성 암호화 키");

        assertThatThrownBy(() -> new AppleProperties(
                true, "TEAM", "KEY", "app.pickple.ios", "base64-key",
                "k1=dG9vLXNob3J0", "k1",
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");

        assertThatThrownBy(() -> new AppleProperties(
                false, "not-configured", "not-configured", "not-configured", "not-configured",
                "not-configured", "not-configured",
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofSeconds(15_777_001)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createsEs256ClientSecretFromBase64P8() throws Exception {
        KeyPair pair = ecKeyPair();
        AppleProperties properties = properties(base64Pem(pair));
        String token = new AppleClientSecretProvider(properties, CLOCK).create();

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith((ECPublicKey) pair.getPublic())
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(token);

        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("KEY123");
        assertThat(parsed.getPayload().getIssuer()).isEqualTo("TEAM123");
        assertThat(parsed.getPayload().getSubject()).isEqualTo("app.pickple.ios");
        assertThat(parsed.getPayload().getAudience()).containsExactly("https://appleid.apple.com");
        assertThat(parsed.getPayload().getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void clientSecretRemainsAvailableForRevokeWhenLoginToggleIsOff() throws Exception {
        KeyPair pair = ecKeyPair();
        AppleProperties enabled = properties(base64Pem(pair));
        AppleProperties loginDisabled = new AppleProperties(
                false, enabled.teamId(), enabled.keyId(), enabled.clientId(), enabled.privateKeyBase64(),
                enabled.providerTokenEncryptionKeys(), enabled.providerTokenActiveKeyId(),
                enabled.apiBaseUrl(), enabled.issuer(), enabled.jwkSetUri(),
                enabled.clientSecretValidity());

        String token = new AppleClientSecretProvider(loginDisabled, CLOCK).create();

        assertThat(token).isNotBlank();
    }

    @Test
    void verifiesClaimsAndHashedNonce() throws Exception {
        AppleProperties properties = properties(base64Pem(ecKeyPair()));
        String rawNonce = "raw-nonce-at-least-16-characters";
        Jwt jwt = Jwt.withTokenValue("test-identity-token")
                .header("alg", "RS256")
                .claim("iss", properties.issuer())
                .claim("aud", List.of(properties.clientId()))
                .claim("sub", "apple-user-sub")
                .claim("exp", NOW.plusSeconds(300))
                .claim("nonce", AppleIdTokenVerifier.sha256(rawNonce))
                .claim("email", "private@privaterelay.appleid.com")
                .claim("email_verified", true)
                .build();
        AppleIdTokenVerifier verifier = new AppleIdTokenVerifier(properties, CLOCK, token -> jwt);

        AppleIdentity identity = verifier.verify("test-identity-token", rawNonce);

        assertThat(identity.providerId()).isEqualTo("apple-user-sub");
        assertThat(identity.email()).isEqualTo("private@privaterelay.appleid.com");
        assertThatThrownBy(() -> verifier.verify("test-identity-token", "different-raw-nonce"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.OAUTH2_FAILED);
    }

    @Test
    void mapsAppleJwksNetworkFailureToServiceUnavailable() throws Exception {
        AppleProperties properties = properties(base64Pem(ecKeyPair()));
        AppleIdTokenVerifier verifier = new AppleIdTokenVerifier(properties, CLOCK, token -> {
            throw new JwtException("JWK fetch failed",
                    new RemoteKeySourceException("remote key source unavailable", null));
        });

        assertThatThrownBy(() -> verifier.verify(
                "test-identity-token", "raw-nonce-at-least-16-characters"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_LOGIN_UNAVAILABLE);
    }

    @Test
    void rejectsWrongIssuerAudienceExpiryAndBlankSubject() throws Exception {
        AppleProperties properties = properties(base64Pem(ecKeyPair()));
        String rawNonce = "raw-nonce-at-least-16-characters";
        String nonce = AppleIdTokenVerifier.sha256(rawNonce);
        List<Jwt> invalidTokens = List.of(
                identityJwt("https://attacker.example", properties.clientId(),
                        "apple-sub", NOW.plusSeconds(300), nonce),
                identityJwt(properties.issuer(), "another.bundle.id",
                        "apple-sub", NOW.plusSeconds(300), nonce),
                identityJwt(properties.issuer(), properties.clientId(),
                        "apple-sub", NOW.minusSeconds(61), nonce),
                identityJwt(properties.issuer(), properties.clientId(),
                        " ", NOW.plusSeconds(300), nonce));

        for (Jwt invalid : invalidTokens) {
            AppleIdTokenVerifier verifier = new AppleIdTokenVerifier(properties, CLOCK, token -> invalid);
            assertThatThrownBy(() -> verifier.verify("test-identity-token", rawNonce))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ResponseCode.OAUTH2_FAILED);
        }
    }

    private static AppleProperties properties(String base64Pem) {
        return new AppleProperties(
                true,
                "TEAM123",
                "KEY123",
                "app.pickple.ios",
                base64Pem,
                "k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "k1",
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10));
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static Jwt identityJwt(String issuer, String audience, String subject,
                                   Instant expiresAt, String nonce) {
        return Jwt.withTokenValue("test-identity-token")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("aud", List.of(audience))
                .claim("sub", subject)
                .claim("exp", expiresAt)
                .claim("nonce", nonce)
                .build();
    }

    private static String base64Pem(KeyPair pair) {
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(pair.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
        return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
    }
}
