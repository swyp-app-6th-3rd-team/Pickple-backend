package app.pickple.comment.service;

import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.PostCommenterStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.post.domain.PostCounters;
import app.pickple.item.domain.AttachType;
import app.pickple.item.service.AttachableContainerGuard;
import app.pickple.post.service.ActivePostGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    private static final long POST_ID = 10L;
    private static final long COMMENT_ID = 20L;
    private static final long AUTHOR_ID = 30L;

    @Mock
    private CommentStore commentStore;
    @Mock
    private ActivePostGuard activePost;
    @Mock
    private AttachableContainerGuard attachableContainer;
    @Mock
    private PostCommenterStore commenterStore;
    @Mock
    private PostCounters counters;

    private CommentService commentService;

    @BeforeEach
    void setUp() {
        commentService = new CommentService(commentStore, activePost, attachableContainer, commenterStore, counters);
    }

    @Test
    void writesCommentAndIncreasesBothCountsForFirstCommenter() {
        Comment comment = new Comment(POST_ID, AUTHOR_ID, "첫 댓글", null);
        Comment saved = activeComment("첫 댓글");
        given(commentStore.save(comment)).willReturn(saved);
        given(commenterStore.recordIfFirst(POST_ID, AUTHOR_ID)).willReturn(true);

        assertThat(commentService.write(comment)).isSameAs(saved);

        verify(activePost).requireActive(POST_ID);
        verify(counters).increaseCommentCount(POST_ID);
        verify(counters).increaseCommenterCount(POST_ID);
    }

    @Test
    void repeatedCommenterOnlyIncreasesCommentCount() {
        Comment comment = new Comment(POST_ID, AUTHOR_ID, "또 댓글", null);
        given(commentStore.save(comment)).willReturn(activeComment("또 댓글"));
        given(commenterStore.recordIfFirst(POST_ID, AUTHOR_ID)).willReturn(false);

        commentService.write(comment);

        verify(counters).increaseCommentCount(POST_ID);
        verify(counters, never()).increaseCommenterCount(POST_ID);
    }

    @Test
    void authorCanEditLockedActiveComment() {
        Comment comment = activeComment("수정 전");
        given(commentStore.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.of(comment));
        given(commentStore.save(comment)).willAnswer(invocation -> invocation.getArgument(0));

        Comment edited = commentService.edit(COMMENT_ID, AUTHOR_ID, "수정 후");

        assertThat(edited.content()).isEqualTo("수정 후");
        verify(commentStore).save(comment);
    }

    @Test
    void nonAuthorCannotEdit() {
        Comment comment = activeComment("수정 전");
        given(commentStore.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.edit(COMMENT_ID, 999L, "침범"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.FORBIDDEN));

        verify(commentStore, never()).save(comment);
    }

    @Test
    void authorCanDeleteOnceAndCountIsDecreasedOnce() {
        Comment comment = activeComment("삭제할 댓글");
        given(commentStore.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.of(comment));
        given(commentStore.save(comment)).willAnswer(invocation -> invocation.getArgument(0));

        commentService.delete(COMMENT_ID, AUTHOR_ID);

        assertThat(comment.isDeleted()).isTrue();
        verify(counters).decreaseCommentCount(POST_ID);
    }

    @Test
    void nonAuthorCannotDelete() {
        Comment comment = activeComment("남의 댓글");
        given(commentStore.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.delete(COMMENT_ID, 999L))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.FORBIDDEN));

        verify(commentStore, never()).save(comment);
        verify(counters, never()).decreaseCommentCount(POST_ID);
    }

    @Test
    void missingOrDeletedCommentIsNotFound() {
        given(commentStore.findByIdForUpdate(404L)).willReturn(Optional.empty());
        Comment deleted = Comment.restore(COMMENT_ID, POST_ID, AUTHOR_ID, "삭제됨", null, true);
        given(commentStore.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.of(deleted));

        assertThatThrownBy(() -> commentService.edit(404L, AUTHOR_ID, "수정"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.NOT_FOUND));
        assertThatThrownBy(() -> commentService.delete(COMMENT_ID, AUTHOR_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.NOT_FOUND));
    }

    @Test
    void rejectsContainerThatIsNotUsableForComments() {
        // 상품용 컨테이너를 댓글에 붙이려는 요청. 관문이 막아 저장까지 가지 않아야 한다.
        Comment comment = new Comment(POST_ID, AUTHOR_ID, "사진 댓글", 99L);
        willThrow(new ApiException(ResponseCode.INVALID_REQUEST, "PRODUCT 용 컨테이너를 COMMENT 에 붙일 수 없습니다."))
                .given(attachableContainer).requireUsableAs(99L, AttachType.COMMENT);

        assertThatThrownBy(() -> commentService.write(comment))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.INVALID_REQUEST));

        // DB 까지 내려가면 복합 FK 가 DataIntegrityViolationException 으로 뭉갠다.
        verify(commentStore, never()).save(comment);
        verify(counters, never()).increaseCommentCount(POST_ID);
    }

    @Test
    void checksContainerPurposeBeforeSaving() {
        Comment comment = new Comment(POST_ID, AUTHOR_ID, "사진 댓글", 99L);
        given(commentStore.save(comment)).willReturn(activeComment("사진 댓글"));

        commentService.write(comment);

        verify(attachableContainer).requireUsableAs(99L, AttachType.COMMENT);
    }

    private Comment activeComment(String content) {
        return Comment.restore(COMMENT_ID, POST_ID, AUTHOR_ID, content, null, false);
    }
}
