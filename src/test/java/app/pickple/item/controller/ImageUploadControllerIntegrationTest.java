package app.pickple.item.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.support.IntegrationTest;
import app.pickple.support.LocalStackConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 HTTP 요청부터 LocalStack S3와 MySQL까지 이미지 업로드의 수직 경로를 검증한다.
 *
 * <p>Mock S3나 실제 AWS 계정은 사용하지 않는다. 테스트 트랜잭션으로 감싸지 않아
 * 컨트롤러 요청 안의 서비스 트랜잭션이 실제로 commit된 뒤 DB를 조회한다.
 */
@IntegrationTest
@Import(LocalStackConfig.class)
class ImageUploadControllerIntegrationTest {

    private static final String BUCKET = "pickple-image-upload-it";
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ItemContainerStore containerStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private S3Client s3Client;

    private MockMvc mockMvc;
    private Long userId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        ensureBucket();

        User user = userStore.save(new User(
                SocialProvider.GOOGLE,
                "image-upload-" + UUID.randomUUID(),
                null,
                "업로더"));
        userId = user.id();
        accessToken = jwtService.createAccessToken(user);
    }

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }

        String prefix = "product-images/" + userId + "/";
        for (S3Object object : s3Client.listObjectsV2(request -> request
                .bucket(BUCKET)
                .prefix(prefix)).contents()) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(BUCKET)
                    .key(object.key())
                    .build());
        }

        jdbcTemplate.update("""
                DELETE r FROM item_resource r
                INNER JOIN item_container c ON c.id = r.item_container_id
                WHERE c.user_id = ?
                """, userId);
        jdbcTemplate.update("DELETE FROM item_container WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("API 요청이 S3 객체와 item_resource 행을 함께 만든다")
    void uploadsThroughApiToS3AndDatabase() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "images", "상품 사진.png", "image/png", ONE_PIXEL_PNG);

        MvcResult result = mockMvc.perform(multipart("/api/images")
                        .file(image)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.returnObject.itemContainerId").isNumber())
                .andExpect(jsonPath("$.returnObject.images[0].resourceId").isNumber())
                .andExpect(jsonPath("$.returnObject.images[0].originalFileName").value("상품 사진.png"))
                .andExpect(jsonPath("$.returnObject.images[0].size").value(ONE_PIXEL_PNG.length))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        long containerId = response.at("/returnObject/itemContainerId").asLong();

        ItemContainer container = containerStore.findById(containerId).orElseThrow();
        assertThat(container.ownerId()).isEqualTo(userId);
        assertThat(container.attachType()).isEqualTo(AttachType.PRODUCT);
        assertThat(container.resources()).hasSize(1);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT size, original_file_name, item_key, access_url
                FROM item_resource
                WHERE item_container_id = ?
                """, containerId);
        String itemKey = (String) row.get("item_key");
        assertThat(((Number) row.get("size")).longValue()).isEqualTo(ONE_PIXEL_PNG.length);
        assertThat(row.get("original_file_name")).isEqualTo("상품 사진.png");
        assertThat(itemKey).startsWith("product-images/" + userId + "/").endsWith(".png");
        assertThat(row.get("access_url")).isEqualTo("http://images.local.test/" + itemKey);

        ResponseBytes<GetObjectResponse> stored = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(BUCKET).key(itemKey).build());
        assertThat(stored.asByteArray()).containsExactly(ONE_PIXEL_PNG);
        assertThat(stored.response().contentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("이미지가 아닌 Content-Type은 400으로 거부한다")
    void rejectsNonImageContentType() throws Exception {
        MockMultipartFile text = new MockMultipartFile(
                "images", "payload.txt", "text/plain", "not-an-image".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/images")
                        .file(text)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));

        assertNoStoredImage();
    }

    @Test
    @DisplayName("Content-Type과 실제 파일 시그니처가 다르면 400으로 거부한다")
    void rejectsMismatchedFileSignature() throws Exception {
        MockMultipartFile fakePng = new MockMultipartFile(
                "images", "fake.png", "image/png", "not-a-png".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/images")
                        .file(fakePng)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));

        assertNoStoredImage();
    }

    @Test
    @DisplayName("파일당 5MB를 넘으면 413으로 거부한다")
    void rejectsOversizedImage() throws Exception {
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(ONE_PIXEL_PNG, 0, oversized, 0, 8);
        MockMultipartFile image = new MockMultipartFile(
                "images", "large.png", "image/png", oversized);

        mockMvc.perform(multipart("/api/images")
                        .file(image)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));

        assertNoStoredImage();
    }

    @Test
    @DisplayName("인증 없이 업로드하면 401이다")
    void rejectsUnauthenticatedUpload() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "images", "product.png", "image/png", ONE_PIXEL_PNG);

        mockMvc.perform(multipart("/api/images").file(image))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertNoStoredImage();
    }

    private void ensureBucket() {
        boolean exists = s3Client.listBuckets().buckets().stream()
                .anyMatch(bucket -> BUCKET.equals(bucket.name()));
        if (!exists) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    private void assertNoStoredImage() {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM item_container WHERE user_id = ?", Integer.class, userId);
        assertThat(rows).isZero();
        assertThat(s3Client.listObjectsV2(request -> request
                        .bucket(BUCKET)
                        .prefix("product-images/" + userId + "/"))
                .keyCount()).isZero();
    }
}
