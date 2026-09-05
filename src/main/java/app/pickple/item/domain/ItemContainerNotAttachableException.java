package app.pickple.item.domain;

/** 요청한 대상에 이미지 컨테이너의 용도가 맞지 않는다. */
public class ItemContainerNotAttachableException extends IllegalStateException {

    public ItemContainerNotAttachableException(String message) {
        super(message);
    }
}
