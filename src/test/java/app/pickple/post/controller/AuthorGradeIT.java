package app.pickple.post.controller;

import app.pickple.auth.domain.AuthProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AccountWithdrawalPersistenceService;
import app.pickple.auth.service.JwtService;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.service.CommentService;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.support.SqlCapture;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** F04: 저장 등급을 목록·상세·댓글의 실제 HTTP 응답과 대조한다. */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
@Import(SqlCapture.Config.class)
class AuthorGradeIT {

    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private UserStore userStore;
    @Autowired private PostStore postStore;
    @Autowired private CommentService commentService;
    @Autowired private JwtService jwtService;
    @Autowired private AccountWithdrawalPersistenceService withdrawalPersistenceService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private SqlCapture sqlCapture;

    private MockMvc mockMvc;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain).build();
        viewerToken = jwtService.createAccessToken(user());
    }

    @Test
    void samePointsButDifferentStoredGradesMatchAcrossAllThreeApis() throws Exception {
        User first = user();
        User second = user();
        Post firstPost = postAndComment(first);
        Post secondPost = postAndComment(second);
        // 현재 포인트·투표 수는 같지만 도달한 최고 등급은 다르다(R-16).
        setGrade(first, 2);
        setGrade(second, 5);
        assertAllGrades(firstPost, 2);
        assertAllGrades(secondPost, 5);
    }

    @Test
    void newAuthorUsesDefaultGradeAndExistingContentReflectsStoredGradeChange() throws Exception {
        User author = user();
        Post post = postAndComment(author);
        assertAllGrades(post, 1);
        setGrade(author, 4);
        assertAllGrades(post, 4);
    }

    @Test
    void withdrawnAuthorKeepsGradeWhileNamesAreAnonymizedAcrossApis() throws Exception {
        User author = user();
        Post post = postAndComment(author);
        setGrade(author, 3);
        withdrawalPersistenceService.complete(author.id());
        entityManager.flush();
        entityManager.clear();

        assertAllGrades(post, 3);
        assertThat(listItem(post.id()).get("authorNickname")).isEqualTo("알 수 없음");
        assertThat(detail(post.id()).get("authorNickname")).isEqualTo("알 수 없음");
        assertThat(comments(post.id()).getFirst().get("nickname")).isEqualTo("알 수 없음");
    }

    @Test
    void postAndCommentSqlCountsStayConstantAsAuthorsAndItemsIncrease() {
        Post first = postAndComment(user());
        entityManager.flush();
        entityManager.clear();
        int smallPosts = capture("/posts?category=ETC&size=50", false);
        int smallComments = capture("/posts/" + first.id() + "/comments", true);

        for (int i = 0; i < 12; i++) {
            User author = user();
            postAndComment(author);
            commentService.write(new Comment(first.id(), author.id(), "추가 댓글", null));
            setGrade(author, i % 5 + 1);
        }
        entityManager.flush();
        entityManager.clear();
        int largePosts = capture("/posts?category=ETC&size=50", false);
        int largeComments = capture("/posts/" + first.id() + "/comments", true);

        assertThat(largePosts).isEqualTo(smallPosts).isEqualTo(2);
        assertThat(largeComments).isEqualTo(smallComments).isEqualTo(4);
    }

    private User user() {
        return userStore.save(new User(AuthProvider.GOOGLE,
                "f04-" + java.util.UUID.randomUUID(), null, "작성자"));
    }

    private Post postAndComment(User author) {
        Post post = postStore.save(new Post(author.id(), PostType.GENERAL,
                PostCategory.ETC, "F04 등급 확인", null));
        commentService.write(new Comment(post.id(), author.id(), "작성자의 댓글", null));
        return post;
    }

    private void setGrade(User author, int grade) {
        entityManager.flush();
        jdbc.update("UPDATE users SET highest_grade = ?, point = 0, vote_count = 0 WHERE id = ?",
                grade, author.id());
        entityManager.clear();
    }

    private void assertAllGrades(Post post, int level) throws Exception {
        entityManager.flush();
        entityManager.clear();
        for (Map<String, Object> response : List.of(listItem(post.id()), detail(post.id()),
                comments(post.id()).getFirst())) {
            assertThat(response.get("authorGradeLevel")).isEqualTo(level);
            assertThat(response.get("authorGradeName")).isEqualTo("LV." + level);
        }
    }

    private Map<String, Object> listItem(Long postId) throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(
                request("/posts?category=ETC&size=50", false), "$.returnObject.content");
        return rows.stream().filter(row -> ((Number) row.get("id")).longValue() == postId)
                .findFirst().orElseThrow();
    }

    private Map<String, Object> detail(Long postId) throws Exception {
        return JsonPath.read(request("/posts/" + postId, false), "$.returnObject");
    }

    private List<Map<String, Object>> comments(Long postId) throws Exception {
        return JsonPath.read(request("/posts/" + postId + "/comments", true), "$.returnObject.comments");
    }

    private String request(String path, boolean authenticated) throws Exception {
        var request = get(path);
        if (authenticated) {
            request.header("Authorization", "Bearer " + viewerToken);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int capture(String path, boolean authenticated) {
        AtomicReference<String> body = new AtomicReference<>();
        List<String> sql = sqlCapture.record(() -> {
            try {
                body.set(request(path, authenticated));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
        List<?> items = JsonPath.read(body.get(), authenticated
                ? "$.returnObject.comments" : "$.returnObject.content");
        assertThat(items).isNotEmpty();
        return sql.size();
    }
}
