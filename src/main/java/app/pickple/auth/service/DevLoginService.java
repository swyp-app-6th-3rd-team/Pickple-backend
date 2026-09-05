package app.pickple.auth.service;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.config.DevLoginProperties;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 사전에 허용한 QA 계정으로만 기존 JWT 발급 흐름에 진입한다. */
@RequiredArgsConstructor
public class DevLoginService {

    private final DevLoginProperties properties;
    private final UserStore userStore;
    private final AuthService authService;

    @Transactional
    public AuthService.TokenPair login(Long userId, String key) {
        if (key == null || key.length() > 512 || !MessageDigest.isEqual(
                properties.key().getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        if (userId == null || !properties.allowedUserIds().contains(userId)) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        User user = userStore.findById(userId)
                .filter(User::isActive)
                .filter(candidate -> candidate.role() == Role.ROLE_USER)
                .orElseThrow(() -> new ApiException(ResponseCode.UNAUTHORIZED));
        return authService.issueTokens(user);
    }
}
