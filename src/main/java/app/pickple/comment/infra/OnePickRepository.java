package app.pickple.comment.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/** package-private. */
interface OnePickRepository extends JpaRepository<OnePickEntity, Long> {

    Optional<OnePickEntity> findByUserIdAndPostId(Long userId, Long postId);

    long countByCommentId(Long commentId);

    long countByPostId(Long postId);
}
