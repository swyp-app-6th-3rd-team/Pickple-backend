package app.pickple.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Base64;

/** Apple 네이티브 로그인 서버 설정. */
@ConfigurationProperties(prefix = "app.oauth.apple")
public record AppleProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("not-configured") String teamId,
        @DefaultValue("not-configured") String keyId,
        @DefaultValue("not-configured") String clientId,
        @DefaultValue("not-configured") String privateKeyBase64,
        @DefaultValue("not-configured") String providerTokenEncryptionKeys,
        @DefaultValue("not-configured") String providerTokenActiveKeyId,
        @DefaultValue("https://appleid.apple.com") String apiBaseUrl,
        @DefaultValue("https://appleid.apple.com") String issuer,
        @DefaultValue("https://appleid.apple.com/auth/keys") String jwkSetUri,
        @DefaultValue("PT10M") Duration clientSecretValidity) {

    private static final Duration MAX_CLIENT_SECRET_VALIDITY = Duration.ofSeconds(15_777_000);

    public AppleProperties {
        if (enabled) {
            requireConfigured("team-id", teamId);
            requireConfigured("key-id", keyId);
            requireConfigured("client-id", clientId);
            requireConfigured("private-key-base64", privateKeyBase64);
            requireConfigured("provider-token-encryption-keys", providerTokenEncryptionKeys);
            requireConfigured("provider-token-active-key-id", providerTokenActiveKeyId);
            validateEncryptionKeys(providerTokenEncryptionKeys, providerTokenActiveKeyId);
        }
        if (clientSecretValidity == null || clientSecretValidity.isZero()
                || clientSecretValidity.isNegative()
                || clientSecretValidity.compareTo(MAX_CLIENT_SECRET_VALIDITY) > 0) {
            throw new IllegalStateException("Apple client secret 유효기간은 0초 초과, 15,777,000초 이하여야 합니다.");
        }
    }

    private static void requireConfigured(String name, String value) {
        if (value == null || value.isBlank()
                || "not-configured".equalsIgnoreCase(value)
                || "CHANGE_ME".equalsIgnoreCase(value)) {
            throw new IllegalStateException("Apple 로그인이 활성화됐지만 " + name + " 설정이 없습니다.");
        }
    }

    private static void validateEncryptionKeys(String value, String activeKeyId) {
        boolean activeFound = false;
        for (String entry : value.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalStateException("Apple provider token 암호화 keyring 형식이 올바르지 않습니다.");
            }
            String keyId = entry.substring(0, separator).strip();
            String encodedKey = entry.substring(separator + 1).strip();
            validateKeyId(keyId);
            try {
                if (Base64.getDecoder().decode(encodedKey).length != 32) {
                    throw new IllegalStateException("Apple provider token 암호화 키는 32바이트여야 합니다.");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Apple provider token 암호화 키는 Base64 형식이어야 합니다.");
            }
            activeFound |= keyId.equals(activeKeyId);
        }
        validateKeyId(activeKeyId);
        if (!activeFound) {
            throw new IllegalStateException("Apple provider token 활성 암호화 키가 keyring에 없습니다.");
        }
    }

    private static void validateKeyId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,50}")) {
            throw new IllegalStateException("Apple provider token 암호화 키 ID 형식이 올바르지 않습니다.");
        }
    }

    @Override
    public String toString() {
        return "AppleProperties[redacted]";
    }
}
