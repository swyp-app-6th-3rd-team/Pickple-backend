package app.pickple.point.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.OnePickStore;
import app.pickple.point.domain.PointHistory;
import app.pickple.point.domain.PointHistoryStore;
import app.pickple.point.domain.PointReason;
import app.pickple.point.service.RankingBatchService;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 피커 랭킹 API (이슈 #26) 의 완료 판정을 실제 MySQL 로 확인한다.
 *
 * <p><b>왜 매번 테이블을 비우는가</b> — 순위는 전역 값이라 "이 테스트가 만든 회원만"
 * 보는 방법이 없다. {@code PostControllerIT} 는 쓰지 않는 카테고리로 창을 좁히지만
 * 랭킹에는 그런 필터가 없다. {@code JpaRankingStoreIT} 와 같은 방식으로 지운다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다 — 붙이면 MockMvc 요청이
 * 별도 커넥션에서 도는 동안 픽스처가 아직 커밋되지 않아 목록이 비어 보인다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RankingControllerIT {

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
    private OnePickStore pickStore;
    @Autowired
    private PointHistoryStore pointStore;
    @Autowired
    private RankingBatchService rankingBatch;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private MockMvc mockMvc;
    private long seed;

    /**
     * 모든 원픽을 이 한 사람이 한다.
     *
     * <p>픽커도 활성 회원이라 순위를 받는다 — grant 마다 새로 만들면 회원 수가
     * 픽 횟수만큼 불어나 "사용자 10명" 같은 전제가 성립하지 않는다.
     * 이 사람은 PICKED 를 받지 못하므로(픽한 쪽은 PICKING 이고 이 픽스처는 쓰지 않는다)
     * 0P 로 항상 목록 맨 뒤에 선다.
     */
    private User picker;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        seed = System.nanoTime();

        // FK 검사를 끄고 지운다. 순서를 손으로 맞추면 users 를 참조하는 테이블이
        // 하나 늘 때마다 이 목록이 조용히 깨진다(JpaRankingStoreIT 와 같은 이유).
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
            for (String table : new String[]{
                    "point_history", "comment_pick", "post_commenter", "comment", "vote",
                    "post_option", "post_product", "post", "item_resource", "item_container",
                    "user_refresh_token", "apple_provider_token", "users"}) {
                entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate();
            }
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
        });

        picker = newUser("picker");
    }

    // ─────────────────────────────────────────────────────────────
    // 완료 판정 4 — 포인트 보유자가 없을 때 빈 배열
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TOP 피커가 없으면 200 과 빈 배열을 준다")
    void emptyTopReturnsEmptyArray() throws Exception {
        // 서버는 "아직 TOP 피커가 존재하지 않아요" 문구를 만들지 않는다.
        // 빈 배열이 그 상태를 말하고, 문구는 화면의 몫이다.
        mockMvc.perform(get("/rankings/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject").isArray())
                .andExpect(jsonPath("$.returnObject").isEmpty());

        mockMvc.perform(get("/rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content").isEmpty())
                .andExpect(jsonPath("$.returnObject.hasNext").value(false))
                .andExpect(jsonPath("$.returnObject.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("회원이 있어도 배치 전에는 목록이 비어 있다")
    void unrankedUsersAreNotListed() throws Exception {
        // 순위가 없는 사람을 순위 목록에 넣을 자리가 없다.
        // 0 이나 "전체 + 1" 로 채우지 않는다 (ADR-0028).
        newUser("no-batch");
        newUser("no-batch-2");

        mockMvc.perform(get("/rankings/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject").isEmpty());
    }

    // ─────────────────────────────────────────────────────────────
    // 완료 판정 2 — 상위 피커가 정확히 5명 이하
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("사용자 10명이어도 상위 피커는 5명이다")
    void topIsCappedAtFive() throws Exception {
        for (int i = 0; i < 10; i++) {
            grant(newUser("top-" + i), i + 1);
        }
        rankingBatch.refresh();

        mockMvc.perform(get("/rankings/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.length()").value(5))
                // 1위부터 차례로 나온다.
                .andExpect(jsonPath("$.returnObject[0].ranking").value(1))
                .andExpect(jsonPath("$.returnObject[4].ranking").value(5))
                // 포인트가 높은 쪽이 앞이다. 10건 준 사람이 100P 로 1위다.
                .andExpect(jsonPath("$.returnObject[0].point").value(100));
    }

    @Test
    @DisplayName("size 를 키워도 상한을 넘지 못한다")
    void topSizeIsBounded() throws Exception {
        for (int i = 0; i < 10; i++) {
            grant(newUser("bound-" + i), i + 1);
        }
        rankingBatch.refresh();

        // 이 경로로 목록 전체를 뽑아 무한 스크롤을 우회하지 못하게 한다.
        // 상한(50)에 걸리므로, 회원이 11명(대상 10 + 픽커 1)인 지금은 전원이 나온다.
        mockMvc.perform(get("/rankings/top?size=100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.length()").value(11));
    }

    // ─────────────────────────────────────────────────────────────
    // 완료 판정 1 — 동점자가 가입일 빠른 순
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("동점 3명이 가입일 빠른 순으로 나온다")
    void tieIsBrokenByRegistrationOrder() throws Exception {
        // 셋 다 +10P 로 같다. 갈리는 것은 가입 순서뿐이다.
        User first = newUser("tie-1");
        User second = newUser("tie-2");
        User third = newUser("tie-3");
        grant(first, 1);
        grant(second, 1);
        grant(third, 1);
        rankingBatch.refresh();

        mockMvc.perform(get("/rankings/top"))
                .andExpect(status().isOk())
                // 공동 순위가 아니라 전순서다 — 1, 1, 3 이 아니라 1, 2, 3 이다.
                .andExpect(jsonPath("$.returnObject[0].userId").value(first.id()))
                .andExpect(jsonPath("$.returnObject[0].ranking").value(1))
                .andExpect(jsonPath("$.returnObject[1].userId").value(second.id()))
                .andExpect(jsonPath("$.returnObject[1].ranking").value(2))
                .andExpect(jsonPath("$.returnObject[2].userId").value(third.id()))
                .andExpect(jsonPath("$.returnObject[2].ranking").value(3));
    }

    // ─────────────────────────────────────────────────────────────
    // 완료 판정 5 — 게스트 요청 시 본인 랭킹 필드가 없음
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("게스트는 랭킹 목록을 보지만 본인 랭킹은 받지 못한다")
    void guestGetsNoMyRanking() throws Exception {
        grant(newUser("guest-view"), 2);
        rankingBatch.refresh();

        // 목록 자체는 게스트에게 열려 있다 (§2.5·§3.1).
        // 대상 1명 + 픽커 1명. 픽커는 0P 라 뒤에 선다.
        mockMvc.perform(get("/rankings/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.length()").value(2));
        mockMvc.perform(get("/rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content.length()").value(2));

        // 본인 랭킹은 별도 엔드포인트이고 인증을 요구한다 — 토큰이 없으면 401 이다.
        // 응답 어디에도 "내 순위" 에 해당하는 값이 실리지 않는다.
        mockMvc.perform(get("/users/me/points"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("게스트 목록 응답에는 본인 랭킹 필드 자체가 없다")
    void guestListHasNoMyRankingField() throws Exception {
        grant(newUser("no-field"), 1);
        rankingBatch.refresh();

        // 응답 모양이 로그인 여부에 따라 갈리지 않는다 — 목록은 언제나 목록이다.
        mockMvc.perform(get("/rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.myRanking").doesNotExist())
                .andExpect(jsonPath("$.returnObject.me").doesNotExist());
    }

    // ─────────────────────────────────────────────────────────────
    // 완료 판정 3 — 조회된 포인트가 이력 합계와 일치 (R-14)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("응답 포인트가 원장 합계와 같다")
    void pointMatchesLedgerSum() throws Exception {
        User user = newUser("ledger");
        grant(user, 3);   // PICKED +10 × 3
        rankingBatch.refresh();

        long ledgerSum = pointStore.sumByUser(user.id());
        assertThat(ledgerSum).isEqualTo(30L);

        // 응답이 캐시 컬럼에서 오지만 그 값의 근거는 원장이다.
        mockMvc.perform(get("/rankings/top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject[0].point").value((int) ledgerSum));

        mockMvc.perform(get("/users/me/points").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.point").value((int) ledgerSum));
    }

    // ─────────────────────────────────────────────────────────────
    // §7.3 — 내 포인트와 순위
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("본인 조회는 순위와 포인트를 준다")
    void mineExposesRankingAndPoint() throws Exception {
        User me = newUser("mine");
        User other = newUser("other");
        grant(other, 5);   // 50P — 이쪽이 1위
        grant(me, 1);      // 10P — 2위
        rankingBatch.refresh();

        mockMvc.perform(get("/users/me/points").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.userId").value(me.id()))
                .andExpect(jsonPath("$.returnObject.ranking").value(2))
                .andExpect(jsonPath("$.returnObject.point").value(10));
    }

    @Test
    @DisplayName("아직 순위가 없으면 ranking 필드가 응답에서 빠진다")
    void unrankedMineOmitsRankingField() throws Exception {
        // 가입 직후 다음 배치까지가 이 상태다. 0 으로 채우면 "아직 모른다" 가
        // "0위" 라는 거짓이 된다 (ADR-0028).
        User fresh = newUser("fresh");

        mockMvc.perform(get("/users/me/points").header("Authorization", bearer(fresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.ranking").doesNotExist())
                // 순위가 없어도 포인트는 있다 — 비는 것은 순위 하나뿐이다.
                .andExpect(jsonPath("$.returnObject.point").value(0));
    }

    // ─────────────────────────────────────────────────────────────
    // §3.1 — 무한 스크롤
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("커서로 이어 읽으면 중복도 누락도 없다")
    void cursorWalksEveryRowExactlyOnce() throws Exception {
        int targets = 25;
        for (int i = 0; i < targets; i++) {
            grant(newUser("scroll-" + i), i + 1);
        }
        // 대상 25명 + 픽커 1명이 순위를 받는다.
        int total = targets + 1;
        rankingBatch.refresh();

        List<Integer> seen = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            MvcResult result = mockMvc.perform(
                            get("/rankings" + (cursor == null ? "" : "?cursor=" + cursor)))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.returnObject.content[*].ranking"));

            if (!Boolean.TRUE.equals(JsonPath.read(body, "$.returnObject.hasNext"))) {
                break;
            }
            cursor = JsonPath.read(body, "$.returnObject.nextCursor");
        }

        // 25명을 10개 단위로 나눠 세 조각. 순위 1..25 가 정확히 한 번씩 나온다.
        assertThat(seen).hasSize(total);
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).isSorted();
        assertThat(seen.get(0)).isEqualTo(1);
        assertThat(seen.get(total - 1)).isEqualTo(total);
    }

    @Test
    @DisplayName("기본 조각 크기는 10 이다")
    void defaultSliceIsTen() throws Exception {
        for (int i = 0; i < 12; i++) {
            grant(newUser("slice-" + i), i + 1);
        }
        rankingBatch.refresh();

        mockMvc.perform(get("/rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content.length()").value(10))
                .andExpect(jsonPath("$.returnObject.hasNext").value(true))
                .andExpect(jsonPath("$.returnObject.nextCursor").isString());
    }

    @Test
    @DisplayName("조작된 커서는 400 이다")
    void tamperedCursorIsRejected() throws Exception {
        mockMvc.perform(get("/rankings?cursor=not-a-real-cursor"))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────
    // 픽스처
    // ─────────────────────────────────────────────────────────────

    private User newUser(String tag) {
        return userStore.save(new User(SocialProvider.GOOGLE, "rk-" + tag + "-" + seed, null, tag));
    }

    /**
     * 원픽 {@code times} 건으로 PICKED(+10) 를 쌓는다.
     *
     * <p>포인트를 직접 UPDATE 하지 않는다 — 정본은 원장이고(R-14) 배치가 원장에서
     * 유도한다. 컬럼에 직접 쓰면 다음 배치가 그 값을 원장 합계로 되돌린다.
     */
    private void grant(User user, int times) {
        Long userId = user.id();
        for (int i = 0; i < times; i++) {
            // 픽하는 사람을 매번 새로 만들지 않는다. 픽커도 활성 회원이라 순위를 받으므로,
            // grant 마다 회원이 늘면 "10명 중 5명" 같은 단정이 성립하지 않는다.
            // 원픽의 멱등키는 (comment_pick_id, reason) 이고 유일성 범위는 게시글이라
            // (ADR-0020) 한 사람이 서로 다른 글의 댓글을 여러 번 픽할 수 있다.
            Post post = postStore.save(
                    new Post(userId, PostType.GENERAL, PostCategory.ETC, "랭킹 대상", null));
            Comment comment = commentStore.save(new Comment(post.id(), userId, "의견", null));
            Long pickId = pickStore.saveIfAbsent(comment.pick(picker.id())).orElseThrow();
            pointStore.saveIfAbsent(PointHistory.forPick(userId, PointReason.PICKED, pickId));
        }
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.createAccessToken(user);
    }
}
