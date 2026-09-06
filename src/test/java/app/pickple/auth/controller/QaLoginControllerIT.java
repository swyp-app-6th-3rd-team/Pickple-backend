package app.pickple.auth.controller;

import app.pickple.auth.domain.RefreshTokenStore;
import app.pickple.auth.service.JwtService;
import app.pickple.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@ActiveProfiles({"test", "dev"})
@SpringBootTest
@Transactional
class QaLoginControllerIT {

    private static final String LOGIN_ID = "qa-user";
    private static final String PASSWORD = "qa-test-password";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(10).encode(PASSWORD);

    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefreshTokenStore refreshTokenStore;
    @Autowired private JwtService jwtService;

    private MockMvc mvc;

    @DynamicPropertySource
    static void qaLoginProperties(DynamicPropertyRegistry registry) {
        registry.add("app.auth.qa-login.enabled", () -> true);
        registry.add("app.auth.qa-login.login-id", () -> LOGIN_ID);
        registry.add("app.auth.qa-login.password-hash", () -> PASSWORD_HASH);
        registry.add("app.auth.qa-login.user-id", () -> 11701L);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        jdbc.update("""
                INSERT INTO users (id, provider, provider_id, name, role, state, created_at, updated_at)
                VALUES (11701, 'KAKAO', 'qa-login-it', 'QA', 'ROLE_USER', 'ACTIVE', NOW(), NOW())
                """);
    }

    @Test
    void logsInWithConfiguredCredentialsAndUsesExistingJwtFlow() throws Exception {
        MvcResult result = login();
        String access = token(result, "accessToken");
        String refresh = token(result, "refreshToken");

        assertThat(jwtService.parseAccessToken(access).userId()).isEqualTo(11701L);
        assertThat(jwtService.parseRefreshTokenSubject(refresh)).isEqualTo(11701L);
        assertThat(refreshTokenStore.findByUserId(11701L).orElseThrow().tokenHash())
                .isEqualTo(JwtService.hash(refresh)).isNotEqualTo(refresh);
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.userId").value(11701));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"loginId\":\"wrong-user\",\"password\":\"qa-test-password\"}",
            "{\"loginId\":\"qa-user\",\"password\":\"wrong-password\"}"
    })
    void rejectsWrongCredentialsWithoutIssuingRefreshToken(String body) throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(refreshTokenStore.findByUserId(11701L)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"loginId\":\"\",\"password\":\"x\"}",
            "{\"loginId\":\"invalid id\",\"password\":\"x\"}", "{\"loginId\":"})
    void rejectsInvalidRequestBody(String body) throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void documentsQaLoginOnlyWhenEnabled() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/auth/login'].post").exists());
    }

    private MvcResult login() throws Exception {
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + LOGIN_ID + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.returnObject.refreshToken").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andReturn();
    }

    private static String token(MvcResult result, String field) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.returnObject." + field);
    }
}
