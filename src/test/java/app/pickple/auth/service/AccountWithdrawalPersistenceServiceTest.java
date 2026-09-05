package app.pickple.auth.service;

import app.pickple.auth.domain.AppleProviderTokenStore;
import app.pickple.auth.domain.RefreshTokenStore;
import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccountWithdrawalPersistenceServiceTest {

    @Mock
    private UserStore userStore;
    @Mock
    private RefreshTokenStore refreshTokenStore;
    @Mock
    private AppleProviderTokenStore appleProviderTokenStore;

    @Test
    void marksUserInactiveDetachesAppleIdentityAndDeletesBothTokenTypes() {
        User user = User.restore(7L, SocialProvider.APPLE, "apple-sub", "user@example.com", "사용자",
                Role.ROLE_USER, User.State.ACTIVE, null, null);
        given(userStore.findById(7L)).willReturn(Optional.of(user));
        given(userStore.save(user)).willReturn(user);
        AccountWithdrawalPersistenceService service = new AccountWithdrawalPersistenceService(
                userStore, refreshTokenStore, appleProviderTokenStore);

        service.complete(7L);

        assertThat(user.state()).isEqualTo(User.State.INACTIVE);
        assertThat(user.providerId()).isNull();
        verify(userStore).save(user);
        verify(refreshTokenStore).deleteByUserId(7L);
        verify(appleProviderTokenStore).deleteByUserId(7L);
    }
}
