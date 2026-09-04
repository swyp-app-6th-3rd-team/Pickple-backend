package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.service.CommentService;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.support.IntegrationTest;
import app.pickple.vote.service.VoteService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 홈 화면의 인기 게시글 Top 10 (이슈 #29) 의 완료 판정을 실제 MySQL 로 확인한다.
 *
 * <p><b>{@code PostControllerIT} 와 나눈 이유는 격리 방식이 다르기 때문</b>이다.
 * 목록 API 는 {@code category} 로 창을 좁혀 다른 테스트 클래스가 남긴 게시글을
 * 피할 수 있지만, 이 엔드포인트에는 필터가 없다 — <b>전역 상위 10건</b>이 계약이다.
 * 그래서 "0건" 과 "정확히 10건" 을 보려면 잔여 게시글을 먼저 치워야 하고,
 * 그 전제를 목록 테스트와 한 클래스에 섞으면 어느 테스트가 어느 전제 위에 서 있는지
 * 읽기 어려워진다.
 *
 * <p>치우는 방법은 <b>소프트 삭제</b>다. 물리 {@code DELETE} 는 {@code post_product}·
 * {@code comment}·{@code vote} 의 FK 를 먼저 깨뜨리는 반면, {@code deleted_at} 은
 * 조회 쿼리가 실제로 쓰는 필터({@code WHERE p.deleted_at IS NULL}) 그 자체라
 * 프로덕션 경로를 우회하지 않는다. 클래스 {@code @Transactional} 이 롤백하므로
 * 다른 테스트에 남지 않는다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class PopularPostsIT {

    private static final String POPULAR = "/posts/popular";
    private static final String CONTENT = "$.returnObject";

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
    private CommentService commentService;
    @Autowired
    private VoteService voteService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MockMvc mockMvc;
    private User author;
    private long seed;
    private int nicknameSequence;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        seed = System.nanoTime();
        author = saveUser("popular-author-" + seed, "글쓴이");
        hideExistingPosts();
    }

    @Test
    @DisplayName("게시글이 없으면 게스트에게 200 과 빈 배열을 준다")
    void emptyArrayForGuest() throws Exception {
        // 완료 판정: "게시글이 없을 때 빈 배열을 정상 응답 → 200 + []".
        // 서버가 더미 게시글을 지어내지 않는다 — 기능명세서 §2.4 의 "더미 데이터 2개" 는
        // 화면의 빈 상태이지 서버 응답이 아니다. 지어내면 탭했을 때 갈 곳이 없다.
        //
        // 토큰 없이 부른다. 게스트 허용이므로 401 이 아니라 200 이어야 한다.
        mockMvc.perform(get(POPULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath(CONTENT).isArray())
                .andExpect(jsonPath(CONTENT).isEmpty());
    }

    @Test
    @DisplayName("게시글이 20개면 정확히 10개만 준다")
    void returnsAtMostTen() throws Exception {
        // 완료 판정: "게시글 20개 상황에서 응답 길이 = 10".
        for (int i = 0; i < 20; i++) {
            saveGeneralPost("인기 " + i);
        }
        flush();

        mockMvc.perform(get(POPULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + ".length()").value(10));
    }

    @Test
    @DisplayName("10개보다 적으면 있는 만큼만 준다")
    void returnsFewerWhenScarce() throws Exception {
        // 상한이지 정원이 아니다. 3건뿐이면 3건이고, 빈자리를 지어내 채우지 않는다.
        for (int i = 0; i < 3; i++) {
            saveGeneralPost("몇 안 되는 " + i);
        }
        flush();

        mockMvc.perform(get(POPULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + ".length()").value(3));
    }

    @Test
    @DisplayName("한 사람이 댓글 5개를 달아도 인기 점수는 1만 오른다")
    void popularityCountsPeopleNotComments() throws Exception {
        // 완료 판정 (R-25): "댓글 5개 작성 전후 popularity_score 비교".
        // 점수는 vote_count + commenter_count 생성 컬럼이고 commenter_count 가
        // "댓글을 단 사람 수" 라, 같은 사람이 여러 번 써도 1이다.
        Post many = saveGeneralPost("한 사람이 5개");
        Post few = saveGeneralPost("두 사람이 하나씩");
        User first = saveUser("popular-c-a-" + seed, "댓글A");
        User second = saveUser("popular-c-b-" + seed, "댓글B");

        assertThat(popularityScore(many.id())).isZero();

        for (int i = 0; i < 5; i++) {
            commentService.write(new Comment(many.id(), first.id(), "댓글 " + i, null));
        }
        commentService.write(new Comment(few.id(), first.id(), "하나", null));
        commentService.write(new Comment(few.id(), second.id(), "둘", null));
        flush();

        // 댓글 5건이지만 사람은 하나다.
        assertThat(popularityScore(many.id())).isEqualTo(1);
        assertThat(popularityScore(few.id())).isEqualTo(2);

        // 건수가 많은 쪽(5건)이 아니라 인원이 많은 쪽(2명)이 앞에 온다.
        mockMvc.perform(get(POPULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + "[0].id").value(few.id()))
                .andExpect(jsonPath(CONTENT + "[0].commentCount").value(2))
                .andExpect(jsonPath(CONTENT + "[1].id").value(many.id()))
                .andExpect(jsonPath(CONTENT + "[1].commentCount").value(5));
    }

    @Test
    @DisplayName("투표 인원과 댓글 인원의 합이 큰 순서로 준다")
    void ordersBySumOfVotersAndCommenters() throws Exception {
        // R-24. 투표 2명(점수 2)이 댓글 1명(점수 1)보다 앞선다.
        Post voted = saveAgreePost("투표 2명");
        Post commented = saveGeneralPost("댓글 1명");
        Post quiet = saveGeneralPost("반응 없음");
        Long optionId = firstOptionId(voted.id());
        voteService.castOrChange(voted.id(), optionId, saveUser("popular-v-a-" + seed, "투표A").id());
        voteService.castOrChange(voted.id(), optionId, saveUser("popular-v-b-" + seed, "투표B").id());
        commentService.write(new Comment(
                commented.id(), saveUser("popular-c-c-" + seed, "댓글C").id(), "한마디", null));
        flush();

        mockMvc.perform(get(POPULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + "[0].id").value(voted.id()))
                .andExpect(jsonPath(CONTENT + "[0].voteCount").value(2))
                .andExpect(jsonPath(CONTENT + "[1].id").value(commented.id()))
                .andExpect(jsonPath(CONTENT + "[2].id").value(quiet.id()));
    }

    @Test
    @DisplayName("집계는 애플리케이션이 아니라 생성 컬럼 인덱스가 한다")
    void sortsOnTheGeneratedColumnIndex() throws Exception {
        // 완료 판정: "집계가 애플리케이션이 아닌 쿼리에서 이뤄짐 — 실행 쿼리 확인".
        //
        // 결과만 보면 애플리케이션 정렬과 구별되지 않는다. 그래서 실행 계획을 직접 본다.
        // COUNT(DISTINCT ...) 로 조회 시점에 세면 idx_post_popular_all 이 뜨지 않는다.
        for (int i = 0; i < 3; i++) {
            saveGeneralPost("계획 확인 " + i);
        }
        flush();

        // 실측 결과: "idx_post_popular_all Using where; Using index".
        // filesort 가 없다는 것이 핵심이다 — 조회 시점에 세거나 임시 테이블로 정렬하면
        // 여기에 "Using filesort" 가 뜬다.
        assertThat(explainPopular())
                .contains("idx_post_popular_all")
                .doesNotContain("Using filesort");
    }

    @Test
    @DisplayName("응답이 몇 건이든 쿼리는 한 번이다")
    void staysOneQuery() throws Exception {
        // 목록 조회 경로를 그대로 타므로 "먼저 자르고 나중에 붙인다" 가 여기도 적용된다.
        // 작성자·랭킹·대표 사진이 행마다 붙으면(N+1) 건수에 비례해 늘어난다.
        // 유형을 섞는다 — 상품이 있는 글에서만 추가 조회가 붙는 경우를 잡기 위해서다.
        for (int i = 0; i < 4; i++) {
            saveGeneralPost("일반 " + i);
            saveAgreePost("찬반 " + i);
        }
        flush();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        mockMvc.perform(get(POPULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + ".length()").value(8));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("커서를 돌려주지 않는다 — Top 10 이 전부다")
    void carriesNoCursorEnvelope() throws Exception {
        // 목록과 같은 항목 스키마를 쓰되 봉투는 벗는다. hasNext 를 실어 보내면
        // 11번째 글이 있을 때 true 가 되고, 클라이언트가 그 커서로 다시 부르면
        // 이 엔드포인트가 정의하지 않은 동작이 된다. 더 보기는 목록 API 로 간다.
        for (int i = 0; i < 12; i++) {
            saveGeneralPost("봉투 " + i);
        }
        flush();

        mockMvc.perform(get(POPULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT).isArray())
                .andExpect(jsonPath(CONTENT + ".length()").value(10))
                .andExpect(jsonPath("$.returnObject.hasNext").doesNotExist())
                .andExpect(jsonPath("$.returnObject.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("삭제된 게시글은 인기 목록에 오르지 않는다")
    void skipsDeletedPosts() throws Exception {
        // 점수가 가장 높아도 삭제된 글은 빠진다. 조회 쿼리의 deleted_at 필터가
        // 정렬 인덱스의 첫 컬럼이기도 하다.
        Post deleted = saveGeneralPost("지워진 인기글");
        Post alive = saveGeneralPost("살아있는 글");
        commentService.write(new Comment(
                deleted.id(), saveUser("popular-c-d-" + seed, "댓글D").id(), "한마디", null));
        flush();
        assertThat(popularityScore(deleted.id())).isEqualTo(1);

        softDelete(deleted.id());
        flush();

        mockMvc.perform(get(POPULAR))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CONTENT + ".length()").value(1))
                .andExpect(jsonPath(CONTENT + "[0].id").value(alive.id()));
    }

    // --- 격리 --------------------------------------------------------------

    /**
     * 다른 테스트 클래스가 남긴 게시글을 이 트랜잭션 안에서만 치운다.
     *
     * <p>컨테이너를 재사용하므로({@code ContainerConfig.withReuse}) {@code post} 테이블에
     * 남의 데이터가 있다. 이 엔드포인트는 카테고리 필터가 없어 그것들이 전부 후보에 든다 —
     * "0건" 도 "정확히 10건" 도 그대로는 볼 수 없다.
     */
    private void hideExistingPosts() {
        jdbcTemplate.update("UPDATE post SET deleted_at = NOW() WHERE deleted_at IS NULL");
        flush();
    }

    private void softDelete(Long postId) {
        jdbcTemplate.update("UPDATE post SET deleted_at = NOW() WHERE id = ?", postId);
    }

    /** 인기순 조회가 실제로 어떤 인덱스를 타는지 본다. */
    private String explainPopular() {
        List<String> plan = jdbcTemplate.query(
                """
                EXPLAIN SELECT p.id
                  FROM post p
                 WHERE p.deleted_at IS NULL
                 ORDER BY p.popularity_score DESC, p.id DESC
                 LIMIT 11
                """,
                (rs, rowNum) -> rs.getString("key") + " " + rs.getString("Extra"));
        return String.join(" | ", plan);
    }

    private int popularityScore(Long postId) {
        Integer score = jdbcTemplate.queryForObject(
                "SELECT popularity_score FROM post WHERE id = ?", Integer.class, postId);
        assertThat(score).isNotNull();
        return score;
    }

    // --- 픽스처 ------------------------------------------------------------

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private User saveUser(String providerId, String name) {
        User saved = userStore.save(new User(SocialProvider.GOOGLE, providerId, null, name));
        jdbcTemplate.update("UPDATE users SET nickname = ? WHERE id = ?", uniqueNickname(name), saved.id());
        return saved;
    }

    /** 닉네임은 5자 이내이고 활성 회원 사이에서 유일하다 ({@code uk_users_active_nickname}). */
    private String uniqueNickname(String name) {
        String suffix = Long.toString(nicknameSequence++, 36);
        int room = Math.max(0, 5 - suffix.length());
        return name.substring(0, Math.min(name.length(), room)) + suffix;
    }

    private Post saveGeneralPost(String title) {
        return postStore.save(new Post(author.id(), PostType.GENERAL, PostCategory.ETC, title, "설명"));
    }

    private Post saveAgreePost(String title) {
        Post post = new Post(author.id(), PostType.AGREE, PostCategory.LIVING, title, "설명")
                .addProduct(new PostProduct(newContainer("agree", 1), "상품", 10_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));
        return postStore.save(post);
    }

    /** 사진 여러 장을 등록 순서대로 담은 상품용 컨테이너. */
    private Long newContainer(String tag, int photoCount) {
        ItemContainer container = new ItemContainer(author.id(), AttachType.PRODUCT);
        long unique = System.nanoTime();
        for (int i = 1; i <= photoCount; i++) {
            container = container.add(new ItemResource(
                    1024L, tag + "-" + i + ".jpg",
                    "product-images/%d/%d-%d.jpg".formatted(author.id(), unique, i),
                    "https://cdn.test/" + tag + "-" + i + "-" + seed));
        }
        return containerStore.save(container).id();
    }

    private Long firstOptionId(Long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM post_option WHERE post_id = ? ORDER BY display_order LIMIT 1",
                Long.class, postId);
    }
}
