package app.pickple.post.domain;

/** 요청으로 만든 게시글 구성이 발행 규칙을 만족하지 않는다. */
public class PostNotPublishableException extends IllegalStateException {

    public PostNotPublishableException(String message) {
        super(message);
    }
}
