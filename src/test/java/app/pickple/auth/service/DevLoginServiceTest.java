package app.pickple.auth.service;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.config.DevLoginProperties;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevLoginServiceTest {

    private static final String KEY = "test-only-qa-key-at-least-32-bytes-long";
    private UserStore userStore;
    private AuthService authService;
    private DevLoginService service;

    @BeforeEach
    void setUp() {
        userStore = mock(UserStore.class);
        authService = mock(AuthService.class);
        service = new DevLoginService(new DevLoginProperties(KEY, Set.of(1L, 2L)), userStore, authService);
    }

    @Test
    void issuesExistingTokenPairForAllowedActiveUser() {
        User user = user(Role.ROLE_USER, User.State.ACTIVE);
        var tokens = new AuthService.TokenPair("test-access", "test-refresh");
        when(userStore.findById(1L)).thenReturn(Optional.of(user));
        when(authService.issueTokens(user)).thenReturn(tokens);

        assertThat(service.login(1L, KEY)).isSameAs(tokens);
        verify(authService).issueTokens(user);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong-key", " test-only-qa-key-at-least-32-bytes-long", "TEST-only-qa-key-at-least-32-bytes-long"})
    void rejectsInvalidKeyBeforeAccessingAnyAccount(String key) {
        assertUnauthorized(() -> service.login(1L, key));
        verifyNoInteractions(userStore, authService);
    }

    @Test
    void rejectsOversizedKeyBeforeAccessingAnyAccount() {
        assertUnauthorized(() -> service.login(1L, "x".repeat(513)));
        verifyNoInteractions(userStore, authService);
    }

    @Test
    void rejectsUnlistedAndMissingIdBeforeDatabaseAccess() {
        assertUnauthorized(() -> service.login(3L, KEY));
        assertUnauthorized(() -> service.login(null, KEY));
        verifyNoInteractions(userStore, authService);
    }

    @Test
    void neverCreatesUnknownUsers() {
        when(userStore.findById(1L)).thenReturn(Optional.empty());
        assertUnauthorized(() -> service.login(1L, KEY));
        verifyNoInteractions(authService);
    }

    @Test
    void rejectsWithdrawnUsersEvenIfAllowlisted() {
        when(userStore.findById(1L)).thenReturn(Optional.of(user(Role.ROLE_USER, User.State.INACTIVE)));
        assertUnauthorized(() -> service.login(1L, KEY));
        verifyNoInteractions(authService);
    }

    @Test
    void rejectsAdministratorsEvenIfAllowlisted() {
        when(userStore.findById(1L)).thenReturn(Optional.of(user(Role.ROLE_ADMIN, User.State.ACTIVE)));
        assertUnauthorized(() -> service.login(1L, KEY));
        verifyNoInteractions(authService);
    }

    @Test
    void configurationDoesNotPrintSecret() {
        assertThat(new DevLoginProperties(KEY, Set.of(1L)).toString())
                .isEqualTo("DevLoginProperties[redacted]").doesNotContain(KEY);
    }

    private static User user(Role role, User.State state) {
        return User.restore(1L, SocialProvider.GOOGLE, "qa-test-only", null, "QA", role, state, null, null);
    }

    private static void assertUnauthorized(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ResponseCode.UNAUTHORIZED));
    }
}
