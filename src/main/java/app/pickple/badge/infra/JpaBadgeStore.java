package app.pickple.badge.infra;

import app.pickple.badge.domain.Badge;
import app.pickple.badge.domain.BadgeStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaBadgeStore implements BadgeStore {

    private final BadgeRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<Badge> findAllOrdered() {
        return repository.findAllByOrderByDisplayOrderAsc().stream()
                .map(BadgeEntity::toDomain)
                .toList();
    }
}
