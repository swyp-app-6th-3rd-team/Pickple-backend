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

    /** 중복 픽을 막는 유니크 키. 이 위반만 도메인 예외로 옮긴다. */
    private static final String DUPLICATE_KEY = "uk_pick_user_comment";

    /**
     * 중복 픽 판정을 유니크 키에 맡긴다 (R-26).
     *
     * <p>"이미 픽했나" 를 조회로 먼저 확인하면 확인과 삽입 사이에서 동시 요청이 뚫린다.
     * 삽입을 시도하고 위반을 도메인 예외로 옮기는 편이 원자적이다.
     *
     * <p><b>모든 무결성 위반을 중복으로 보면 안 된다.</b> 다른 게시글의 댓글을 픽하면
     * 복합 FK 가 막는데, 그것까지 "이미 원픽했다" 로 보고하면 원인을 엉뚱한 곳에서 찾게 된다.
     * 제약 이름으로 가른다.
     */
    @Override
    @Transactional
    public Long save(OnePick pick) {
        try {
            return repository.saveAndFlush(
                    OnePickEntity.from(pick, LocalDateTime.now(clock))).getId();
        } catch (DataIntegrityViolationException e) {
            if (isDuplicatePick(e)) {
                throw new DuplicatePickException(pick.commentId(), pick.pickerId());
            }
            throw e;
        }
    }

    private boolean isDuplicatePick(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains(DUPLICATE_KEY)) {
                return true;
            }
        }
        return false;
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
