package app.pickple.badge.controller;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 뱃지 API (이슈 #27) 의 완료 판정을 실제 MySQL 로 확인한다.
 *
 * <p>일일·누적 판정은 투표 API 를 실제로 두들겨 확인한다 — 집계를 직접 넣어 확인하면
 * "투표가 집계를 올린다" 는 연결 자체가 검증되지 않는다. 연속 판정은 날짜를 여러 날
 * 걸쳐야 해서 {@code JpaDailyActivityStoreIT} 가 날짜를 직접 넘겨 확인한다.
 *
 * <p>{@code @Transactional} 을 붙이지 않는다. 투표 경로가 원자 UPDATE 와 UPSERT 를 쓰므로
 * 롤백에 기대는 대신 실행마다 고유한 픽스처를 만든다 ({@code VoteControllerIT} 와 같은 이유).
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class BadgeControllerIT {

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
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdPostIds = new ArrayList<>();

    private MockMvc mockMvc;
    private long seed;
    private User author;
    private User voter;
    private String voterToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        seed = System.nanoTime();
        author = userStore.save(new User(SocialProvider.GOOGLE, "badge-author-" + seed, null, "글쓴이"));
        voter = userStore.save(new User(SocialProvider.GOOGLE, "badge-voter-" + seed, null, "투표자"));
        voterToken = jwtService.createAccessToken(voter);
    }

    @AfterEach
    void tearDown() {
        // 롤백이 없으니 직접 지운다. 자식부터 지워야 FK 가 막지 않는다.
        jdbcTemplate.update("DELETE FROM user_badge WHERE user_id IN (?, ?)", voter.id(), author.id());
        jdbcTemplate.update("DELETE FROM user_daily_activity WHERE user_id IN (?, ?)", voter.id(), author.id());
        createdPostIds.forEach(postId -> jdbcTemplate.update("DELETE FROM vote WHERE post_id = ?", postId));
    }

    /** 투표할 수 있는 찬반 게시글 하나를 만든다. */
    private Post votablePost() {
        Long containerId = containerStore.save(new ItemContainer(author.id(), AttachType.PRODUCT)
                .add(new ItemResource(1L, "p.jpg", "s3/" + System.nanoTime(), "https://cdn/x"))).id();
        Post post = postStore.save(
                new Post(author.id(), PostType.AGREE, PostCategory.ETC, "투표 대상", null)
                        .addProduct(new PostProduct(containerId, "상품", 1000L, null, 1))
                        .addOption(PostOption.ofLabel("사자", 1))
                        .addOption(PostOption.ofLabel("말자", 2)));
        createdPostIds.add(post.id());
        return post;
    }

    /** 서로 다른 게시글에 {@code times} 번 투표한다. 1인 1표라 게시글을 매번 새로 만든다 (R-09). */
    private void voteOnDistinctPosts(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            Post post = votablePost();
            castVote(post, post.options().getFirst().id());
        }
    }

    private void castVote(Post post, Long optionId) throws Exception {
        mockMvc.perform(post("/api/posts/{postId}/votes", post.id())
                        .header("Authorization", "Bearer " + voterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":%d}".formatted(optionId)))
                .andExpect(status().isOk());
    }

    @Nested
    @DisplayName("내 뱃지 현황")
    class Collection {

        @Test
        @DisplayName("미인증이면 401 이다 — 게스트에게는 미션을 보여주지 않는다")
        void guestIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/users/me/badges"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("아무것도 얻지 못했어도 8종이 모두 내려오고 수집 개수는 0 이다")
        void listsAllBadgesWithNoneAcquired() throws Exception {
            mockMvc.perform(get("/api/users/me/badges")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.collectedCount").value(0))
                    .andExpect(jsonPath("$.returnObject.badges.length()").value(8))
                    // 미획득도 이름과 함께 내려간다 — 화면이 일러스트만 가린다 (§12.2).
                    .andExpect(jsonPath("$.returnObject.badges[0].name").value("투표 꿈나무"))
                    .andExpect(jsonPath("$.returnObject.badges[0].acquired").value(false));
        }

        @Test
        @DisplayName("뱃지를 얻으면 획득 플래그와 수집 개수가 함께 오른다 (§12.1·§12.2)")
        void reflectsAcquiredBadges() throws Exception {
            // 10회 투표 → 누적 10회(투표 꿈나무) 획득. 하루 20개에는 못 미친다.
            voteOnDistinctPosts(10);

            mockMvc.perform(get("/api/users/me/badges")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.collectedCount").value(1))
                    .andExpect(jsonPath("$.returnObject.badges[?(@.code == 'TOTAL_VOTE_10')].acquired")
                            .value(true))
                    .andExpect(jsonPath("$.returnObject.badges[?(@.code == 'TOTAL_VOTE_100')].acquired")
                            .value(false));
        }
    }

    @Nested
    @DisplayName("획득 판정 경계값")
    class Boundary {

        @Test
        @DisplayName("하루 19회에서는 투표 헌터를 얻지 못하고 20회에서 얻는다")
        void dailyBadgeBoundary() throws Exception {
            voteOnDistinctPosts(19);

            mockMvc.perform(get("/api/users/me/badges")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(jsonPath("$.returnObject.badges[?(@.code == 'DAILY_VOTE_20')].acquired")
                            .value(false));

            voteOnDistinctPosts(1);

            mockMvc.perform(get("/api/users/me/badges")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(jsonPath("$.returnObject.badges[?(@.code == 'DAILY_VOTE_20')].acquired")
                            .value(true));
        }

        @Test
        @DisplayName("누적 9회에서는 투표 꿈나무를 얻지 못하고 10회에서 얻는다")
        void totalBadgeBoundary() throws Exception {
            voteOnDistinctPosts(9);

            mockMvc.perform(get("/api/users/me/badges")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(jsonPath("$.returnObject.collectedCount").value(0));

            voteOnDistinctPosts(1);

            mockMvc.perform(get("/api/users/me/badges")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(jsonPath("$.returnObject.collectedCount").value(1));
        }

        @Test
        @DisplayName("조건을 다시 충족해도 보유 행이 하나로 유지된다 (R-17)")
        void doesNotGrantTwice() throws Exception {
            voteOnDistinctPosts(12);

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_badge ub JOIN badge b ON b.id = ub.badge_id "
                            + "WHERE ub.user_id = ? AND b.code = 'TOTAL_VOTE_10'",
                    Integer.class, voter.id());

            // 10회째에 지급된 뒤 11·12회에서도 조건은 계속 참이지만 행은 하나다.
            org.assertj.core.api.Assertions.assertThat(rows).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("재투표 (R-22)")
    class Revote {

        @Test
        @DisplayName("같은 게시글에 다시 투표해도 일별 활동이 늘지 않는다")
        void revoteDoesNotIncreaseDailyActivity() throws Exception {
            Post post = votablePost();
            Long buy = post.options().getFirst().id();
            Long skip = post.options().get(1).id();

            castVote(post, buy);
            Integer afterFirst = dailyVoteCount();

            castVote(post, skip);
            castVote(post, buy);

            org.assertj.core.api.Assertions.assertThat(dailyVoteCount()).isEqualTo(afterFirst);
        }

        private Integer dailyVoteCount() {
            return jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(vote_count), 0) FROM user_daily_activity WHERE user_id = ?",
                    Integer.class, voter.id());
        }
    }

    @Nested
    @DisplayName("미해제 미션 (§2.3)")
    class Missions {

        @Test
        @DisplayName("미인증이면 401 이다")
        void guestIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/users/me/badges/missions"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("계열마다 가장 낮은 미션을 하나씩, 현재값과 목표값을 함께 준다")
        void returnsLowestPerSeries() throws Exception {
            voteOnDistinctPosts(3);

            mockMvc.perform(get("/api/users/me/badges/missions")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(status().isOk())
                    // 누적 계열 1개 + 일일 계열 1개. 연속은 슬롯에 들어가지 않는다.
                    .andExpect(jsonPath("$.returnObject.length()").value(2))
                    .andExpect(jsonPath("$.returnObject[0].code").value("TOTAL_VOTE_10"))
                    .andExpect(jsonPath("$.returnObject[0].current").value(3))
                    .andExpect(jsonPath("$.returnObject[0].goal").value(10))
                    .andExpect(jsonPath("$.returnObject[1].code").value("DAILY_VOTE_20"))
                    .andExpect(jsonPath("$.returnObject[1].current").value(3))
                    .andExpect(jsonPath("$.returnObject[1].goal").value(20));
        }

        @Test
        @DisplayName("낮은 미션을 달성하면 다음 미션으로 넘어간다 — 하위 미션 먼저")
        void advancesToNextThreshold() throws Exception {
            voteOnDistinctPosts(10);

            mockMvc.perform(get("/api/users/me/badges/missions")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(status().isOk())
                    // 10회를 채웠으니 누적 슬롯은 100회로 넘어간다.
                    .andExpect(jsonPath("$.returnObject[0].code").value("TOTAL_VOTE_100"))
                    .andExpect(jsonPath("$.returnObject[0].current").value(10))
                    .andExpect(jsonPath("$.returnObject[0].goal").value(100));
        }

        @Test
        @DisplayName("투표하면 미션 진행률이 그 자리에서 오른다")
        void progressMovesImmediately() throws Exception {
            voteOnDistinctPosts(1);

            mockMvc.perform(get("/api/users/me/badges/missions")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(jsonPath("$.returnObject[1].current").value(1));

            voteOnDistinctPosts(1);

            mockMvc.perform(get("/api/users/me/badges/missions")
                            .header("Authorization", "Bearer " + voterToken))
                    .andExpect(jsonPath("$.returnObject[1].current").value(2));
        }
    }
}
