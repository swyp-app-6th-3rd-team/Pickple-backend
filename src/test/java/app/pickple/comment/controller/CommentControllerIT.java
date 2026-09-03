package app.pickple.comment.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.OnePickStore;
import app.pickple.comment.service.CommentService;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class CommentControllerIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private PostStore postStore;
    @Autowired
    private CommentService commentService;
    @Autowired
    private OnePickStore onePickStore;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MockMvc mockMvc;
    private User postAuthor;
    private User commentAuthor;
    private User otherUser;
    private User secondPicker;
    private Post postEntity;
    private String commentAuthorToken;
    private String otherUserToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        long seed = System.nanoTime();
        postAuthor = saveUser("post-author-" + seed, "글쓴이");
        commentAuthor = saveUser("comment-author-" + seed, "댓글러");
        otherUser = saveUser("other-user-" + seed, "다른이");
        secondPicker = saveUser("second-picker-" + seed, "두번째");
        postEntity = postStore.save(new Post(
                postAuthor.id(), PostType.GENERAL, PostCategory.ETC, "댓글 테스트", null));

        jdbcTemplate.update(
                "UPDATE users SET nickname = ?, profile_image_url = ? WHERE id = ?",
                "댓글러", "https://cdn.example/profile.png", commentAuthor.id());

        commentAuthorToken = jwtService.createAccessToken(commentAuthor);
        otherUserToken = jwtService.createAccessToken(otherUser);
    }

    @Test
    void guestCanReadEmptyListButCannotWrite() throws Exception {
        mockMvc.perform(get("/api/posts/{postId}/comments", postEntity.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject.commentCount").value(0))
                .andExpect(jsonPath("$.returnObject.comments").isEmpty());

        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"로그인 없이 작성\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void onlyAuthorCanEditAndDeleteComment() throws Exception {
        Long commentId = writeThroughApi("처음 내용");

        mockMvc.perform(patch("/api/comments/{id}", commentId)
                        .header("Authorization", bearer(otherUserToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"남이 수정\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(delete("/api/comments/{id}", commentId)
                        .header("Authorization", bearer(otherUserToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(patch("/api/comments/{id}", commentId)
                        .header("Authorization", bearer(commentAuthorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"수정한 내용\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.id").value(commentId))
                .andExpect(jsonPath("$.returnObject.content").value("수정한 내용"));

        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/posts/{postId}/comments", postEntity.id())
                        .header("Authorization", bearer(commentAuthorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.commentCount").value(1))
                .andExpect(jsonPath("$.returnObject.comments[0].nickname").value("댓글러"))
                .andExpect(jsonPath("$.returnObject.comments[0].profileImageUrl")
                        .value("https://cdn.example/profile.png"))
                .andExpect(jsonPath("$.returnObject.comments[0].content").value("수정한 내용"))
                .andExpect(jsonPath("$.returnObject.comments[0].mine").value(true));

        mockMvc.perform(delete("/api/comments/{id}", commentId)
                        .header("Authorization", bearer(commentAuthorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        mockMvc.perform(get("/api/posts/{postId}/comments", postEntity.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.commentCount").value(0))
                .andExpect(jsonPath("$.returnObject.comments").isEmpty());

        mockMvc.perform(delete("/api/comments/{id}", commentId)
                        .header("Authorization", bearer(commentAuthorToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(postStore.findById(postEntity.id()).orElseThrow().commentCount()).isZero();
    }

    @Test
    void returnsTwoThirdPartyPicksWithConstantQueryCount() throws Exception {
        Comment picked = commentService.write(new Comment(
                postEntity.id(), commentAuthor.id(), "픽 받을 댓글", null));
        commentService.write(new Comment(postEntity.id(), commentAuthor.id(), "두 번째", null));
        commentService.write(new Comment(postEntity.id(), otherUser.id(), "세 번째", null));

        assertThat(onePickStore.saveIfAbsent(picked.pick(postAuthor.id()))).isPresent();
        assertThat(onePickStore.saveIfAbsent(picked.pick(secondPicker.id()))).isPresent();

        Post counted = postStore.findById(postEntity.id()).orElseThrow();
        assertThat(counted.commentCount()).isEqualTo(3L);
        assertThat(counted.commenterCount()).isEqualTo(2L);

        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        mockMvc.perform(get("/api/posts/{postId}/comments", postEntity.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.commentCount").value(3))
                .andExpect(jsonPath("$.returnObject.comments[0].id").value(picked.id()))
                .andExpect(jsonPath("$.returnObject.comments[0].onePickCount").value(2))
                .andExpect(jsonPath("$.returnObject.comments[0].mine").value(false))
                .andExpect(jsonPath("$.returnObject.comments[0].createdAgo").isString());

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2L);
    }

    @Test
    void rejectsBlankOrTooLongContent() throws Exception {
        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.id())
                        .header("Authorization", bearer(commentAuthorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        String tooLong = "가".repeat(301);
        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.id())
                        .header("Authorization", bearer(commentAuthorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsWritingOnDeletedPost() throws Exception {
        Post loaded = postStore.findById(postEntity.id()).orElseThrow();
        loaded.delete();
        postStore.save(loaded);

        mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.id())
                        .header("Authorization", bearer(commentAuthorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"삭제된 글의 댓글\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private User saveUser(String providerId, String name) {
        return userStore.save(new User(SocialProvider.GOOGLE, providerId, null, name));
    }

    private Long writeThroughApi(String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/posts/{postId}/comments", postEntity.id())
                        .header("Authorization", bearer(commentAuthorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.returnObject.id");
        return id.longValue();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
