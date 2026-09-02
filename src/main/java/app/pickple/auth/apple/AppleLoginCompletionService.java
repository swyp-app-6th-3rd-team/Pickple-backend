package app.pickple.auth.apple;

import app.pickple.auth.domain.User;
import app.pickple.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 외부 Apple 검증 뒤 필요한 로컬 쓰기를 하나의 트랜잭션으로 완료한다. */
@Service
@RequiredArgsConstructor
public class AppleLoginCompletionService {

    private final AuthService authService;
    private final AppleProviderTokenService providerTokenService;

    @Transactional
    public AuthService.TokenPair complete(AppleIdentity identity, String providerRefreshToken) {
        User user = authService.loginOrRegister(identity);
        providerTokenService.store(user.id(), providerRefreshToken);
        return authService.issueTokens(user);
    }
}
