package app.pickple.config;

import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.DevLoginService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("dev & !prod & !production")
@ConditionalOnProperty(prefix = "app.auth.dev-login", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DevLoginProperties.class)
public class DevLoginConfiguration {

    // @Service 자동 탐색으로 다른 프로필에서 생성되지 않도록 이 설정에서만 등록한다.
    @Bean
    public DevLoginService devLoginService(DevLoginProperties properties,
                                          UserStore userStore, AuthService authService) {
        return new DevLoginService(properties, userStore, authService);
    }
}
