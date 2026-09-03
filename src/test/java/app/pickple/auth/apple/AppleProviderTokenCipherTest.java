package app.pickple.auth.apple;

import app.pickple.config.AppleProperties;
import app.pickple.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppleProviderTokenCipherTest {

    @Test
    void encryptsWithRandomIvAndDecrypts() {
        AppleProviderTokenCipher cipher = new AppleProviderTokenCipher(properties("k1", keyring("k1", (byte) 1)));

        AppleProviderTokenCipher.EncryptedToken first = cipher.encrypt(7L, "provider-refresh-token");
        AppleProviderTokenCipher.EncryptedToken second = cipher.encrypt(7L, "provider-refresh-token");

        assertThat(first.ciphertext()).doesNotContain("provider-refresh-token");
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.decrypt(7L, first)).isEqualTo("provider-refresh-token");
    }

    @Test
    void rejectsCiphertextMovedToAnotherUserOrTampered() {
        AppleProviderTokenCipher cipher = new AppleProviderTokenCipher(properties("k1", keyring("k1", (byte) 1)));
        AppleProviderTokenCipher.EncryptedToken encrypted = cipher.encrypt(7L, "provider-refresh-token");

        assertThatThrownBy(() -> cipher.decrypt(8L, encrypted))
                .isInstanceOf(ApiException.class);

        byte[] tampered = Base64.getDecoder().decode(encrypted.ciphertext());
        tampered[0] ^= 1;
        AppleProviderTokenCipher.EncryptedToken modified = new AppleProviderTokenCipher.EncryptedToken(
                encrypted.formatVersion(), Base64.getEncoder().encodeToString(tampered),
                encrypted.iv(), encrypted.keyId());
        assertThatThrownBy(() -> cipher.decrypt(7L, modified))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void oldKeyRemainsDecryptableAfterActiveKeyRotation() {
        String oldOnly = keyring("k1", (byte) 1);
        AppleProviderTokenCipher oldCipher = new AppleProviderTokenCipher(properties("k1", oldOnly));
        AppleProviderTokenCipher.EncryptedToken encrypted = oldCipher.encrypt(7L, "old-token");

        String rotatedKeyring = keyring("k2", (byte) 2) + "," + oldOnly;
        AppleProviderTokenCipher rotatedCipher = new AppleProviderTokenCipher(properties("k2", rotatedKeyring));

        assertThat(rotatedCipher.decrypt(7L, encrypted)).isEqualTo("old-token");
        assertThat(rotatedCipher.encrypt(7L, "new-token").keyId()).isEqualTo("k2");
    }

    @Test
    void configurationToStringDoesNotExposeSecrets() {
        AppleProperties properties = properties("k1", keyring("k1", (byte) 1));

        assertThat(properties.toString()).isEqualTo("AppleProperties[redacted]");
        assertThat(properties.toString()).doesNotContain(properties.privateKeyBase64());
        assertThat(properties.toString()).doesNotContain(properties.providerTokenEncryptionKeys());
    }

    private static AppleProperties properties(String activeKeyId, String keyring) {
        return new AppleProperties(
                true, "TEAM", "KEY", "app.pickple.ios", "base64-p8",
                keyring, activeKeyId,
                "https://appleid.apple.com",
                "https://appleid.apple.com",
                "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10));
    }

    private static String keyring(String keyId, byte fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        return keyId + "=" + Base64.getEncoder().encodeToString(key);
    }
}
