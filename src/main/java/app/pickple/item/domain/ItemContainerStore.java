package app.pickple.item.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface ItemContainerStore {

    ItemContainer save(ItemContainer container);

    Optional<ItemContainer> findById(Long id);

    /** 여러 컨테이너와 그 리소스를 한 번에 조회한다. 결과는 컨테이너 id로 찾는다. */
    Map<Long, ItemContainer> findAllByIds(Collection<Long> ids);
}
