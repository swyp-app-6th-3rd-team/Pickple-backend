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

    /**
     * 이 게시글에서 아직 픽하지 않았다면 저장한다 (R-05).
     *
     * <p>판정 범위가 <b>게시글</b>이다 — 같은 댓글 재픽뿐 아니라
     * "같은 게시글의 다른 댓글" 도 막는다.
     *
     * <p>존재를 먼저 확인한다. 확인과 삽입 사이에 틈이 있지만
     * {@code UNIQUE(user_id, post_id)} 가 최종 방어선이라 데이터는 깨지지 않는다 —
     * 그 좁은 창에서 두 요청이 겹치면 뒤쪽이 무결성 예외로 실패한다.
     *
     * <p><b>{@code INSERT IGNORE} 를 쓰지 않는 이유</b>: FK 위반까지 경고로 낮춰
     * 조용히 삼킨다. 다른 게시글의 댓글을 픽해도 아무 일 없이 넘어가면
     * 잘못된 요청이 신호 없이 사라진다.
     */
    @Override
    @Transactional
    public Optional<Long> saveIfAbsent(OnePick pick) {
        if (repository.existsByUserIdAndPostId(pick.pickerId(), pick.postId())) {
            return Optional.empty();
        }
        try {
            return Optional.of(repository.saveAndFlush(
                    OnePickEntity.from(pick, LocalDateTime.now(clock))).getId());
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && violation.getErrorCode() == 1062
                        && isPickUniqueKey(violation.getConstraintName())) {
                    // 실패한 JPA 트랜잭션에서 빈 값을 반환하면 rollback-only를 숨기게 된다.
                    throw new DuplicatePickException(pick.postId(), pick.pickerId(), failure);
                }
            }
            throw failure;
        }
    }

    private boolean isPickUniqueKey(String constraintName) {
        return constraintName != null && (constraintName.equals("uk_pick_user_post")
                || constraintName.equals("comment_pick.uk_pick_user_post"));
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
