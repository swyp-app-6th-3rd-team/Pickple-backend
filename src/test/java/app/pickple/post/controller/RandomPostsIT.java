package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.common.CursorCodec;
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
import app.pickple.vote.service.VoteService;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import net.minidev.json.JSONArray;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 홈 랜덤 투표 카드 API(이슈 #22)의 완료 판정을 실제 MySQL로 확인한다. */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class RandomPostsIT {

    private static final String RANDOM_POSTS = "/posts/random";
    private static final String CONTENT = "$.returnObject.content";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private PostStore postStore;
    @Autowired
    private ItemContainerStore containerStore;
    @Autowired
    private VoteService voteService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MockMvc mockMvc;
    private User author;
    private long seed;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        seed = System.nanoTime();
        author = saveUser("random-author-" + seed, "글쓴이");
        hideExistingPosts();
    }

    @Test
    @DisplayName("게시글이 없으면 게스트에게 200과 빈 조각을 준다")
    void emptySliceForGuest() throws Exception {
        mockMvc.perform(get(RANDOM_POSTS).param("type", "AGREE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath(CONTENT).isEmpty())
                .andExpect(jsonPath("$.returnObject.hasNext").value(false))
                .andExpect(jsonPath("$.returnObject.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("찬반 카드는 일반·A/B·삭제 글을 빼고 최초 상품 사진과 선택지 둘을 준다")
    void agreeCardHasOneProductAndTwoOptions() throws Exception {
        saveGeneralPost("일반 글");
        saveAbPost("A/B 주제");
        Post deleted = saveAgreePost("삭제할 상품", "삭제 상품", 1);
        softDelete(deleted.id());
        Post agree = saveAgreePost("살까 말까", "찬반 상품", 3);
        flush();

        mockMvc.perform(get(RANDOM_POSTS).param("type", "AGREE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + ".length()").value(1))
                .andExpect(jsonPath(CONTENT + "[0].id").value(agree.id()))
                .andExpect(jsonPath(CONTENT + "[0].type").value("AGREE"))
                .andExpect(jsonPath(CONTENT + "[0].title").value("찬반 상품"))
                .andExpect(jsonPath(CONTENT + "[0].description").value("설명"))
                .andExpect(jsonPath(CONTENT + "[0].voterCount").value(0))
                .andExpect(jsonPath(CONTENT + "[0].selectedOptionId").doesNotExist())
                .andExpect(jsonPath(CONTENT + "[0].products.length()").value(1))
                .andExpect(jsonPath(CONTENT + "[0].products[0].name").value("찬반 상품"))
                .andExpect(jsonPath(CONTENT + "[0].products[0].displayOrder").value(1))
                .andExpect(jsonPath(CONTENT + "[0].products[0].imageUrl")
                        .value("https://cdn.test/agree-1-" + seed))
                .andExpect(jsonPath(CONTENT + "[0].options.length()").value(2))
                .andExpect(jsonPath(CONTENT + "[0].options[0].label").value("사자"))
                .andExpect(jsonPath(CONTENT + "[0].options[0].displayOrder").value(1))
                .andExpect(jsonPath(CONTENT + "[0].options[0].voteCount").doesNotExist())
                .andExpect(jsonPath(CONTENT + "[0].options[0].percentage").doesNotExist())
                .andExpect(jsonPath(CONTENT + "[0].options[1].label").value("말자"))
                .andExpect(jsonPath(CONTENT + "[0].options[1].displayOrder").value(2));
    }

    @Test
    @DisplayName("A/B 카드는 두 상품의 이름과 각 상품 최초 사진을 표시 순서대로 준다")
    void abCardHasBothProducts() throws Exception {
        Post post = saveAbPost("둘 중 뭐가 나아");
        List<Long> productIds = productIdsOf(post.id());
        flush();

        mockMvc.perform(get(RANDOM_POSTS).param("type", "A_B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + ".length()").value(1))
                .andExpect(jsonPath(CONTENT + "[0].id").value(post.id()))
                .andExpect(jsonPath(CONTENT + "[0].products.length()").value(2))
                .andExpect(jsonPath(CONTENT + "[0].products[0].productId").value(productIds.get(0)))
                .andExpect(jsonPath(CONTENT + "[0].products[0].name").value("A 상품"))
                .andExpect(jsonPath(CONTENT + "[0].products[0].imageUrl")
                        .value("https://cdn.test/ab-a-1-" + seed))
                .andExpect(jsonPath(CONTENT + "[0].products[1].productId").value(productIds.get(1)))
                .andExpect(jsonPath(CONTENT + "[0].products[1].name").value("B 상품"))
                .andExpect(jsonPath(CONTENT + "[0].products[1].imageUrl")
                        .value("https://cdn.test/ab-b-1-" + seed))
                .andExpect(jsonPath(CONTENT + "[0].options.length()").value(2))
                .andExpect(jsonPath(CONTENT + "[0].options[0].productId").value(productIds.get(0)))
                .andExpect(jsonPath(CONTENT + "[0].options[1].productId").value(productIds.get(1)));
    }

    @Test
    @DisplayName("투표한 사용자의 카드에만 선택과 득표 결과가 함께 내려온다")
    void exposesResultOnlyToParticipant() throws Exception {
        Post post = saveAgreePost("결과 확인", "결과 상품", 1);
        List<Long> optionIds = optionIdsOf(post.id());
        User viewer = saveUser("random-viewer-" + seed, "조회자");
        User second = saveUser("random-second-" + seed, "둘째");
        User third = saveUser("random-third-" + seed, "셋째");

        voteService.castOrChange(post.id(), optionIds.get(0), viewer.id());
        voteService.castOrChange(post.id(), optionIds.get(0), second.id());
        voteService.castOrChange(post.id(), optionIds.get(1), third.id());
        flush();

        mockMvc.perform(get(RANDOM_POSTS)
                        .param("type", "AGREE")
                        .header("Authorization", bearer(jwtService.createAccessToken(viewer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + "[0].voterCount").value(3))
                .andExpect(jsonPath(CONTENT + "[0].selectedOptionId").value(optionIds.get(0)))
                .andExpect(jsonPath(CONTENT + "[0].options[0].voteCount").value(2))
                .andExpect(jsonPath(CONTENT + "[0].options[0].percentage").value(67))
                .andExpect(jsonPath(CONTENT + "[0].options[1].voteCount").value(1))
                .andExpect(jsonPath(CONTENT + "[0].options[1].percentage").value(33));

        mockMvc.perform(get(RANDOM_POSTS).param("type", "AGREE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + "[0].selectedOptionId").doesNotExist())
                .andExpect(jsonPath(CONTENT + "[0].options[0].voteCount").doesNotExist())
                .andExpect(jsonPath(CONTENT + "[0].options[0].percentage").doesNotExist());

        mockMvc.perform(get(RANDOM_POSTS).param("type", "AGREE")
                        .header("Authorization", bearer(jwtService.createAccessToken(author))))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + "[0].voterCount").value(3))
                .andExpect(jsonPath(CONTENT + "[0].selectedOptionId").doesNotExist())
                .andExpect(jsonPath(CONTENT + "[0].options[*].voteCount").isEmpty())
                .andExpect(jsonPath(CONTENT + "[0].options[*].percentage").isEmpty());
    }

    @Test
    @DisplayName("A/B 카드에서 투표·선택 변경 후 재조회해도 선택과 득표율이 같다")
    void abVoteResponseMatchesReloadAfterChangingChoice() throws Exception {
        Post card = saveAbPost("A/B 투표");
        List<Long> options = optionIdsOf(card.id());
        String token = bearer(jwtService.createAccessToken(author));
        flush();

        for (int selectedIndex = 0; selectedIndex < options.size(); selectedIndex++) {
            Long optionId = options.get(selectedIndex);
            mockMvc.perform(post("/posts/{postId}/votes", card.id())
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"optionId\":" + optionId + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.selectedOptionId").value(optionId))
                    .andExpect(jsonPath("$.returnObject.voterCount").value(1))
                    .andExpect(jsonPath("$.returnObject.options[" + selectedIndex + "].optionId")
                            .value(optionId))
                    .andExpect(jsonPath("$.returnObject.options[" + selectedIndex + "].percentage").value(100))
                    .andExpect(jsonPath("$.returnObject.options[" + (1 - selectedIndex) + "].percentage").value(0));
            flush();
            mockMvc.perform(get(RANDOM_POSTS).param("type", "A_B").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(CONTENT + "[0].selectedOptionId").value(optionId))
                    .andExpect(jsonPath(CONTENT + "[0].voterCount").value(1))
                    .andExpect(jsonPath(CONTENT + "[0].options[" + selectedIndex + "].optionId")
                            .value(optionId))
                    .andExpect(jsonPath(CONTENT + "[0].options[" + selectedIndex + "].percentage").value(100))
                    .andExpect(jsonPath(CONTENT + "[0].options[" + (1 - selectedIndex) + "].percentage").value(0));
        }
    }

    @Test
    @DisplayName("탈퇴 전 토큰으로 조회하면 카드는 공개하되 내 투표 결과는 숨긴다")
    void withdrawnVoterIsReadAsGuest() throws Exception {
        Post card = saveAgreePost("탈퇴자 조회", "상품", 1);
        User viewer = saveUser("random-withdrawn-" + seed, "탈퇴자");
        String token = bearer(jwtService.createAccessToken(viewer));
        voteService.castOrChange(card.id(), optionIdsOf(card.id()).getFirst(), viewer.id());
        viewer.withdraw();
        userStore.save(viewer);
        flush();

        mockMvc.perform(get(RANDOM_POSTS).param("type", "AGREE").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + "[0].id").value(card.id()))
                .andExpect(jsonPath(CONTENT + "[0].voterCount").value(1))
                .andExpect(jsonPath(CONTENT + "[0].selectedOptionId").doesNotExist())
                .andExpect(jsonPath(CONTENT + "[0].options[*].voteCount").isEmpty())
                .andExpect(jsonPath(CONTENT + "[0].options[*].percentage").isEmpty());
    }

    @Test
    @DisplayName("유형 필터와 시드 커서로 끝까지 조회하면 중복·누락 카드가 없다")
    void scrollsFilteredTypeWithoutDuplicatesOrGaps() throws Exception {
        Set<Long> expected = new LinkedHashSet<>();
        for (int i = 0; i < 23; i++) {
            expected.add(saveAgreePost("찬반 " + i, "상품 " + i, 1).id());
        }
        Set<Long> expectedAb = new LinkedHashSet<>();
        for (int i = 0; i < 12; i++) {
            expectedAb.add(saveAbPost("A/B " + i).id());
        }
        saveGeneralPost("일반");
        flush();

        List<Long> actual = scrollAll(PostType.AGREE);

        assertThat(actual).hasSize(expected.size()).doesNotHaveDuplicates();
        assertThat(new LinkedHashSet<>(actual)).containsExactlyInAnyOrderElementsOf(expected);
        List<Long> actualAb = scrollAll(PostType.A_B);
        assertThat(actualAb).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expectedAb);
    }

    @Test
    @DisplayName("동일 커서를 재요청하면 같고 경계 게시글이 삭제돼도 다음 카드로 전진한다")
    void retriesCursorAndContinuesAfterBoundaryDeletion() throws Exception {
        for (int i = 0; i < 21; i++) {
            saveAgreePost("순회 " + i, "상품 " + i, 1);
        }
        flush();
        String first = read(PostType.AGREE, null);
        String cursor = JsonPath.read(first, "$.returnObject.nextCursor");
        String second = read(PostType.AGREE, cursor);
        assertThat(read(PostType.AGREE, cursor)).isEqualTo(second);

        Number boundaryId = JsonPath.read(first, CONTENT + "[9].id");
        softDelete(boundaryId.longValue());
        flush();
        assertThat(read(PostType.AGREE, cursor)).isEqualTo(second);
    }

    @Test
    @DisplayName("랜덤 키가 커서 경계와 같으면 게시글 id로 포함 여부를 가른다")
    void usesPostIdWhenRandomKeyEqualsBoundary() throws Exception {
        saveGeneralPost("id 경계 확보");
        Post card = saveAgreePost("동률", "동률 상품", 1);
        flush();
        Long key = jdbcTemplate.queryForObject(
                "SELECT CRC32(CONCAT('314:', CAST(? AS CHAR)))", Long.class, card.id());

        String beforeCard = CursorCodec.encode(ScrollPosition.forward(Map.of(
                "randomSeed", 314L, "postType", "AGREE", "randomKey", key, "id", card.id() - 1)));
        String atCard = CursorCodec.encode(ScrollPosition.forward(Map.of(
                "randomSeed", 314L, "postType", "AGREE", "randomKey", key, "id", card.id())));

        JSONArray included = JsonPath.read(read(PostType.AGREE, beforeCard), CONTENT + "[*].id");
        JSONArray excluded = JsonPath.read(read(PostType.AGREE, atCard), CONTENT);
        assertThat(included).extracting(id -> ((Number) id).longValue()).containsExactly(card.id());
        assertThat(excluded).isEmpty();
    }

    @Test
    @DisplayName("깨진·키 누락·소수 경계 커서는 HTTP 400으로 거부한다")
    void rejectsMalformedCursorOverHttp() throws Exception {
        String missing = CursorCodec.encode(ScrollPosition.forward(Map.of("randomSeed", 1L)));
        String fractional = CursorCodec.encode(ScrollPosition.forward(Map.of(
                "randomSeed", 1L, "postType", "AGREE", "randomKey", 3.7, "id", 20L)));
        for (String cursor : List.of("not-a-cursor", missing, fractional)) {
            mockMvc.perform(get(RANDOM_POSTS).param("type", "AGREE").param("cursor", cursor))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("찬반 커서를 A/B 조회에 재사용하면 400이다")
    void rejectsCursorFromAnotherType() throws Exception {
        for (int i = 0; i < 11; i++) {
            saveAgreePost("커서 " + i, "상품 " + i, 1);
        }
        saveAbPost("A/B");
        flush();

        String first = read(PostType.AGREE, null);
        assertThat((boolean) JsonPath.read(first, "$.returnObject.hasNext")).isTrue();
        String cursor = JsonPath.read(first, "$.returnObject.nextCursor");

        mockMvc.perform(get(RANDOM_POSTS)
                        .param("type", "A_B")
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("일반·알 수 없는·누락 유형은 400이다")
    void rejectsNonVotingOrMissingType() throws Exception {
        mockMvc.perform(get(RANDOM_POSTS).param("type", "GENERAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get(RANDOM_POSTS).param("type", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get(RANDOM_POSTS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("OpenAPI는 랜덤 카드 유형으로 AGREE와 A_B만 안내한다")
    void documentsOnlyVotingTypes() throws Exception {
        String openApi = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JSONArray allowedTypes = JsonPath.read(openApi,
                "$.paths['/posts/random'].get.parameters[?(@.name == 'type')].schema.enum[*]");
        assertThat(allowedTypes).containsExactly("AGREE", "A_B");

        JSONArray responseTypes = JsonPath.read(openApi,
                "$.components.schemas.RandomVoteCard.properties.type.enum[*]");
        assertThat(responseTypes).containsExactly("AGREE", "A_B");
    }

    @Test
    @DisplayName("카드 SQL 1회에 유형·삭제 필터를 적용하고 로그인은 계정 확인 1회만 더한다")
    void keepsCardQueryConstantAndFiltersInSql() throws Exception {
        User viewer = saveUser("random-query-viewer-" + seed, "조회자");
        for (int i = 0; i < 5; i++) {
            saveAbPost("쿼리 " + i);
        }
        flush();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            mockMvc.perform(get(RANDOM_POSTS).param("type", "A_B"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(CONTENT + ".length()").value(5));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
            assertThat(statistics.getQueries()).singleElement().satisfies(sql -> {
                String normalized = sql.replaceAll("\\s+", " ");
                assertThat(normalized).contains("WHERE p.deleted_at IS NULL AND p.type =");
            });

            statistics.clear();
            mockMvc.perform(get(RANDOM_POSTS).param("type", "A_B")
                            .header("Authorization", bearer(jwtService.createAccessToken(viewer))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(CONTENT + ".length()").value(5));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
            statistics.clear();
        }
    }

    // --- 커서 순회 ---------------------------------------------------------

    private List<Long> scrollAll(PostType type) throws Exception {
        List<Long> collected = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (true) {
            String body = read(type, cursor);
            JSONArray ids = JsonPath.read(body, CONTENT + "[*].id");
            ids.forEach(id -> collected.add(((Number) id).longValue()));

            JSONArray types = JsonPath.read(body, CONTENT + "[*].type");
            assertThat(types).allMatch(type.name()::equals);

            if (!(boolean) JsonPath.read(body, "$.returnObject.hasNext")) {
                assertThat(ids).hasSizeBetween(0, 10);
                Map<String, Object> response = JsonPath.read(body, "$.returnObject");
                assertThat(response.get("nextCursor")).isNull();
                break;
            }
            assertThat(ids).hasSize(10);
            cursor = JsonPath.read(body, "$.returnObject.nextCursor");
            assertThat(cursor).isNotBlank();
            assertThat(++guard).as("커서가 전진하지 않아 무한 반복이다").isLessThan(50);
        }
        return collected;
    }

    private String read(PostType type, String cursor) throws Exception {
        var request = get(RANDOM_POSTS).param("type", type.name());
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    // --- 테스트 격리 -------------------------------------------------------

    /** 재사용 컨테이너의 기존 글을 이 테스트 트랜잭션 안에서만 숨긴다. */
    private void hideExistingPosts() {
        jdbcTemplate.update("UPDATE post SET deleted_at = NOW() WHERE deleted_at IS NULL");
        flush();
    }

    private void softDelete(Long postId) {
        jdbcTemplate.update("UPDATE post SET deleted_at = NOW() WHERE id = ?", postId);
    }

    // --- 픽스처 ------------------------------------------------------------

    private User saveUser(String providerId, String name) {
        return userStore.save(new User(SocialProvider.GOOGLE, providerId, null, name));
    }

    private Post saveGeneralPost(String title) {
        return postStore.save(new Post(
                author.id(), PostType.GENERAL, PostCategory.ETC, title, "설명"));
    }

    private Post saveAgreePost(String title, String productName, int photoCount) {
        Post post = new Post(author.id(), PostType.AGREE, PostCategory.LIVING, title, "설명")
                .addProduct(new PostProduct(
                        newContainer("agree", photoCount), productName, 10_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));
        return postStore.save(post);
    }

    private Post saveAbPost(String title) {
        return postStore.save(new Post(author.id(), PostType.A_B, PostCategory.FASHION, title, "설명")
                .addProduct(new PostProduct(newContainer("ab-a", 1), "A 상품", 10_000L, null, 1))
                .addProduct(new PostProduct(newContainer("ab-b", 1), "B 상품", 20_000L, null, 2))
                .addOption(PostOption.ofProductDisplayOrder(1, 1))
                .addOption(PostOption.ofProductDisplayOrder(2, 2)));
    }

    private Long newContainer(String tag, int photoCount) {
        ItemContainer container = new ItemContainer(author.id(), AttachType.PRODUCT);
        long unique = System.nanoTime();
        for (int i = 1; i <= photoCount; i++) {
            container = container.add(new ItemResource(
                    1024L,
                    tag + "-" + i + ".jpg",
                    "product-images/%d/%d-%d.jpg".formatted(author.id(), unique, i),
                    "https://cdn.test/" + tag + "-" + i + "-" + seed));
        }
        return containerStore.save(container).id();
    }

    private List<Long> productIdsOf(Long postId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM post_product WHERE post_id = ? ORDER BY display_order",
                Long.class,
                postId);
    }

    private List<Long> optionIdsOf(Long postId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM post_option WHERE post_id = ? ORDER BY display_order",
                Long.class,
                postId);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
