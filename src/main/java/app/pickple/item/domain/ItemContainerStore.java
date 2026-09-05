package app.pickple.item.domain;

import java.util.Optional;

public interface ItemContainerStore {

    /** 리소스 키까지 DB에 기록하고 반환한다. 외부 객체 쓰기보다 먼저 호출한다. */
    ItemContainer save(ItemContainer container);

    Optional<ItemContainer> findById(Long id);
}
