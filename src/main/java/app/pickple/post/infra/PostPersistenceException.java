package app.pickple.post.infra;

/** 게시글 영속화 과정에서만 성립해야 하는 내부 상태가 깨졌다. */
public class PostPersistenceException extends RuntimeException {

    public PostPersistenceException(String message) {
        super(message);
    }
}
