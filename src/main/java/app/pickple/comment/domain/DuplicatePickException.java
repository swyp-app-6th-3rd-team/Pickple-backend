package app.pickple.comment.domain;

/** 같은 사람이 같은 댓글을 두 번 픽했다 (R-26). */
public class DuplicatePickException extends RuntimeException {

    public DuplicatePickException(Long commentId, Long pickerId) {
        super("이미 원픽한 댓글입니다: commentId=%d, pickerId=%d".formatted(commentId, pickerId));
    }
}
