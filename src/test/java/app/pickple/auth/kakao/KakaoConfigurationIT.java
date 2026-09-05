package app.pickple.auth.kakao;

import app.pickple.common.config.KakaoUnlinkClientConfig;
import app.pickple.config.KakaoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class KakaoConfigurationIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "app.oauth.kakao.native-app-key=native-app-key",
                    "app.oauth.kakao.admin-key=admin-key",
                    "app.oauth.kakao.api-base-url=https://kapi.kakao.test")
            .withUserConfiguration(KakaoClientTestConfiguration.class);

    @Test
    void bindsPropertiesAndCreatesHttpInterfaceBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(KakaoProperties.class);
            assertThat(context).hasSingleBean(KakaoUnlinkClient.class);
            assertThat(context).hasSingleBean(KakaoUnlinkGateway.class);

            KakaoProperties properties = context.getBean(KakaoProperties.class);
            assertThat(properties.nativeAppKey()).isEqualTo("native-app-key");
            assertThat(properties.adminKey()).isEqualTo("admin-key");
            assertThat(properties.issuer()).isEqualTo("https://kauth.kakao.com");
            assertThat(properties.jwkSetUri())
                    .isEqualTo("https://kauth.kakao.com/.well-known/jwks.json");
            assertThat(properties.apiBaseUrl()).isEqualTo("https://kapi.kakao.test");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KakaoProperties.class)
    @Import({KakaoUnlinkClientConfig.class, KakaoUnlinkGateway.class})
    static class KakaoClientTestConfiguration {
    }
}
