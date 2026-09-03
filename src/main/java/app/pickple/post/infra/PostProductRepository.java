package app.pickple.post.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/** package-private. 바깥은 {@link app.pickple.post.domain.PostStore}만 본다. */
interface PostProductRepository extends JpaRepository<PostProductEntity, Long> {

    boolean existsByItemContainerId(Long itemContainerId);
}
