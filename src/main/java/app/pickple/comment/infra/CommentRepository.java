package app.pickple.comment.infra;

import org.springframework.data.jpa.repository.JpaRepository;

/** package-private. 바깥은 {@link app.pickple.comment.domain.CommentStore} 만 본다. */
interface CommentRepository extends JpaRepository<CommentEntity, Long> {
}
