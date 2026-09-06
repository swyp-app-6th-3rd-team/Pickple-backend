package app.pickple.config;

import app.pickple.auth.controller.QaLoginController;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.QaLoginService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QaLoginConfigurationIT {

    private static String passwordHash;

    @BeforeAll
    static void createPasswordHash() {
        passwordHash = new BCryptPasswordEncoder(10).encode("qa-test-password");
    }

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(MvcConfiguration.class, QaLoginConfiguration.class, QaLoginController.class)
                .withBean(UserStore.class, () -> mock(UserStore.class))
                .withBean(AuthService.class, () -> mock(AuthService.class))
                .withPropertyValues(
                        "app.auth.qa-login.login-id=qa-user",
                        "app.auth.qa-login.password-hash=" + passwordHash,
                        "app.auth.qa-login.user-id=11701");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "default", "local", "test", "prod", "production",
            "dev,prod", "dev,production", "dev,prod,production", "prod,dev", "production,dev"})
    void neverRegistersOutsideDevOrWithProductionEvenWhenEnabled(String profiles) {
        runner().withPropertyValues("spring.profiles.active=" + profiles, "app.auth.qa-login.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean(QaLoginController.class)
                            .doesNotHaveBean(QaLoginService.class)
                            .doesNotHaveBean(QaLoginProperties.class);
                    assertNoQaEndpoint(context);
                    verifyNoInteractions(context.getBean(AuthService.class), context.getBean(UserStore.class));
                });
    }

    @Test
    void missingEnableFlagDoesNotRegisterInDev() {
        runner().withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(QaLoginController.class).doesNotHaveBean(QaLoginService.class);
            assertNoQaEndpoint(context);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "", "yes", "1"})
    void onlyExplicitTrueEnablesQaLogin(String enabled) {
        runner().withPropertyValues("spring.profiles.active=dev", "app.auth.qa-login.enabled=" + enabled)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean(QaLoginController.class).doesNotHaveBean(QaLoginService.class);
                    assertNoQaEndpoint(context);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "dev,local", "test,dev"})
    void registersOnlyForEnabledDev(String profiles) {
        runner().withPropertyValues("spring.profiles.active=" + profiles, "app.auth.qa-login.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(QaLoginController.class)
                            .hasSingleBean(QaLoginService.class).hasSingleBean(QaLoginProperties.class);
                    assertThat(context.getBean(QaLoginProperties.class).loginId()).isEqualTo("qa-user");
                    assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                            .anyMatch(mapping -> mapping.getPatternValues().contains("/auth/login"));
                });
    }

    @ParameterizedTest
    @CsvSource(value = {
            "login-id|",
            "login-id|invalid id",
            "password-hash|",
            "password-hash|not-bcrypt",
            "user-id|",
            "user-id|0",
            "user-id|-1"
    }, delimiter = '|', nullValues = "")
    void invalidEnabledConfigurationFailsStartup(String property, String value) {
        runner().withPropertyValues("spring.profiles.active=dev", "app.auth.qa-login.enabled=true",
                        "app.auth.qa-login." + property + "=" + (value == null ? "" : value))
                .run(context -> assertThat(context).hasFailed());
    }

    private static void assertNoQaEndpoint(WebApplicationContext context) throws Exception {
        assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                .noneMatch(mapping -> mapping.getPatternValues().contains("/auth/login"));
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"qa-user\",\"password\":\"qa-test-password\"}"))
                .andExpect(status().isNotFound());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {
    }
}
