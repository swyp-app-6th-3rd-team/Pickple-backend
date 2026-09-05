package app.pickple.auth.infra;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class JpaUserStoreTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime FIXED_NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private UserRepository repository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private JpaUserStore store;

    @BeforeEach
    void setUp() {
        store = new JpaUserStore(repository, FIXED_CLOCK, transactionTemplate);
    }

    @Test
    @DisplayName("존재하지 않는 사용자 갱신은 영속화 오류로 분류한다")
    void missingUserOnUpdateIsPersistenceFailure() {
        User user = savedUser(null);
        given(repository.findById(17L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> store.save(user))
                .isInstanceOf(UserPersistenceException.class)
                .hasMessageContaining("userId=17");
    }

    @Test
    @DisplayName("프로필 갱신 대상 활성 사용자가 없으면 영속화 오류로 분류한다")
    void missingActiveUserDuringProfileUpdateIsPersistenceFailure() {
        User user = savedUser("pick");
        executeTransactionCallback();
        given(repository.findIdByActiveNickname("pick")).willReturn(Optional.empty());
        given(repository.updateProfile(17L, "pick", null, FIXED_NOW)).willReturn(0);

        assertThatThrownBy(() -> store.saveProfileIfNicknameFree(user))
                .isInstanceOf(UserPersistenceException.class)
                .hasMessageContaining("활성 사용자")
                .hasMessageContaining("userId=17");
    }

    @Test
    @DisplayName("프로필 갱신 성공 뒤 사용자를 다시 읽지 못하면 영속화 오류로 분류한다")
    void missingUserAfterProfileUpdateIsPersistenceFailure() {
        User user = savedUser("pick");
        executeTransactionCallback();
        given(repository.findIdByActiveNickname("pick")).willReturn(Optional.empty());
        given(repository.updateProfile(17L, "pick", null, FIXED_NOW)).willReturn(1);
        given(repository.findById(17L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> store.saveProfileIfNicknameFree(user))
                .isInstanceOf(UserPersistenceException.class)
                .hasMessageContaining("프로필 저장 뒤")
                .hasMessageContaining("userId=17");
    }

    private void executeTransactionCallback() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private User savedUser(String nickname) {
        return User.restore(
                17L,
                SocialProvider.GOOGLE,
                "provider-id",
                "user@example.com",
                "사용자",
                Role.ROLE_USER,
                User.State.ACTIVE,
                nickname,
                null);
    }
}
