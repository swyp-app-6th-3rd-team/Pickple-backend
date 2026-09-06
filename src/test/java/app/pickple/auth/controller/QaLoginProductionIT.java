package app.pickple.auth.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.QaLoginService;
import app.pickple.config.QaLoginProperties;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@ActiveProfiles({"test", "dev", "prod"})
@SpringBootTest
@Transactional
class QaLoginProductionIT {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(10).encode("qa-test-password");

    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private UserStore userStore;
    @Autowired private AuthService authService;

    @DynamicPropertySource
    static void qaLoginProperties(DynamicPropertyRegistry registry) {
        registry.add("app.auth.qa-login.enabled", () -> true);
        registry.add("app.auth.qa-login.login-id", () -> "qa-user");
        registry.add("app.auth.qa-login.password-hash", () -> PASSWORD_HASH);
        registry.add("app.auth.qa-login.user-id", () -> 11701L);
    }

    @Test
    void rejectsQaLoginWhenProductionProfileIsAlsoActive() throws Exception {
        assertThat(context.getBeansOfType(QaLoginController.class)).isEmpty();
        assertThat(context.getBeansOfType(QaLoginService.class)).isEmpty();
        assertThat(context.getBeansOfType(QaLoginProperties.class)).isEmpty();
        assertThat(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet())
                .noneMatch(mapping -> mapping.getPatternValues().contains("/auth/login"));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        String body = "{\"loginId\":\"qa-user\",\"password\":\"qa-test-password\"}";
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        User user = userStore.save(new User(SocialProvider.KAKAO, "qa-prod-gate-it", null, "QA"));
        String access = authService.issueTokens(user).accessToken();
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + access).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/auth/login']").doesNotExist());
    }
}
