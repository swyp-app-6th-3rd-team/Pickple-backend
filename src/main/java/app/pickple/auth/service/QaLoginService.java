package app.pickple.auth.service;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.QaAccount;
import app.pickple.auth.domain.QaAccountStore;
import app.pickple.auth.domain.AuthProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 외부 소셜 인증 없이 DB의 테스터 자격증명을 확인하고 기존 JWT를 발급한다. */
public class QaLoginService {
    private final QaAccountStore accounts;
    private final UserStore userStore;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final String dummyHash;

    public QaLoginService(QaAccountStore accounts, UserStore userStore, AuthService authService,
                          PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.userStore = userStore;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthService.TokenPair login(String loginId, String password) {
        // BCrypt는 72바이트를 초과하는 입력을 지원하지 않는다.
        if (!QaAccount.isValidLoginId(loginId) || password == null || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        QaAccount account = accounts.findByLoginId(loginId).orElse(null);
        boolean matches = passwordEncoder.matches(password, account == null ? dummyHash : account.passwordHash());
        if (!matches || account == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }

        User user = userStore.findById(account.userId())
                .filter(User::isActive)
                .filter(candidate -> candidate.role() == Role.ROLE_USER)
                .filter(candidate -> candidate.provider() == AuthProvider.QA)
                .orElseThrow(() -> new ApiException(ResponseCode.UNAUTHORIZED));
        return authService.issueTokens(user);
    }
}
