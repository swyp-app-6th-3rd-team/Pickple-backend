package app.pickple.auth.apple;

import app.pickple.auth.config.AppleProperties;
import app.pickple.auth.domain.AppleProviderTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AppleProviderTokenServiceTest {

    @Mock
    private AppleProviderTokenStore store;

    private AppleProviderTokenService service;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        AppleProperties properties = new AppleProperties(
                true, "TEAM", "KEY", "app.pickple.ios", "base64-p8", "k1=" + key, "k1",
                "https://appleid.apple.com", "https://appleid.apple.com/auth/token",
                "https://appleid.apple.com/auth/revoke", "https://appleid.apple.com/auth/keys",
                Duration.ofMinutes(10));
        service = new AppleProviderTokenService(store, new AppleProviderTokenCipher(properties));
    }

    @Test
    void storesOnlyCiphertextAndDecryptsStoredValue() {
        service.store(7L, "provider-refresh-token");

        ArgumentCaptor<String> ciphertext = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> iv = ArgumentCaptor.forClass(String.class);
        verify(store).store(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(1), ciphertext.capture(), iv.capture(),
                org.mockito.ArgumentMatchers.eq("k1"));
        assertThat(ciphertext.getValue()).doesNotContain("provider-refresh-token");

        org.mockito.BDDMockito.given(store.findByUserId(7L)).willReturn(Optional.of(
                new AppleProviderTokenStore.StoredAppleProviderToken(
                        7L, 1, ciphertext.getValue(), iv.getValue(), "k1", LocalDateTime.now())));
        assertThat(service.findDecryptedByUserId(7L)).contains("provider-refresh-token");
    }
}
