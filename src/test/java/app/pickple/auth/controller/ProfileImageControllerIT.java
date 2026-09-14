package app.pickple.auth.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.SocialIdentity;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.auth.service.AuthService;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.support.IntegrationTest;
import app.pickple.support.LocalStackConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** F01: HTTP → LocalStack → MySQL → 프로필 재조회. 실제 앱·실 AWS 검증과 구분한다. */
@IntegrationTest
@Import(LocalStackConfig.class)
class ProfileImageControllerIT {
    private static final String BUCKET = "pickple-image-upload-it";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy security;
    @Autowired private UserStore users;
    @Autowired private JwtService jwt;
    @Autowired private AuthService auth;
    @Autowired private ItemContainerStore containers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private S3Client s3;
    private final List<Long> userIds = new ArrayList<>();
    private MockMvc mvc;
    private User user;
    private String token;
    private String nickname;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        if (s3.listBuckets().buckets().stream().noneMatch(bucket -> BUCKET.equals(bucket.name()))) {
            s3.createBucket(request -> request.bucket(BUCKET));
        }
        user = newUser();
        token = jwt.createAccessToken(user);
        nickname = "p" + UUID.randomUUID().toString().substring(0, 4);
    }

    private User newUser() {
        User created = users.save(new User(SocialProvider.GOOGLE,
                "profile-image-" + UUID.randomUUID(), null, "이미지검증"));
        userIds.add(created.id());
        return created;
    }

    @AfterEach
    void cleanUp() {
        for (Long id : userIds) {
            for (AttachType type : AttachType.values()) {
                for (var object : s3.listObjectsV2(request -> request.bucket(BUCKET)
                        .prefix(type.keyPrefix() + "/" + id + "/")).contents()) {
                    s3.deleteObject(request -> request.bucket(BUCKET).key(object.key()));
                }
            }
            jdbc.update("DELETE FROM item_container WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("PROFILE 업로드·최초 등록·교체·생략 수정·재로그인이 같은 영속 URL을 사용한다")
    void registersAndReplacesProfilePhoto() throws Exception {
        String first = upload(token, "PROFILE");
        mvc.perform(post("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(nickname, first)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.returnObject.profileImageUrl").value(first));
        assertProfile(first, nickname, token);

        byte[] replacement = bluePng();
        String second = upload(token, "PROFILE", replacement);
        assertThat(second).isNotEqualTo(first);
        mvc.perform(patch("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(nickname, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.profileImageUrl").value(second));
        String changed = "q" + nickname.substring(1);
        mvc.perform(patch("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("nickname", changed))))
                .andExpect(status().isOk());
        // 외부 소셜 신원 검증 이후의 실제 공통 재로그인 경로로 프로필 보존을 확인한다.
        var login = auth.completeLogin(new TestIdentity(
                user.provider(), user.providerId(), null, "다시로그인"));
        assertProfile(second, changed, login.tokens().accessToken());

        String key = jdbc.queryForObject("SELECT item_key FROM item_resource WHERE access_url = ?",
                String.class, second);
        assertThat(key).startsWith("profile-images/" + user.id() + "/");
        var stored = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build());
        assertThat(stored.asByteArray()).containsExactly(replacement);
        assertThat(stored.response().contentType()).isEqualTo("image/png");

        String defaultUrl = "https://images.local.test/defaults/profile-1.png";
        mvc.perform(patch("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(changed, defaultUrl)))
                .andExpect(status().isOk());
        assertProfile(defaultUrl, changed, token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER_OWNER", "PRODUCT", "COMMENT", "UNKNOWN", "CASE", "MULTIPLE"})
    @DisplayName("허용하지 않는 URL은 등록·수정 모두 거부하고 기존 프로필을 보존한다")
    void rejectsUnusableImage(String kind) throws Exception {
        String own = upload(token, "PROFILE");
        String rejected = switch (kind) {
            case "OTHER_OWNER" -> upload(jwt.createAccessToken(newUser()), "PROFILE");
            case "PRODUCT", "COMMENT" -> upload(token, kind);
            case "CASE" -> own.replace("/profile-images/", "/PROFILE-IMAGES/");
            case "MULTIPLE" -> {
                String url = "http://images.local.test/profile-images/" + user.id() + "/multiple.png";
                containers.save(new ItemContainer(user.id(), AttachType.PROFILE)
                        .add(new ItemResource(10L, "a.png", "a-" + user.id(), url))
                        .add(new ItemResource(10L, "b.png", "b-" + user.id(), url + "b")));
                yield url;
            }
            default -> "https://unregistered.example/image.png";
        };
        mvc.perform(post("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(nickname, rejected)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(users.findById(user.id()).orElseThrow().nickname()).isNull();

        mvc.perform(post("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(nickname, own)))
                .andExpect(status().isCreated());
        mvc.perform(patch("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body("r" + nickname.substring(1), rejected)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertProfile(own, nickname, token);
    }

    @Test
    @DisplayName("닉네임 충돌로 사진 교체가 실패하면 이전 사진을 유지한다")
    void duplicateNicknameDoesNotReplacePhoto() throws Exception {
        String first = upload(token, "PROFILE");
        mvc.perform(post("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(nickname, first)))
                .andExpect(status().isCreated());
        User other = newUser();
        String taken = "t" + nickname.substring(1);
        mvc.perform(post("/users/profile").header("Authorization", bearer(jwt.createAccessToken(other)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("nickname", taken))))
                .andExpect(status().isCreated());
        mvc.perform(patch("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(taken, upload(token, "PROFILE"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NICKNAME_ALREADY_IN_USE"));
        assertProfile(first, nickname, token);
    }

    @Test
    @DisplayName("PROFILE의 다중 파일·형식 위조·용량 초과는 기존 사진과 업로드 행을 보존한다")
    void uploadFailurePreservesProfile() throws Exception {
        String first = upload(token, "PROFILE");
        mvc.perform(post("/users/profile").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(nickname, first)))
                .andExpect(status().isCreated());
        mvc.perform(multipart("/images").file(png()).file(png()).param("attachType", "PROFILE")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(multipart("/images")
                        .file(new MockMultipartFile("images", "fake.png", "image/png", new byte[] {1, 2, 3}))
                        .param("attachType", "PROFILE").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        mvc.perform(multipart("/images")
                        .file(new MockMultipartFile("images", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]))
                        .param("attachType", "PROFILE").header("Authorization", bearer(token)))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_container WHERE user_id = ?",
                Long.class, user.id())).isEqualTo(1L);
        assertThat(s3.listObjectsV2(request -> request.bucket(BUCKET)
                .prefix("profile-images/" + user.id() + "/")).contents()).hasSize(1);
        assertProfile(first, nickname, token);
    }

    private String upload(String accessToken, String type) throws Exception {
        return upload(accessToken, type, PNG);
    }

    private String upload(String accessToken, String type, byte[] bytes) throws Exception {
        MvcResult result = mvc.perform(multipart("/images")
                        .file(new MockMultipartFile("images", "profile.png", "image/png", bytes)).param("attachType", type)
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray())
                .at("/returnObject/images/0/accessUrl").asText();
    }

    private void assertProfile(String url, String expectedNickname, String accessToken) throws Exception {
        mvc.perform(get("/users/me").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.profileImageUrl").value(url))
                .andExpect(jsonPath("$.returnObject.nickname").value(expectedNickname));
        assertThat(users.findById(user.id()).orElseThrow().profileImageUrl()).isEqualTo(url);
    }

    private String body(String name, String url) throws Exception {
        return mapper.writeValueAsString(Map.of("nickname", name, "profileImageUrl", url));
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("images", "profile.png", "image/png", PNG);
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private byte[] bluePng() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x0000FF);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private record TestIdentity(SocialProvider provider, String providerId, String email, String name)
            implements SocialIdentity {
    }
}
