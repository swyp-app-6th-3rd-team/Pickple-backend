package app.pickple.auth.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AuthService;
import app.pickple.auth.service.DevLoginService;
import app.pickple.config.DevLoginProperties;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
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

/** dev까지 실수로 켰더라도 prod가 있으면 실제 앱의 HTTP·OpenAPI에서 제공하지 않는다. */
@IntegrationTest
@ActiveProfiles({"test", "dev", "prod"})
@SpringBootTest(properties = {
        "app.auth.dev-login.enabled=true",
        "app.auth.dev-login.key=test-only-qa-key-at-least-32-bytes-long",
        "app.auth.dev-login.allowed-user-ids=11701"
})
@Transactional
class DevLoginProductionIT {

    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private UserStore userStore;
    @Autowired private AuthService authService;

    @Test
    void rejectsQaLoginForAnonymousAndAuthenticatedUsersAndOmitsOpenApi() throws Exception {
        assertThat(context.getBeansOfType(DevLoginController.class)).isEmpty();
        assertThat(context.getBeansOfType(DevLoginService.class)).isEmpty();
        assertThat(context.getBeansOfType(DevLoginProperties.class)).isEmpty();
        assertThat(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet())
                .noneMatch(mapping -> mapping.getPatternValues().contains("/auth/dev/login"));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        mvc.perform(post("/auth/dev/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-QA-Login-Key", "test-only-qa-key-at-least-32-bytes-long")
                        .content("{\"userId\":11701}"))
                .andExpect(status().isUnauthorized());

        User user = userStore.save(new User(SocialProvider.GOOGLE, "qa-prod-gate-it", null, "QA"));
        String access = authService.issueTokens(user).accessToken();
        mvc.perform(post("/auth/dev/login").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + access)
                        .header("X-QA-Login-Key", "test-only-qa-key-at-least-32-bytes-long")
                        .content("{\"userId\":11701}"))
                .andExpect(status().isForbidden());
        // 기존 보호 API는 같은 토큰으로 계속 사용할 수 있다.
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/auth/dev/login']").doesNotExist());
    }
}
