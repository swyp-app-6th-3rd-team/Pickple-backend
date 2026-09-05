package app.pickple.comment.infra;

import app.pickple.comment.domain.OnePick;
import app.pickple.comment.domain.DuplicatePickException;
import app.pickple.comment.domain.OnePickStore;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaOnePickStore implements OnePickStore {

    private final OnePickRepository repository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<OnePick> findByPickerIdAndPostId(Long pickerId, Long postId) {
        return repository.findByUserIdAndPostId(pickerId, postId)
                .map(entity -> new OnePick(entity.getCommentId(), entity.getPostId(), entity.getUserId()));
    }

    @Override
    @Transactional
    public Long save(OnePick pick) {
        try {
            return repository.saveAndFlush(OnePickEntity.from(pick, LocalDateTime.now(clock))).getId();
        } catch (DataIntegrityViolationException failure) {
            // 조회 이후의 경합은 유니크 제약이 막는다. DB별 오류 해석은 Hibernate에 맡긴다.
            if (failure.getCause() instanceof ConstraintViolationException violation
                    && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE
                    && isPickUniqueKey(violation.getConstraintName())) {
                throw new DuplicatePickException(pick.postId(), pick.pickerId(), failure);
            }
            throw failure;
        }
    }

    private boolean isPickUniqueKey(String constraintName) {
        return constraintName != null
                && constraintName.substring(constraintName.lastIndexOf('.') + 1).equals("uk_pick_user_post");
    }

    @Override
    @Transactional(readOnly = true)
    public long countByComment(Long commentId) {
        return repository.countByCommentId(commentId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByPost(Long postId) {
        return repository.countByPostId(postId);
    }
}
