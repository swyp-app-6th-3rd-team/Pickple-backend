package app.pickple.auth.security;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.RefreshTokenStore;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AccountWithdrawalPersistenceService;
import app.pickple.auth.service.JwtService;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.service.CommentService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴 회원 차단 관문의 완료 판정 (이슈 #106, PRD-021).
 *
 * <p>실서버에서 <b>탈퇴 전 발급받은 액세스 토큰</b>(TTL 30분)으로 댓글 201, 투표 200,
 * 원픽 201 이 났다. 탈퇴 회원이 게스트보다 권한이 많았다.
 * 여기서 재현하는 것은 그 토큰이다 — 탈퇴 <b>전</b>에 발급하고, 탈퇴 <b>후</b>에 쓴다.
 * 탈퇴 후 발급을 시도하면 애초에 다른 경로로 막히므로 결함을 재현하지 못한다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 관문의 상태 조회는 서비스 트랜잭션
 * 밖에서 별도 커넥션으로 돈다({@code open-in-view: false}). 테스트가 연 트랜잭션 안에서
 * 탈퇴를 커밋하지 않으면 관문이 그 변경을 보지 못해, 결함이 고쳐졌는지 아닌지를
 * 판정할 수 없다. 롤백 대신 실행마다 고유한 픽스처를 만들고 직접 지운다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("탈퇴 회원 차단 관문 (#106)")
class WithdrawnUserAuthorizationIT {

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
    private JwtService jwtService;
    @Autowired
    private AccountWithdrawalPersistenceService withdrawalPersistenceService;
    @Autowired
    private RefreshTokenStore refreshTokenStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdPostIds = new ArrayList<>();

    private MockMvc mockMvc;
    private User author;
    private User withdrawn;
    private User active;
    private Post post;
    private Long optionId;
    private Long commentId;

    /** 탈퇴 <b>전</b>에 발급한 토큰. 이 결함의 핵심 재료다. */
    private String withdrawnToken;
    private String activeToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        long seed = System.nanoTime();
        author = saveUser("gate-author-" + seed);
        withdrawn = saveUser("gate-withdrawn-" + seed);
        active = saveUser("gate-active-" + seed);

        // 탈퇴 전에 발급한다. 실서버에서 일어난 순서 그대로다.
        withdrawnToken = jwtService.createAccessToken(withdrawn);
        activeToken = jwtService.createAccessToken(active);

        post = saveAgreePost("탈퇴 차단 검증용");
        optionId = jdbcTemplate.queryForObject(
                "SELECT id FROM post_option WHERE post_id = ? AND display_order = 1",
                Long.class, post.id());
        commentId = commentService.write(
                new Comment(post.id(), author.id(), "원픽 대상 댓글", null)).id();

        withdraw(withdrawn);
    }

    @AfterEach
    void tearDown() {
        // 순서가 곧 FK 그래프의 역순이다. point_history -> comment_pick -> comment -> post.
        // comment_pick 은 (comment_id, post_id) 복합 FK 로 comment 를 참조하는데 CASCADE 가
        // 없어(R-05 를 지키는 그 FK), 게시글부터 지우면 무결성 위반이 난다.
        for (Long postId : createdPostIds) {
            jdbcTemplate.update("DELETE FROM point_history WHERE comment_pick_id IN "
                    + "(SELECT id FROM comment_pick WHERE post_id = ?)", postId);
            jdbcTemplate.update("DELETE FROM comment_pick WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM vote WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM comment WHERE post_id = ?", postId);
            jdbcTemplate.update("DELETE FROM post WHERE id = ?", postId);
        }
        createdPostIds.clear();
    }

    @Nested
    @DisplayName("쓰기 경로 — 탈퇴 전 토큰으로 뚫리던 세 곳")
    class WriteePaths {

        @Test
        @DisplayName("C-1 탈퇴 회원은 댓글을 쓸 수 없고 행이 늘지 않는다")
        void cannotWriteComment() throws Exception {
            long before = commentCount();

            mockMvc.perform(post("/posts/{postId}/comments", post.id())
                            .header("Authorization", bearer(withdrawnToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"탈퇴 후 댓글\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            // 상태 코드만으로는 부족하다 — 거부됐는데 행이 남는 경우를 배제한다.
            assertThat(commentCount()).isEqualTo(before);
        }

        @Test
        @DisplayName("C-2 탈퇴 회원은 투표할 수 없고 투표 인원이 늘지 않는다")
        void cannotVote() throws Exception {
            mockMvc.perform(post("/posts/{postId}/votes", post.id())
                            .header("Authorization", bearer(withdrawnToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"optionId\":" + optionId + "}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            assertThat(voteCount()).isZero();
        }

        @Test
        @DisplayName("C-3 탈퇴 회원은 원픽할 수 없고 포인트 원장이 늘지 않는다")
        void cannotPick() throws Exception {
            long beforeLedger = pointLedgerCount();

            mockMvc.perform(post("/comments/{commentId}/pick", commentId)
                            .header("Authorization", bearer(withdrawnToken)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

            assertThat(pickCount()).isZero();
            // 이 결함의 실제 피해는 여기다. 원픽은 PICKING +5 / PICKED +10 을 적립하고,
            // 그 포인트가 TOP 피커 랭킹의 입력이 된다(ADR-0028).
            assertThat(pointLedgerCount()).isEqualTo(beforeLedger);
        }

        @Test
        @DisplayName("C-4 정상 회원은 세 경로가 그대로 성공한다")
        void activeUserIsUnaffected() throws Exception {
            mockMvc.perform(post("/posts/{postId}/comments", post.id())
                            .header("Authorization", bearer(activeToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"정상 회원 댓글\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/posts/{postId}/votes", post.id())
                            .header("Authorization", bearer(activeToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"optionId\":" + optionId + "}"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/comments/{commentId}/pick", commentId)
                            .header("Authorization", bearer(activeToken)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("공개 경로 — 관문이 막아서는 안 되는 곳")
    class PublicPaths {

        @Test
        @DisplayName("C-5 게스트의 공개 목록은 그대로 200 이다")
        void guestStillReadsPublicLists() throws Exception {
            mockMvc.perform(get("/posts")).andExpect(status().isOk());
            mockMvc.perform(get("/posts/popular")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("C-6 탈퇴자 토큰을 보내도 공개 게시글 목록은 200 이다")
        void withdrawnUserStillReadsPublicPostLists() throws Exception {
            mockMvc.perform(get("/posts")
                            .header("Authorization", bearer(withdrawnToken)))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/posts/popular")
                            .header("Authorization", bearer(withdrawnToken)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("인증 경로 예외 — 관문이 대신할 수 없는 곳")
    class AuthPaths {

        @Test
        @DisplayName("탈퇴 재호출이 200 이 아니라 401 이 된다 — 의식적으로 수용한 계약 변경")
        void repeatedWithdrawalIsNoLongerIdempotent() throws Exception {
            // 이전에는 `DELETE /auth/me` 재호출이 멱등하게 200 이었다(E2E §D-5).
            // 중앙 차단이 들어가면 두 번째 호출이 관문에 막혀 401 이 된다.
            //
            // 외부에 보이는 계약 변경이라 <b>테스트로 명시한다.</b> 이걸 적어 두지 않으면
            // 나중에 누가 "왜 멱등성이 깨졌지" 하고 되돌리려 할 수 있다 —
            // 이미 탈퇴한 계정이 탈퇴 API 를 다시 부르는 것은 정상 흐름이 아니고,
            // 관문에 예외를 뚫는 비용이 그 멱등성의 가치보다 크다고 판단했다(PRD-021).
            // 이 계약 변경은 #108 에 알린다.
            mockMvc.perform(delete("/auth/me").header("Authorization", bearer(withdrawnToken)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("C-7 탈퇴자도 로그아웃해 쿠키를 지울 수 있다")
        void withdrawnUserCanStillLogout() throws Exception {
            // 자격증명을 버리는 데 ACTIVE 를 요구할 이유가 없다.
            mockMvc.perform(post("/auth/logout")
                            .header("Authorization", bearer(withdrawnToken)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("C-8 탈퇴 전에 받은 유효한 리프레시 토큰이 401 로 거부된다")
        void withdrawnUserCannotRefresh() throws Exception {
            // permitAll 이고 액세스 토큰 없이도 불리므로 중앙 관문이 대신할 수 없다.
            // 없는 토큰을 넣어 "4xx 면 통과" 로 두면 이 판정은 아무것도 증명하지 못한다 —
            // 토큰 조회 실패 경로만 타고 탈퇴자 분기에는 닿지도 않는다.
            // 그래서 <b>탈퇴 전에 발급해 저장까지 된</b> 토큰으로 확인한다.
            User target = saveUser("gate-refresh-" + System.nanoTime());
            String refreshToken = jwtService.createRefreshToken(target);
            refreshTokenStore.store(target.id(), JwtService.hash(refreshToken),
                    jwtService.refreshTokenExpiresAt());
            assertThat(refreshTokenStore.findByUserId(target.id())).isPresent();

            withdraw(target);

            // 탈퇴는 리프레시 토큰 행도 함께 지운다
            // (AccountWithdrawalPersistenceService). 그래서 거부 사유는 "비활성 계정" 이
            // 아니라 "그런 토큰이 없다" 이고, 코드는 401 INVALID_TOKEN 이다.
            // 실서버 E2E 에서 관측된 것과 같은 값이다.
            assertThat(refreshTokenStore.findByUserId(target.id())).isEmpty();

            mockMvc.perform(post("/auth/refresh")
                            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refreshToken)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        }
    }

    @Nested
    @DisplayName("우회 경로 — 강등을 피해 신원이 되살아나는 길이 있나")
    class BypassPaths {

        @Test
        @DisplayName("에러 응답에도 탈퇴자 신원이 실리지 않는다")
        void errorDispatchDoesNotResurrectIdentity() throws Exception {
            // OncePerRequestFilter 는 기본적으로 ERROR 디스패치에서 다시 돌지 않는다
            // (shouldNotFilterErrorDispatch=true). 강등이 "비우기" 라서 되살아날 원본이
            // 없다고 보지만, 그 판단이 맞는지는 실행으로 확인한다.
            // 없는 경로 → 404 이지만, 401 이 아닌 200 계열이 나오면 신원이 살아 있다는 뜻이다.
            mockMvc.perform(get("/posts/{postId}/comments/does-not-exist", post.id())
                            .header("Authorization", bearer(withdrawnToken)))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("잘못된 본문으로 거부돼도 탈퇴자가 컨트롤러에 닿지 않는다")
        void malformedBodyStillBlockedAtTheGate() throws Exception {
            // 400(본문 검증)이 아니라 401(관문)이어야 한다 — 관문이 컨트롤러보다 앞이라는 증거다.
            // 400 이 나오면 탈퇴자가 이미 핸들러까지 들어왔다는 뜻이다.
            mockMvc.perform(post("/posts/{postId}/comments", post.id())
                            .header("Authorization", bearer(withdrawnToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"   \"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("강등을 건너뛰는 경로여도 보호 경로면 관문이 막는다 — 두 겹이다")
        void gateStillBlocksEvenWhenDemotionIsSkipped() throws Exception {
            // shouldNotFilter 목록이 잘못 넓어져 보호 경로가 거기 들어가면
            // 강등 마커가 남지 않는다. 그때 관문은 마커가 없으므로 <b>직접 조회</b>해
            // 여전히 막아야 한다 — 그것이 관문을 남겨둔 이유다.
            //
            // 여기서는 그 두 번째 겹이 실제로 도는지를 본다. 관문이 마커에만 의존한다면
            // 목록이 넓어지는 순간 조용히 뚫린다.
            var manager = context.getBean(ActiveAccountAuthorizationManager.class);
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            var authentication = new org.springframework.security.authentication
                    .UsernamePasswordAuthenticationToken(
                    new AuthenticatedPrincipal(withdrawn.id(), app.pickple.auth.domain.Role.ROLE_USER),
                    null, java.util.List.of());

            // 마커 없이(= 강등을 건너뛴 상태) 관문에 물어본다.
            var decision = manager.authorize(() -> authentication,
                    new org.springframework.security.web.access.intercept.RequestAuthorizationContext(request));

            assertThat(decision).isNotNull();
            assertThat(decision.isGranted())
                    .as("강등을 건너뛴 요청이라도 탈퇴자는 관문이 직접 조회해 막아야 한다")
                    .isFalse();
        }

        @Test
        @DisplayName("정적·문서 경로는 토큰이 붙어도 막히지 않는다")
        void staticAndDocPathsStayOpen() throws Exception {
            // permitAll 이고 관문이 돌지 않으므로 강등되든 말든 통과해야 한다.
            // 다만 강등 필터는 shouldNotFilter 를 두지 않아 이 경로에서도 조회가 돈다 —
            // 정적 경로에 토큰을 붙이는 트래픽이 드물어 감수하는 비용이다(PRD-021 참조).
            mockMvc.perform(get("/llms.txt").header("Authorization", bearer(withdrawnToken)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("관리 포트 체인은 별개다 — 서비스 경로의 관문이 actuator 를 막지 않는다")
        void managementChainIsUnaffected() throws Exception {
            // @Order(1) 체인은 permitAll 이고 이 필터들을 달지 않는다.
            // 헬스체크가 관문 때문에 막히면 배포 파이프라인이 죽는다.
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("관문 적용 범위")
    class GateCoverage {

        @Test
        @DisplayName("C-10 보호 경로 전반이 한 관문에 걸린다 — 세 곳만 고친 것이 아니다")
        void gateCoversEveryProtectedPath() throws Exception {
            // 이 결함이 세 곳에서 동시에 터진 이유는 확인이 산발적이었기 때문이다.
            // 관문이 .anyRequest() 에 걸려 있으면 손대지 않은 경로도 함께 막힌다.
            // 그 사실을 고정해 둔다 — 새 보호 엔드포인트가 자동으로 보호된다는 근거다.
            String[] untouchedProtectedPaths = {
                    "/auth/me", "/users/me", "/users/me/points",
                    "/users/me/grade", "/users/me/badges", "/users/me/activities"
            };
            for (String path : untouchedProtectedPaths) {
                mockMvc.perform(get(path).header("Authorization", bearer(withdrawnToken)))
                        .andExpect(status().isUnauthorized());
                // 대조군 — 같은 경로가 정상 회원에게는 열려 있다.
                // 이게 없으면 "전부 401 인 설정" 과 구분되지 않는다.
                mockMvc.perform(get(path).header("Authorization", bearer(activeToken)))
                        .andExpect(status().isOk());
            }
        }
    }

    // --- 픽스처 -----------------------------------------------------------

    private User saveUser(String providerId) {
        return userStore.save(new User(SocialProvider.GOOGLE, providerId, null, "테스터"));
    }

    /**
     * 탈퇴를 <b>커밋</b>한다. 관문은 서비스 트랜잭션 밖에서 별도로 읽으므로
     * ({@code open-in-view: false}) 커밋되지 않은 변경은 보지 못한다.
     *
     * <p>직접 {@code save} 하지 않고 실제 탈퇴 서비스를 부른다 — 트랜잭션 경계가 필요하고
     * (변경 감지가 flush 되려면), 무엇보다 <b>제품 코드가 실제로 쓰는 경로</b>로 탈퇴시켜야
     * 이 테스트가 재현하는 상태가 운영의 그것과 같아진다.
     */
    private void withdraw(User user) {
        withdrawalPersistenceService.complete(user.id());
        assertThat(userStore.findById(user.id()).orElseThrow().isActive()).isFalse();
    }

    private Post saveAgreePost(String title) {
        Long containerId = containerStore.save(new ItemContainer(author.id(), AttachType.PRODUCT)
                .add(new ItemResource(1024L, "bag.jpg",
                        "product-images/%d/%d.jpg".formatted(author.id(), System.nanoTime()),
                        "https://cdn.test/bag-" + System.nanoTime()))).id();
        Post saved = postStore.save(new Post(author.id(), PostType.AGREE, PostCategory.LIVING, title, "설명")
                .addProduct(new PostProduct(containerId, "가방", 100_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2)));
        createdPostIds.add(saved.id());
        return saved;
    }

    // --- 검증 도구 ---------------------------------------------------------

    private long commentCount() {
        return count("SELECT COUNT(*) FROM comment WHERE post_id = ?", post.id());
    }

    private long voteCount() {
        return count("SELECT COUNT(*) FROM vote WHERE post_id = ?", post.id());
    }

    private long pickCount() {
        return count("SELECT COUNT(*) FROM comment_pick WHERE comment_id = ?", commentId);
    }

    private long pointLedgerCount() {
        return count("SELECT COUNT(*) FROM point_history WHERE user_id = ?", withdrawn.id());
    }

    private long count(String sql, Object arg) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, arg);
        return value == null ? 0L : value;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
