package app.pickple.point.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.OnePickStore;
import app.pickple.point.domain.PointHistory;
import app.pickple.point.domain.PointHistoryStore;
import app.pickple.point.domain.PointReason;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.point.domain.RankingStore;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 랭킹 사전 계산 검증 (ADR-0028).
 *
 * <p>여기서 확인하는 것은 <b>순위 정의</b>다 — 포인트 내림차순, 동점이면 가입이 빠른 쪽.
 * 성능은 이 테스트가 판정하지 못한다(픽스처가 몇 명뿐이다). 그건 PR 의 측정표가 맡는다.
 */
@IntegrationTest
class JpaRankingStoreIT {

    @Autowired
    private RankingStore rankingStore;

    @Autowired
    private PointHistoryStore pointStore;

    @Autowired
    private UserStore userStore;

    @Autowired
    private PostStore postStore;

    @Autowired
    private ItemContainerStore containerStore;

    @Autowired
    private CommentStore commentStore;

    @Autowired
    private OnePickStore pickStore;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long seed;
    private int pickerSequence;

    @BeforeEach
    void setUp() {
        seed = System.nanoTime();
        pickerSequence = 0;
        // 다른 IT 가 남긴 회원이 순위에 섞이면 절대 등수를 단정할 수 없다.
        // 등수는 전역 값이라 "이 테스트가 만든 회원" 만 보는 방법이 없다.
        //
        // FK 검사를 끄고 지운다. 순서를 손으로 맞추면 users 를 참조하는 테이블이
        // 하나 늘 때마다 이 목록이 조용히 깨진다(실제로 item_container 를 빠뜨려 실패했다).
        // 전부 지우는 자리라 매달릴 참조 자체가 남지 않아 안전하다.
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
    }

    @Test
    @DisplayName("포인트가 많은 쪽이 앞선다")
    void higherPointRanksFirst() {
        Long low = newUser("low");
        Long high = newUser("high");
        grant(high, 3);   // +30
        grant(low, 1);    // +10

        refresh();

        assertThat(rankingOf(high)).isEqualTo(1);
        assertThat(rankingOf(low)).isEqualTo(2);
    }

    @Test
    @DisplayName("동점이면 가입이 빠른 쪽이 앞선다 — 공동 순위가 아니다")
    void tieIsBrokenByRegistrationOrder() {
        // 용어사전은 "가입이 빠른 쪽이 앞선다" 고 쓴다. 앞선다는 것은 전순서를 말하므로
        // 동점자에게 같은 번호를 주는 RANK() 가 아니라 ROW_NUMBER() 여야 한다.
        Long first = newUser("tie-first");
        Long second = newUser("tie-second");
        grant(first, 1);
        grant(second, 1);

        refresh();

        assertThat(rankingOf(first)).isEqualTo(1);
        assertThat(rankingOf(second)).isEqualTo(2);
        // 공동 1위였다면 둘 다 1이고 다음이 3이 된다. 그렇지 않음을 못박는다.
        assertThat(rankingOf(first)).isNotEqualTo(rankingOf(second));
    }

    @Test
    @DisplayName("포인트가 없는 회원도 순위를 받는다")
    void zeroPointUserStillRanked() {
        Long earner = newUser("earner");
        Long idle = newUser("idle");
        grant(earner, 1);

        refresh();

        assertThat(rankingOf(earner)).isEqualTo(1);
        assertThat(rankingOf(idle)).isEqualTo(2);
    }

    @Test
    @DisplayName("배치 전에는 순위가 없다 — 0 이 아니라 null 이다")
    void unrankedIsNullNotZero() {
        Long user = newUser("fresh");

        // 0 으로 채우면 "아직 모른다" 가 "0위" 라는 거짓이 된다.
        assertThat(rankingOf(user)).isNull();
    }

    @Test
    @DisplayName("탈퇴하면 순위에서 빠진다")
    void withdrawnUserLosesRanking() {
        Long staying = newUser("staying");
        Long leaving = newUser("leaving");
        grant(leaving, 3);
        grant(staying, 1);
        refresh();
        assertThat(rankingOf(leaving)).isEqualTo(1);

        // JpaUserStore.save 는 기존 회원을 더티체킹으로 반영한다 — 트랜잭션 밖에서
        // 부르면 flush 가 일어나지 않아 state 가 그대로다. 운영 경로(탈퇴 서비스)는
        // @Transactional 안이라 문제가 없고, 여기서만 경계를 만들어 준다.
        transactionTemplate.executeWithoutResult(status -> {
            User user = userStore.findById(leaving).orElseThrow();
            user.withdraw();
            userStore.save(user);
        });
        refresh();

        // 탈퇴자는 순위를 갖지 않고, 남은 사람이 그 자리를 채운다.
        assertThat(rankingOf(leaving)).isNull();
        assertThat(rankingOf(staying)).isEqualTo(1);
    }

    @Test
    @DisplayName("포인트가 바뀌면 다음 배치에서 순위가 뒤집힌다")
    void rankingFollowsPointChange() {
        Long leader = newUser("leader");
        Long chaser = newUser("chaser");
        grant(leader, 2);   // +20
        grant(chaser, 1);   // +10
        refresh();
        assertThat(rankingOf(leader)).isEqualTo(1);

        // 추격자가 역전한다. 배치를 부르기 전까지 순위는 낡은 값 그대로다.
        grant(chaser, 2);   // 누적 +30
        assertThat(rankingOf(chaser)).isEqualTo(2);

        refresh();

        assertThat(rankingOf(chaser)).isEqualTo(1);
        assertThat(rankingOf(leader)).isEqualTo(2);
    }

    @Test
    @DisplayName("변동이 없으면 아무 행도 쓰지 않는다")
    void secondRunWritesNothing() {
        // 이 조건이 없으면 변동이 없어도 주기마다 회원 전체를 다시 쓴다.
        // 로그인 경로가 함께 쓰는 users 테이블에 얹히는 부담이라 실측으로 못박는다.
        grant(newUser("a"), 2);
        grant(newUser("b"), 1);
        refresh();

        assertThat(rankingStore.recalculateRankings()).isZero();
    }

    @Test
    @DisplayName("원장 합계가 users.point 로 옮겨진다 (R-14)")
    void pointsAreSyncedFromLedger() {
        Long user = newUser("ledger");
        grant(user, 2);

        // 지급 경로는 원장에만 쓴다. 배치가 옮기기 전에는 캐시 컬럼이 0 이다.
        assertThat(pointOf(user)).isZero();
        assertThat(pointStore.sumByUser(user)).isEqualTo(20L);

        rankingStore.syncPointsFromLedger();

        assertThat(pointOf(user)).isEqualTo(20);
    }

    @Test
    @DisplayName("투표 정본에서 users.vote_count 가 채워진다 (ADR-0032)")
    void voteCountsAreSyncedFromVotes() {
        // 이 컬럼은 V3 에 있지만 투표 경로가 채우지 않는다 — 올라가는 것은
        // post·post_option 의 카운터뿐이다. 이 단계가 없으면 등급 판정의
        // "투표 20회" 가 영원히 거짓이라 전원 LV.1 이 된다.
        Long voter = newUser("voter");
        castVotes(voter, 3);

        assertThat(voteCountOf(voter)).isZero();

        rankingStore.syncVoteCountsFromVotes();

        assertThat(voteCountOf(voter)).isEqualTo(3);
    }

    @Test
    @DisplayName("투표하지 않은 회원은 0 이다")
    void neverVotedStaysZero() {
        Long idle = newUser("idle-voter");

        rankingStore.syncVoteCountsFromVotes();

        assertThat(voteCountOf(idle)).isZero();
    }

    @Test
    @DisplayName("투표 수에 변동이 없으면 아무 행도 쓰지 않는다")
    void voteCountSyncWritesNothingWhenUnchanged() {
        // recalculateRankings 와 같은 이유다 — 변동이 없어도 주기마다 회원 전체를
        // 다시 쓰면 로그인 경로가 함께 쓰는 users 테이블에 부담이 얹힌다.
        castVotes(newUser("stable-voter"), 2);
        rankingStore.syncVoteCountsFromVotes();

        assertThat(rankingStore.syncVoteCountsFromVotes()).isZero();
    }

    /** 배치 한 주기와 같은 순서다. */
    private void refresh() {
        rankingStore.syncPointsFromLedger();
        rankingStore.syncVoteCountsFromVotes();
        rankingStore.recalculateRankings();
    }

    /**
     * 서로 다른 게시글에 {@code times} 번 투표한다.
     *
     * <p>같은 게시글에 다시 투표하면 {@code uk_vote_post_user} 때문에 행이 늘지 않는다 —
     * 그게 R-22 이고, {@code COUNT(*)} 가 누적 투표 횟수와 같은 이유이기도 하다.
     */
    private void castVotes(Long voterId, int times) {
        Long authorId = newUser("vote-target-" + voterId);
        for (int i = 0; i < times; i++) {
            // 찬반 게시글이어야 선택지가 있다. vote 의 FK 는 (post_option_id, post_id)
            // 복합이라(ERD 2.3) 다른 글의 선택지를 가리키면 통과하지 못한다 —
            // 선택지가 없는 일반 게시글로는 애초에 투표 행을 만들 수 없다.
            Long containerId = containerStore.save(
                    new ItemContainer(authorId, AttachType.PRODUCT)
                            .add(new ItemResource(1024L, "p.jpg",
                                    "product-images/%d/%d.jpg".formatted(authorId, System.nanoTime()),
                                    "https://cdn.test/p-" + System.nanoTime()))).id();
            Post post = postStore.save(
                    new Post(authorId, PostType.AGREE, PostCategory.ETC, "투표 대상", "설명")
                            .addProduct(new PostProduct(containerId, "상품", 1000L, null, 1))
                            .addOption(PostOption.ofLabel("사자", 1))
                            .addOption(PostOption.ofLabel("말자", 2)));
            Long optionId = post.options().get(0).id();
            transactionTemplate.executeWithoutResult(status ->
                    entityManager.createNativeQuery("""
                            INSERT INTO vote (post_id, post_option_id, user_id, created_at)
                            VALUES (:postId, :optionId, :userId, NOW())
                            """)
                            .setParameter("postId", post.id())
                            .setParameter("optionId", optionId)
                            .setParameter("userId", voterId)
                            .executeUpdate());
        }
    }

    private int voteCountOf(Long userId) {
        Number value = (Number) entityManager
                .createNativeQuery("SELECT vote_count FROM users WHERE id = :id")
                .setParameter("id", userId)
                .getSingleResult();
        return value.intValue();
    }

    private Long newUser(String tag) {
        return userStore.save(
                new User(SocialProvider.GOOGLE, "rank-" + tag + "-" + seed, null, tag)).id();
    }

    /**
     * 원픽 {@code times} 건으로 PICKED(+10) 를 쌓는다.
     *
     * <p>포인트를 직접 UPDATE 하지 않는 이유 — 정본은 원장이고(R-14) 배치가 원장에서
     * 유도한다. 컬럼에 직접 쓰면 다음 배치가 그 값을 원장 합계로 되돌려
     * 테스트가 검증하려던 상태가 사라진다.
     */
    private void grant(Long userId, int times) {
        for (int i = 0; i < times; i++) {
            // 카운터로 유일성을 만든다. (userId, i) 로는 grant 를 두 번 부를 때 겹친다.
            Long pickerId = userStore.save(new User(
                    SocialProvider.GOOGLE, "rank-picker-" + seed + "-" + (++pickerSequence), null, "픽커")).id();
            Post post = postStore.save(
                    new Post(userId, PostType.GENERAL, PostCategory.ETC, "랭킹 대상", null));
            Comment comment = commentStore.save(new Comment(post.id(), userId, "의견", null));
            Long pickId = pickStore.saveIfAbsent(comment.pick(pickerId)).orElseThrow();
            pointStore.saveIfAbsent(PointHistory.forPick(userId, PointReason.PICKED, pickId));
        }
    }

    private Integer rankingOf(Long userId) {
        return (Integer) entityManager
                .createNativeQuery("SELECT ranking FROM users WHERE id = :id")
                .setParameter("id", userId)
                .getSingleResult();
    }

    private int pointOf(Long userId) {
        Number value = (Number) entityManager
                .createNativeQuery("SELECT point FROM users WHERE id = :id")
                .setParameter("id", userId)
                .getSingleResult();
        return value.intValue();
    }
}
