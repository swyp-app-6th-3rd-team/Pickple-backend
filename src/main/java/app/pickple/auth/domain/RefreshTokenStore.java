package app.pickple.auth.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 리프레시 토큰 저장소.
 *
 * <p>토큰 <b>원문을 저장하지 않는다.</b> SHA-256 해시만 보관하므로 DB 가 유출되어도
 * 토큰을 그대로 재사용할 수 없다. 사용자당 한 행만 유지한다(재발급 시 갱신).
 */
public interface RefreshTokenStore {

    /** 사용자당 한 행을 유지한다. 이미 있으면 교체한다. */
    void store(Long userId, String tokenHash, LocalDateTime expiresAt);

    /**
     * 저장된 해시가 제출된 해시와 같을 때만 새 토큰으로 회전한다.
     * 동시 요청 중 먼저 성공한 한 건만 {@code true}를 받는다.
     */
    boolean rotateIfMatches(Long userId,
                            String expectedTokenHash,
                            String newTokenHash,
                            LocalDateTime newExpiresAt);

    Optional<StoredRefreshToken> findByUserId(Long userId);

    void deleteByUserId(Long userId);

    record StoredRefreshToken(Long userId, String tokenHash, LocalDateTime expiresAt) {

        public boolean isExpired(LocalDateTime now) {
            return expiresAt.isBefore(now);
        }

        public boolean matches(String candidateHash) {
            return tokenHash.equals(candidateHash);
        }
    }
}
