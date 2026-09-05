package app.pickple.config;

import app.pickple.auth.controller.DevLoginController;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.DevLoginService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DB 없이도 실제 컴포넌트 조건과 MVC 매핑 부재를 검증한다. */
class DevLoginConfigurationIT {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(MvcConfiguration.class, DevLoginConfiguration.class, DevLoginController.class)
            .withBean(UserStore.class, () -> mock(UserStore.class))
            .withBean(AuthService.class, () -> mock(AuthService.class))
            .withPropertyValues(
                    "app.auth.dev-login.key=test-only-qa-key-at-least-32-bytes-long",
                    "app.auth.dev-login.allowed-user-ids=11701,11702");

    @ParameterizedTest
    @ValueSource(strings = {"", "default", "local", "test", "prod", "production",
            "dev,prod", "dev,production", "dev,prod,production", "prod,dev", "production,dev"})
    void neverRegistersOutsideDevOrWithProductionEvenWhenEnabled(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles, "app.auth.dev-login.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean(DevLoginController.class)
                            .doesNotHaveBean(DevLoginService.class)
                            .doesNotHaveBean(DevLoginProperties.class);
                    assertNoDevEndpoint(context);
                    verifyNoInteractions(context.getBean(AuthService.class), context.getBean(UserStore.class));
                });
    }

    @Test
    void missingEnableFlagDoesNotRegisterInDev() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(DevLoginController.class).doesNotHaveBean(DevLoginService.class);
            assertNoDevEndpoint(context);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "", "yes", "1"})
    void onlyExplicitTrueEnablesDevLogin(String enabled) {
        runner.withPropertyValues("spring.profiles.active=dev", "app.auth.dev-login.enabled=" + enabled)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .doesNotHaveBean(DevLoginController.class).doesNotHaveBean(DevLoginService.class);
                    assertNoDevEndpoint(context);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "dev,local", "test,dev"})
    void registersOnlyForEnabledDevAndBindsQaAccountList(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles, "app.auth.dev-login.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DevLoginController.class)
                            .hasSingleBean(DevLoginService.class).hasSingleBean(DevLoginProperties.class);
                    assertThat(context.getBean(DevLoginProperties.class).allowedUserIds())
                            .containsExactlyInAnyOrder(11701L, 11702L);
                    assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                            .anyMatch(mapping -> mapping.getPatternValues().contains("/auth/dev/login"));
                });
    }

    @ParameterizedTest
    @CsvSource(value = {"key|", "key|short", "allowed-user-ids|", "allowed-user-ids|0", "allowed-user-ids|-1"},
            delimiter = '|', nullValues = "")
    void invalidEnabledConfigurationFailsStartup(String property, String value) {
        runner.withPropertyValues("spring.profiles.active=dev", "app.auth.dev-login.enabled=true",
                        "app.auth.dev-login." + property + "=" + (value == null ? "" : value))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void defaultApplicationConfigurationHasNoDevBeans() {
        new WebApplicationContextRunner()
                .withUserConfiguration(MvcConfiguration.class, DevLoginConfiguration.class, DevLoginController.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(DevLoginController.class).doesNotHaveBean(DevLoginService.class));
    }

    private static void assertNoDevEndpoint(WebApplicationContext context) throws Exception {
        assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                .noneMatch(mapping -> mapping.getPatternValues().contains("/auth/dev/login"));
        // 필터를 제외한 MVC에서도 404: 인증 실패가 실제 핸들러를 가리는 것이 아니다.
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/auth/dev/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-QA-Login-Key", "test-only-qa-key-at-least-32-bytes-long")
                        .content("{\"userId\":11701}"))
                .andExpect(status().isNotFound());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {
    }
}
