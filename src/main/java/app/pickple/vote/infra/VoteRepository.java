package app.pickple.vote.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** package-private. 바깥은 {@link app.pickple.vote.domain.VoteStore} 만 본다. */
interface VoteRepository extends JpaRepository<VoteEntity, Long> {

    Optional<VoteEntity> findByPostIdAndUserId(Long postId, Long userId);

    long countByPostId(Long postId);
}
