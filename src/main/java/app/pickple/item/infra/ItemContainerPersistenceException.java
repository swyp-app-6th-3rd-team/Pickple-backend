package app.pickple.item.infra;

/** 이미지 컨테이너 영속화 과정에서만 성립해야 하는 내부 상태가 깨졌다. */
public class ItemContainerPersistenceException extends RuntimeException {

    public ItemContainerPersistenceException(String message) {
        super(message);
    }
}
