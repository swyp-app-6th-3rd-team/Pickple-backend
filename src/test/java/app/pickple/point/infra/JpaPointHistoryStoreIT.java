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
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JpaPointHistoryStoreIT {

    @Autowired
    private PointHistoryStore pointStore;

    @Autowired
    private OnePickStore pickStore;

    @Autowired
    private CommentStore commentStore;

    @Autowired
    private PostStore postStore;

    @Autowired
    private UserStore userStore;

    private Long authorId;
    private Long pickerId;
    private Long onePickId;

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        authorId = userStore.save(new User(SocialProvider.GOOGLE, "pt-author-" + seed, null, "작성자")).id();
        pickerId = userStore.save(new User(SocialProvider.GOOGLE, "pt-picker-" + seed, null, "픽커")).id();

        Post post = postStore.save(
                new Post(authorId, PostType.GENERAL, PostCategory.ETC, "포인트 대상", null));
        Comment comment = commentStore.save(new Comment(post.id(), authorId, "좋은 의견", null));
        onePickId = pickStore.saveIfAbsent(comment.pick(pickerId)).orElseThrow();
    }

    @Test
    @DisplayName("원픽 하나가 두 사람에게 지급한다 (R-12)")
    void onePickGrantsTwoPeople() {
        pointStore.saveIfAbsent(PointHistory.forPick(authorId, PointReason.PICKED, onePickId));
        pointStore.saveIfAbsent(PointHistory.forPick(pickerId, PointReason.PICKING, onePickId));

        assertThat(pointStore.sumByUser(authorId)).isEqualTo(10L);
        assertThat(pointStore.sumByUser(pickerId)).isEqualTo(5L);
    }

    @Test
    @DisplayName("같은 원픽·사유로는 한 번만 적립된다 (R-13)")
    void duplicateGrantReturnsEmpty() {
        // 조회 후 삽입은 동시 요청에서 두 번 지급된다. 멱등키가 원자적으로 막는다.
        pointStore.saveIfAbsent(PointHistory.forPick(authorId, PointReason.PICKED, onePickId));

        assertThat(pointStore.saveIfAbsent(
                PointHistory.forPick(authorId, PointReason.PICKED, onePickId))).isEmpty();
        assertThat(pointStore.sumByUser(authorId)).isEqualTo(10L);
    }

    @Test
    @DisplayName("사유가 다르면 같은 원픽이어도 지급된다")
    void differentReasonIsSeparateGrant() {
        // 멱등키는 (원픽, 사유) 쌍이다. 원픽 하나만으로는 안 된다 — R-12 가 두 건을 요구한다.
        pointStore.saveIfAbsent(PointHistory.forPick(authorId, PointReason.PICKED, onePickId));
        pointStore.saveIfAbsent(PointHistory.forPick(authorId, PointReason.PICKING, onePickId));

        assertThat(pointStore.sumByUser(authorId)).isEqualTo(15L);
    }

    @Test
    @DisplayName("금액은 사유가 정한다 — 호출자가 못 정한다")
    void amountComesFromReason() {
        pointStore.saveIfAbsent(PointHistory.forPick(authorId, PointReason.PICKED, onePickId));

        assertThat(pointStore.sumByUser(authorId)).isEqualTo(PointReason.PICKED.amount());
    }

    @Test
    @DisplayName("적립이 없으면 합계는 0이다")
    void emptyLedgerSumsToZero() {
        // SUM 은 행이 없으면 NULL 이다. COALESCE 가 없으면 여기서 터진다.
        assertThat(pointStore.sumByUser(pickerId)).isZero();
    }

    @Test
    @DisplayName("출처 원픽 없이는 만들 수 없다")
    void onePickIdRequired() {
        assertThatThrownBy(() -> PointHistory.forPick(authorId, PointReason.PICKED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("멱등키");
    }
}
