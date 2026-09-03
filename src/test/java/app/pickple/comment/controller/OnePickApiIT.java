package app.pickple.comment.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.point.domain.PointReason;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 원픽 API 의 HTTP 계약 (Issue #24).
 *
 * <p><b>여기서 보는 것과 보지 않는 것.</b> 정책 자체는 {@code OnePickServiceIT} 가,
 * 제약의 동작은 {@code JpaOnePickStoreIT} 가 이미 본다.
 * 이 테스트가 새로 증명하는 것은 <b>그 정책들이 어떤 상태코드로 나가는가</b>다 —
 * 이 PR 이전에는 {@code DuplicatePickException} 이 매핑되지 않아 500 으로 나갔다.
 *
 * <p><b>동시성은 여기 없다.</b> {@code @Transactional} 테스트는 커밋되지 않은 행이
 * 다른 커넥션에 보이지 않아 동시 요청을 재현할 수 없다.
 * {@code OnePickConcurrencyIT} 가 트랜잭션 밖에서 본다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class OnePickApiIT {

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
    private User commentAuthor;
    private User picker;
    private Post post;
    private Comment comment;
    private String pickerToken;
    private String authorToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        long seed = System.nanoTime();
        commentAuthor = saveUser("pick-author-" + seed, "댓글러");
        picker = saveUser("pick-picker-" + seed, "픽커");
        post = postStore.save(new Post(
                commentAuthor.id(), PostType.GENERAL, PostCategory.ETC, "원픽 대상 글", null));
        comment = commentStore.save(new Comment(post.id(), commentAuthor.id(), "도움이 되는 댓글", null));

        pickerToken = jwtService.createAccessToken(picker);
        authorToken = jwtService.createAccessToken(commentAuthor);
    }

    @Test
    @DisplayName("원픽 1회로 포인트 이력이 정확히 2행 생긴다 (R-12·R-13)")
    void pickGrantsExactlyTwoRows() throws Exception {
        Long pickId = pickExpectingCreated(comment.id());

        // 행 수를 센다. 합계만 보면 "+10 한 행" 과 "+5 두 행" 을 구분하지 못한다.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id, amount, reason FROM point_history WHERE comment_pick_id = ?", pickId);

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("user_id")).isEqualTo(commentAuthor.id());
            assertThat(row.get("amount")).isEqualTo(PointReason.PICKED.amount());
            assertThat(row.get("reason")).isEqualTo(PointReason.PICKED.name());
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("user_id")).isEqualTo(picker.id());
            assertThat(row.get("amount")).isEqualTo(PointReason.PICKING.amount());
            assertThat(row.get("reason")).isEqualTo(PointReason.PICKING.name());
        });
    }

    @Test
    @DisplayName("같은 게시글의 다른 댓글을 픽하면 409, 행은 그대로 1개 (R-05)")
    void secondPickOnSamePostConflicts() throws Exception {
        Comment another = commentStore.save(
                new Comment(post.id(), commentAuthor.id(), "같은 글의 다른 댓글", null));

        pickExpectingCreated(comment.id());

        // 유일성 범위가 게시글이 아니라 댓글이었다면 이 요청이 201 로 통과한다.
        mockMvc.perform(post("/api/comments/{id}/pick", another.id())
                        .header("Authorization", bearer(pickerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PICKED"));

        assertThat(countPicks(picker.id(), post.id())).isEqualTo(1);
        // 두 번째 시도가 포인트를 더 주지 않았다 (R-13).
        assertThat(countPointRows(picker.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 댓글을 두 번 픽해도 409 (R-05 가 R-26 을 흡수한다)")
    void repickingSameCommentConflicts() throws Exception {
        pickExpectingCreated(comment.id());

        mockMvc.perform(post("/api/comments/{id}/pick", comment.id())
                        .header("Authorization", bearer(pickerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PICKED"));

        assertThat(countPicks(picker.id(), post.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("자기 댓글은 원픽할 수 없다 — 400 (R-07)")
    void cannotPickOwnComment() throws Exception {
        // 인가 실패가 아니라 요청 자체가 무효라 403 이 아니라 400 이다.
        mockMvc.perform(post("/api/comments/{id}/pick", comment.id())
                        .header("Authorization", bearer(authorToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(countPicks(commentAuthor.id(), post.id())).isZero();
    }

    @Test
    @DisplayName("다른 게시글에서는 다시 픽할 수 있다 — 범위가 게시글이다 (R-05)")
    void pickOnAnotherPostAllowed() throws Exception {
        Post otherPost = postStore.save(new Post(
                commentAuthor.id(), PostType.GENERAL, PostCategory.ETC, "다른 글", null));
        Comment otherComment = commentStore.save(
                new Comment(otherPost.id(), commentAuthor.id(), "다른 글의 댓글", null));

        pickExpectingCreated(comment.id());
        pickExpectingCreated(otherComment.id());

        assertThat(countPointRows(picker.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("인증 없이는 픽할 수 없다 — 401")
    void guestCannotPick() throws Exception {
        mockMvc.perform(post("/api/comments/{id}/pick", comment.id()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(countPicks(picker.id(), post.id())).isZero();
    }

    @Test
    @DisplayName("없는 댓글을 픽하면 400")
    void missingCommentRejected() throws Exception {
        mockMvc.perform(post("/api/comments/{id}/pick", -1L)
                        .header("Authorization", bearer(pickerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("삭제된 댓글은 픽할 수 없다 — 400")
    void deletedCommentRejected() throws Exception {
        // 소프트 삭제라 행이 남고 findById 로도 읽힌다. 막는 것은 Comment.pick() 이다.
        Comment loaded = commentStore.findById(comment.id()).orElseThrow();
        loaded.delete();
        commentStore.save(loaded);

        mockMvc.perform(post("/api/comments/{id}/pick", comment.id())
                        .header("Authorization", bearer(pickerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(countPicks(picker.id(), post.id())).isZero();
    }

    @Test
    @DisplayName("삭제된 게시글의 댓글은 픽할 수 없다 — 400")
    void deletedPostRejected() throws Exception {
        Post loaded = postStore.findById(post.id()).orElseThrow();
        loaded.delete();
        postStore.save(loaded);

        mockMvc.perform(post("/api/comments/{id}/pick", comment.id())
                        .header("Authorization", bearer(pickerToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(countPicks(picker.id(), post.id())).isZero();
    }

    private Long pickExpectingCreated(Long commentId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/comments/{id}/pick", commentId)
                        .header("Authorization", bearer(pickerToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.returnObject.commentId").value(commentId))
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.returnObject.id");
        return id.longValue();
    }

    private int countPicks(Long userId, Long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment_pick WHERE user_id = ? AND post_id = ?",
                Integer.class, userId, postId);
    }

    private int countPointRows(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_history WHERE user_id = ?", Integer.class, userId);
    }

    private User saveUser(String providerId, String name) {
        return userStore.save(new User(SocialProvider.GOOGLE, providerId, null, name));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
