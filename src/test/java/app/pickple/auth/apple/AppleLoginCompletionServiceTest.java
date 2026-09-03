package app.pickple.auth.apple;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class AppleLoginCompletionServiceTest {

    @Mock
    private AuthService authService;
    @Mock
    private AppleProviderTokenService providerTokenService;

    @Test
    void persistsProviderTokenBeforeIssuingServiceTokens() {
        AppleLoginCompletionService service = new AppleLoginCompletionService(authService, providerTokenService);
        AppleIdentity identity = new AppleIdentity("apple-sub", "user@example.com", "사용자");
        User user = User.restore(7L, SocialProvider.APPLE, "apple-sub", "user@example.com", "사용자",
                Role.ROLE_USER, User.State.ACTIVE, null, null);
        given(authService.loginOrRegister(identity)).willReturn(user);
        given(authService.issueTokens(user)).willReturn(new AuthService.TokenPair("access", "refresh"));

        AuthService.TokenPair result = service.complete(identity, "provider-refresh");

        assertThat(result).isEqualTo(new AuthService.TokenPair("access", "refresh"));
        InOrder order = inOrder(authService, providerTokenService);
        order.verify(authService).loginOrRegister(identity);
        order.verify(providerTokenService).store(7L, "provider-refresh");
        order.verify(authService).issueTokens(user);
    }
}
