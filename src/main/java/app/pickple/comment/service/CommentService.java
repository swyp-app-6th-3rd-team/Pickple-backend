package app.pickple.comment.service;

import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.PostCommenterStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.post.service.ActivePostGuard;
import app.pickple.post.domain.PostCounters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글 작성과 그에 딸린 집계 갱신.
 *
 * <p><b>여기가 서비스인 이유</b> — R-25(한 사람이 여러 번 달아도 인원은 1)는
 * 댓글과 게시글 집계 두 도메인에 걸친다. 댓글 객체 혼자서는 "이 사람이 처음인가" 를 알 수 없다.
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentStore commentStore;
    private final ActivePostGuard activePost;
    private final PostCommenterStore commenterStore;
    private final PostCounters counters;

    /**
     * 댓글을 쓰고 집계를 올린다.
     *
     * <p>인기순의 입력은 댓글 <b>건수</b>가 아니라 <b>인원 수</b>다 (R-24).
     * 첫 댓글 판정은 유니크 키가 원자적으로 한다 — 조회 후 삽입하면
     * 동시 댓글에서 둘 다 "처음" 으로 판정되어 카운터가 두 번 오른다.
     */
    @Transactional
    public Comment write(Comment comment) {
        activePost.requireActive(comment.postId());
        Comment saved = commentStore.save(comment);

        counters.increaseCommentCount(saved.postId());
        if (commenterStore.recordIfFirst(saved.postId(), saved.authorId())) {
            counters.increaseCommenterCount(saved.postId());
        }
        return saved;
    }

    /** 작성자만 자신의 활성 댓글 내용을 바꿀 수 있다. */
    @Transactional
    public Comment edit(Long commentId, Long requesterId, String content) {
        Comment comment = findActiveForUpdate(commentId);
        requireAuthor(comment, requesterId);
        comment.edit(content);
        return commentStore.save(comment);
    }

    /**
     * 댓글을 지운다.
     *
     * <p>소프트 삭제라 행은 남는다. 건수만 줄이고 <b>인원은 줄이지 않는다</b> —
     * 그 사람의 다른 댓글이 남아 있을 수 있고, 인원을 정확히 되돌리려면
     * 남은 댓글을 세야 해서 삭제가 무거워진다. 인기순이 조금 후하게 남는 쪽을 택한다.
     */
    @Transactional
    public void delete(Long commentId, Long requesterId) {
        Comment comment = findActiveForUpdate(commentId);
        requireAuthor(comment, requesterId);
        comment.delete();
        commentStore.save(comment);
        counters.decreaseCommentCount(comment.postId());
    }

    private Comment findActiveForUpdate(Long commentId) {
        Comment comment = commentStore.findByIdForUpdate(commentId)
                .orElseThrow(() -> new ApiException(
                        ResponseCode.NOT_FOUND, "댓글을 찾을 수 없습니다: id=" + commentId));
        if (comment.isDeleted()) {
            throw new ApiException(ResponseCode.NOT_FOUND, "삭제된 댓글입니다: id=" + commentId);
        }
        return comment;
    }

    private void requireAuthor(Comment comment, Long requesterId) {
        if (!comment.isWrittenBy(requesterId)) {
            throw new ApiException(
                    ResponseCode.FORBIDDEN,
                    "댓글 작성자만 수정하거나 삭제할 수 있습니다: id=" + comment.id());
        }
    }
}
