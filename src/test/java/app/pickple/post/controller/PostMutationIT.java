package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시글 수정·삭제 API (이슈 #31) 의 완료 판정을 실제 MySQL 로 확인한다 (PRD-025).
 *
 * <p>단위 테스트로는 잡히지 않는 것만 여기서 본다 — 스키마 밖의 키가 <b>정말</b> 바인딩되지 않는지,
 * 소프트 삭제 뒤 잠자던 {@code deleted_at IS NULL} 필터와 {@code ActivePostGuard} 가 실제로 깨어나는지,
 * 인가 경로(401·403·404)가 HTTP 로 어떻게 나가는지.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class PostMutationIT {

    /** 이 클래스만 쓰는 카테고리 — 재사용 컨테이너의 잔여 게시글과 목록을 분리한다. */
    private static final PostCategory OWN_CATEGORY = PostCategory.BEAUTY;

    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private UserStore userStore;
    @Autowired private PostStore postStore;
    @Autowired private ItemContainerStore containerStore;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private MockMvc mockMvc;
    private User author;
    private User other;
    private String authorToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        long seed = System.nanoTime();
        author = userStore.save(new User(SocialProvider.GOOGLE, "mut-author-" + seed, null, "글쓴이"));
        other = userStore.save(new User(SocialProvider.GOOGLE, "mut-other-" + seed, null, "남"));
        authorToken = jwtService.createAccessToken(author);
        otherToken = jwtService.createAccessToken(other);
    }

    @Test
    @DisplayName("미인증 요청은 401 이다")
    void rejectsUnauthenticated() throws Exception {
        Long postId = saveGeneralPost("제목").id();

        patchPost(postId, null, "{\"title\":\"로그인 없이\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        deletePost(postId, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("작성자가 아니면 수정·삭제 모두 403 이고 아무것도 바뀌지 않는다")
    void onlyAuthorCanEditOrDelete() throws Exception {
        Long postId = saveGeneralPost("원래 제목").id();

        patchPost(postId, otherToken, "{\"title\":\"남이 수정\",\"category\":\"LIVING\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        deletePost(postId, otherToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        flush();
        assertThat(column(postId, "title")).isEqualTo("원래 제목");
        assertThat(column(postId, "category")).isEqualTo(OWN_CATEGORY.name());
        assertThat(column(postId, "deleted_at")).isNull();
    }

    @Test
    @DisplayName("카테고리·제목·설명은 각각 수정되고 재조회에 반영된다. 빈 설명은 비운다")
    void editsCategoryTitleAndDescription() throws Exception {
        Long postId = saveGeneralPost("첫 제목").id();

        patchPost(postId, authorToken, "{\"category\":\"LIVING\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.postId").value(postId))
                .andExpect(jsonPath("$.returnObject.category").value("LIVING"))
                .andExpect(jsonPath("$.returnObject.title").value("첫 제목"));
        patchPost(postId, authorToken, "{\"title\":\"둘째 제목\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.title").value("둘째 제목"))
                .andExpect(jsonPath("$.returnObject.category").value("LIVING"));
        patchPost(postId, authorToken, "{\"description\":\"새 설명\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.description").value("새 설명"));

        flush();
        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.category").value("LIVING"))
                .andExpect(jsonPath("$.returnObject.title").value("둘째 제목"))
                .andExpect(jsonPath("$.returnObject.description").value("새 설명"))
                .andExpect(jsonPath("$.returnObject.type").value("GENERAL"));

        // 설명은 선택 입력이라 지우는 길이 있어야 한다 — 빈 문자열이 비움이다.
        patchPost(postId, authorToken, "{\"description\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.description").doesNotExist());
        flush();
        assertThat(column(postId, "description")).isNull();
        assertThat(column(postId, "title")).as("null 은 유지다").isEqualTo("둘째 제목");
    }

    @Test
    @DisplayName("유형과 상품 필드는 스키마 밖이라 보내도 바인딩되지 않는다 — 투표 0건이어도 (R-01·R-33)")
    void ignoresTypeAndProductFieldsOutsideSchema() throws Exception {
        Post post = saveAbPost("A냐 B냐");
        Long postId = post.id();
        assertThat(((Number) column(postId, "vote_count")).longValue())
                .as("투표 0건에서도 상품은 불변이어야 한다").isZero();

        // 편집 화면이 작성 폼을 재사용하므로 폼 전체가 올 수 있다. 200 이되 스키마 밖 값은 버려진다.
        patchPost(postId, authorToken, """
                {"type":"GENERAL","category":"FASHION","title":"새 주제","description":"새 설명",
                 "products":[{"name":"바꾼 상품","price":1,"linkUrl":"https://x","itemContainerId":1},
                             {"name":"바꾼 상품2","price":2,"linkUrl":"https://y","itemContainerId":2}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.type").value("A_B"))
                .andExpect(jsonPath("$.returnObject.title").value("새 주제"))
                .andExpect(jsonPath("$.returnObject.category").value("FASHION"));

        flush();
        assertThat(column(postId, "type")).isEqualTo("A_B");
        assertThat(jdbcTemplate.queryForList(
                "SELECT name FROM post_product WHERE post_id = ? ORDER BY display_order", String.class, postId))
                .containsExactly("A 상품", "B 상품");
        assertThat(jdbcTemplate.queryForList(
                "SELECT price FROM post_product WHERE post_id = ? ORDER BY display_order", Long.class, postId))
                .containsExactly(10_000L, 20_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_option WHERE post_id = ?", Long.class, postId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("찬반 게시글의 제목은 상품명이라 바꿀 수 없다. 같은 값을 다시 보내는 것은 변경이 아니다")
    void agreeTitleIsProductNameAndImmutable() throws Exception {
        Long postId = saveAgreePost("가방 살까").id();
        assertThat(column(postId, "title")).as("찬반의 제목은 상품명이다").isEqualTo("가방 살까");

        patchPost(postId, authorToken, "{\"title\":\"다른 상품명\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 상세로 채운 편집 폼은 상품명을 그대로 되돌려 보낸다 — 그 저장은 성공해야 한다 (멱등).
        patchPost(postId, authorToken, "{\"title\":\"가방 살까\",\"description\":\"설명만 바꿈\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.title").value("가방 살까"))
                .andExpect(jsonPath("$.returnObject.description").value("설명만 바꿈"));

        flush();
        assertThat(column(postId, "title")).isEqualTo("가방 살까");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM post_product WHERE post_id = ?", String.class, postId)).isEqualTo("가방 살까");
    }

    @Test
    @DisplayName("삭제는 소프트 삭제다 — 조회에서 사라지고 행·상품·선택지는 남으며 이미지는 재사용되지 않는다")
    void softDeleteHidesPostButKeepsRows() throws Exception {
        Post post = saveAgreePost("지울 글");
        Long postId = post.id();
        Long containerId = post.products().getFirst().itemContainerId();

        deletePost(postId, authorToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        flush();

        assertThat(column(postId, "deleted_at")).isNotNull();
        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/posts?category=" + OWN_CATEGORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content[?(@.id == %d)]".formatted(postId)).isEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_product WHERE post_id = ?", Long.class, postId)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_option WHERE post_id = ?", Long.class, postId)).isEqualTo(2L);
        assertThat(postStore.findAttachedItemContainerIds(Set.of(containerId)))
                .as("지운 글의 이미지는 다른 게시글에 재사용되지 않는다 (PR #76 리뷰)")
                .contains(containerId);
    }

    @Test
    @DisplayName("삭제된 게시글에 투표·댓글을 시도하면 기존 ActivePostGuard 가 거부한다")
    void deletedPostRejectsVoteAndComment() throws Exception {
        Long postId = saveAgreePost("투표 막힐 글").id();
        Long optionId = jdbcTemplate.queryForObject(
                "SELECT id FROM post_option WHERE post_id = ? AND display_order = 1", Long.class, postId);

        deletePost(postId, authorToken).andExpect(status().isOk());
        flush();

        mockMvc.perform(post("/posts/{id}/votes", postId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":" + optionId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/posts/{id}/comments", postId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"지운 글에 댓글\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vote WHERE post_id = ?", Long.class, postId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE post_id = ?", Long.class, postId)).isZero();
    }

    @Test
    @DisplayName("삭제된 글은 다시 수정·삭제할 수 없고, 남에게도 403 이 아니라 404 다")
    void deletedPostCannotBeEditedOrDeletedAgain() throws Exception {
        Long postId = saveGeneralPost("두 번 지울 글").id();
        deletePost(postId, authorToken).andExpect(status().isOk());
        flush();

        patchPost(postId, authorToken, "{\"title\":\"되살리기\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        deletePost(postId, authorToken)
                .andExpect(status().isNotFound());
        // 지운 글의 존재를 남에게 알리지 않는다 — 404 가 403 보다 먼저다.
        deletePost(postId, otherToken)
                .andExpect(status().isNotFound());

        flush();
        assertThat(column(postId, "title")).isEqualTo("두 번 지울 글");
        assertThat(column(postId, "deleted_at")).isNotNull();
    }

    // --- 픽스처 -------------------------------------------------------------

    private ResultActions patchPost(Long postId, String token, String body) throws Exception {
        MockHttpServletRequestBuilder request = patch("/posts/{id}", postId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private ResultActions deletePost(Long postId, String token) throws Exception {
        MockHttpServletRequestBuilder request = delete("/posts/{id}", postId);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private Post saveGeneralPost(String title) {
        return postStore.save(new Post(author.id(), PostType.GENERAL, OWN_CATEGORY, title, "설명"));
    }

    private Post saveAgreePost(String productName) {
        // 작성 API 와 같이 찬반의 제목은 상품명이다 (PostService.resolveTitle).
        Post post = new Post(author.id(), PostType.AGREE, OWN_CATEGORY, productName, "설명")
                .addProduct(new PostProduct(newContainer("agree"), productName, 10_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));
        return postStore.save(post);
    }

    private Post saveAbPost(String topic) {
        Post post = new Post(author.id(), PostType.A_B, OWN_CATEGORY, topic, "설명")
                .addProduct(new PostProduct(newContainer("ab-a"), "A 상품", 10_000L, null, 1))
                .addProduct(new PostProduct(newContainer("ab-b"), "B 상품", 20_000L, null, 2))
                .addOption(PostOption.ofProductDisplayOrder(1, 1))
                .addOption(PostOption.ofProductDisplayOrder(2, 2));
        return postStore.save(post);
    }

    private Long newContainer(String tag) {
        long unique = System.nanoTime();
        ItemContainer container = new ItemContainer(author.id(), AttachType.PRODUCT)
                .add(new ItemResource(1024L, tag + ".jpg",
                        "product-images/%d/%d.jpg".formatted(author.id(), unique),
                        "https://cdn.test/" + tag + "-" + unique));
        return containerStore.save(container).id();
    }

    private Object column(Long postId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM post WHERE id = ?", Object.class, postId);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
