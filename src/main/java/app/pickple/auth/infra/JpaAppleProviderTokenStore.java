package app.pickple.auth.infra;

import app.pickple.auth.domain.AppleProviderTokenStore;
import app.pickple.auth.domain.AppleClientType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaAppleProviderTokenStore implements AppleProviderTokenStore {

    private final AppleProviderTokenRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public void store(Long userId,
                      AppleClientType clientType,
                      int encryptionFormatVersion,
                      String encryptedRefreshToken,
                      String encryptionIv,
                      String encryptionKeyId) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.findByUserIdAndClientType(userId, clientType)
                .ifPresentOrElse(
                        existing -> existing.rotate(
                                encryptionFormatVersion, encryptedRefreshToken, encryptionIv, encryptionKeyId, now),
                        () -> repository.save(new AppleProviderTokenEntity(
                                userId, clientType, encryptionFormatVersion,
                                encryptedRefreshToken, encryptionIv, encryptionKeyId, now)));
    }

    @Override
    public Optional<StoredAppleProviderToken> findByUserIdAndClientType(
            Long userId, AppleClientType clientType) {
        return repository.findByUserIdAndClientType(userId, clientType).map(this::toStored);
    }

    @Override
    public List<StoredAppleProviderToken> findAllByUserId(Long userId) {
        return repository.findAllByUserId(userId).stream().map(this::toStored).toList();
    }

    @Override
    public void deleteByUserId(Long userId) {
        repository.deleteAllByUserId(userId);
    }

    private StoredAppleProviderToken toStored(AppleProviderTokenEntity entity) {
        return new StoredAppleProviderToken(
                entity.getUserId(), entity.getClientType(), entity.getEncryptionFormatVersion(),
                entity.getEncryptedRefreshToken(), entity.getEncryptionIv(),
                entity.getEncryptionKeyId(), entity.getUpdatedAt());
    }
}
