package app.pickple.auth.service;

import app.pickple.auth.apple.AppleProviderTokenService;
import app.pickple.auth.apple.AppleTokenClient;
import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AccountWithdrawalServiceTest {

    @Mock
    private UserStore userStore;
    @Mock
    private AppleProviderTokenService providerTokenService;
    @Mock
    private AppleTokenClient appleTokenClient;
    @Mock
    private AccountWithdrawalPersistenceService persistenceService;

    private AccountWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new AccountWithdrawalService(
                userStore, providerTokenService, appleTokenClient, persistenceService);
    }

    @Test
    void revokesAppleTokenBeforeCompletingLocalWithdrawal() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.APPLE)));
        given(providerTokenService.findDecryptedByUserId(7L)).willReturn(Optional.of("provider-refresh"));

        service.withdraw(7L);

        InOrder order = inOrder(appleTokenClient, persistenceService);
        order.verify(appleTokenClient).revokeRefreshToken("provider-refresh");
        order.verify(persistenceService).complete(7L);
    }

    @Test
    void revokeFailurePreservesLocalStateForRetry() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.APPLE)));
        given(providerTokenService.findDecryptedByUserId(7L)).willReturn(Optional.of("provider-refresh"));
        org.mockito.Mockito.doThrow(new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE))
                .when(appleTokenClient).revokeRefreshToken("provider-refresh");

        assertThatThrownBy(() -> service.withdraw(7L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        verify(persistenceService, never()).complete(7L);
    }

    @Test
    void missingLegacyAppleTokenDoesNotBlockWithdrawal() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.APPLE)));
        given(providerTokenService.findDecryptedByUserId(7L)).willReturn(Optional.empty());

        service.withdraw(7L);

        verifyNoInteractions(appleTokenClient);
        verify(persistenceService).complete(7L);
    }

    @Test
    void nonAppleUserSkipsAppleSystems() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.GOOGLE)));

        service.withdraw(7L);

        verifyNoInteractions(providerTokenService, appleTokenClient);
        verify(persistenceService).complete(7L);
    }

    private static User user(SocialProvider provider) {
        return User.restore(7L, provider, "provider-sub", "user@example.com", "사용자",
                Role.ROLE_USER, User.State.ACTIVE);
    }
}
