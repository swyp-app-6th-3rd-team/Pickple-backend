package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleProviderTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Apple provider refresh token의 평문 수명을 암호화/복호화 호출 안으로 제한한다. */
@Service
@RequiredArgsConstructor
public class AppleProviderTokenService {

    private final AppleProviderTokenStore store;
    private final AppleProviderTokenCipher cipher;

    @Transactional
    public void store(Long userId, String refreshToken) {
        AppleProviderTokenCipher.EncryptedToken encrypted = cipher.encrypt(userId, refreshToken);
        store.store(userId, encrypted.formatVersion(),
                encrypted.ciphertext(), encrypted.iv(), encrypted.keyId());
    }

    @Transactional(readOnly = true)
    public Optional<String> findDecryptedByUserId(Long userId) {
        return store.findByUserId(userId)
                .map(stored -> cipher.decrypt(userId, new AppleProviderTokenCipher.EncryptedToken(
                        stored.encryptionFormatVersion(),
                        stored.encryptedRefreshToken(),
                        stored.encryptionIv(),
                        stored.encryptionKeyId())));
    }
}
