package app.pickple.auth.apple;

import app.pickple.auth.config.AppleProperties;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Apple provider refresh token을 AES-256-GCM으로 암·복호화한다. */
@Component
public class AppleProviderTokenCipher {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_REFRESH_TOKEN_BYTES = 3_000;

    private final AppleProperties properties;
    private final SecureRandom secureRandom;
    private final Map<String, SecretKey> encryptionKeys;

    @Autowired
    public AppleProviderTokenCipher(AppleProperties properties) {
        this(properties, new SecureRandom());
    }

    AppleProviderTokenCipher(AppleProperties properties, SecureRandom secureRandom) {
        this.properties = properties;
        this.secureRandom = secureRandom;
        this.encryptionKeys = configuredKeys(properties.providerTokenEncryptionKeys());
    }

    public EncryptedToken encrypt(Long userId, String refreshToken) {
        requireUserId(userId);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ResponseCode.OAUTH2_FAILED);
        }
        byte[] plaintext = refreshToken.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_REFRESH_TOKEN_BYTES) {
            throw new ApiException(ResponseCode.OAUTH2_FAILED);
        }
        String activeKeyId = properties.providerTokenActiveKeyId();
        SecretKey key = requireEncryptionKey(activeKeyId);
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(userId, FORMAT_VERSION, activeKeyId));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedToken(
                    FORMAT_VERSION,
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv),
                    activeKeyId);
        } catch (GeneralSecurityException e) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 암호화에 실패했습니다.");
        }
    }

    public String decrypt(Long userId, EncryptedToken encryptedToken) {
        requireUserId(userId);
        if (encryptedToken == null
                || encryptedToken.ciphertext() == null
                || encryptedToken.iv() == null
                || encryptedToken.keyId() == null) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 암호문이 올바르지 않습니다.");
        }
        if (encryptedToken.formatVersion() != FORMAT_VERSION) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 암호화 형식을 사용할 수 없습니다.");
        }

        try {
            byte[] iv = Base64.getDecoder().decode(encryptedToken.iv());
            if (iv.length != IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("invalid iv length");
            }
            byte[] ciphertext = Base64.getDecoder().decode(encryptedToken.ciphertext());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireEncryptionKey(encryptedToken.keyId()),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(userId, encryptedToken.formatVersion(), encryptedToken.keyId()));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 무결성 검증에 실패했습니다.");
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 복호화에 실패했습니다.");
        }
    }

    private byte[] aad(Long userId, int formatVersion, String keyId) {
        return ("pickple|apple-provider-refresh-token|v" + formatVersion
                + "|userId=" + userId + "|keyId=" + keyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private SecretKey requireEncryptionKey(String keyId) {
        SecretKey key = encryptionKeys.get(keyId);
        if (key == null) {
            throw new ApiException(ResponseCode.APPLE_ACCOUNT_REVOCATION_UNAVAILABLE);
        }
        return key;
    }

    private static Map<String, SecretKey> configuredKeys(String value) {
        if (value == null || value.isBlank()
                || "not-configured".equalsIgnoreCase(value)
                || "CHANGE_ME".equalsIgnoreCase(value)) {
            return Map.of();
        }
        Map<String, SecretKey> keys = new HashMap<>();
        for (String entry : value.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalStateException("Apple provider token 암호화 keyring 형식이 올바르지 않습니다.");
            }
            String keyId = entry.substring(0, separator).strip();
            String encodedKey = entry.substring(separator + 1).strip();
            try {
                byte[] decoded = Base64.getDecoder().decode(encodedKey);
                if (decoded.length != 32 || keys.putIfAbsent(keyId, new SecretKeySpec(decoded, "AES")) != null) {
                    throw new IllegalStateException("Apple provider token 암호화 keyring이 올바르지 않습니다.");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Apple provider token 암호화 키 형식이 올바르지 않습니다.");
            }
        }
        return Map.copyOf(keys);
    }

    private static void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 사용자 식별자가 없습니다.");
        }
    }

    public record EncryptedToken(int formatVersion, String ciphertext, String iv, String keyId) {

        @Override
        public String toString() {
            return "EncryptedToken[redacted]";
        }
    }
}
