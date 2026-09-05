package app.pickple.comment.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.OnePick;
import app.pickple.comment.domain.DuplicatePickException;
import app.pickple.comment.domain.OnePickStore;
import app.pickple.comment.domain.PostCommenterStore;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JpaOnePickStoreIT {

    @Autowired
    private OnePickStore pickStore;

    @Autowired
    private CommentStore commentStore;

    @Autowired
    private PostCommenterStore commenterStore;

    @Autowired
    private PostStore postStore;

    @Autowired
    private UserStore userStore;

    private Long authorId;
    private Long pickerId;
    private Post post;

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        authorId = userStore.save(new User(SocialProvider.GOOGLE, "cm-author-" + seed, null, "작성자")).id();
        pickerId = userStore.save(new User(SocialProvider.GOOGLE, "cm-picker-" + seed, null, "픽커")).id();
        post = postStore.save(new Post(authorId, PostType.GENERAL, PostCategory.ETC, "댓글 대상", null));
    }

    private Comment newComment(Long writerId) {
        return commentStore.save(new Comment(post.id(), writerId, "의견입니다", null));
    }

    @Test
    @DisplayName("원픽이 저장되고 세어진다")
    void savesAndCounts() {
        Comment comment = newComment(authorId);

        Long pickId = pickStore.save(comment.pick(pickerId));

        assertThat(pickId).isNotNull();
        assertThat(pickStore.countByComment(comment.id())).isEqualTo(1L);
        assertThat(pickStore.countByPost(post.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("저장된 원픽을 사용자와 게시글로 조회한다")
    void findsPickByPickerAndPost() {
        Comment comment = newComment(authorId);
        assertThat(pickStore.findByPickerIdAndPostId(pickerId, post.id())).isEmpty();
        pickStore.save(comment.pick(pickerId));
        assertThat(pickStore.findByPickerIdAndPostId(pickerId, post.id())).contains(comment.pick(pickerId));
    }

    @Test
    @DisplayName("조회 이후에 중복 저장해도 유니크 제약을 원픽 중복으로 변환한다")
    void duplicateSaveThrowsDomainException() {
        Comment comment = newComment(authorId);
        pickStore.save(comment.pick(pickerId));
        assertThatThrownBy(() -> pickStore.save(comment.pick(pickerId)))
                .isInstanceOf(DuplicatePickException.class);
    }

    @Test
    @DisplayName("여러 사람이 같은 댓글을 픽할 수 있다")
    void multipleUsersCanPick() {
        // 유일성 범위는 (user_id, post_id) 다 — 사람이 다르면 같은 댓글도 픽할 수 있다.
        Comment comment = newComment(authorId);
        Long another = userStore.save(
                new User(SocialProvider.GOOGLE, "cm-p2-" + System.nanoTime(), null, "픽커2")).id();

        pickStore.save(comment.pick(pickerId));
        pickStore.save(comment.pick(another));

        assertThat(pickStore.countByComment(comment.id())).isEqualTo(2L);
    }

    @Test
    @DisplayName("다른 게시글의 댓글은 픽할 수 없다")
    void crossPostPickRejected() {
        // 복합 FK (comment_id, post_id) 가 막는다.
        Comment comment = newComment(authorId);
        Post otherPost = postStore.save(
                new Post(authorId, PostType.GENERAL, PostCategory.ETC, "다른 글", null));

        OnePick forged = new OnePick(comment.id(), otherPost.id(), pickerId);

        // 중복이 아닌 위반은 그대로 올라온다 — 중복으로 뭉개면 원인을 못 찾는다.
        assertThatThrownBy(() -> pickStore.save(forged))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("첫 댓글만 인원으로 세어진다 (R-25)")
    void onlyFirstCommentCounts() {
        // 한 사람이 열 번 달아도 1이다. 판정은 유니크 키가 한다.
        assertThat(commenterStore.recordIfFirst(post.id(), pickerId)).isTrue();
        assertThat(commenterStore.recordIfFirst(post.id(), pickerId)).isFalse();
        assertThat(commenterStore.recordIfFirst(post.id(), authorId)).isTrue();

        assertThat(commenterStore.countByPost(post.id())).isEqualTo(2L);
    }
}
