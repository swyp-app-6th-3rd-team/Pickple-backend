package app.pickple.auth.service;

import app.pickple.auth.domain.RefreshTokenStore;
import app.pickple.auth.domain.SocialIdentity;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
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
    private final RefreshTokenRevocationService refreshTokenRevocationService;

    /**
     * 소셜 로그인 성공 시 사용자를 찾거나 만든다.
     * 조회 키는 {@code (provider, providerId)} 쌍이다.
     */
    @Transactional
    public User loginOrRegister(SocialIdentity userInfo) {
        if (userInfo.providerId() == null || userInfo.providerId().isBlank()) {
            throw new ApiException(ResponseCode.OAUTH2_FAILED, "프로바이더가 식별자를 주지 않았습니다.");
        }

        return userStore.findByProviderAndProviderId(userInfo.provider(), userInfo.providerId())
                .map(existing -> {
                    if (!existing.isActive()) {
                        throw new ApiException(ResponseCode.FORBIDDEN, "탈퇴한 계정입니다.");
                    }
                    // 프로바이더 쪽에서 이름·이메일을 바꿨을 수 있으므로 로그인마다 갱신한다.
                    // Apple name은 ID token 클레임이 아니라 앱이 최초 동의 때 전달하는 값이므로
                    // 기존 사용자의 이름을 매 로그인마다 덮어쓰는 근거로 사용하지 않는다.
                    String nameToSync = userInfo.provider() == SocialProvider.APPLE
                            ? null
                            : userInfo.name();
                    existing.syncProfile(userInfo.email(), nameToSync);
                    return userStore.save(existing);
                })
                .orElseGet(() -> {
                    User created = new User(
                            userInfo.provider(), userInfo.providerId(), userInfo.email(), userInfo.name());
                    // providerId(Apple sub 포함)는 안정적인 개인 식별자이므로 로그에 남기지 않는다.
                    log.info("신규 사용자 등록: provider={}", userInfo.provider());
                    return userStore.save(created);
                });
    }

    /**
     * 액세스·리프레시 토큰을 발급하고 리프레시는 해시로 저장한다.
     * 사용자당 한 행만 유지하므로 재로그인해도 행이 쌓이지 않는다.
     */
    @Transactional
    public TokenPair issueTokens(User user) {
        TokenPair tokens = createTokenPair(user);
        refreshTokenStore.store(
                user.id(), JwtService.hash(tokens.refreshToken()), jwtService.refreshTokenExpiresAt());
        return tokens;
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

        String submittedHash = JwtService.hash(refreshToken);
        RefreshTokenStore.StoredRefreshToken stored = refreshTokenStore.findByUserId(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.INVALID_TOKEN, "저장된 리프레시 토큰이 없습니다."));

        if (!stored.matches(submittedHash)) {
            // 단일행 저장소만으로는 늦게 도착한 동시 요청과 실제 재사용 공격을 구분할 수 없다.
            // 현재 유효한 토큰을 지우면 정상 클라이언트까지 재로그인되므로 제출 요청만 거부한다.
            log.warn("리프레시 토큰 불일치. 현재 토큰은 보존한다: userId={}", userId);
            throw new ApiException(ResponseCode.INVALID_TOKEN);
        }
        if (stored.isExpired(LocalDateTime.now(clock))) {
            refreshTokenRevocationService.revokeAllForUser(userId);
            throw new ApiException(ResponseCode.EXPIRED_TOKEN);
        }

        User user = userStore.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.INVALID_TOKEN));
        if (!user.isActive()) {
            throw new ApiException(ResponseCode.FORBIDDEN, "탈퇴한 계정입니다.");
        }

        TokenPair rotated = createTokenPair(user);
        boolean wonRotation = refreshTokenStore.rotateIfMatches(
                userId,
                submittedHash,
                JwtService.hash(rotated.refreshToken()),
                jwtService.refreshTokenExpiresAt());
        if (!wonRotation) {
            // 다른 요청이 먼저 같은 토큰을 회전했다. 승자의 현재 토큰은 폐기하지 않는다.
            log.warn("리프레시 토큰 회전 경합. 현재 토큰은 보존한다: userId={}", userId);
            throw new ApiException(ResponseCode.INVALID_TOKEN);
        }
        return rotated;
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenStore.deleteByUserId(userId);
    }

    @Transactional(readOnly = true)
    public User getById(Long userId) {
        User user = userStore.findById(userId)
                .orElseThrow(() -> new ApiException(ResponseCode.UNAUTHORIZED));
        if (!user.isActive()) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        return user;
    }

    private TokenPair createTokenPair(User user) {
        return new TokenPair(
                jwtService.createAccessToken(user),
                jwtService.createRefreshToken(user));
    }

    public record TokenPair(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "TokenPair[redacted]";
        }
    }
}
