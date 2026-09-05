package app.pickple.comment.infra;

/** 댓글 영속화 과정에서만 성립해야 하는 내부 상태가 깨졌다. */
public class CommentPersistenceException extends RuntimeException {

    public CommentPersistenceException(String message) {
        super(message);
    }
}
