package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.controller.PostCreateRequest.ProductRequest;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.support.LocalStackConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이미지 업로드부터 게시글·상품·선택지 저장까지 실제 HTTP/DB 수직 경로를 검증한다. */
@IntegrationTest
@Import(LocalStackConfig.class)
class PostCreationFlowIT {

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
    private ItemContainerStore itemContainerStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private S3Client s3Client;

    private final List<Long> createdUserIds = new ArrayList<>();
    private MockMvc mockMvc;
    private Long userId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        ensureBucket();

        User user = createUser("작성자");
        userId = user.id();
        accessToken = jwtService.createAccessToken(user);
    }

    @AfterEach
    void cleanUp() {
        for (Long createdUserId : createdUserIds) {
            deleteUploadedObjects(createdUserId);
            jdbcTemplate.update("""
                    DELETE o FROM post_option o
                    INNER JOIN post p ON p.id = o.post_id
                    WHERE p.user_id = ?
                    """, createdUserId);
            jdbcTemplate.update("""
                    DELETE pp FROM post_product pp
                    INNER JOIN post p ON p.id = pp.post_id
                    WHERE p.user_id = ?
                    """, createdUserId);
            jdbcTemplate.update("DELETE FROM post WHERE user_id = ?", createdUserId);
            jdbcTemplate.update("""
                    DELETE r FROM item_resource r
                    INNER JOIN item_container c ON c.id = r.item_container_id
                    WHERE c.user_id = ?
                    """, createdUserId);
            jdbcTemplate.update("DELETE FROM item_container WHERE user_id = ?", createdUserId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", createdUserId);
        }
        createdUserIds.clear();
    }

    @Test
    @DisplayName("업로드한 이미지로 찬반 게시글을 만들고 상품명과 선택지를 저장한다")
    void createsAgreePostFromUploadedImage() throws Exception {
        long containerId = uploadImages(accessToken, 1);
        String longLink = "https://example.test/products/" + "a".repeat(70_000);

        MvcResult result = createPost(accessToken, request(
                PostType.AGREE,
                PostCategory.FASHION,
                "찬반에서는 사용하지 않는 값",
                "구매를 고민 중이에요",
                List.of(product(containerId, "검정 가방", 89_000L, longLink))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.returnObject.postId").isNumber())
                .andReturn();
        long postId = postId(result);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM post WHERE id = ?", String.class, postId))
                .isEqualTo("검정 가방");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT description FROM post WHERE id = ?", String.class, postId))
                .isEqualTo("구매를 고민 중이에요");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT item_container_id FROM post_product WHERE post_id = ?", Long.class, postId))
                .isEqualTo(containerId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT CHAR_LENGTH(link_url) FROM post_product WHERE post_id = ?", Integer.class, postId))
                .isEqualTo(longLink.length());
        assertThat(jdbcTemplate.queryForList(
                "SELECT label FROM post_option WHERE post_id = ? ORDER BY display_order", String.class, postId))
                .containsExactly("사자", "말자");
        assertThat(count("SELECT COUNT(*) FROM post_option WHERE post_id = ?", postId)).isEqualTo(2);
    }

    @Test
    @DisplayName("A/B 게시글의 두 선택지가 저장된 A와 B 상품을 각각 가리킨다")
    void createsAbPostWithProductOptions() throws Exception {
        long firstContainerId = uploadImages(accessToken, 1);
        long secondContainerId = uploadImages(accessToken, 1);

        MvcResult result = createPost(accessToken, request(
                PostType.A_B,
                PostCategory.BEAUTY,
                "봄 립 A vs B",
                null,
                List.of(
                        product(firstContainerId, "A 립", 15_000L, null),
                        product(secondContainerId, "B 립", null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andReturn();
        long postId = postId(result);

        List<Long> productIds = jdbcTemplate.queryForList(
                "SELECT id FROM post_product WHERE post_id = ? ORDER BY display_order", Long.class, postId);
        List<Long> optionTargets = jdbcTemplate.queryForList(
                "SELECT post_product_id FROM post_option WHERE post_id = ? ORDER BY display_order", Long.class, postId);
        assertThat(productIds).hasSize(2);
        assertThat(optionTargets).containsExactlyElementsOf(productIds);
        assertThat(count("SELECT COUNT(*) FROM post_option WHERE post_id = ? AND label IS NULL", postId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("일반 게시글은 상품과 선택지 없이 저장한다")
    void createsGeneralPostWithoutProductsOrOptions() throws Exception {
        MvcResult result = createPost(accessToken, request(
                PostType.GENERAL,
                PostCategory.LIVING,
                "같이 골라 주세요",
                "일반 게시글 본문",
                List.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andReturn();
        long postId = postId(result);

        assertThat(count("SELECT COUNT(*) FROM post_product WHERE post_id = ?", postId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM post_option WHERE post_id = ?", postId)).isZero();
    }

    @Test
    @DisplayName("유형별 상품 개수가 정확하지 않으면 게시글을 남기지 않는다")
    void rejectsWrongProductCountsWithoutPartialRows() throws Exception {
        long first = newContainer(userId, AttachType.PRODUCT, 1);
        long second = newContainer(userId, AttachType.PRODUCT, 1);

        expectInvalid(request(PostType.AGREE, PostCategory.ETC, null, null,
                List.of(product(first, "A", null, null), product(second, "B", null, null))));
        expectInvalid(request(PostType.A_B, PostCategory.ETC, "A vs B", null,
                List.of(product(first, "A", null, null))));
        expectInvalid(request(PostType.GENERAL, PostCategory.ETC, "일반", null,
                List.of(product(first, "불필요", null, null))));

        assertThat(postCount(userId)).isZero();
        assertThat(childCount("post_product", userId)).isZero();
        assertThat(childCount("post_option", userId)).isZero();
    }

    @Test
    @DisplayName("찬반은 사진 0장과 4장을 거부하고 3장은 허용한다")
    void enforcesAgreePhotoBoundaries() throws Exception {
        long empty = newContainer(userId, AttachType.PRODUCT, 0);
        long three = uploadImages(accessToken, 3);
        long four = uploadImages(accessToken, 4);

        expectInvalid(agreeRequest(empty, "사진 없음"));
        createPost(accessToken, agreeRequest(three, "사진 세 장"))
                .andExpect(status().isCreated());
        expectInvalid(agreeRequest(four, "사진 네 장"));

        assertThat(postCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("A/B 상품은 각각 사진이 정확히 한 장이어야 한다")
    void enforcesAbPhotoCount() throws Exception {
        long twoPhotos = uploadImages(accessToken, 2);
        long onePhoto = uploadImages(accessToken, 1);

        expectInvalid(request(
                PostType.A_B,
                PostCategory.ELECTRONICS,
                "노트북 A vs B",
                null,
                List.of(
                        product(twoPhotos, "A 노트북", null, null),
                        product(onePhoto, "B 노트북", null, null))));
        assertThat(postCount(userId)).isZero();
    }

    @Test
    @DisplayName("상품명·설명·가격의 최대 경계값은 허용한다")
    void acceptsMaximumFieldLimits() throws Exception {
        long containerId = newContainer(userId, AttachType.PRODUCT, 1);

        createPost(accessToken, request(
                PostType.AGREE,
                PostCategory.ETC,
                null,
                "나".repeat(300),
                List.of(product(containerId, "가".repeat(30), PostProduct.MAX_PRICE, null))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("상품명·설명·가격의 최대 경계값 초과는 400이다")
    void rejectsFieldLimitViolations() throws Exception {
        expectInvalid(agreeRequest(newContainer(userId, AttachType.PRODUCT, 1), "가".repeat(31)));
        expectInvalid(request(
                PostType.AGREE,
                PostCategory.ETC,
                null,
                "나".repeat(301),
                List.of(product(newContainer(userId, AttachType.PRODUCT, 1), "정상 상품", null, null))));
        expectInvalid(request(
                PostType.AGREE,
                PostCategory.ETC,
                null,
                null,
                List.of(product(
                        newContainer(userId, AttachType.PRODUCT, 1),
                        "정상 상품",
                        PostProduct.MAX_PRICE + 1,
                        null))));
        assertThat(postCount(userId)).isZero();
    }

    @Test
    @DisplayName("A/B 주제와 일반 제목은 필수이고 30자 이내다")
    void requiresConditionalTitles() throws Exception {
        long first = newContainer(userId, AttachType.PRODUCT, 1);
        long second = newContainer(userId, AttachType.PRODUCT, 1);

        expectBeanValidationFailure(request(
                PostType.A_B,
                PostCategory.ETC,
                "   ",
                null,
                List.of(product(first, "A", null, null), product(second, "B", null, null))));
        expectBeanValidationFailure(request(PostType.GENERAL, PostCategory.ETC, null, null, List.of()));
        expectBeanValidationFailure(request(
                PostType.GENERAL, PostCategory.ETC, "가".repeat(31), null, List.of()));

        createPost(accessToken, request(
                PostType.GENERAL, PostCategory.ETC, "가".repeat(30), null, List.of()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("미인증 사용자는 게시글을 작성할 수 없다")
    void rejectsUnauthenticatedRequest() throws Exception {
        createPost(null, request(
                PostType.GENERAL,
                PostCategory.ETC,
                "로그인이 필요해요",
                null,
                List.of()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(postCount(userId)).isZero();
    }

    @Test
    @DisplayName("없는·타인 소유·댓글용 이미지 컨테이너를 거부한다")
    void rejectsMissingForeignAndWrongTypeContainers() throws Exception {
        User other = createUser("다른 사용자");
        long foreign = newContainer(other.id(), AttachType.PRODUCT, 1);
        long comment = newContainer(userId, AttachType.COMMENT, 1);

        createPost(accessToken, agreeRequest(Long.MAX_VALUE, "없는 이미지"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        createPost(accessToken, agreeRequest(foreign, "타인 이미지"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        expectInvalid(agreeRequest(comment, "댓글 이미지"));
        assertThat(postCount(userId)).isZero();
    }

    @Test
    @DisplayName("이미 사용한 이미지 컨테이너는 다른 상품에 다시 붙일 수 없다")
    void rejectsReusedContainer() throws Exception {
        long containerId = newContainer(userId, AttachType.PRODUCT, 1);

        createPost(accessToken, agreeRequest(containerId, "첫 게시글"))
                .andExpect(status().isCreated());
        expectConflict(agreeRequest(containerId, "재사용 게시글"));

        assertThat(postCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("상품 링크는 형식을 제한하지 않고 텍스트로 저장한다")
    void storesProductLinkAsText() throws Exception {
        long containerId = newContainer(userId, AttachType.PRODUCT, 1);
        String linkText = "example.test/products/1";

        MvcResult result = createPost(accessToken, request(
                PostType.AGREE,
                PostCategory.ETC,
                null,
                null,
                List.of(product(containerId, "상품", null, linkText))))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT link_url FROM post_product WHERE post_id = ?", String.class, postId(result)))
                .isEqualTo(linkText);
    }

    @Test
    @DisplayName("알 수 없는 게시글 유형 JSON은 400으로 응답한다")
    void rejectsUnknownPostType() throws Exception {
        mockMvc.perform(post("/posts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"VS","category":"ETC","title":"잘못된 유형","products":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(postCount(userId)).isZero();
    }

    private User createUser(String name) {
        User user = userStore.save(new User(
                SocialProvider.GOOGLE,
                "post-create-" + UUID.randomUUID(),
                null,
                name));
        createdUserIds.add(user.id());
        return user;
    }

    private long newContainer(Long ownerId, AttachType attachType, int photoCount) {
        ItemContainer container = new ItemContainer(ownerId, attachType);
        for (int index = 0; index < photoCount; index++) {
            String suffix = UUID.randomUUID().toString();
            container.add(new ItemResource(
                    ONE_PIXEL_PNG.length,
                    "fixture-" + index + ".png",
                    "test-fixtures/" + suffix + ".png",
                    "https://images.test/" + suffix + ".png"));
        }
        return itemContainerStore.save(container).id();
    }

    private long uploadImages(String token, int imageCount) throws Exception {
        var request = multipart("/images")
                .param("attachType", "PRODUCT")
                .header("Authorization", "Bearer " + token);
        for (int index = 0; index < imageCount; index++) {
            request.file(new MockMultipartFile(
                    "images",
                    "product-" + index + ".png",
                    "image/png",
                    ONE_PIXEL_PNG));
        }

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return response.at("/returnObject/itemContainerId").asLong();
    }

    private ResultActions createPost(String token, PostCreateRequest request) throws Exception {
        MockHttpServletRequestBuilder builder = post("/posts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(builder);
    }

    private void expectInvalid(PostCreateRequest request) throws Exception {
        createPost(accessToken, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private void expectConflict(PostCreateRequest request) throws Exception {
        createPost(accessToken, request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_CONTAINER_ALREADY_IN_USE"));
    }

    private void expectBeanValidationFailure(PostCreateRequest request) throws Exception {
        createPost(accessToken, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentNotValidException.class));
    }

    private PostCreateRequest agreeRequest(long containerId, String productName) {
        return request(
                PostType.AGREE,
                PostCategory.ETC,
                null,
                null,
                List.of(product(containerId, productName, null, null)));
    }

    private PostCreateRequest request(
            PostType type,
            PostCategory category,
            String title,
            String description,
            List<ProductRequest> products
    ) {
        return new PostCreateRequest(type, category, title, description, products);
    }

    private ProductRequest product(Long containerId, String name, Long price, String linkUrl) {
        return new ProductRequest(containerId, name, price, linkUrl);
    }

    private long postId(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return response.at("/returnObject/postId").asLong();
    }

    private int postCount(Long authorId) {
        return count("SELECT COUNT(*) FROM post WHERE user_id = ?", authorId);
    }

    private int childCount(String table, Long authorId) {
        if (!table.equals("post_product") && !table.equals("post_option")) {
            throw new IllegalArgumentException("허용하지 않은 게시글 자식 테이블입니다: " + table);
        }
        return count("""
                SELECT COUNT(*) FROM %s child
                INNER JOIN post p ON p.id = child.post_id
                WHERE p.user_id = ?
                """.formatted(table), authorId);
    }

    private int count(String sql, Object... args) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return result == null ? 0 : result;
    }

    private void ensureBucket() {
        boolean exists = s3Client.listBuckets().buckets().stream()
                .anyMatch(bucket -> BUCKET.equals(bucket.name()));
        if (!exists) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    private void deleteUploadedObjects(Long ownerId) {
        String prefix = "product-images/" + ownerId + "/";
        for (S3Object object : s3Client.listObjectsV2(request -> request
                .bucket(BUCKET)
                .prefix(prefix)).contents()) {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(BUCKET)
                    .key(object.key())
                    .build());
        }
    }
}
