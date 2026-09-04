package app.pickple.item.infra;

import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class JpaItemContainerStore implements ItemContainerStore {

    private final ItemContainerRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public ItemContainer save(ItemContainer container) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (container.id() == null) {
            return repository.save(ItemContainerEntity.from(container, now)).toDomain();
        }
        ItemContainerEntity entity = repository.findById(container.id())
                .orElseThrow(() -> new IllegalStateException(
                        "컨테이너를 찾을 수 없습니다: id=" + container.id()));
        entity.applyResources(container, now);
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ItemContainer> findById(Long id) {
        return repository.findById(id).map(ItemContainerEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ItemContainer> findAllByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.findAllWithResourcesByIdIn(ids).stream()
                .map(ItemContainerEntity::toDomain)
                .collect(Collectors.toUnmodifiableMap(ItemContainer::id, Function.identity()));
    }
}
