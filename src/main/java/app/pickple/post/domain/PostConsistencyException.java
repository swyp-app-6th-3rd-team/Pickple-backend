package app.pickple.post.domain;

/** 게시글을 조립하거나 복원한 뒤 상품과 선택지의 내부 참조가 서로 모순된다. */
public class PostConsistencyException extends RuntimeException {

    public PostConsistencyException(String message) {
        super(message);
    }
}
