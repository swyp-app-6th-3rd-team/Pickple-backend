package app.pickple.config;

import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.QaLoginService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("dev & !prod & !production")
@ConditionalOnProperty(prefix = "app.auth.qa-login", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(QaLoginProperties.class)
public class QaLoginConfiguration {

    @Bean
    public QaLoginService qaLoginService(QaLoginProperties properties,
                                         UserStore userStore, AuthService authService) {
        return new QaLoginService(properties, userStore, authService, new BCryptPasswordEncoder());
    }
}
