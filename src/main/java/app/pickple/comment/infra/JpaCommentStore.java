package app.pickple.comment.infra;

import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaCommentStore implements CommentStore {

    private final CommentRepository repository;
    private final Clock clock;

    @Override
    @Transactional
    public Comment save(Comment comment) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (comment.id() == null) {
            return repository.save(CommentEntity.from(comment, now)).toDomain();
        }
        CommentEntity entity = repository.findById(comment.id())
                .orElseThrow(() -> new IllegalStateException("댓글을 찾을 수 없습니다: id=" + comment.id()));
        entity.applyState(comment, now);
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Comment> findById(Long id) {
        return repository.findById(id).map(CommentEntity::toDomain);
    }
}
