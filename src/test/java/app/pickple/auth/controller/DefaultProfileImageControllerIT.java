package app.pickple.auth.controller;

import app.pickple.auth.domain.AuthProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.support.IntegrationTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 제공 PNG를 HTTP로 제공하는 CDN 대역이다. 실제 AWS 접근 권한 검증은 별도다. */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DefaultProfileImageControllerIT {

    private static final HttpServer CDN = startCdn();
    private static final String BASE = "http://127.0.0.1:" + CDN.getAddress().getPort();

    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy security;
    @Autowired private UserStore users;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private RandomGenerator random;

    @DynamicPropertySource
    static void profileImages(DynamicPropertyRegistry registry) {
        registry.add("app.profile.default-image-urls", () -> "");
        registry.add("app.file.s3.public-base-url", () -> BASE);
    }

    @AfterAll
    static void stopCdn() {
        CDN.stop(0);
    }

    @Test
    void allDefaultUrlsReturnedByProfileApiServeTheProvidedPngAndRemainStable() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        byte[] expectedImage = Files.readAllBytes(Path.of("terraform/assets/default-profile.png"));
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int index = 0; index < 4; index++) {
                given(random.nextInt(4)).willReturn(index);
                String subject = UUID.randomUUID().toString();
                User user = users.save(new User(AuthProvider.APPLE, subject, null, "이미지검증"));
                String nickname = "p" + subject.replace("-", "").substring(0, 4);
                String bearer = "Bearer " + jwt.createAccessToken(user);
                String url = BASE + "/defaults/profile-" + (index + 1) + ".png";
                try {
                    mvc.perform(post("/users/profile").header("Authorization", bearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"nickname\":\"" + nickname + "\"}"))
                            .andExpect(status().isCreated())
                            .andExpect(jsonPath("$.returnObject.profileImageUrl").value(url));

                    var response = client.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
                    assertThat(response.body()).isEqualTo(expectedImage);
                    assertThat(ImageIO.read(new ByteArrayInputStream(response.body()))).isNotNull();

                    // 다시 발급한 토큰으로 재조회하고, 이미지 없는 수정 뒤에도 선택은 유지된다.
                    mvc.perform(get("/users/me").header("Authorization", "Bearer " + jwt.createAccessToken(user)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.returnObject.profileImageUrl").value(url));
                    mvc.perform(patch("/users/profile").header("Authorization", bearer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"nickname\":\"" + nickname + "\"}"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.returnObject.profileImageUrl").value(url));
                    assertThat(users.findById(user.id()).orElseThrow().profileImageUrl()).isEqualTo(url);
                } finally {
                    jdbc.update("DELETE FROM users WHERE id = ?", user.id());
                }
            }
        }
    }

    private static HttpServer startCdn() {
        try {
            byte[] png = Files.readAllBytes(Path.of("terraform/assets/default-profile.png"));
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/defaults/", exchange -> {
                try (exchange) {
                    if (!exchange.getRequestURI().getPath().matches("/defaults/profile-[1-4]\\.png")) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.sendResponseHeaders(200, png.length);
                    exchange.getResponseBody().write(png);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
