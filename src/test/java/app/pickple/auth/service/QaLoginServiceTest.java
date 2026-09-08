package app.pickple.auth.service;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.config.QaLoginProperties;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QaLoginServiceTest {

    private static final String LOGIN_ID = "qa-user";
    private static final String PASSWORD = "qa-test-password";
    private static BCryptPasswordEncoder passwordEncoder;
    private static String passwordHash;

    private UserStore userStore;
    private AuthService authService;
    private QaLoginService service;

    @BeforeAll
    static void createPasswordHash() {
        passwordEncoder = new BCryptPasswordEncoder(10);
        passwordHash = passwordEncoder.encode(PASSWORD);
    }

    @BeforeEach
    void setUp() {
        userStore = mock(UserStore.class);
        authService = mock(AuthService.class);
        service = new QaLoginService(
                new QaLoginProperties(LOGIN_ID, passwordHash, 1L),
                userStore, authService, passwordEncoder);
    }

    @Test
    void issuesExistingTokenPairForConfiguredActiveUser() {
        User user = user(Role.ROLE_USER, User.State.ACTIVE);
        var tokens = new AuthService.TokenPair("test-access", "test-refresh");
        when(userStore.findById(1L)).thenReturn(Optional.of(user));
        when(authService.issueTokens(user)).thenReturn(tokens);

        assertThat(service.login(LOGIN_ID, PASSWORD)).isSameAs(tokens);
        verify(authService).issueTokens(user);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong-id", " qa-user", "QA-USER"})
    void rejectsWrongLoginIdBeforeAccessingAccount(String loginId) {
        assertUnauthorized(() -> service.login(loginId, PASSWORD));
        verifyNoInteractions(userStore, authService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong-password", " qa-test-password", "QA-TEST-PASSWORD"})
    void rejectsWrongPasswordBeforeAccessingAccount(String password) {
        assertUnauthorized(() -> service.login(LOGIN_ID, password));
        verifyNoInteractions(userStore, authService);
    }

    @Test
    void rejectsOversizedCredentialsBeforeAccessingAccount() {
        assertUnauthorized(() -> service.login("x".repeat(101), PASSWORD));
        assertUnauthorized(() -> service.login(LOGIN_ID, "x".repeat(257)));
        verifyNoInteractions(userStore, authService);
    }

    @Test
    void rejectsMissingWithdrawnAndAdministratorAccounts() {
        when(userStore.findById(1L)).thenReturn(Optional.empty());
        assertUnauthorized(() -> service.login(LOGIN_ID, PASSWORD));

        when(userStore.findById(1L)).thenReturn(Optional.of(user(Role.ROLE_USER, User.State.INACTIVE)));
        assertUnauthorized(() -> service.login(LOGIN_ID, PASSWORD));

        when(userStore.findById(1L)).thenReturn(Optional.of(user(Role.ROLE_ADMIN, User.State.ACTIVE)));
        assertUnauthorized(() -> service.login(LOGIN_ID, PASSWORD));

        verifyNoInteractions(authService);
    }

    @Test
    void configurationStringRedactsCredentialValues() {
        assertThat(new QaLoginProperties(LOGIN_ID, passwordHash, 1L).toString())
                .isEqualTo("QaLoginProperties[redacted]")
                .doesNotContain(LOGIN_ID, passwordHash);
    }

    private static User user(Role role, User.State state) {
        return User.restore(1L, SocialProvider.KAKAO, "qa-test-only", null, "QA",
                role, state, null, null);
    }

    private static void assertUnauthorized(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ResponseCode.UNAUTHORIZED));
    }
}
