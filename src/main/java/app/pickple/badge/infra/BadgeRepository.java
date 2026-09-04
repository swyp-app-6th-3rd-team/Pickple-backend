package app.pickple.badge.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** package-private. 바깥은 {@link app.pickple.badge.domain.BadgeStore} 만 본다. */
interface BadgeRepository extends JpaRepository<BadgeEntity, Long> {

    List<BadgeEntity> findAllByOrderByDisplayOrderAsc();
}
