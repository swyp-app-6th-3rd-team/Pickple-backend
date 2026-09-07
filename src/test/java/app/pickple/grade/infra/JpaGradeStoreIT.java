package app.pickple.grade.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.OnePickStore;
import app.pickple.grade.domain.Grade;
import app.pickple.grade.domain.GradeStore;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.point.domain.PointHistory;
import app.pickple.point.domain.PointHistoryStore;
import app.pickple.point.domain.PointReason;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.vote.domain.Vote;
import app.pickple.vote.domain.VoteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 승급 판정 입력값을 원장에서 읽는다 (ADR-0030).
 *
 * <p>이슈 #25 완료 판정 중 R-22(재투표가 횟수를 늘리지 않는다)와
 * R-14(저장된 포인트가 이력 합계와 일치한다)를 여기서 확인한다.
 */
@IntegrationTest
class JpaGradeStoreIT {

    @Autowired
    private GradeStore gradeStore;

    @Autowired
    private PointHistoryStore pointStore;

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
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long otherId;
    private Long thirdId;

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        userId = userStore.save(new User(SocialProvider.GOOGLE, "grade-user-" + seed, null, "회원")).id();
        otherId = userStore.save(new User(SocialProvider.GOOGLE, "grade-other-" + seed, null, "타인")).id();
        // 원픽은 자기 댓글에 할 수 없다 (R-07). 작성자와 픽하는 사람을 갈라 둔다.
        thirdId = userStore.save(new User(SocialProvider.GOOGLE, "grade-third-" + seed, null, "픽커")).id();
    }

    @Test
    @DisplayName("가입 직후 입력값은 0P·0회이고 등급은 LV.1 이다")
    void freshUserStartsEmpty() {
        GradeStore.GradeInputs inputs = gradeStore.readInputs(userId);

        assertThat(inputs.point()).isZero();
        assertThat(inputs.voteCount()).isZero();
        assertThat(inputs.reachedGrade()).isEqualTo(Grade.LV1);
        // 미산정 상태가 없다 — 정책표가 "가입 시 기본 부여" 라고 정했다.
        assertThat(gradeStore.readHighestGrade(userId)).isEqualTo(Grade.LV1);
    }

    @Test
    @DisplayName("누적 포인트는 이력 합계와 일치한다 (R-14)")
    void pointIsTheLedgerSum() {
        // 원픽 두 건으로 PICKED(+10) · PICKING(+5) 를 각각 만든다.
        grantPoint(PointReason.PICKED);
        grantPoint(PointReason.PICKING);

        GradeStore.GradeInputs inputs = gradeStore.readInputs(userId);

        // 원장이 정본이다. users.point 는 배치가 채우는 캐시라 여기선 여전히 0 이다.
        assertThat(inputs.point()).isEqualTo(pointStore.sumByUser(userId));
        assertThat(inputs.point()).isEqualTo(15L);
    }

    @Test
    @DisplayName("포인트 캐시 컬럼이 비어 있어도 판정은 원장을 읽는다 (ADR-0030)")
    void judgementIgnoresTheStaleCache() {
        // 랭킹 배치를 돌리지 않았으므로 users.point 는 0 이다.
        // 캐시를 읽도록 짰다면 여기서 0 이 나와 승급이 영원히 일어나지 않는다.
        grantPoint(PointReason.PICKED);

        assertThat(gradeStore.readInputs(userId).point()).isEqualTo(10L);
    }

    @Test
    @DisplayName("누적 투표 횟수는 투표한 게시글 수다")
    void voteCountCountsPosts() {
        castVote(agreePost(), 0);
        castVote(agreePost(), 0);

        assertThat(gradeStore.readInputs(userId).voteCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("재투표는 누적 투표 횟수를 늘리지 않는다 (R-22)")
    void revotingDoesNotIncreaseCount() {
        Post post = agreePost();
        castVote(post, 0);
        long before = gradeStore.readInputs(userId).voteCount();

        // 선택을 바꾼다. 새 행이 아니라 기존 행의 UPDATE 다 —
        // UNIQUE (post_id, user_id) 가 두 번째 행 자체를 허용하지 않는다.
        Vote existing = voteStore.findByPostAndVoter(post.id(), userId).orElseThrow();
        existing.changeTo(post.options().get(1).id());
        voteStore.save(existing);

        assertThat(gradeStore.readInputs(userId).voteCount()).isEqualTo(before);
        assertThat(gradeStore.readInputs(userId).voteCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("다른 사람의 포인트·투표는 내 판정에 섞이지 않는다")
    void inputsAreScopedToTheUser() {
        grantPoint(PointReason.PICKED);
        castVote(agreePost(), 0);

        assertThat(gradeStore.readInputs(otherId).point()).isZero();
        assertThat(gradeStore.readInputs(otherId).voteCount()).isZero();
    }

    @Test
    @DisplayName("포인트 이력과 투표가 함께 있어도 서로 부풀리지 않는다")
    void ledgerAndVotesDoNotMultiply() {
        // 조인으로 짰다면 카티전 곱이 생겨 합계가 투표 수만큼, 횟수가 이력 수만큼 부푼다.
        grantPoint(PointReason.PICKED);
        grantPoint(PointReason.PICKING);
        castVote(agreePost(), 0);
        castVote(agreePost(), 0);
        castVote(agreePost(), 0);

        GradeStore.GradeInputs inputs = gradeStore.readInputs(userId);

        assertThat(inputs.point()).isEqualTo(15L);
        assertThat(inputs.voteCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("도달 등급은 올라가고 내려가지 않는다 (R-16)")
    void highestGradeOnlyRises() {
        assertThat(gradeStore.raiseHighestGrade(userId, Grade.LV3)).isTrue();
        assertThat(gradeStore.readHighestGrade(userId)).isEqualTo(Grade.LV3);

        // 낮은 등급으로 부르면 아무 행도 갱신되지 않는다. 조건이 SQL 안에 있어
        // 읽고-비교하는 동안 끼어든 승급을 되돌리지 못한다.
        assertThat(gradeStore.raiseHighestGrade(userId, Grade.LV2)).isFalse();
        assertThat(gradeStore.readHighestGrade(userId)).isEqualTo(Grade.LV3);

        // 같은 등급도 올릴 것이 없다.
        assertThat(gradeStore.raiseHighestGrade(userId, Grade.LV3)).isFalse();
        assertThat(gradeStore.readHighestGrade(userId)).isEqualTo(Grade.LV3);

        assertThat(gradeStore.raiseHighestGrade(userId, Grade.LV5)).isTrue();
        assertThat(gradeStore.readHighestGrade(userId)).isEqualTo(Grade.LV5);
    }

    /**
     * 판정 입력값 조회가 두 서브쿼리 모두 인덱스를 타는지 실행 계획으로 확인한다.
     *
     * <p><b>왜 결과 단언으로는 부족한가.</b> 인덱스를 못 타도 결과는 똑같이 옳다 —
     * {@code point_history} 와 {@code vote} 를 전량 읽어 더하면 된다. 등급 조회는
     * 마이페이지 진입마다 불리므로 그 회귀는 정확성 테스트가 아니라 여기서만 드러난다.
     *
     * <p>SQL 을 여기 베끼지 않고 {@link GradeRepository#READ_INPUTS} 를 그대로 쓴다.
     * 베껴 두면 리포지토리가 바뀌어도 테스트는 옛 문장을 검사한다.
     */
    @Test
    @DisplayName("판정 입력값 조회는 두 서브쿼리 모두 user_id 선행 인덱스를 탄다")
    void readInputsUsesUserIndexes() {
        // 실제 경로를 한 번 태운다 — 프로젝션 별칭이 어긋나면 EXPLAIN 이전에 여기서 깨진다.
        gradeStore.readInputs(userId);

        String plan = String.join(" ", jdbcTemplate.queryForList(
                "EXPLAIN FORMAT=TREE " + GradeRepository.READ_INPUTS.replace(":userId", String.valueOf(userId)),
                String.class));

        assertThat(plan)
                .as("포인트 합계는 idx_point_user_created 로 좁혀져야 한다")
                .contains("Index lookup on ph using idx_point_user_created");
        assertThat(plan)
                .as("투표 횟수는 idx_vote_user_created 커버링 조회여야 한다")
                .contains("Covering index lookup on v using idx_vote_user_created");
        assertThat(plan)
                .as("테이블 스캔이 보이면 인덱스가 조건을 맡지 못한 것이다")
                .doesNotContain("Table scan");
    }

    /** 원픽 한 건을 만들어 이 회원에게 사유별 포인트를 적립한다. */
    private void grantPoint(PointReason reason) {
        Post post = postStore.save(
                new Post(otherId, PostType.GENERAL, PostCategory.ETC, "포인트 대상", null));
        Comment comment = commentStore.save(new Comment(post.id(), otherId, "의견", null));
        Long pickId = pickStore.saveIfAbsent(comment.pick(thirdId)).orElseThrow();
        pointStore.saveIfAbsent(PointHistory.forPick(userId, reason, pickId));
    }

    /** 찬반 게시글 하나. 투표 대상이 필요할 때마다 새로 만든다. */
    private Post agreePost() {
        Long containerId = containerStore.save(new ItemContainer(otherId, AttachType.PRODUCT)
                .add(new ItemResource(1L, "p.jpg", "s3/" + System.nanoTime(), "https://cdn/x"))).id();
        return postStore.save(
                new Post(otherId, PostType.AGREE, PostCategory.ETC, "투표 대상", null)
                        .addProduct(new PostProduct(containerId, "상품", 1000L, null, 1))
                        .addOption(PostOption.ofLabel("사자", 1))
                        .addOption(PostOption.ofLabel("말자", 2)));
    }

    private void castVote(Post post, int optionIndex) {
        voteStore.save(new Vote(post.id(), post.options().get(optionIndex).id(), userId));
    }
}
