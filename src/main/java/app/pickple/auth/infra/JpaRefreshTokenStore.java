package app.pickple.auth.infra;

import app.pickple.auth.domain.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaRefreshTokenStore implements RefreshTokenStore {

    private final UserRefreshTokenRepository repository;
    private final Clock clock;

    /**
     * 사용자당 한 행을 유지한다. 이미 있으면 갱신, 없으면 삽입.
     * 로그인마다 INSERT 하면 행이 쌓여 어느 것이 유효한지 알 수 없게 된다.
     */
    @Override
    public void store(Long userId, String tokenHash, LocalDateTime expiresAt) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.findByUserId(userId)
                .ifPresentOrElse(
                        existing -> existing.rotate(tokenHash, expiresAt, now),
                        () -> repository.save(new UserRefreshTokenEntity(userId, tokenHash, expiresAt, now)));
    }

    @Override
    public boolean rotateIfMatches(Long userId,
                                   String expectedTokenHash,
                                   String newTokenHash,
                                   LocalDateTime newExpiresAt) {
        return repository.rotateIfMatches(
                userId, expectedTokenHash, newTokenHash, newExpiresAt, LocalDateTime.now(clock)) == 1;
    }

    @Override
    public Optional<StoredRefreshToken> findByUserId(Long userId) {
        return repository.findByUserId(userId)
                .map(e -> new StoredRefreshToken(e.getUserId(), e.getTokenHash(), e.getExpiresAt()));
    }

    @Override
    public void deleteByUserId(Long userId) {
        repository.deleteByUserId(userId);
    }
}
