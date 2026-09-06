package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleClientType;
import app.pickple.common.ResponseCode;
import app.pickple.config.AppleProperties;
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
    private static final int LEGACY_PROVIDER_FORMAT_VERSION = 1;
    private static final int PROVIDER_FORMAT_VERSION = 2;
    private static final int WEB_ATTEMPT_FORMAT_VERSION = 1;
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
        return encrypt(userId, AppleClientType.NATIVE, refreshToken);
    }

    public EncryptedToken encrypt(Long userId, AppleClientType clientType, String refreshToken) {
        requireUserId(userId);
        if (clientType == null) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token client 구분이 없습니다.");
        }
        return encrypt(refreshToken, PROVIDER_FORMAT_VERSION,
                providerAad(userId, clientType, PROVIDER_FORMAT_VERSION));
    }

    public String decrypt(Long userId, EncryptedToken encryptedToken) {
        return decrypt(userId, AppleClientType.NATIVE, encryptedToken);
    }

    public String decrypt(Long userId, AppleClientType clientType, EncryptedToken encryptedToken) {
        requireUserId(userId);
        if (clientType == null) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token client 구분이 없습니다.");
        }
        if (encryptedToken != null
                && encryptedToken.formatVersion() == LEGACY_PROVIDER_FORMAT_VERSION
                && clientType != AppleClientType.NATIVE) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR,
                    "기존 Apple provider token은 native client에서만 사용할 수 있습니다.");
        }
        byte[] aad = encryptedToken != null
                && encryptedToken.formatVersion() == LEGACY_PROVIDER_FORMAT_VERSION
                ? legacyProviderAad(userId)
                : providerAad(userId, clientType, PROVIDER_FORMAT_VERSION);
        return decrypt(encryptedToken, aad, LEGACY_PROVIDER_FORMAT_VERSION, PROVIDER_FORMAT_VERSION);
    }

    public EncryptedToken encryptWebAttempt(String stateHash, String refreshToken) {
        requireStateHash(stateHash);
        return encrypt(refreshToken, WEB_ATTEMPT_FORMAT_VERSION, webAttemptAad(stateHash));
    }

    public String decryptWebAttempt(String stateHash, EncryptedToken encryptedToken) {
        requireStateHash(stateHash);
        return decrypt(encryptedToken, webAttemptAad(stateHash), WEB_ATTEMPT_FORMAT_VERSION);
    }

    private EncryptedToken encrypt(String refreshToken, int formatVersion, byte[] aad) {
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
            cipher.updateAAD(withKeyId(aad, activeKeyId));
            return new EncryptedToken(formatVersion,
                    Base64.getEncoder().encodeToString(cipher.doFinal(plaintext)),
                    Base64.getEncoder().encodeToString(iv), activeKeyId);
        } catch (GeneralSecurityException e) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 암호화에 실패했습니다.");
        }
    }

    private String decrypt(EncryptedToken token, byte[] aad, int... allowedVersions) {
        validateEncryptedToken(token, allowedVersions);
        try {
            byte[] iv = Base64.getDecoder().decode(token.iv());
            if (iv.length != IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("invalid iv length");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireEncryptionKey(token.keyId()),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            cipher.updateAAD(withKeyId(aad, token.keyId()));
            byte[] ciphertext = Base64.getDecoder().decode(token.ciphertext());
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 무결성 검증에 실패했습니다.");
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 복호화에 실패했습니다.");
        }
    }

    private void validateEncryptedToken(EncryptedToken token, int... allowedVersions) {
        if (token == null || token.ciphertext() == null || token.iv() == null || token.keyId() == null) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 암호문이 올바르지 않습니다.");
        }
        for (int version : allowedVersions) {
            if (token.formatVersion() == version) {
                return;
            }
        }
        throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple provider token 암호화 형식을 사용할 수 없습니다.");
    }

    private byte[] legacyProviderAad(Long userId) {
        return ("pickple|apple-provider-refresh-token|v1|userId=" + userId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] providerAad(Long userId, AppleClientType clientType, int version) {
        return ("pickple|apple-provider-refresh-token|v" + version
                + "|userId=" + userId + "|clientType=" + clientType.name())
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] webAttemptAad(String stateHash) {
        return ("pickple|apple-web-attempt-refresh-token|v1|stateHash=" + stateHash)
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] withKeyId(byte[] aad, String keyId) {
        byte[] suffix = ("|keyId=" + keyId).getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[aad.length + suffix.length];
        System.arraycopy(aad, 0, result, 0, aad.length);
        System.arraycopy(suffix, 0, result, aad.length, suffix.length);
        return result;
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

    private static void requireStateHash(String stateHash) {
        if (stateHash == null || !stateHash.matches("[0-9a-f]{64}")) {
            throw new ApiException(ResponseCode.SYSTEM_ERROR, "Apple 웹 로그인 시도 식별자가 없습니다.");
        }
    }

    public record EncryptedToken(int formatVersion, String ciphertext, String iv, String keyId) {
        @Override
        public String toString() {
            return "EncryptedToken[redacted]";
        }
    }
}
