package app.pickple.comment.domain;

/** 같은 사람이 한 게시글에서 두 번 픽했다 (R-05). */
public class DuplicatePickException extends RuntimeException {

    public DuplicatePickException(Long postId, Long pickerId) {
        super("이 게시글에서 이미 원픽했습니다: postId=%d, pickerId=%d".formatted(postId, pickerId));
    }
}
