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
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MySQL 저장소·JWT·기존 SecurityConfig를 통과하는 QA 인증 시나리오. */
@IntegrationTest
@ActiveProfiles({"test", "dev"})
@SpringBootTest(properties = {
        "DEV_LOGIN_ENABLED=true",
        "DEV_LOGIN_KEY=test-only-qa-key-at-least-32-bytes-long",
        "DEV_LOGIN_ALLOWED_USER_IDS=11701,11702,11703,11704,11799"
})
@Transactional
class DevLoginControllerIT {

    private static final String KEY = "test-only-qa-key-at-least-32-bytes-long";

    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefreshTokenStore refreshTokenStore;
    @Autowired private JwtService jwtService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        for (long id = 11701; id <= 11705; id++) {
            jdbc.update("""
                    INSERT INTO users (id, provider, provider_id, name, role, state, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, 'QA', ?, ?, NOW(), NOW())
                    """, id, "qa-dev-login-it-" + id,
                    id == 11704 ? "ROLE_ADMIN" : "ROLE_USER", id == 11703 ? "INACTIVE" : "ACTIVE");
        }
    }

    @Test
    void twoQaUsersLoginAndReadTheirOwnIdentityWithRealJwt() throws Exception {
        for (long id : new long[]{11701, 11702}) {
            MvcResult result = login(id);
            String access = token(result, "accessToken");
            String refresh = token(result, "refreshToken");
            assertThat(jwtService.parseAccessToken(access).userId()).isEqualTo(id);
            assertThat(jwtService.parseRefreshTokenSubject(refresh)).isEqualTo(id);
            assertThat(refreshTokenStore.findByUserId(id).orElseThrow().tokenHash())
                    .isEqualTo(JwtService.hash(refresh)).isNotEqualTo(refresh);
            mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.userId").value(id))
                    .andExpect(jsonPath("$.returnObject.role").value("ROLE_USER"));
        }
    }

    @Test
    void refreshRotatesAndLogoutRevokesQaTokenWithoutChangingOtherUsers() throws Exception {
        MvcResult first = login(11701);
        String firstRefresh = token(first, "refreshToken");
        String otherRefresh = token(login(11702), "refreshToken");
        MvcResult rotated = mvc.perform(refresh(firstRefresh))
                .andExpect(status().isOk()).andReturn();
        String currentRefresh = token(rotated, "refreshToken");
        assertThat(currentRefresh).isNotEqualTo(firstRefresh);
        mvc.perform(refresh(firstRefresh)).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token(rotated, "accessToken")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.returnObject.userId").value(11701));
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token(rotated, "accessToken")))
                .andExpect(status().isOk());
        assertThat(refreshTokenStore.findByUserId(11701L)).isEmpty();
        mvc.perform(refresh(currentRefresh)).andExpect(status().isUnauthorized());
        mvc.perform(refresh(otherRefresh)).andExpect(status().isOk());
    }

    @Test
    void repeatLoginReplacesRefreshToken() throws Exception {
        String previous = token(login(11701), "refreshToken");
        String current = token(login(11701), "refreshToken");
        mvc.perform(refresh(previous)).andExpect(status().isUnauthorized());
        mvc.perform(refresh(current)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_refresh_token WHERE user_id = 11701", Long.class))
                .isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(longs = {11703, 11704, 11705, 11799})
    void rejectsWithdrawnAdministratorUnlistedAndMissingAccounts(long userId) throws Exception {
        mvc.perform(loginRequest(userId).header("X-QA-Login-Key", KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.returnObject").isEmpty());
        assertThat(refreshTokenStore.findByUserId(userId)).isEmpty();
    }

    @Test
    void rejectsMissingAndWrongKeyEvenWithExistingBearerToken() throws Exception {
        mvc.perform(loginRequest(11701)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(11701).header("X-QA-Login-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        assertThat(refreshTokenStore.findByUserId(11701L)).isEmpty();
        String access = token(login(11701), "accessToken");
        mvc.perform(loginRequest(11702).header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        assertThat(refreshTokenStore.findByUserId(11702L)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"userId\":null}", "{\"userId\":0}", "{\"userId\":-1}",
            "{\"userId\":\"abc\"}", "{\"userId\":"})
    void rejectsInvalidRequestBody(String body) throws Exception {
        mvc.perform(post("/auth/dev/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-QA-Login-Key", KEY).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void documentsQaLoginOnlyWhenEnabled() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/auth/dev/login'].post").exists());
    }

    private MvcResult login(long userId) throws Exception {
        return mvc.perform(loginRequest(userId).header("X-QA-Login-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.returnObject.refreshToken").isNotEmpty())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andReturn();
    }

    private static MockHttpServletRequestBuilder loginRequest(long userId) {
        return post("/auth/dev/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + userId + "}");
    }

    private static MockHttpServletRequestBuilder refresh(String token) {
        return post("/auth/mobile/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + token + "\"}");
    }

    private static String token(MvcResult result, String field) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.returnObject." + field);
    }
}
