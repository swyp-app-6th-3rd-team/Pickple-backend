package app.pickple.post.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** package-private. 바깥은 {@link app.pickple.post.domain.PostStore}만 본다. */
interface PostProductRepository extends JpaRepository<PostProductEntity, Long> {

    @Query("""
            select distinct product.itemContainerId
            from PostProductEntity product
            where product.itemContainerId in :itemContainerIds
            """)
    List<Long> findAttachedItemContainerIds(@Param("itemContainerIds") Collection<Long> itemContainerIds);
}
