package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleProviderTokenStore;
import app.pickple.auth.domain.AppleClientType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Apple provider refresh token의 평문 수명을 암호화/복호화 호출 안으로 제한한다. */
@Service
@RequiredArgsConstructor
public class AppleProviderTokenService {

    private final AppleProviderTokenStore store;
    private final AppleProviderTokenCipher cipher;

    @Transactional
    public void store(Long userId, String refreshToken) {
        store(userId, AppleClientType.NATIVE, refreshToken);
    }

    @Transactional
    public void store(Long userId, AppleClientType clientType, String refreshToken) {
        AppleProviderTokenCipher.EncryptedToken encrypted = cipher.encrypt(userId, clientType, refreshToken);
        store.store(userId, clientType, encrypted.formatVersion(),
                encrypted.ciphertext(), encrypted.iv(), encrypted.keyId());
    }

    @Transactional(readOnly = true)
    public Optional<String> findDecryptedByUserId(Long userId) {
        return store.findByUserIdAndClientType(userId, AppleClientType.NATIVE)
                .map(stored -> decrypt(userId, stored));
    }

    @Transactional(readOnly = true)
    public List<ClientToken> findAllDecryptedByUserId(Long userId) {
        return store.findAllByUserId(userId).stream()
                .map(stored -> new ClientToken(stored.clientType(), decrypt(userId, stored)))
                .toList();
    }

    private String decrypt(Long userId, AppleProviderTokenStore.StoredAppleProviderToken stored) {
        return cipher.decrypt(userId, stored.clientType(), new AppleProviderTokenCipher.EncryptedToken(
                        stored.encryptionFormatVersion(),
                        stored.encryptedRefreshToken(),
                        stored.encryptionIv(),
                        stored.encryptionKeyId()));
    }

    public record ClientToken(AppleClientType clientType, String refreshToken) {
        @Override
        public String toString() {
            return "ClientToken[redacted]";
        }
    }
}
