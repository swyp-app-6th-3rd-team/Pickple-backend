package app.pickple.vote.infra;

import app.pickple.vote.domain.Vote;
import app.pickple.vote.domain.VoteStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaVoteStore implements VoteStore {

    private final VoteRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public Vote save(Vote vote) {
        if (vote.id() == null) {
            return repository.save(VoteEntity.from(vote, LocalDateTime.now(clock))).toDomain();
        }
        VoteEntity entity = repository.findById(vote.id())
                .orElseThrow(() -> new IllegalStateException("투표를 찾을 수 없습니다: id=" + vote.id()));
        entity.applyChoice(vote);
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Vote> findByPostAndVoter(Long postId, Long voterId) {
        return repository.findByPostIdAndUserId(postId, voterId).map(VoteEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByPost(Long postId) {
        return repository.countByPostId(postId);
    }
}
