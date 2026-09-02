package app.pickple.auth.service;

import app.pickple.auth.config.AuthProperties;
import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 15, 12, 0);
    private static final String SECRET = "test-secret-key-for-unit-tests-only-32bytes+";

    private static final User USER = User.restore(
            42L, SocialProvider.GOOGLE, "sub-1", "u@example.com", "홍길동",
            Role.ROLE_USER, User.State.ACTIVE);

    private JwtService jwtServiceAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
        AuthProperties properties = new AuthProperties(
                new AuthProperties.Jwt(SECRET, Duration.ofMinutes(30), Duration.ofDays(14), "test-issuer"),
                new AuthProperties.Auth("http://localhost:3000/cb", List.of("localhost"), false),
                new AuthProperties.Cors(List.of("http://localhost:3000")));
        return new JwtService(properties, clock);
    }

    @DisplayName("액세스 토큰에 사용자 ID 와 권한이 담긴다")
    @Test
    void accessTokenCarriesIdentity() {
        JwtService jwtService = jwtServiceAt(NOW);

        String token = jwtService.createAccessToken(USER);
        JwtService.Authenticated authenticated = jwtService.parseAccessToken(token);

        // DB 조회 없이 토큰만으로 신원을 안다.
        assertThat(authenticated.userId()).isEqualTo(42L);
        assertThat(authenticated.role()).isEqualTo(Role.ROLE_USER);
    }

    @DisplayName("만료된 토큰은 EXPIRED_TOKEN 으로 거부한다")
    @Test
    void rejectsExpiredToken() {
        String token = jwtServiceAt(NOW).createAccessToken(USER);

        // 31분 뒤 시계로 검증한다(유효기간 30분).
        JwtService later = jwtServiceAt(NOW.plusMinutes(31));

        assertThatThrownBy(() -> later.parseAccessToken(token))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.EXPIRED_TOKEN);
    }

    @DisplayName("서명이 다른 토큰은 거부한다")
    @Test
    void rejectsTamperedSignature() {
        JwtService jwtService = jwtServiceAt(NOW);
        String token = jwtService.createAccessToken(USER);

        // payload 를 건드리면 서명이 깨진다.
        String tampered = token.substring(0, token.lastIndexOf('.')) + ".invalidsignature";

        assertThatThrownBy(() -> jwtService.parseAccessToken(tampered))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.INVALID_TOKEN);
    }

    @DisplayName("리프레시 토큰을 액세스 토큰으로 쓸 수 없다")
    @Test
    void refreshTokenIsNotAccessToken() {
        JwtService jwtService = jwtServiceAt(NOW);
        String refreshToken = jwtService.createRefreshToken(USER);

        assertThatThrownBy(() -> jwtService.parseAccessToken(refreshToken))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("액세스 토큰이 아닙니다");
    }

    @DisplayName("발급자가 다르면 거부한다")
    @Test
    void rejectsWrongIssuer() {
        AuthProperties other = new AuthProperties(
                new AuthProperties.Jwt(SECRET, Duration.ofMinutes(30), Duration.ofDays(14), "another-issuer"),
                new AuthProperties.Auth("http://localhost:3000/cb", List.of("localhost"), false),
                new AuthProperties.Cors(List.of("http://localhost:3000")));
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        String foreignToken = new JwtService(other, clock).createAccessToken(USER);

        assertThatThrownBy(() -> jwtServiceAt(NOW).parseAccessToken(foreignToken))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.INVALID_TOKEN);
    }

    @DisplayName("32바이트 미만 키는 기동 시점에 막는다")
    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new AuthProperties.Jwt(
                "too-short", Duration.ofMinutes(30), Duration.ofDays(14), "test-issuer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트 이상");
    }

    @DisplayName("설정 문자열에 JWT secret을 노출하지 않는다")
    @Test
    void redactsSecretFromConfigurationString() {
        AuthProperties.Jwt properties = new AuthProperties.Jwt(
                SECRET, Duration.ofMinutes(30), Duration.ofDays(14), "test-issuer");

        assertThat(properties.toString())
                .contains("secretKey=redacted")
                .doesNotContain(SECRET);
    }

    @DisplayName("같은 토큰은 같은 해시를 낸다")
    @Test
    void hashIsDeterministic() {
        String token = jwtServiceAt(NOW).createRefreshToken(USER);

        assertThat(JwtService.hash(token))
                .isEqualTo(JwtService.hash(token))
                .hasSize(64)                 // SHA-256 hex
                .isNotEqualTo(token);        // 원문과 다르다
    }
}
