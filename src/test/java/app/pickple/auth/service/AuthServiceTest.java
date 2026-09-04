package app.pickple.auth.service;

import app.pickple.config.AuthProperties;
import app.pickple.auth.apple.AppleIdentity;
import app.pickple.auth.domain.RefreshTokenStore;
import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.oauth.OAuth2UserInfo;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 15, 12, 0);
    private static final String SECRET = "test-secret-key-for-unit-tests-only-32bytes+";

    @Mock
    private UserStore userStore;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private RefreshTokenRevocationService refreshTokenRevocationService;

    private AuthService authService;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        AuthProperties properties = new AuthProperties(
                new AuthProperties.Jwt(SECRET, Duration.ofMinutes(30), Duration.ofDays(14), "test-issuer"),
                new AuthProperties.Auth("http://localhost:3000/cb", java.util.List.of("localhost"), false),
                new AuthProperties.Cors(java.util.List.of("http://localhost:3000")));
        jwtService = new JwtService(properties, clock);
        authService = new AuthService(
                userStore, refreshTokenStore, jwtService, clock, refreshTokenRevocationService);
    }

    private OAuth2UserInfo googleUser(String sub) {
        return OAuth2UserInfo.of("google", Map.of("sub", sub, "email", "u@example.com", "name", "홍길동"));
    }

    @DisplayName("로그인 — 기존 사용자가 없으면 새로 만든다")
    @Test
    void registersNewUser() {
        given(userStore.findByProviderAndProviderId(SocialProvider.GOOGLE, "sub-1")).willReturn(Optional.empty());
        given(userStore.save(any(User.class))).willAnswer(inv -> {
            User u = inv.getArgument(0);
            return User.restore(1L, u.provider(), u.providerId(), u.email(), u.name(), u.role(), u.state(), null, null);
        });

        User result = authService.loginOrRegister(googleUser("sub-1"));

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(result.providerId()).isEqualTo("sub-1");
    }

    @DisplayName("Apple 재가입 — 분리된 sub가 조회되지 않으면 새 사용자로 만든다")
    @Test
    void registersNewAppleUserWhenIdentityWasDetached() {
        given(userStore.findByProviderAndProviderId(SocialProvider.APPLE, "apple-sub"))
                .willReturn(Optional.empty());
        given(userStore.save(any(User.class))).willAnswer(inv -> {
            User user = inv.getArgument(0);
            return User.restore(10L, user.provider(), user.providerId(), user.email(), user.name(),
                    user.role(), user.state(), null, null);
        });

        User result = authService.loginOrRegister(
                new AppleIdentity("apple-sub", "new@example.com", "새이름"));

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.provider()).isEqualTo(SocialProvider.APPLE);
        assertThat(result.providerId()).isEqualTo("apple-sub");
        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(result.name()).isEqualTo("새이름");
        assertThat(result.hasProfile()).isFalse();
    }

    @DisplayName("로그인 — 기존 사용자면 프로필만 갱신한다")
    @Test
    void syncsExistingUser() {
        User existing = User.restore(7L, SocialProvider.GOOGLE, "sub-1",
                "old@example.com", "옛이름", Role.ROLE_USER, User.State.ACTIVE, null, null);
        given(userStore.findByProviderAndProviderId(SocialProvider.GOOGLE, "sub-1")).willReturn(Optional.of(existing));
        given(userStore.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        User result = authService.loginOrRegister(googleUser("sub-1"));

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.email()).isEqualTo("u@example.com");   // 갱신됨
        assertThat(result.name()).isEqualTo("홍길동");
    }

    @DisplayName("Apple 재로그인 — 앱이 보낸 이름으로 기존 이름을 덮어쓰지 않는다")
    @Test
    void doesNotOverwriteAppleNameOnRelogin() {
        User existing = User.restore(8L, SocialProvider.APPLE, "apple-sub",
                "old@example.com", "최초이름", Role.ROLE_USER, User.State.ACTIVE, null, null);
        given(userStore.findByProviderAndProviderId(SocialProvider.APPLE, "apple-sub"))
                .willReturn(Optional.of(existing));
        given(userStore.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        User result = authService.loginOrRegister(
                new AppleIdentity("apple-sub", "new@example.com", "변조된이름"));

        assertThat(result.email()).isEqualTo("new@example.com");
        assertThat(result.name()).isEqualTo("최초이름");
    }

    @DisplayName("로그인 — 탈퇴한 비 Apple 계정은 계속 거부한다")
    @Test
    void rejectsWithdrawnNonAppleUser() {
        User withdrawn = User.restore(9L, SocialProvider.GOOGLE, "sub-1",
                null, null, Role.ROLE_USER, User.State.INACTIVE, null, null);
        given(userStore.findByProviderAndProviderId(SocialProvider.GOOGLE, "sub-1")).willReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> authService.loginOrRegister(googleUser("sub-1")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.FORBIDDEN);
    }

    @DisplayName("내 정보 — 탈퇴한 계정은 기존 access token이 있어도 거부한다")
    @Test
    void rejectsWithdrawnUserLookup() {
        User withdrawn = User.restore(9L, SocialProvider.GOOGLE, "sub-1",
                null, null, Role.ROLE_USER, User.State.INACTIVE, null, null);
        given(userStore.findById(9L)).willReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> authService.getById(9L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.UNAUTHORIZED);
    }

    @DisplayName("토큰 발급 — 리프레시는 해시로 저장한다")
    @Test
    void storesRefreshTokenAsHash() {
        User user = User.restore(1L, SocialProvider.GOOGLE, "sub-1", null, null,
                Role.ROLE_USER, User.State.ACTIVE, null, null);

        AuthService.TokenPair tokens = authService.issueTokens(user);

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        // 저장된 값은 원문이 아니라 해시여야 한다.
        verify(refreshTokenStore).store(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(JwtService.hash(tokens.refreshToken())),
                any(LocalDateTime.class));
        assertThat(tokens.toString())
                .isEqualTo("TokenPair[redacted]")
                .doesNotContain(tokens.accessToken(), tokens.refreshToken());
    }

    @DisplayName("재발급 — 저장된 해시와 일치하면 새 토큰을 준다")
    @Test
    void refreshesWithValidToken() {
        User user = User.restore(1L, SocialProvider.GOOGLE, "sub-1", null, null,
                Role.ROLE_USER, User.State.ACTIVE, null, null);
        String refreshToken = jwtService.createRefreshToken(user);

        given(refreshTokenStore.findByUserId(1L)).willReturn(Optional.of(
                new RefreshTokenStore.StoredRefreshToken(1L, JwtService.hash(refreshToken), NOW.plusDays(14))));
        given(userStore.findById(1L)).willReturn(Optional.of(user));
        given(refreshTokenStore.rotateIfMatches(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(JwtService.hash(refreshToken)),
                anyString(),
                any(LocalDateTime.class))).willReturn(true);

        AuthService.TokenPair result = authService.refresh(refreshToken);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        verify(refreshTokenStore).rotateIfMatches(
                1L,
                JwtService.hash(refreshToken),
                JwtService.hash(result.refreshToken()),
                jwtService.refreshTokenExpiresAt());
        verify(refreshTokenStore, never()).store(anyLong(), anyString(), any(LocalDateTime.class));
    }

    @DisplayName("재발급 — 해시가 다르면 현재 토큰을 보존하고 제출 요청만 거부한다")
    @Test
    void rejectsHashMismatchWithoutRevokingCurrentToken() {
        User user = User.restore(1L, SocialProvider.GOOGLE, "sub-1", null, null,
                Role.ROLE_USER, User.State.ACTIVE, null, null);
        String submitted = jwtService.createRefreshToken(user);

        // 저장소에는 다른 토큰의 해시가 있다 = 이미 회전됐거나 탈취 상황
        given(refreshTokenStore.findByUserId(1L)).willReturn(Optional.of(
                new RefreshTokenStore.StoredRefreshToken(1L, JwtService.hash("other-token"), NOW.plusDays(14))));

        assertThatThrownBy(() -> authService.refresh(submitted))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.INVALID_TOKEN);

        verify(refreshTokenRevocationService, never()).revokeAllForUser(1L);
        verify(refreshTokenStore, never()).rotateIfMatches(anyLong(), anyString(), anyString(), any());
    }

    @DisplayName("재발급 — CAS 경합에서 진 요청은 승자의 현재 토큰을 폐기하지 않는다")
    @Test
    void rejectsLostRotationRaceWithoutRevokingWinner() {
        User user = User.restore(1L, SocialProvider.GOOGLE, "sub-1", null, null,
                Role.ROLE_USER, User.State.ACTIVE, null, null);
        String submitted = jwtService.createRefreshToken(user);
        String submittedHash = JwtService.hash(submitted);

        given(refreshTokenStore.findByUserId(1L)).willReturn(Optional.of(
                new RefreshTokenStore.StoredRefreshToken(1L, submittedHash, NOW.plusDays(14))));
        given(userStore.findById(1L)).willReturn(Optional.of(user));
        given(refreshTokenStore.rotateIfMatches(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(submittedHash),
                anyString(),
                any(LocalDateTime.class))).willReturn(false);

        assertThatThrownBy(() -> authService.refresh(submitted))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.INVALID_TOKEN);

        verify(refreshTokenRevocationService, never()).revokeAllForUser(1L);
        verify(refreshTokenStore, never()).deleteByUserId(1L);
    }

    @DisplayName("재발급 — 저장된 토큰이 만료됐으면 거부한다")
    @Test
    void rejectsExpiredStoredToken() {
        User user = User.restore(1L, SocialProvider.GOOGLE, "sub-1", null, null,
                Role.ROLE_USER, User.State.ACTIVE, null, null);
        String refreshToken = jwtService.createRefreshToken(user);

        given(refreshTokenStore.findByUserId(1L)).willReturn(Optional.of(
                new RefreshTokenStore.StoredRefreshToken(1L, JwtService.hash(refreshToken), NOW.minusDays(1))));

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.EXPIRED_TOKEN);

        verify(refreshTokenRevocationService).revokeAllForUser(1L);
    }

    @DisplayName("재발급 — 토큰이 없으면 거부한다")
    @Test
    void rejectsNullToken() {
        assertThatThrownBy(() -> authService.refresh(null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("리프레시 토큰이 없습니다");
    }

    @DisplayName("재발급 — 액세스 토큰을 리프레시로 쓰면 거부한다")
    @Test
    void rejectsAccessTokenAsRefresh() {
        User user = User.restore(1L, SocialProvider.GOOGLE, "sub-1", null, null,
                Role.ROLE_USER, User.State.ACTIVE, null, null);
        String accessToken = jwtService.createAccessToken(user);

        // typ 클레임으로 토큰 종류를 구분하므로 서로 바꿔 쓸 수 없다.
        assertThatThrownBy(() -> authService.refresh(accessToken))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("리프레시 토큰이 아닙니다");
    }

    @DisplayName("로그아웃 — 저장된 리프레시 토큰을 지운다")
    @Test
    void logoutDeletesRefreshToken() {
        authService.logout(1L);

        verify(refreshTokenStore).deleteByUserId(1L);
    }
}
