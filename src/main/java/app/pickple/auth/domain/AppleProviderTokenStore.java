package app.pickple.auth.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/** 암호화된 Apple provider refresh token 저장소. 평문 토큰은 이 경계를 통과하지 않는다. */
public interface AppleProviderTokenStore {

    void store(Long userId,
               int encryptionFormatVersion,
               String encryptedRefreshToken,
               String encryptionIv,
               String encryptionKeyId);

    Optional<StoredAppleProviderToken> findByUserId(Long userId);

    void deleteByUserId(Long userId);

    record StoredAppleProviderToken(
            Long userId,
            int encryptionFormatVersion,
            String encryptedRefreshToken,
            String encryptionIv,
            String encryptionKeyId,
            LocalDateTime updatedAt) {
    }
}
