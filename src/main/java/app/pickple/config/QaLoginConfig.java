package app.pickple.config;

import app.pickple.auth.domain.UserStore;
import app.pickple.auth.domain.QaAccountStore;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.QaLoginService;
import app.pickple.auth.service.QaLoginRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.auth.qa-login", name = "enabled", havingValue = "true")
public class QaLoginConfig {

    @Bean
    public QaLoginRateLimiter qaLoginRateLimiter(Clock clock) {
        return new QaLoginRateLimiter(clock);
    }

    @Bean
    public QaLoginService qaLoginService(QaAccountStore qaAccountStore, UserStore userStore,
                                         AuthService authService) {
        return new QaLoginService(qaAccountStore, userStore, authService, new BCryptPasswordEncoder());
    }
}
