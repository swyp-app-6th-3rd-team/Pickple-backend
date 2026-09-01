package app.pickple.item.domain;

import java.util.Optional;

public interface ItemContainerStore {

    ItemContainer save(ItemContainer container);

    Optional<ItemContainer> findById(Long id);
}
