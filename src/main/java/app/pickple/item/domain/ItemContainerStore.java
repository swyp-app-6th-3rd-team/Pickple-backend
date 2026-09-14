package app.pickple.item.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ItemContainerStore {

    ItemContainer save(ItemContainer container);

    Optional<ItemContainer> findById(Long id);

    /** 소유자와 리소스 URL로 컨테이너를 찾는다. URL의 정확한 일치는 호출자가 검증한다. */
    List<ItemContainer> findAllByOwnerIdAndResourceAccessUrl(Long ownerId, String accessUrl);

    /** 여러 컨테이너와 그 리소스를 한 번에 조회한다. 결과는 컨테이너 id로 찾는다. */
    Map<Long, ItemContainer> findAllByIds(Collection<Long> ids);
}
