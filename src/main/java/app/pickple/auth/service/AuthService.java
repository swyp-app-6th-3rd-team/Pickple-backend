package app.pickple.auth.service;

import app.pickple.auth.domain.RefreshTokenStore;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.oauth.OAuth2UserInfo;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserStore userStore;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtService jwtService;
    private final Clock clock;

    /**
     * 소셜 로그인 성공 시 사용자를 찾거나 만든다.
     * 조회 키는 {@code (provider, providerId)} 쌍이다.
     */
    @Transactional
    public User loginOrRegister(OAuth2UserInfo userInfo) {
        if (userInfo.providerId() == null || userInfo.providerId().isBlank()) {
            throw new ApiException(ResponseCode.OAUTH2_FAILED, "프로바이더가 식별자를 주지 않았습니다.");
        }

        return userStore.findByProviderAndProviderId(userInfo.provider(), userInfo.providerId())
                .map(existing -> {
                    if (!existing.isActive()) {
                        throw new ApiException(ResponseCode.FORBIDDEN, "탈퇴한 계정입니다.");
                    }
                    // 프로바이더 쪽에서 이름·이메일을 바꿨을 수 있으므로 로그인마다 갱신한다.
                    existing.syncProfile(userInfo.email(), userInfo.name());
                    return userStore.save(existing);
                })
                .orElseGet(() -> {
                    User created = new User(
                            userInfo.provider(), userInfo.providerId(), userInfo.email(), userInfo.name());
                    log.info("신규 사용자 등록: provider={}, providerId={}",
                            userInfo.provider(), userInfo.providerId());
                    return userStore.save(created);
                });
    }

    /**
     * 액세스·리프레시 토큰을 발급하고 리프레시는 해시로 저장한다.
     * 사용자당 한 행만 유지하므로 재로그인해도 행이 쌓이지 않는다.
     */
    @Transactional
    public TokenPair issueTokens(User user) {
        String accessToken = jwtService.createAccessToken(user);
        String refreshToken = jwtService.createRefreshToken(user);

        refreshTokenStore.store(user.id(), JwtService.hash(refreshToken), jwtService.refreshTokenExpiresAt());
        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * 리프레시 토큰 회전.
     *
     * <p>제출된 토큰이 저장된 해시와 일치해야 한다. 이미 회전되어 저장소에 없는
     * 옛 토큰을 내밀면 거부된다.
     */
    @Transactional
    public TokenPair refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ResponseCode.INVALID_TOKEN, "리프레시 토큰이 없습니다.");
        }

        Long userId = jwtService.parseRefreshTokenSubject(refreshToken);

        RefreshTokenStore.StoredRefreshToken stored = refreshTokenStore.findByUserId(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.INVALID_TOKEN, "저장된 리프레시 토큰이 없습니다."));

        if (!stored.matches(JwtService.hash(refreshToken))) {
            // 이미 회전된 옛 토큰이거나 위조된 토큰이다.
            // 탈취 가능성이 있으므로 저장된 토큰을 지워 재로그인을 강제한다.
            log.warn("리프레시 토큰 불일치. 저장된 토큰을 폐기한다: userId={}", userId);
            refreshTokenStore.deleteByUserId(userId);
            throw new ApiException(ResponseCode.INVALID_TOKEN);
        }
        if (stored.isExpired(LocalDateTime.now(clock))) {
            refreshTokenStore.deleteByUserId(userId);
            throw new ApiException(ResponseCode.EXPIRED_TOKEN);
        }

        User user = userStore.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.INVALID_TOKEN));
        if (!user.isActive()) {
            throw new ApiException(ResponseCode.FORBIDDEN, "탈퇴한 계정입니다.");
        }
        return issueTokens(user);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenStore.deleteByUserId(userId);
    }

    @Transactional(readOnly = true)
    public User getById(Long userId) {
        return userStore.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.UNAUTHORIZED));
    }

    public record TokenPair(String accessToken, String refreshToken) {
    }
}
