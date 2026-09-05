package app.pickple.post.domain;

/** 이미지 컨테이너가 이미 다른 게시글 상품에 연결되어 있다. */
public class ItemContainerAlreadyAttachedException extends RuntimeException {

    public ItemContainerAlreadyAttachedException(Long itemContainerId) {
        super("이미 게시글 상품에 사용된 이미지 컨테이너입니다: itemContainerId=" + itemContainerId);
    }

    public ItemContainerAlreadyAttachedException(Throwable cause) {
        super("이미 게시글 상품에 사용된 이미지 컨테이너입니다.", cause);
    }
}
