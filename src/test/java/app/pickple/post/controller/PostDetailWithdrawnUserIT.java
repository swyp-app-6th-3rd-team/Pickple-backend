package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AccountWithdrawalPersistenceService;
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
import app.pickple.vote.service.VoteService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴 회원 토큰으로 게시글 상세를 조회하는 경로 (ADR-0035 강등 관문 · ADR-0046 응답 계약).
 *
 * <p>{@code GET /posts/{id}} 는 permitAll 이고 {@code AnonymousDemotionFilter} 의
 * 강등 대상 경로다(개인화하는 공개 경로라 {@code SKIP_DEMOTION} 에 없다). 탈퇴 <b>전</b>에
 * 발급한 토큰으로 부르면 필터가 신원을 익명으로 강등하므로, 응답은 게스트와 같아야 한다 —
 * {@code voted}·{@code mine} 이 false 이고 득표율도 보이지 않는다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> {@code open-in-view: false} 라
 * {@code AnonymousDemotionFilter} 의 계정 상태 조회가 서비스 트랜잭션과 다른 커넥션으로 돈다.
 * 테스트 트랜잭션 안에서 탈퇴를 커밋하지 않으면 필터가 그 변경을 보지 못해 강등이 재현되지
 * 않는다({@code WithdrawnUserAuthorizationIT} 와 같은 이유). 롤백 대신 픽스처를 직접 정리한다.
 *
 * <p>탈퇴한 <b>작성자</b>의 경로도 여기서 본다 (ADR-0040 · R-20). 첫 판(PR #128)에는 없던 테스트다 —
 * 그 사이 #111 이 탈퇴 시 닉네임·이름·프로필 이미지를 즉시 파기하기로 정했고, 글은 남으므로 상세는
 * 목록(C-8)과 같은 비식별 표기를 따라야 한다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PostDetailWithdrawnUserIT {

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
    private AccountWithdrawalPersistenceService withdrawalPersistenceService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private User author;
    private User withdrawn;
    private String withdrawnToken;
    private Long postId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        long seed = System.nanoTime();
        author = saveUser("detail-withdraw-author-" + seed);
        withdrawn = saveUser("detail-withdraw-voter-" + seed);

        // 탈퇴 전에 발급한다 — 실서버에서 결함이 재현된 순서 그대로다.
        withdrawnToken = jwtService.createAccessToken(withdrawn);

        Post post = saveAgreePost("탈퇴자 상세 조회 검증");
        postId = post.id();
        Long optionId = jdbcTemplate.queryForObject(
                "SELECT id FROM post_option WHERE post_id = ? AND display_order = 1",
                Long.class, postId);
        voteService.castOrChange(postId, optionId, withdrawn.id());

        withdrawalPersistenceService.complete(withdrawn.id());
        assertThat(userStore.findById(withdrawn.id()).orElseThrow().isActive()).isFalse();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM vote WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM post_option WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM post_product WHERE post_id = ?", postId);
        jdbcTemplate.update("DELETE FROM post WHERE id = ?", postId);
    }

    @Test
    @DisplayName("탈퇴자 토큰으로 조회하면 강등돼 게스트와 같은 응답을 받는다 — voted·mine 이 false, 득표율 부재")
    void withdrawnUserTokenIsDemotedToGuestResponse() throws Exception {
        // 탈퇴자 본인이 투표한 글인데도, 강등 뒤에는 그 이력이 응답에 드러나면 안 된다.
        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(withdrawnToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.vote.voted").value(false))
                .andExpect(jsonPath("$.returnObject.vote.selectedOptionId").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[0].voteCount").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[0].percentage").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[1].voteCount").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[1].percentage").doesNotExist())
                // 탈퇴자는 작성자가 아니지만, "본인 확인"이 강등으로 무력화되는지도 함께 본다.
                .andExpect(jsonPath("$.returnObject.mine").value(false));
    }

    @Test
    @DisplayName("탈퇴한 작성자의 글은 남고 작성자만 '알 수 없음' 으로 나온다 — 등급은 그대로다 (ADR-0040 · R-20)")
    void withdrawnAuthorPostStaysWithMaskedAuthor() throws Exception {
        // 파기 전에는 닉네임과 프로필이 그대로 실린다 — 그래야 아래 "가려짐" 이 파기의 효과임을 안다.
        jdbcTemplate.update("UPDATE users SET nickname = ?, profile_image_url = ? WHERE id = ?",
                "글쓴이", "https://cdn.test/author.png", author.id());
        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.authorNickname").value("글쓴이"))
                .andExpect(jsonPath("$.returnObject.authorProfileImageUrl").value("https://cdn.test/author.png"));

        // 제품 코드가 실제로 쓰는 탈퇴 경로로 파기시킨다 (PostControllerIT C-8 과 같은 방식).
        withdrawalPersistenceService.complete(author.id());

        mockMvc.perform(get("/posts/{id}", postId))
                // 글은 404 가 아니다 — 콘텐츠는 남긴다 (R-20). 목록에서 탭한 글이 상세에서 사라지면 안 된다.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.authorId").value(author.id()))
                .andExpect(jsonPath("$.returnObject.authorNickname").value("알 수 없음"))
                .andExpect(jsonPath("$.returnObject.authorProfileImageUrl").value(nullValue()))
                // 등급은 개인정보가 아니라 활동의 결과라 파기되지 않는다.
                .andExpect(jsonPath("$.returnObject.authorGradeLevel").value(1))
                .andExpect(jsonPath("$.returnObject.authorGradeName").value("LV.1"))
                // 투표 영역도 그대로다 — 탈퇴자가 남긴 표는 인원 수에 남는다.
                .andExpect(jsonPath("$.returnObject.vote.voterCount").value(1));
    }

    // --- 픽스처 -------------------------------------------------------------

    private User saveUser(String providerId) {
        return userStore.save(new User(SocialProvider.GOOGLE, providerId, null, "탈퇴검증"));
    }

    private Post saveAgreePost(String title) {
        Long containerId = containerStore.save(new ItemContainer(author.id(), AttachType.PRODUCT)
                .add(new ItemResource(1024L, "bag.jpg",
                        "product-images/%d/%d.jpg".formatted(author.id(), System.nanoTime()),
                        "https://cdn.test/bag-" + System.nanoTime()))).id();
        return postStore.save(new Post(author.id(), PostType.AGREE, PostCategory.LIVING, title, "설명")
                .addProduct(new PostProduct(containerId, "가방", 100_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2)));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
