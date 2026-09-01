package app.pickple.comment.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/** package-private. */
interface OnePickRepository extends JpaRepository<OnePickEntity, Long> {

    long countByCommentId(Long commentId);

    long countByPostId(Long postId);
}
