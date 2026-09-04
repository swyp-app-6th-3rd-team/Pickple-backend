package app.pickple.auth.service;

import app.pickple.auth.apple.AppleProviderTokenService;
import app.pickple.auth.apple.AppleTokenGateway;
import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.kakao.KakaoUnlinkGateway;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
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
    private AppleTokenGateway appleTokenGateway;
    @Mock
    private AccountWithdrawalPersistenceService persistenceService;
    @Mock
    private KakaoUnlinkGateway kakaoUnlinkGateway;

    private AccountWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new AccountWithdrawalService(
                userStore, providerTokenService, appleTokenGateway, persistenceService,
                kakaoUnlinkGateway);
    }

    @Test
    void revokesAppleTokenBeforeCompletingLocalWithdrawal() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.APPLE)));
        given(providerTokenService.findDecryptedByUserId(7L)).willReturn(Optional.of("provider-refresh"));

        AccountWithdrawalService.WithdrawalOutcome outcome = service.withdraw(7L);

        assertThat(outcome).isEqualTo(AccountWithdrawalService.WithdrawalOutcome.COMPLETED);
        InOrder order = inOrder(appleTokenGateway, persistenceService);
        order.verify(appleTokenGateway).revokeRefreshToken("provider-refresh");
        order.verify(persistenceService).complete(7L);
    }

    @Test
    void revokeFailurePreservesLocalStateForRetry() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.APPLE)));
        given(providerTokenService.findDecryptedByUserId(7L)).willReturn(Optional.of("provider-refresh"));
        org.mockito.Mockito.doThrow(new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE))
                .when(appleTokenGateway).revokeRefreshToken("provider-refresh");

        assertThatThrownBy(() -> service.withdraw(7L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        verify(persistenceService, never()).complete(7L);
    }

    @Test
    void missingAppleTokenCompletesLocallyAndRequestsManualRevocation() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.APPLE)));
        given(providerTokenService.findDecryptedByUserId(7L)).willReturn(Optional.empty());

        AccountWithdrawalService.WithdrawalOutcome outcome = service.withdraw(7L);

        assertThat(outcome).isEqualTo(
                AccountWithdrawalService.WithdrawalOutcome.COMPLETED_REQUIRES_MANUAL_APPLE_REVOCATION);
        verifyNoInteractions(appleTokenGateway);
        verify(persistenceService).complete(7L);
    }

    @Test
    void retriesIdempotentAppleRevokeWhenLocalCompletionFailed() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.APPLE)));
        given(providerTokenService.findDecryptedByUserId(7L)).willReturn(Optional.of("provider-refresh"));
        doThrow(new IllegalStateException("temporary db failure"))
                .doNothing()
                .when(persistenceService).complete(7L);

        assertThatThrownBy(() -> service.withdraw(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary db failure");

        assertThat(service.withdraw(7L))
                .isEqualTo(AccountWithdrawalService.WithdrawalOutcome.COMPLETED);
        InOrder order = inOrder(appleTokenGateway, persistenceService);
        order.verify(appleTokenGateway).revokeRefreshToken("provider-refresh");
        order.verify(persistenceService).complete(7L);
        order.verify(appleTokenGateway).revokeRefreshToken("provider-refresh");
        order.verify(persistenceService).complete(7L);
    }

    @Test
    void nonAppleUserSkipsAppleSystems() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.GOOGLE)));

        AccountWithdrawalService.WithdrawalOutcome outcome = service.withdraw(7L);

        assertThat(outcome).isEqualTo(AccountWithdrawalService.WithdrawalOutcome.COMPLETED);
        verifyNoInteractions(providerTokenService, appleTokenGateway, kakaoUnlinkGateway);
        verify(persistenceService).complete(7L);
    }

    @Test
    void unlinksKakaoBeforeCompletingLocalWithdrawal() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.KAKAO)));

        AccountWithdrawalService.WithdrawalOutcome outcome = service.withdraw(7L);

        assertThat(outcome).isEqualTo(AccountWithdrawalService.WithdrawalOutcome.COMPLETED);
        InOrder order = inOrder(kakaoUnlinkGateway, persistenceService);
        order.verify(kakaoUnlinkGateway).unlink("provider-sub");
        order.verify(persistenceService).complete(7L);
        verifyNoInteractions(providerTokenService, appleTokenGateway);
    }

    @Test
    void kakaoUnlinkFailurePreservesLocalStateForRetry() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.KAKAO)));
        doThrow(new ApiException(ResponseCode.KAKAO_ACCOUNT_REVOCATION_UNAVAILABLE))
                .when(kakaoUnlinkGateway).unlink("provider-sub");

        assertThatThrownBy(() -> service.withdraw(7L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.KAKAO_ACCOUNT_REVOCATION_UNAVAILABLE);

        verify(persistenceService, never()).complete(7L);
        verifyNoInteractions(providerTokenService, appleTokenGateway);
    }

    @Test
    void retriesKakaoUnlinkWhenLocalCompletionFailed() {
        given(userStore.findById(7L)).willReturn(Optional.of(user(SocialProvider.KAKAO)));
        doThrow(new IllegalStateException("temporary db failure"))
                .doNothing()
                .when(persistenceService).complete(7L);

        assertThatThrownBy(() -> service.withdraw(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary db failure");

        assertThat(service.withdraw(7L))
                .isEqualTo(AccountWithdrawalService.WithdrawalOutcome.COMPLETED);
        InOrder order = inOrder(kakaoUnlinkGateway, persistenceService);
        order.verify(kakaoUnlinkGateway).unlink("provider-sub");
        order.verify(persistenceService).complete(7L);
        order.verify(kakaoUnlinkGateway).unlink("provider-sub");
        order.verify(persistenceService).complete(7L);
    }

    private static User user(SocialProvider provider) {
        return User.restore(7L, provider, "provider-sub", "user@example.com", "사용자",
                Role.ROLE_USER, User.State.ACTIVE, null, null);
    }
}
