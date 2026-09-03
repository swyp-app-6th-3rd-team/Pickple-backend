package app.pickple.auth.apple;

import app.pickple.config.AppleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class AppleConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AppleTestConfiguration.class);

    @Test
    void startsAppleBeansWithoutAKeyWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBean(AppleProperties.class).enabled()).isFalse();
            assertThat(context.getBean(AppleClientSecretProvider.class)).isNotNull();
            assertThat(context.getBean(AppleTokenClient.class)).isNotNull();
            assertThat(context.getBean(AppleTokenGateway.class)).isNotNull();
            assertThat(context.getBean(AppleIdTokenVerifier.class)).isNotNull();
        });
    }

    @Test
    void bindsAllRequiredConfigurationWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "app.oauth.apple.enabled=true",
                        "app.oauth.apple.team-id=TEAM",
                        "app.oauth.apple.key-id=KEY",
                        "app.oauth.apple.client-id=app.pickple.ios",
                        "app.oauth.apple.private-key-base64=base64-p8",
                        "app.oauth.apple.provider-token-encryption-keys="
                                + "k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "app.oauth.apple.provider-token-active-key-id=k1",
                        "app.oauth.apple.api-base-url=https://apple-proxy.example",
                        "app.oauth.apple.issuer=https://appleid.apple.com")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    AppleProperties properties = context.getBean(AppleProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.apiBaseUrl()).isEqualTo("https://apple-proxy.example");
                    assertThat(properties.issuer()).isEqualTo("https://appleid.apple.com");
                    assertThat(context.getBean(AppleProviderTokenCipher.class)).isNotNull();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppleProperties.class)
    @Import({AppleClientSecretProvider.class, AppleTokenClientConfiguration.class, AppleTokenGateway.class,
            AppleIdTokenVerifier.class, AppleProviderTokenCipher.class})
    static class AppleTestConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
