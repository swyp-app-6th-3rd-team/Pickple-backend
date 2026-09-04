package app.pickple.vote.controller;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 투표 참여 API (이슈 #21) 의 완료 판정을 실제 MySQL 로 확인한다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 마지막 테스트가 별도 스레드에서
 * 동시에 투표하는데, 테스트가 연 트랜잭션은 테스트 스레드에 묶여 있어
 * 다른 스레드가 그 안의 데이터를 보지 못한다. 롤백에 기대는 대신
 * 실행마다 고유한 픽스처({@code seed})를 만들어 서로 간섭하지 않게 한다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class VoteControllerIT {

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

    /** 이 테스트가 커밋한 것들. 롤백이 없으니 직접 지운다. */
    private final List<Long> createdPostIds = new ArrayList<>();

    private MockMvc mockMvc;
    private long seed;
    private User author;
    private User voter;
    private String voterToken;
    private Post post;
    private Long buyOptionId;
    private Long skipOptionId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        seed = System.nanoTime();
        author = saveUser("vote-author-" + seed, "글쓴이");
        voter = saveUser("vote-voter-" + seed, "투표자");
        voterToken = jwtService.createAccessToken(voter);
        post = saveAgreePost("이 가방 살까", PostCategory.LIVING);
        buyOptionId = optionIdOf(post, 1);
        skipOptionId = optionIdOf(post, 2);
    }

    /**
     * 커밋한 픽스처를 지운다.
     *
     * <p><b>이게 없으면 다른 테스트가 깨진다.</b> {@code PostControllerIT} 의 인기순 검증은
     * 필터 없이 {@code sort=POPULAR} 를 부르고 첫 항목을 단언하는데, 여기서 남긴
     * 표 많은 게시글이 그 자리를 차지한다(실제로 겪었다).
     * {@code @Transactional} 인 테스트들은 롤백에 기대지만 이 클래스는 그럴 수 없으므로
     * 뒷정리가 이 클래스의 책임이다.
     */
    @AfterEach
    void tearDown() {
        for (Long postId : createdPostIds) {
            // 표를 먼저 지운다. post 삭제는 vote 와 post_option 두 갈래로 동시에 CASCADE 되는데,
            // vote -> post_option 복합 FK(fk_vote_option)에는 CASCADE 가 없어
            // 선택지가 먼저 지워지는 순서에서 무결성 위반이 난다. R-10 을 지키는 그 FK 다.
            jdbcTemplate.update("DELETE FROM vote WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM post WHERE id = ?", postId);
        }
        createdPostIds.clear();
        // 회원은 남긴다. item_container 가 CASCADE 없이 users 를 참조해 지울 수 없고,
        // 게시글 없는 회원은 어떤 목록에도 나타나지 않아 다른 테스트를 흔들지 않는다.
    }

    @Test
    @DisplayName("게스트는 투표할 수 없다")
    void guestCannotVote() throws Exception {
        // R-11. 인증이 없으면 도메인까지 가지 않고 401 이다.
        mockMvc.perform(post("/posts/{postId}/votes", post.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(buyOptionId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(voterCountOf(post.id())).isZero();
    }

    @Test
    @DisplayName("투표하면 그 자리에서 선택지별 득표 수와 득표율이 내려온다")
    void returnsTallyRightAfterVoting() throws Exception {
        // §2.2 — 카드에서 투표하면 페이지 이동 없이 게이지로 바뀐다.
        vote(voterToken, buyOptionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject.postId").value(post.id()))
                .andExpect(jsonPath("$.returnObject.selectedOptionId").value(buyOptionId))
                .andExpect(jsonPath("$.returnObject.voterCount").value(1))
                .andExpect(jsonPath("$.returnObject.options[0].optionId").value(buyOptionId))
                .andExpect(jsonPath("$.returnObject.options[0].label").value("사자"))
                .andExpect(jsonPath("$.returnObject.options[0].displayOrder").value(1))
                .andExpect(jsonPath("$.returnObject.options[0].voteCount").value(1))
                .andExpect(jsonPath("$.returnObject.options[0].percentage").value(100))
                .andExpect(jsonPath("$.returnObject.options[1].optionId").value(skipOptionId))
                .andExpect(jsonPath("$.returnObject.options[1].voteCount").value(0))
                .andExpect(jsonPath("$.returnObject.options[1].percentage").value(0));
    }

    @Test
    @DisplayName("한 사람이 양쪽 선택지에 투표해도 총 투표 인원은 1이다")
    void oneVoterCountsOnceEvenAfterVotingBothOptions() throws Exception {
        // R-09. 사람 수는 게시글 단위로 센다 — 선택지 단위가 아니다.
        vote(voterToken, buyOptionId).andExpect(status().isOk());
        vote(voterToken, skipOptionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.voterCount").value(1));

        assertThat(voterCountOf(post.id())).isEqualTo(1L);
        assertThat(postVoteCountOf(post.id())).isEqualTo(1L);
        // 행도 하나뿐이다. 새 행을 만들면 UNIQUE(post_id, user_id) 에 걸린다.
        assertThat(voteRowsOf(post.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("다른 게시글의 선택지로는 투표할 수 없다")
    void rejectsOptionFromAnotherPost() throws Exception {
        // R-10. 스키마의 복합 FK 가 최종 방어선이지만, 거기까지 가면 500 이다.
        // 잘못된 요청이므로 400 으로 답한다.
        Post other = saveAgreePost("남의 글", PostCategory.LIVING);
        Long foreignOptionId = optionIdOf(other, 1);

        mockMvc.perform(post("/posts/{postId}/votes", post.id())
                        .header("Authorization", bearer(voterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(foreignOptionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(voterCountOf(post.id())).isZero();
        assertThat(voterCountOf(other.id())).isZero();
    }

    @Test
    @DisplayName("재투표는 표만 옮기고 사람 수와 누적 횟수는 그대로 둔다")
    void changingChoiceMovesOptionCountersOnly() throws Exception {
        // R-22. 이전 선택지 -1, 새 선택지 +1, 총 인원 불변.
        vote(voterToken, buyOptionId).andExpect(status().isOk());
        assertThat(optionVoteCountOf(buyOptionId)).isEqualTo(1L);
        assertThat(optionVoteCountOf(skipOptionId)).isZero();
        long cumulativeBefore = cumulativeVoteCountOf(voter.id());

        vote(voterToken, skipOptionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.selectedOptionId").value(skipOptionId))
                .andExpect(jsonPath("$.returnObject.voterCount").value(1))
                .andExpect(jsonPath("$.returnObject.options[0].voteCount").value(0))
                .andExpect(jsonPath("$.returnObject.options[0].percentage").value(0))
                .andExpect(jsonPath("$.returnObject.options[1].voteCount").value(1))
                .andExpect(jsonPath("$.returnObject.options[1].percentage").value(100));

        assertThat(optionVoteCountOf(buyOptionId)).isZero();
        assertThat(optionVoteCountOf(skipOptionId)).isEqualTo(1L);
        assertThat(postVoteCountOf(post.id())).isEqualTo(1L);
        // 누적 투표 횟수도 그대로다 — 재투표는 "다시 투표"가 아니라 "선택 변경"이다.
        assertThat(cumulativeVoteCountOf(voter.id())).isEqualTo(cumulativeBefore);
    }

    @Test
    @DisplayName("같은 선택지에 다시 투표해도 카운터가 부풀지 않는다")
    void repeatingSameChoiceIsIdempotent() throws Exception {
        vote(voterToken, buyOptionId).andExpect(status().isOk());
        vote(voterToken, buyOptionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.voterCount").value(1))
                .andExpect(jsonPath("$.returnObject.options[0].voteCount").value(1));

        assertThat(optionVoteCountOf(buyOptionId)).isEqualTo(1L);
        assertThat(postVoteCountOf(post.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("득표율은 선택지별 표를 합쳐 100 이 되도록 나눈다")
    void splitsPercentageBetweenOptions() throws Exception {
        vote(voterToken, buyOptionId).andExpect(status().isOk());
        String secondToken = jwtService.createAccessToken(saveUser("vote-second-" + seed, "둘째"));

        vote(secondToken, skipOptionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.voterCount").value(2))
                .andExpect(jsonPath("$.returnObject.options[0].voteCount").value(1))
                .andExpect(jsonPath("$.returnObject.options[0].percentage").value(50))
                .andExpect(jsonPath("$.returnObject.options[1].voteCount").value(1))
                .andExpect(jsonPath("$.returnObject.options[1].percentage").value(50));
    }

    @Test
    @DisplayName("투표가 없는 게시글에는 투표할 수 없다")
    void rejectsVotingOnPostWithoutOptions() throws Exception {
        Post general = postStore.save(
                new Post(author.id(), PostType.GENERAL, PostCategory.ETC, "그냥 잡담", null));
        createdPostIds.add(general.id());

        mockMvc.perform(post("/posts/{postId}/votes", general.id())
                        .header("Authorization", bearer(voterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(buyOptionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("선택지 id 가 없으면 400 이다")
    void rejectsMissingOptionId() throws Exception {
        mockMvc.perform(post("/posts/{postId}/votes", post.id())
                        .header("Authorization", bearer(voterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("동시에 투표해도 선택지 카운터의 합이 투표 인원과 같다")
    void concurrentVotesKeepCountersConsistent() throws Exception {
        // 완료 판정 마지막 줄. Java 에서 읽고 더해 쓰면 두 요청이 같은 값을 읽어
        // 하나가 사라진다(lost update). 원자 UPDATE 라야 합이 맞는다.
        int voters = 16;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < voters; i++) {
            tokens.add(jwtService.createAccessToken(saveUser("vote-race-" + i + "-" + seed, "동시" + i)));
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(voters)) {
            for (int i = 0; i < voters; i++) {
                String token = tokens.get(i);
                // 절반은 사자, 절반은 말자 — 한 선택지에 몰리면 다른 쪽 0 이 우연히 맞을 수 있다.
                Long optionId = (i % 2 == 0) ? buyOptionId : skipOptionId;
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/posts/{postId}/votes", post.id())
                                    .header("Authorization", bearer(token))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body(optionId)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        for (Future<Integer> result : results) {
            assertThat(result.get()).isEqualTo(200);
        }

        long people = voterCountOf(post.id());
        assertThat(people).isEqualTo(voters);
        assertThat(optionVoteCountOf(buyOptionId) + optionVoteCountOf(skipOptionId)).isEqualTo(people);
        assertThat(postVoteCountOf(post.id())).isEqualTo(people);
    }

    // --- 픽스처 -------------------------------------------------------------

    private User saveUser(String providerId, String name) {
        return userStore.save(new User(SocialProvider.GOOGLE, providerId, null, name));
    }

    private Post saveAgreePost(String title, PostCategory category) {
        Long containerId = containerStore.save(new ItemContainer(author.id(), AttachType.PRODUCT)
                .add(new ItemResource(1024L, "bag.jpg",
                        "product-images/%d/%d.jpg".formatted(author.id(), System.nanoTime()),
                        "https://cdn.test/bag-" + System.nanoTime()))).id();
        Post saved = postStore.save(new Post(author.id(), PostType.AGREE, category, title, "설명")
                .addProduct(new PostProduct(containerId, "가방", 100_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2)));
        createdPostIds.add(saved.id());
        return saved;
    }

    private Long optionIdOf(Post target, int displayOrder) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM post_option WHERE post_id = ? AND display_order = ?",
                Long.class, target.id(), displayOrder);
    }

    // --- 검증 도구 -----------------------------------------------------------

    /** 투표한 사람 수. 응답이 아니라 DB 에서 직접 센다. */
    private long voterCountOf(Long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM vote WHERE post_id = ?", Long.class, postId);
    }

    private long voteRowsOf(Long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vote WHERE post_id = ?", Long.class, postId);
    }

    private long postVoteCountOf(Long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT vote_count FROM post WHERE id = ?", Long.class, postId);
    }

    private long optionVoteCountOf(Long optionId) {
        return jdbcTemplate.queryForObject(
                "SELECT vote_count FROM post_option WHERE id = ?", Long.class, optionId);
    }

    /** 누적 투표 횟수 (R-22 — 재투표가 늘리면 안 된다). */
    private long cumulativeVoteCountOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT vote_count FROM users WHERE id = ?", Long.class, userId);
    }

    private ResultActions vote(String token, Long optionId) throws Exception {
        return mockMvc.perform(post("/posts/{postId}/votes", post.id())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(optionId)));
    }

    private String body(Long optionId) {
        return "{\"optionId\":" + optionId + "}";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
