package app.pickple.auth.controller;

import app.pickple.auth.domain.RefreshTokenStore;
import app.pickple.auth.service.JwtService;
import app.pickple.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@ActiveProfiles({"test", "prod"})
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
    @Autowired private EntityManager entityManager;

    private MockMvc mvc;
    private static final AtomicInteger CLIENTS = new AtomicInteger();
    private String clientIp;

    @BeforeEach
    void setUp() {
        clientIp = "192.0.2." + CLIENTS.incrementAndGet();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain)
                .defaultRequest(get("/").with(request -> { request.setRemoteAddr(clientIp); return request; }))
                .build();
        jdbc.update("""
                INSERT INTO users (id, provider, provider_id, name, role, state, created_at, updated_at)
                VALUES (11701, 'QA', 'qa-login-it', 'QA', 'ROLE_USER', 'ACTIVE', NOW(), NOW())
                """);
        jdbc.update("""
                INSERT INTO qa_account (login_id, password_hash, user_id, created_at, updated_at)
                VALUES (?, ?, 11701, NOW(), NOW())
                """, LOGIN_ID, PASSWORD_HASH);
    }

    @Test
    void logsInWithDefaultConfigurationInProdAndUsesExistingJwtFlow() throws Exception {
        MvcResult result = login();
        String access = token(result, "accessToken");
        String refresh = token(result, "refreshToken");

        assertThat(jwtService.parseAccessToken(access).userId()).isEqualTo(11701L);
        assertThat(jwtService.parseRefreshTokenSubject(refresh)).isEqualTo(11701L);
        assertThat(refreshTokenStore.findByUserId(11701L).orElseThrow().tokenHash())
                .isEqualTo(JwtService.hash(refresh)).isNotEqualTo(refresh);
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.userId").value(11701))
                .andExpect(jsonPath("$.returnObject.provider").value("QA"));
        mvc.perform(post("/auth/mobile/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.accessToken").isNotEmpty());
    }

    @Test
    void idsAreCaseSensitiveAndPersistAcrossRepeatedLogins() throws Exception {
        login();
        login();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM qa_account WHERE user_id = 11701", Integer.class))
                .isEqualTo(1);
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"QA-USER\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedFailuresAreLimitedWithoutBlockingOtherClients() throws Exception {
        jdbc.update("""
                INSERT INTO users (id, provider, provider_id, name, role, state, created_at, updated_at)
                VALUES (11702, 'QA', 'qa-tester-it', 'Tester', 'ROLE_USER', 'ACTIVE', NOW(), NOW())
                """);
        jdbc.update("""
                INSERT INTO qa_account (login_id, password_hash, user_id, created_at, updated_at)
                VALUES ('tester-user', ?, 11702, NOW(), NOW())
                """, PASSWORD_HASH);

        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginId\":\"tester-user\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"tester-user\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        assertThat(refreshTokenStore.findByUserId(11701L)).isEmpty();
        assertThat(refreshTokenStore.findByUserId(11702L)).isEmpty();

        String reviewAccess = token(login(), "accessToken");
        assertThat(jwtService.parseAccessToken(reviewAccess).userId()).isEqualTo(11701L);
        MvcResult testerLogin = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .with(request -> { request.setRemoteAddr("198.51.100." + CLIENTS.incrementAndGet()); return request; })
                        .content("{\"loginId\":\"tester-user\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(jwtService.parseAccessToken(token(testerLogin, "accessToken")).userId()).isEqualTo(11702L);
        assertThat(refreshTokenStore.findByUserId(11701L)).isPresent();
        assertThat(refreshTokenStore.findByUserId(11702L)).isPresent();
    }

    @Test
    void withdrawalDeletesCredentialsAndPreventsFurtherAccess() throws Exception {
        String access = token(login(), "accessToken");
        mvc.perform(delete("/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        // 테스트의 외부 트랜잭션 때문에 요청 종료 시 commit하지 않는다.
        // JDBC로 읽기 전에 운영의 commit에서 수행할 JPA 삭제를 DB에 반영한다.
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM qa_account WHERE user_id = 11701", Integer.class))
                .isZero();
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + LOGIN_ID + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cannotAttachCredentialsToSocialUser() throws Exception {
        jdbc.update("UPDATE users SET provider = 'KAKAO' WHERE id = 11701");
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + LOGIN_ID + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(refreshTokenStore.findByUserId(11701L)).isEmpty();
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
                .andExpect(jsonPath("$.paths['/auth/login'].post.description").value(
                        org.hamcrest.Matchers.containsString("429")));
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock qaLoginTestClock() {
            return Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }
}
