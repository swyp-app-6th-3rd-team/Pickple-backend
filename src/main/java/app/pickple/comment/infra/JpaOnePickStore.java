package app.pickple.comment.infra;

import app.pickple.comment.domain.DuplicatePickException;
import app.pickple.comment.domain.OnePick;
import app.pickple.comment.domain.OnePickStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class JpaOnePickStore implements OnePickStore {

    private final OnePickRepository repository;
    private final Clock clock;

    /**
     * 중복 픽 판정을 유니크 키에 맡긴다 (R-26).
     *
     * <p>"이미 픽했나" 를 조회로 먼저 확인하면 확인과 삽입 사이에서 동시 요청이 뚫린다.
     * 삽입을 시도하고 위반을 도메인 예외로 옮기는 편이 원자적이다.
     */
    @Override
    @Transactional
    public Long save(OnePick pick) {
        try {
            return repository.saveAndFlush(
                    OnePickEntity.from(pick, LocalDateTime.now(clock))).getId();
        } catch (DataIntegrityViolationException e) {
            throw new DuplicatePickException(pick.commentId(), pick.pickerId());
        }
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
