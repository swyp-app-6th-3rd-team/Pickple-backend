package app.pickple.config;

import app.pickple.auth.controller.QaLoginController;
import app.pickple.auth.domain.QaAccountStore;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.QaLoginService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class QaLoginConfigIT {
    @Test
    void applicationConfigurationEnablesLoginWithoutLegacyEnvironmentVariables() {
        runner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod", "QA_LOGIN_ENABLED=false",
                        "QA_LOGIN_MAX_ATTEMPTS_PER_MINUTE=1")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(QaLoginController.class)
                            .hasSingleBean(QaLoginService.class);
                });
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(MvcConfiguration.class, QaLoginConfig.class, QaLoginController.class)
                .withBean(UserStore.class, () -> mock(UserStore.class))
                .withBean(QaAccountStore.class, () -> mock(QaAccountStore.class))
                .withBean(AuthService.class, () -> mock(AuthService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "local", "dev", "prod", "production", "dev,prod"})
    void explicitFlagEnablesIndependentOfProfile(String profile) {
        runner().withPropertyValues("spring.profiles.active=" + profile, "app.auth.qa-login.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(QaLoginController.class)
                            .hasSingleBean(QaLoginService.class);
                    assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                            .anyMatch(mapping -> mapping.getPatternValues().contains("/auth/login"));
                    verifyNoInteractions(context.getBean(QaAccountStore.class));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "false", "yes", "1"})
    void missingOrNonTrueFlagDoesNotExposeLogin(String enabled) {
        var contextRunner = runner().withPropertyValues("spring.profiles.active=prod");
        if (!enabled.isEmpty()) contextRunner = contextRunner.withPropertyValues("app.auth.qa-login.enabled=" + enabled);
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(QaLoginController.class)
                    .doesNotHaveBean(QaLoginService.class);
            assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                    .noneMatch(mapping -> mapping.getPatternValues().contains("/auth/login"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {}
}
