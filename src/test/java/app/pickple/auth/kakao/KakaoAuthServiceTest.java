package app.pickple.auth.kakao;

import app.pickple.auth.domain.SocialIdentity;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.service.AuthService;
import app.pickple.common.ResponseCode;
import app.pickple.config.KakaoProperties;
import app.pickple.error.ApiException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class KakaoAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String NATIVE_APP_KEY = "native-app-key";
    private static final String ADMIN_KEY = "admin-key";
    private static final String ISSUER = "https://kauth.kakao.com";
    private static final String JWKS_URI = ISSUER + "/.well-known/jwks.json";
    private static final String API_BASE_URL = "https://kapi.kakao.test";
    private static final String NONCE = "nonce-at-least-16-characters";

    @Mock
    private AuthService authService;

    private KakaoProperties properties;

    @BeforeEach
    void setUp() {
        properties = new KakaoProperties(
                NATIVE_APP_KEY, ADMIN_KEY, ISSUER, JWKS_URI, API_BASE_URL);
    }

    @Test
    void verifiesKakaoIdentityAndReturnsServiceLoginResult() {
        Jwt jwt = identityJwt(ISSUER, NATIVE_APP_KEY, "kakao-sub", NOW.plusSeconds(300), NONCE)
                .claim("email", " user@kakao.com ")
                .claim("nickname", " 카카오유저 ")
                .build();
        KakaoAuthService service = serviceReturning(jwt);
        AuthService.LoginResult expected = loginResult(false);
        given(authService.completeLogin(any(SocialIdentity.class))).willReturn(expected);

        AuthService.LoginResult result = service.login("identity-token", NONCE);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<SocialIdentity> identity = ArgumentCaptor.forClass(SocialIdentity.class);
        verify(authService).completeLogin(identity.capture());
        assertThat(identity.getValue().provider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(identity.getValue().providerId()).isEqualTo("kakao-sub");
        assertThat(identity.getValue().email()).isEqualTo("user@kakao.com");
        assertThat(identity.getValue().name()).isEqualTo("카카오유저");
    }

    @Test
    void acceptsMissingOptionalProfileClaims() {
        KakaoAuthService service = serviceReturning(
                identityJwt(ISSUER, NATIVE_APP_KEY, "kakao-sub", NOW.plusSeconds(300), NONCE)
                        .build());
        given(authService.completeLogin(any(SocialIdentity.class))).willReturn(loginResult(false));

        service.login("identity-token", NONCE);

        ArgumentCaptor<SocialIdentity> identity = ArgumentCaptor.forClass(SocialIdentity.class);
        verify(authService).completeLogin(identity.capture());
        assertThat(identity.getValue().email()).isNull();
        assertThat(identity.getValue().name()).isNull();
    }

    @Test
    void rejectsWrongIssuerAudienceExpirySubjectAndNonceWithoutCompletingLogin() {
        List<Jwt> invalidTokens = List.of(
                identityJwt("https://attacker.example", NATIVE_APP_KEY,
                        "kakao-sub", NOW.plusSeconds(300), NONCE).build(),
                identityJwt(ISSUER, "rest-api-key",
                        "kakao-sub", NOW.plusSeconds(300), NONCE).build(),
                identityJwt(ISSUER, NATIVE_APP_KEY,
                        "kakao-sub", NOW.minusSeconds(61), NONCE).build(),
                identityJwt(ISSUER, NATIVE_APP_KEY,
                        " ", NOW.plusSeconds(300), NONCE).build(),
                identityJwt(ISSUER, NATIVE_APP_KEY,
                        "s".repeat(256), NOW.plusSeconds(300), NONCE).build(),
                identityJwt(ISSUER, NATIVE_APP_KEY,
                        "kakao-sub", NOW.plusSeconds(300), "different-nonce").build(),
                Jwt.withTokenValue("identity-token")
                        .header("alg", "RS256")
                        .claim("iss", ISSUER)
                        .claim("aud", List.of(NATIVE_APP_KEY))
                        .claim("sub", "kakao-sub")
                        .claim("exp", NOW.plusSeconds(300))
                        .build(),
                Jwt.withTokenValue("identity-token")
                        .header("alg", "RS256")
                        .claim("iss", ISSUER)
                        .claim("aud", List.of(NATIVE_APP_KEY))
                        .claim("sub", "kakao-sub")
                        .claim("nonce", NONCE)
                        .build());

        for (Jwt invalid : invalidTokens) {
            assertOAuthFailure(() -> serviceReturning(invalid).login("identity-token", NONCE));
        }

        verifyNoInteractions(authService);
    }

    @Test
    void rejectsOversizedProfileClaimsWithoutCompletingLogin() {
        List<Jwt> invalidTokens = List.of(
                identityJwt(ISSUER, NATIVE_APP_KEY,
                        "kakao-sub", NOW.plusSeconds(300), NONCE)
                        .claim("email", "e".repeat(256)).build(),
                identityJwt(ISSUER, NATIVE_APP_KEY,
                        "kakao-sub", NOW.plusSeconds(300), NONCE)
                        .claim("nickname", "n".repeat(101)).build());

        for (Jwt invalid : invalidTokens) {
            assertOAuthFailure(() -> serviceReturning(invalid).login("identity-token", NONCE));
        }

        verifyNoInteractions(authService);
    }

    @Test
    void rejectsBlankInputWithoutDecodingOrCompletingLogin() {
        KakaoAuthService service = new KakaoAuthService(properties, CLOCK, authService, token -> {
            throw new AssertionError("decoder must not be called");
        });

        assertThatThrownBy(() -> service.login(" ", NONCE))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ResponseCode.INVALID_REQUEST);
        assertThatThrownBy(() -> service.login("identity-token", " "))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ResponseCode.INVALID_REQUEST);
        verifyNoInteractions(authService);
    }

    @Test
    void mapsJwksNetworkFailureTo503WithoutCompletingLogin() {
        KakaoAuthService service = new KakaoAuthService(properties, CLOCK, authService, token -> {
            throw new JwtException(
                    "JWK fetch failed",
                    new RemoteKeySourceException("remote key source unavailable", null));
        });

        assertThatThrownBy(() -> service.login("identity-token", NONCE))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ResponseCode.KAKAO_LOGIN_UNAVAILABLE);
        verifyNoInteractions(authService);
    }

    @Test
    void unconfiguredNativeKeyReturns503WithoutDecodingOrCompletingLogin() {
        KakaoProperties unconfigured = new KakaoProperties(
                "not-configured", ADMIN_KEY, ISSUER, JWKS_URI, API_BASE_URL);
        KakaoAuthService service = new KakaoAuthService(unconfigured, CLOCK, authService, token -> {
            throw new AssertionError("decoder must not be called");
        });

        assertThatThrownBy(() -> service.login("identity-token", NONCE))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ResponseCode.KAKAO_LOGIN_UNAVAILABLE);
        verifyNoInteractions(authService);
    }

    @Test
    void configurationStringRedactsCredentials() {
        assertThat(properties.toString())
                .isEqualTo("KakaoProperties[redacted]")
                .doesNotContain(NATIVE_APP_KEY, ADMIN_KEY);
    }

    @Test
    void verifiesRealRs256AgainstJwksAndRejectsUntrustedRs256AndHs256() throws Exception {
        KeyPair trusted = rsaKeyPair();
        RSAKey trustedJwk = rsaJwk(trusted, "trusted-key");
        HttpServer jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jwks = new JWKSet(trustedJwk.toPublicJWK()).toString()
                .getBytes(StandardCharsets.UTF_8);
        jwksServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        jwksServer.start();

        try {
            KakaoProperties localProperties = new KakaoProperties(
                    NATIVE_APP_KEY,
                    ADMIN_KEY,
                    ISSUER,
                    "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks",
                    API_BASE_URL);
            KakaoAuthService service = new KakaoAuthService(localProperties, CLOCK, authService);
            given(authService.completeLogin(any(SocialIdentity.class))).willReturn(loginResult(false));

            assertThat(service.login(signedIdentityToken(trustedJwk, "trusted-key"), NONCE)
                    .tokens().accessToken()).isEqualTo("access");

            clearInvocations(authService);
            RSAKey attacker = rsaJwk(rsaKeyPair(), "trusted-key");
            assertOAuthFailure(() -> service.login(
                    signedIdentityToken(attacker, "trusted-key"), NONCE));
            assertOAuthFailure(() -> service.login(signedHs256IdentityToken(), NONCE));
            verifyNoInteractions(authService);
        } finally {
            jwksServer.stop(0);
        }
    }

    @Test
    void missingAdminKeyStillAllowsLogin() {
        KakaoProperties noAdmin = new KakaoProperties(
                NATIVE_APP_KEY, "not-configured", ISSUER, JWKS_URI, API_BASE_URL);
        Jwt jwt = identityJwt(ISSUER, NATIVE_APP_KEY,
                "kakao-sub", NOW.plusSeconds(300), NONCE).build();
        KakaoAuthService service = new KakaoAuthService(noAdmin, CLOCK, authService, token -> jwt);
        given(authService.completeLogin(any(SocialIdentity.class))).willReturn(loginResult(false));

        assertThat(service.login("identity-token", NONCE).tokens().accessToken()).isEqualTo("access");
    }

    private KakaoAuthService serviceReturning(Jwt jwt) {
        return new KakaoAuthService(properties, CLOCK, authService, token -> jwt);
    }

    private static AuthService.LoginResult loginResult(boolean profileCompleted) {
        return new AuthService.LoginResult(
                new AuthService.TokenPair("access", "refresh"), profileCompleted);
    }

    private static Jwt.Builder identityJwt(String issuer, String audience, String subject,
                                           Instant expiresAt, String nonce) {
        return Jwt.withTokenValue("identity-token")
                .header("alg", "RS256")
                .claim("iss", issuer)
                .claim("aud", List.of(audience))
                .claim("sub", subject)
                .claim("exp", expiresAt)
                .claim("nonce", nonce);
    }

    private String signedIdentityToken(RSAKey key, String keyId) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(keyId)
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .audience(NATIVE_APP_KEY)
                        .subject("kakao-sub")
                        .expirationTime(Date.from(NOW.plusSeconds(300)))
                        .claim("nonce", NONCE)
                        .claim("email", "user@kakao.com")
                        .claim("nickname", "카카오유저")
                        .build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static String signedHs256IdentityToken() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .audience(NATIVE_APP_KEY)
                        .subject("kakao-sub")
                        .expirationTime(Date.from(NOW.plusSeconds(300)))
                        .claim("nonce", NONCE)
                        .build());
        jwt.sign(new MACSigner(new byte[32]));
        return jwt.serialize();
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static RSAKey rsaJwk(KeyPair pair, String keyId) {
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(keyId)
                .build();
    }

    private static void assertOAuthFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo(ResponseCode.OAUTH2_FAILED);
    }
}
