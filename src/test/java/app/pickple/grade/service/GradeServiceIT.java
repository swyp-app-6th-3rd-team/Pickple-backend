package app.pickple.grade.service;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.OnePickStore;
import app.pickple.grade.domain.Grade;
import app.pickple.grade.domain.GradeProgress;
import app.pickple.grade.domain.GradeStore;
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
import app.pickple.vote.domain.Vote;
import app.pickple.vote.domain.VoteStore;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 등급 조회와 승급 (ADR-0030).
 *
 * <p>이슈 #25 완료 판정 중 R-16(포인트가 줄어도 등급이 내려가지 않는다)과
 * 가입 직후 상태를 여기서 확인한다.
 *
 * <p><b>원장의 금액은 SQL 로 직접 넣는다.</b> 도메인 API({@code PointHistory.forPick})는
 * 사유가 금액을 정하므로 10P·5P 단위로만 쌓이고, LV.3 조건인 1,000P 를 만들려면
 * 원픽 100건과 그만큼의 게시글·댓글이 필요하다. 이 테스트가 확인하는 것은
 * <b>합계에서 등급이 나오는가</b>이지 지급 경로가 아니다(그쪽은 {@code JpaPointHistoryStoreIT} 가 본다).
 * FK 가 요구하는 원픽 1건만 도메인 API 로 실제로 만든다.
 */
@IntegrationTest
class GradeServiceIT {

    @Autowired
    private GradeService gradeService;

    @Autowired
    private GradeStore gradeStore;

    @Autowired
    private VoteStore voteStore;

    @Autowired
    private OnePickStore pickStore;

    @Autowired
    private CommentStore commentStore;

    @Autowired
    private PostStore postStore;

    @Autowired
    private ItemContainerStore containerStore;

    @Autowired
    private UserStore userStore;

    @Autowired
    private EntityManager entityManager;

    /**
     * 네이티브 쓰기용 트랜잭션 경계.
     *
     * <p>헬퍼에 {@code @Transactional} 을 붙여도 걸리지 않는다 — 선언적 트랜잭션은
     * 프록시 기반이라 같은 인스턴스 안에서 부르면 프록시를 우회한다(self-invocation).
     *
     * <p>테스트 메서드 전체를 트랜잭션으로 묶는 대안은 택하지 않았다. 그러면 조회 시점
     * 승급(서비스의 쓰기)이 같은 트랜잭션에 섞여 실제 요청과 다른 조건에서 검증하게 된다.
     */
    private TransactionTemplate transaction;

    private Long userId;
    private Long otherId;
    private Long thirdId;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        userId = userStore.save(new User(SocialProvider.GOOGLE, "grade-svc-" + seed, null, "회원")).id();
        otherId = userStore.save(new User(SocialProvider.GOOGLE, "grade-peer-" + seed, null, "타인")).id();
        // 원픽은 자기 댓글에 할 수 없다 (R-07). 작성자와 픽하는 사람을 갈라 둔다.
        thirdId = userStore.save(new User(SocialProvider.GOOGLE, "grade-picker-" + seed, null, "픽커")).id();
    }

    @Test
    @DisplayName("가입 직후는 LV.1 · 0P · 0회 · 달성률 0% 다")
    void freshUserIsLv1() {
        GradeProgress progress = gradeService.readMyGrade(userId);

        assertThat(progress.grade()).isEqualTo(Grade.LV1);
        assertThat(progress.point()).isZero();
        assertThat(progress.voteCount()).isZero();
        assertThat(progress.nextGrade()).contains(Grade.LV2);
        assertThat(progress.achievementRate()).isZero();
    }

    @Test
    @DisplayName("조건을 채우면 조회 시점에 승급하고 저장된다")
    void promotesOnRead() {
        seedPoint(200L);
        seedVotes(20);

        assertThat(gradeService.readMyGrade(userId).grade()).isEqualTo(Grade.LV2);
        // 판정만 하고 저장하지 않으면 R-16 이 붙잡을 값이 남지 않는다.
        assertThat(gradeStore.readHighestGrade(userId)).isEqualTo(Grade.LV2);
    }

    @Test
    @DisplayName("포인트가 줄어도 등급이 내려가지 않는다 (R-16)")
    void gradeNeverFallsBack() {
        seedPoint(1_000L);
        seedVotes(100);
        assertThat(gradeService.readMyGrade(userId).grade()).isEqualTo(Grade.LV3);

        // 회수 정책이 생긴 상황을 원장 조작으로 흉내낸다 — 애플리케이션엔 차감 경로가 없다.
        // 그 부재가 곧 highest_grade 를 저장한 이유다(ADR-0030).
        clearLedger();
        assertThat(gradeStore.readInputs(userId).reachedGrade()).isEqualTo(Grade.LV1);

        // 그럼에도 등급은 유지된다 — 저장된 도달 등급이 붙잡는다.
        GradeProgress afterDeduction = gradeService.readMyGrade(userId);
        assertThat(afterDeduction.grade()).isEqualTo(Grade.LV3);
        assertThat(gradeStore.readHighestGrade(userId)).isEqualTo(Grade.LV3);

        // 포인트 자체는 줄어든 값을 그대로 보여준다 — 등급만 내려가지 않는다 (R-14).
        assertThat(afterDeduction.point()).isZero();
    }

    @Test
    @DisplayName("투표 횟수가 모자라면 포인트를 채워도 오르지 않는다 (R-15)")
    void pointAloneDoesNotPromote() {
        seedPoint(10_000L);
        seedVotes(19);

        assertThat(gradeService.readMyGrade(userId).grade()).isEqualTo(Grade.LV1);
        assertThat(gradeStore.readHighestGrade(userId)).isEqualTo(Grade.LV1);
    }

    @Test
    @DisplayName("포인트가 모자라면 투표를 채워도 오르지 않는다 (R-15)")
    void voteAloneDoesNotPromote() {
        seedPoint(199L);
        seedVotes(20);

        assertThat(gradeService.readMyGrade(userId).grade()).isEqualTo(Grade.LV1);
    }

    @Test
    @DisplayName("반복 조회가 등급을 흔들지 않는다")
    void repeatedReadsAreStable() {
        seedPoint(200L);
        seedVotes(20);

        // 조회에 쓰기가 있지만 오를 때만 일어난다. 두 번째 조회는 읽기뿐이다.
        assertThat(gradeService.readMyGrade(userId).grade()).isEqualTo(Grade.LV2);
        assertThat(gradeService.readMyGrade(userId).grade()).isEqualTo(Grade.LV2);
    }

    @Test
    @DisplayName("전체 등급 기준은 낮은 등급부터 다섯 개다")
    void allGradesAreOrdered() {
        assertThat(gradeService.readAllGrades())
                .containsExactly(Grade.LV1, Grade.LV2, Grade.LV3, Grade.LV4, Grade.LV5);
    }

    /**
     * 원장에 합계가 {@code total} 이 되도록 한 행을 넣는다.
     *
     * <p>{@code comment_pick_id} 는 FK 이자 멱등키라 실제 원픽이 필요하다 —
     * 그 한 건만 도메인 API 로 만들고 금액은 직접 넣는다.
     */
    private void seedPoint(long total) {
        Post post = postStore.save(
                new Post(otherId, PostType.GENERAL, PostCategory.ETC, "포인트 대상", null));
        Comment comment = commentStore.save(new Comment(post.id(), otherId, "의견", null));
        Long pickId = pickStore.save(comment.pick(thirdId));

        transaction.executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        INSERT INTO point_history (user_id, amount, reason, comment_pick_id, created_at)
                        VALUES (:userId, :amount, 'PICKED', :pickId, NOW())
                        """)
                        .setParameter("userId", userId)
                        .setParameter("amount", total)
                        .setParameter("pickId", pickId)
                        .executeUpdate());
    }

    /** 서로 다른 게시글에 {@code count} 번 투표한다 — 한 게시글엔 한 번뿐이다 (R-09). */
    private void seedVotes(int count) {
        for (int i = 0; i < count; i++) {
            Post post = agreePost();
            voteStore.save(new Vote(post.id(), post.options().getFirst().id(), userId));
        }
    }

    private Post agreePost() {
        Long containerId = containerStore.save(new ItemContainer(otherId, AttachType.PRODUCT)
                .add(new ItemResource(1L, "p.jpg", "s3/" + System.nanoTime(), "https://cdn/x"))).id();
        return postStore.save(
                new Post(otherId, PostType.AGREE, PostCategory.ETC, "투표 대상", null)
                        .addProduct(new PostProduct(containerId, "상품", 1000L, null, 1))
                        .addOption(PostOption.ofLabel("사자", 1))
                        .addOption(PostOption.ofLabel("말자", 2)));
    }

    private void clearLedger() {
        transaction.executeWithoutResult(status ->
                entityManager.createNativeQuery("DELETE FROM point_history WHERE user_id = :userId")
                        .setParameter("userId", userId)
                        .executeUpdate());
    }
}
