package app.pickple.item.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * package-private 이라 infra 패키지 밖에서는 보이지 않는다.
 * 바깥은 {@link app.pickple.item.domain.ItemContainerStore} 만 본다.
 */
interface ItemContainerRepository extends JpaRepository<ItemContainerEntity, Long> {
}
