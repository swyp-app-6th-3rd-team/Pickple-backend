package app.pickple.item.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * package-private 이라 infra 패키지 밖에서는 보이지 않는다.
 * 바깥은 {@link app.pickple.item.domain.ItemContainerStore} 만 본다.
 */
interface ItemContainerRepository extends JpaRepository<ItemContainerEntity, Long> {

    @Query("""
            select distinct container
            from ItemContainerEntity container
            left join fetch container.resources
            where container.id in :ids
            """)
    List<ItemContainerEntity> findAllWithResourcesByIdIn(@Param("ids") Collection<Long> ids);
}
