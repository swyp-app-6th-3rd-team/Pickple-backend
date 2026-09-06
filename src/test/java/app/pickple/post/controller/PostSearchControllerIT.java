package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class PostSearchControllerIT {

    private static final String SEARCH = "/posts/search";

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
        author = userStore.save(new User(
                SocialProvider.GOOGLE, "search-author-" + seed, null, "검색자"));
    }

    @Test
    @DisplayName("게스트가 일반 제목·찬반 상품명·A/B 주제와 양 상품명을 게시글 단위로 검색한다")
    void searchesEveryRequiredFieldWithoutDuplicates() throws Exception {
        String keyword = unique("공통");
        Post agree = saveAgree("복사된 옛 제목", keyword + "찬반", "agree");
        Post ab = saveAb(keyword + "주제", keyword + "A", keyword + "B");
        Post general = saveGeneral(keyword + "일반", "설명");
        stamp(agree.id(), 3);
        stamp(ab.id(), 2);
        stamp(general.id(), 1);
        flush();

        mockMvc.perform(get(SEARCH).param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject.totalCount").value(3))
                .andExpect(jsonPath("$.returnObject.content.length()").value(3))
                .andExpect(jsonPath("$.returnObject.hasNext").value(false))
                .andExpect(jsonPath("$.returnObject.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.returnObject.content[0].id").value(general.id()))
                .andExpect(jsonPath("$.returnObject.content[0].type").value("GENERAL"))
                .andExpect(jsonPath("$.returnObject.content[0].voteCount").doesNotExist())
                .andExpect(jsonPath("$.returnObject.content[0].thumbnailUrl").doesNotExist())
                .andExpect(jsonPath("$.returnObject.content[1].id").value(ab.id()))
                .andExpect(jsonPath("$.returnObject.content[1].title").value(keyword + "주제"))
                .andExpect(jsonPath("$.returnObject.content[1].thumbnailUrl")
                        .value("https://cdn.test/ab-a-" + seed))
                .andExpect(jsonPath("$.returnObject.content[2].id").value(agree.id()))
                .andExpect(jsonPath("$.returnObject.content[2].title").value(keyword + "찬반"))
                .andExpect(jsonPath("$.returnObject.content[2].thumbnailUrl")
                        .value("https://cdn.test/agree-" + seed));

        String bOnly = unique("비상품");
        Post bMatched = saveAb("출퇴근 주제", "다른 A", bOnly);
        flush();

        mockMvc.perform(get(SEARCH).param("keyword", bOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.totalCount").value(1))
                .andExpect(jsonPath("$.returnObject.content[0].id").value(bMatched.id()))
                .andExpect(jsonPath("$.returnObject.content[0].title").value("출퇴근 주제"));
    }

    @Test
    @DisplayName("설명과 삭제 글은 제외하고 LIKE 메타문자를 문자 그대로 검색한다")
    void excludesNonTargetsAndEscapesLikeMetacharacters() throws Exception {
        String descriptionOnly = unique("설명");
        saveGeneral("검색 대상 아님", descriptionOnly);
        Post deleted = saveGeneral(descriptionOnly + " 삭제", "설명");
        jdbcTemplate.update("UPDATE post SET deleted_at = NOW() WHERE id = ?", deleted.id());

        String literal = "%_!\\" + Long.toString(seed, 36).substring(0, 4);
        Post exact = saveGeneral("앞" + literal + "뒤", "설명");
        saveGeneral("앞XX" + literal.substring(4) + "뒤", "설명");
        flush();

        mockMvc.perform(get(SEARCH).param("keyword", descriptionOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.totalCount").value(0))
                .andExpect(jsonPath("$.returnObject.content").isEmpty());

        mockMvc.perform(get(SEARCH).param("keyword", literal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.totalCount").value(1))
                .andExpect(jsonPath("$.returnObject.content[0].id").value(exact.id()));

        mockMvc.perform(get(SEARCH).param("keyword", "' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.totalCount").value(0));
    }

    @Test
    @DisplayName("동일 초 25건을 10개씩 순회해도 중복·누락이 없고 전체 건수는 유지된다")
    void scrollsTiedRowsWithStableTotalCount() throws Exception {
        String keyword = unique("동률");
        List<Long> expected = new ArrayList<>();
        for (int index = 0; index < 25; index++) {
            expected.add(saveGeneral(keyword + index, "설명").id());
        }
        LocalDateTime tiedAt = LocalDateTime.of(2026, 9, 6, 12, 0);
        jdbcTemplate.update("UPDATE post SET created_at = ? WHERE title LIKE ?", tiedAt, keyword + "%");
        flush();

        List<Long> collected = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            var request = get(SEARCH).param("keyword", keyword);
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.totalCount").value(25))
                    .andReturn().getResponse().getContentAsString();
            JSONArray ids = JsonPath.read(body, "$.returnObject.content[*].id");
            pageSizes.add(ids.size());
            ids.forEach(id -> collected.add(((Number) id).longValue()));
            boolean hasNext = JsonPath.read(body, "$.returnObject.hasNext");
            cursor = hasNext ? JsonPath.read(body, "$.returnObject.nextCursor") : null;
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(10, 10, 5);
        assertThat(collected).doesNotHaveDuplicates();
        assertThat(collected).containsExactlyElementsOf(
                expected.stream().sorted(java.util.Comparator.reverseOrder()).toList());
    }

    @Test
    @DisplayName("다른 검색어의 커서와 잘못된 입력은 SQL 실행 전에 400이다")
    void rejectsInvalidRequestContext() throws Exception {
        String firstKeyword = unique("첫검색");
        for (int index = 0; index < 11; index++) {
            saveGeneral(firstKeyword + index, "설명");
        }
        flush();
        String firstBody = read(firstKeyword, null);
        String cursor = JsonPath.read(firstBody, "$.returnObject.nextCursor");

        mockMvc.perform(get(SEARCH).param("keyword", unique("다른검색")).param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(SEARCH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(SEARCH).param("keyword", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(SEARCH).param("keyword", "가".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get(SEARCH).param("keyword", firstKeyword).param("cursor", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("게스트 검색은 정확한 count와 페이지를 SQL 한 번에 조회한다")
    void usesOneStatementForGuestSearch() throws Exception {
        String keyword = unique("한쿼리");
        saveGeneral(keyword + "1", "설명");
        saveGeneral(keyword + "2", "설명");
        flush();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        mockMvc.perform(get(SEARCH).param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.totalCount").value(2));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("OpenAPI는 검색 응답 필드와 공개 접근을 실제 계약대로 노출한다")
    void documentsPublicSearchContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/posts/search'].get").exists())
                .andExpect(jsonPath("$.paths['/posts/search'].get.security").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.PostSearchResponse.properties.totalCount").exists())
                .andExpect(jsonPath("$.components.schemas.PostSearchItem.properties.thumbnailUrl").exists());
    }

    private Post saveGeneral(String title, String description) {
        return postStore.save(new Post(
                author.id(), PostType.GENERAL, PostCategory.ETC, title, description));
    }

    private Post saveAgree(String title, String productName, String imageTag) {
        return postStore.save(new Post(author.id(), PostType.AGREE, PostCategory.ETC, title, "설명")
                .addProduct(new PostProduct(newContainer(imageTag), productName, 10_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2)));
    }

    private Post saveAb(String title, String aName, String bName) {
        return postStore.save(new Post(author.id(), PostType.A_B, PostCategory.ETC, title, "설명")
                .addProduct(new PostProduct(newContainer("ab-a"), aName, 10_000L, null, 1))
                .addProduct(new PostProduct(newContainer("ab-b"), bName, 20_000L, null, 2))
                .addOption(PostOption.ofProductDisplayOrder(1, 1))
                .addOption(PostOption.ofProductDisplayOrder(2, 2)));
    }

    private Long newContainer(String tag) {
        ItemContainer container = new ItemContainer(author.id(), AttachType.PRODUCT)
                .add(new ItemResource(
                        1024L,
                        tag + ".jpg",
                        "product-images/" + author.id() + "/" + seed + "-" + tag + ".jpg",
                        "https://cdn.test/" + tag + "-" + seed));
        return containerStore.save(container).id();
    }

    private void stamp(Long postId, int minutesAgo) {
        jdbcTemplate.update("UPDATE post SET created_at = NOW() - INTERVAL ? MINUTE WHERE id = ?",
                minutesAgo, postId);
    }

    private String unique(String prefix) {
        return prefix + Long.toString(seed, 36).substring(0, 6);
    }

    private String read(String keyword, String cursor) throws Exception {
        var request = get(SEARCH).param("keyword", keyword);
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
