package app.pickple.comment.service;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.DuplicatePickException;
import app.pickple.comment.domain.OnePickStore;
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

/**
 * 원픽 정책이 서비스에서 조립되는지 확인한다 (ADR-0019).
 *
 * <p>저장소 테스트({@code JpaOnePickStoreIT})는 "삽입됐는가" 라는 사실을 본다.
 * 여기서는 그 사실이 <b>정책으로 해석되는지</b>를 본다.
 */
@IntegrationTest
class OnePickServiceIT {

    @Autowired
    private OnePickService onePickService;

    @Autowired
    private CommentStore commentStore;

    @Autowired
    private OnePickStore pickStore;

    @Autowired
    private PointHistoryStore pointStore;

    @Autowired
    private PostStore postStore;

    @Autowired
    private UserStore userStore;

    private Long authorId;
    private Long pickerId;
    private Comment comment;

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        authorId = userStore.save(new User(SocialProvider.GOOGLE, "svc-author-" + seed, null, "작성자")).id();
        pickerId = userStore.save(new User(SocialProvider.GOOGLE, "svc-picker-" + seed, null, "픽커")).id();

        Post post = postStore.save(
                new Post(authorId, PostType.GENERAL, PostCategory.ETC, "원픽 대상", null));
        comment = commentStore.save(new Comment(post.id(), authorId, "도움이 되는 댓글", null));
    }

    @Test
    @DisplayName("원픽 한 번에 두 사람이 포인트를 받는다 (R-12)")
    void onePickGrantsBothSides() {
        // 이 규칙은 댓글·원픽·포인트 세 도메인이 얽혀 서비스에만 놓일 수 있다.
        onePickService.pick(comment.id(), pickerId);

        assertThat(pointStore.sumByUser(authorId)).isEqualTo(PointReason.PICKED.amount());
        assertThat(pointStore.sumByUser(pickerId)).isEqualTo(PointReason.PICKING.amount());
    }

    @Test
    @DisplayName("이미 픽했으면 정책 예외가 된다 (R-26)")
    void duplicatePickIsPolicyViolation() {
        // 저장소는 빈 값을 주고, 그것을 위반으로 해석하는 것이 서비스다.
        onePickService.pick(comment.id(), pickerId);

        assertThatThrownBy(() -> onePickService.pick(comment.id(), pickerId))
                .isInstanceOf(DuplicatePickException.class)
                .hasMessageContaining("이미 원픽한 댓글");
    }

    @Test
    @DisplayName("중복 픽이 막히면 포인트도 늘지 않는다")
    void duplicatePickDoesNotGrantAgain() {
        onePickService.pick(comment.id(), pickerId);
        long before = pointStore.sumByUser(authorId);

        assertThatThrownBy(() -> onePickService.pick(comment.id(), pickerId))
                .isInstanceOf(DuplicatePickException.class);

        assertThat(pointStore.sumByUser(authorId)).isEqualTo(before);
    }

    @Test
    @DisplayName("자기 댓글은 픽할 수 없다 — 도메인이 먼저 막는다 (R-07)")
    void cannotPickOwnComment() {
        // 서비스까지 오지 않고 Comment.pick() 에서 걸린다.
        assertThatThrownBy(() -> onePickService.pick(comment.id(), authorId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("자기 댓글");

        assertThat(pickStore.countByComment(comment.id())).isZero();
    }

    @Test
    @DisplayName("여러 사람이 같은 댓글을 픽하면 각자 받는다")
    void multiplePickersEachGranted() {
        Long another = userStore.save(
                new User(SocialProvider.GOOGLE, "svc-p2-" + System.nanoTime(), null, "픽커2")).id();

        onePickService.pick(comment.id(), pickerId);
        onePickService.pick(comment.id(), another);

        // 댓글 작성자는 두 번 받는다 — 픽마다 별도 멱등키다.
        assertThat(pointStore.sumByUser(authorId)).isEqualTo(PointReason.PICKED.amount() * 2);
        assertThat(pointStore.sumByUser(pickerId)).isEqualTo(PointReason.PICKING.amount());
        assertThat(pointStore.sumByUser(another)).isEqualTo(PointReason.PICKING.amount());
    }

    @Test
    @DisplayName("없는 댓글은 픽할 수 없다")
    void missingCommentRejected() {
        assertThatThrownBy(() -> onePickService.pick(-1L, pickerId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }
}
