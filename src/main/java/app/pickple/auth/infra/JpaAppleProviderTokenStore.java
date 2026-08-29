package app.pickple.auth.infra;

import app.pickple.auth.domain.AppleProviderTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaAppleProviderTokenStore implements AppleProviderTokenStore {

    private final AppleProviderTokenRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public void store(Long userId,
                      int encryptionFormatVersion,
                      String encryptedRefreshToken,
                      String encryptionIv,
                      String encryptionKeyId) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.findById(userId)
                .ifPresentOrElse(
                        existing -> existing.rotate(
                                encryptionFormatVersion, encryptedRefreshToken, encryptionIv, encryptionKeyId, now),
                        () -> repository.save(new AppleProviderTokenEntity(
                                userId, encryptionFormatVersion,
                                encryptedRefreshToken, encryptionIv, encryptionKeyId, now)));
    }

    @Override
    public Optional<StoredAppleProviderToken> findByUserId(Long userId) {
        return repository.findById(userId)
                .map(entity -> new StoredAppleProviderToken(
                        entity.getUserId(),
                        entity.getEncryptionFormatVersion(),
                        entity.getEncryptedRefreshToken(),
                        entity.getEncryptionIv(),
                        entity.getEncryptionKeyId(),
                        entity.getUpdatedAt()));
    }

    @Override
    public void deleteByUserId(Long userId) {
        repository.deleteById(userId);
    }
}
