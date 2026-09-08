package app.pickple.auth.service;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.config.QaLoginProperties;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** dev에서 설정한 QA 아이디·비밀번호를 기존 활성 사용자 한 명에 연결한다. */
@RequiredArgsConstructor
public class QaLoginService {

    private final QaLoginProperties properties;
    private final UserStore userStore;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public AuthService.TokenPair login(String loginId, String password) {
        boolean loginIdMatches = loginId != null && loginId.length() <= 100
                && MessageDigest.isEqual(
                properties.loginId().getBytes(StandardCharsets.UTF_8),
                loginId.getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = password != null && password.length() <= 256
                && passwordEncoder.matches(password, properties.passwordHash());
        if (!loginIdMatches || !passwordMatches) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }

        User user = userStore.findById(properties.userId())
                .filter(User::isActive)
                .filter(candidate -> candidate.role() == Role.ROLE_USER)
                .orElseThrow(() -> new ApiException(ResponseCode.UNAUTHORIZED));
        return authService.issueTokens(user);
    }
}
