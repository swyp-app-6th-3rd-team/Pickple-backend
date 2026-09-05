package app.pickple.config;

import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 보안 필터와 생성된 OpenAPI가 같은 게스트 접근 정책을 따르는지 검증한다 (#100). */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class GuestAccessPolicyIT {

    private static final List<String> PUBLIC_READ_PATHS = List.of(
            "/posts", "/posts/popular", "/rankings", "/rankings/top");

    private static final List<Endpoint> PROTECTED_ENDPOINTS = List.of(
            new Endpoint(POST, "/posts"),
            new Endpoint(GET, "/posts/{postId}/comments"),
            new Endpoint(POST, "/posts/{postId}/comments"),
            new Endpoint(PATCH, "/comments/{id}"),
            new Endpoint(DELETE, "/comments/{id}"),
            new Endpoint(POST, "/comments/{commentId}/pick"),
            new Endpoint(POST, "/posts/{postId}/votes"),
            new Endpoint(POST, "/images"),
            new Endpoint(GET, "/users/me"),
            new Endpoint(POST, "/users/profile"),
            new Endpoint(PATCH, "/users/profile"),
            new Endpoint(GET, "/users/me/activities/summary"),
            new Endpoint(GET, "/users/me/activities"),
            new Endpoint(GET, "/users/me/posts/recent"),
            new Endpoint(GET, "/users/me/points"),
            new Endpoint(GET, "/users/me/grade"),
            new Endpoint(GET, "/grades"),
            new Endpoint(GET, "/users/me/badges"),
            new Endpoint(GET, "/users/me/badges/missions"),
            new Endpoint(GET, "/auth/me"),
            new Endpoint(DELETE, "/auth/me"));

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @ParameterizedTest
    @MethodSource("publicReadPaths")
    void guestCanReadPublicBusinessData(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/posts?category=ETC&sort=LATEST&size=10",
            "/posts?category=ETC&sort=POPULAR&size=10"
    })
    void guestCanFilterAndSortPosts(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @ParameterizedTest
    @MethodSource("publicReadPaths")
    void invalidTokenDoesNotBlockPublicBusinessData(String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @ParameterizedTest(name = "{0} requires authentication")
    @MethodSource("protectedEndpoints")
    void guestCannotAccessProtectedBusinessData(Endpoint endpoint) throws Exception {
        // 인증은 본문 검증과 데이터 조회보다 먼저 처리되어야 한다.
        mockMvc.perform(request(endpoint.method(), endpoint.path(), 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.returnObject").isEmpty());
    }

    @Test
    void openApiSecurityMatchesHttpAccessPolicy() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security").doesNotExist());

        for (String path : PUBLIC_READ_PATHS) {
            String operation = "$.paths['" + path + "'].get";
            result.andExpect(jsonPath(operation).exists())
                    .andExpect(jsonPath(operation + ".security").doesNotExist());
        }
        // 인증 시작·복구용 공개 API는 게스트 업무 조회와 별도 계약이다.
        for (String path : List.of("/auth/apple", "/auth/kakao", "/auth/refresh",
                "/auth/mobile/refresh", "/auth/logout")) {
            String operation = "$.paths['" + path + "'].post";
            result.andExpect(jsonPath(operation).exists())
                    .andExpect(jsonPath(operation + ".security").doesNotExist());
        }
        for (Endpoint endpoint : PROTECTED_ENDPOINTS) {
            String operation = "$.paths['" + endpoint.path() + "']."
                    + endpoint.method().name().toLowerCase(Locale.ROOT);
            result.andExpect(jsonPath(operation + ".security[0].bearerAuth").isArray());
        }
    }

    private static Stream<String> publicReadPaths() {
        return PUBLIC_READ_PATHS.stream();
    }

    private static Stream<Endpoint> protectedEndpoints() {
        return PROTECTED_ENDPOINTS.stream();
    }

    private record Endpoint(HttpMethod method, String path) {
    }
}
