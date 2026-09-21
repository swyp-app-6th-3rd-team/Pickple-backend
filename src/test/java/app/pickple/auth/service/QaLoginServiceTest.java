package app.pickple.auth.service;

import app.pickple.auth.domain.*;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QaLoginServiceTest {
    private static final String PASSWORD = "qa-test-password";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);
    private static final String HASH = ENCODER.encode(PASSWORD);
    private QaAccountStore accounts;
    private UserStore users;
    private AuthService auth;
    private QaLoginService service;

    @BeforeEach
    void setUp() {
        accounts = mock(QaAccountStore.class);
        users = mock(UserStore.class);
        auth = mock(AuthService.class);
        service = new QaLoginService(accounts, users, auth, ENCODER);
    }

    @Test
    void issuesJwtForLocalQaUserWithoutSocialIdentity() {
        credentials();
        User user = user(SocialProvider.QA, Role.ROLE_USER, User.State.ACTIVE);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var tokens = new AuthService.TokenPair("test-access", "test-refresh");
        when(auth.issueTokens(user)).thenReturn(tokens);

        assertThat(service.login("qa-user", PASSWORD)).isSameAs(tokens);
        verify(auth).issueTokens(user);
        verifyNoMoreInteractions(auth);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid id", "x/qa", "qa\nuser"})
    void rejectsMalformedLoginIdBeforeDatabaseAccess(String id) {
        unauthorized(() -> service.login(id, PASSWORD));
        verifyNoInteractions(accounts, users, auth);
    }

    @Test
    void unknownIdAndWrongPasswordNeverIssueTokens() {
        unauthorized(() -> service.login("unknown", PASSWORD));
        credentials();
        unauthorized(() -> service.login("qa-user", "wrong-password"));
        verifyNoInteractions(users, auth);
    }

    @Test
    void rejectsMissingWithdrawnAdminAndSocialAccounts() {
        credentials();
        when(users.findById(1L)).thenReturn(Optional.empty());
        unauthorized(() -> service.login("qa-user", PASSWORD));
        for (User user : new User[] {
                user(SocialProvider.QA, Role.ROLE_USER, User.State.INACTIVE),
                user(SocialProvider.QA, Role.ROLE_ADMIN, User.State.ACTIVE),
                user(SocialProvider.KAKAO, Role.ROLE_USER, User.State.ACTIVE)}) {
            when(users.findById(1L)).thenReturn(Optional.of(user));
            unauthorized(() -> service.login("qa-user", PASSWORD));
        }
        verifyNoInteractions(auth);
    }

    @Test
    void rejectsBcryptOversizedUtf8PasswordWithoutServerError() {
        unauthorized(() -> service.login("qa-user", "가".repeat(25)));
        unauthorized(() -> service.login("qa-user", "a".repeat(73)));
        verifyNoInteractions(accounts, users, auth);
    }

    @Test
    void exact72BytePasswordIsAccepted() {
        String password = "가".repeat(24);
        when(accounts.findByLoginId("qa-user")).thenReturn(
                Optional.of(new QaAccount("qa-user", ENCODER.encode(password), 1L)));
        User user = user(SocialProvider.QA, Role.ROLE_USER, User.State.ACTIVE);
        when(users.findById(1L)).thenReturn(Optional.of(user));
        service.login("qa-user", password);
        verify(auth).issueTokens(user);
    }

    @Test
    void credentialsNeverExposeHashInToString() {
        assertThat(new QaAccount("qa-user", HASH, 1L).toString()).isEqualTo("QaAccount[redacted]");
    }

    private void credentials() {
        when(accounts.findByLoginId("qa-user")).thenReturn(Optional.of(new QaAccount("qa-user", HASH, 1L)));
    }

    private static User user(SocialProvider provider, Role role, User.State state) {
        return User.restore(1L, provider, state == User.State.ACTIVE ? "internal-id" : null,
                null, null, role, state, null, null);
    }

    private static void unauthorized(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ResponseCode.UNAUTHORIZED));
    }
}
