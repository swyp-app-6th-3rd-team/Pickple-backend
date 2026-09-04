package app.pickple.comment.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 같은 사람이 한 게시글에서 동시에 여러 댓글을 픽해도 행은 하나만 생긴다 (R-05).
 *
 * <p><b>왜 별도 클래스인가.</b> {@code @Transactional} 테스트는 롤백되는 한 트랜잭션 안에서
 * 돌아 다른 스레드가 그 행을 보지 못한다. 동시성을 재현하려면 각 요청이 실제로 커밋되어야 하므로
 * 트랜잭션 밖에서 돌리고 뒷정리를 손으로 한다.
 *
 * <p><b>확인 후 삽입은 뚫린다.</b> {@code JpaOnePickStore} 가 존재를 먼저 확인하지만
 * 확인과 삽입 사이에 틈이 있다. 최종 방어선은 {@code UNIQUE(user_id, post_id)} 다 (ADR-0020).
 * 이 테스트가 보는 것은 그 틈이 데이터를 깨뜨리지 않는다는 사실이다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class OnePickConcurrencyIT {

    private static final int THREADS = 8;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private PostStore postStore;
    @Autowired
    private CommentStore commentStore;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private Long postId;
    private Long pickerId;
    private String pickerToken;
    private List<Long> commentIds;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        long seed = System.nanoTime();
        User author = userStore.save(
                new User(SocialProvider.GOOGLE, "conc-author-" + seed, null, "댓글러"));
        User picker = userStore.save(
                new User(SocialProvider.GOOGLE, "conc-picker-" + seed, null, "픽커"));
        pickerId = picker.id();
        pickerToken = jwtService.createAccessToken(picker);

        postId = postStore.save(new Post(
                author.id(), PostType.GENERAL, PostCategory.ETC, "동시 원픽 대상", null)).id();
        commentIds = IntStream.range(0, THREADS)
                .mapToObj(i -> commentStore.save(
                        new Comment(postId, author.id(), "댓글 " + i, null)).id())
                .toList();
    }

    @AfterEach
    void tearDown() {
        // 롤백이 없으므로 손으로 지운다. FK 순서대로 — 포인트가 픽을 참조한다.
        jdbcTemplate.update(
                "DELETE FROM point_history WHERE comment_pick_id IN"
                        + " (SELECT id FROM comment_pick WHERE post_id = ?)", postId);
        jdbcTemplate.update("DELETE FROM comment_pick WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM post_commenter WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM comment WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM post WHERE id = ?", postId);
    }

    @Test
    @DisplayName("동시 원픽 요청에서도 comment_pick 행은 하나만 생긴다 (R-05)")
    void concurrentPicksCreateExactlyOneRow() throws Exception {
        // 각 스레드가 같은 게시글의 서로 다른 댓글을 픽한다.
        // 같은 댓글이면 유일성 범위가 (user_id, comment_id) 여도 통과해버려 R-05 를 증명하지 못한다.
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Callable<Integer>> tasks = commentIds.stream()
                .map(commentId -> (Callable<Integer>) () -> {
                    barrier.await();
                    return mockMvc.perform(post("/comments/{id}/pick", commentId)
                                    .header("Authorization", "Bearer " + pickerToken))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                })
                .toList();

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            statuses = futures.stream().map(OnePickConcurrencyIT::get).toList();
        }

        // 완료 판정 — SELECT COUNT(*) WHERE user_id=? AND post_id=? = 1
        assertThat(countPicks()).isEqualTo(1);

        // 상태코드까지 본다. 행이 하나라도 나머지가 500 이면 계약이 깨진 것이다 —
        // "몇 건 실패했나" 만 세면 전부 실패해도 통과한다.
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(THREADS - 1);

        // 성공한 픽 1건에 대해서만 지급된다 — 두 사람 × 1건 = 2행 (R-12·R-13)
        assertThat(countPointRows()).isEqualTo(2);
    }

    private static Integer get(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int countPicks() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment_pick WHERE user_id = ? AND post_id = ?",
                Integer.class, pickerId, postId);
    }

    private int countPointRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_history WHERE comment_pick_id IN"
                        + " (SELECT id FROM comment_pick WHERE post_id = ?)",
                Integer.class, postId);
    }
}
