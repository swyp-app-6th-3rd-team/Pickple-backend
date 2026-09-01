package app.pickple.point.infra;

import app.pickple.point.domain.DuplicateGrantException;
import app.pickple.point.domain.PointHistory;
import app.pickple.point.domain.PointHistoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class JpaPointHistoryStore implements PointHistoryStore {

    /** 멱등키. 이 위반만 도메인 예외로 옮긴다. */
    private static final String IDEMPOTENCY_KEY = "uk_point_idem";

    private final PointHistoryRepository repository;
    private final Clock clock;

    /**
     * 중복 지급 판정을 멱등키에 맡긴다 (R-13).
     *
     * <p>다른 무결성 위반(없는 원픽 참조 등)까지 "이미 지급됨" 으로 보고하면
     * 원인을 엉뚱한 곳에서 찾게 되므로 제약 이름으로 가른다.
     */
    @Override
    @Transactional
    public PointHistory save(PointHistory history) {
        try {
            return repository.saveAndFlush(
                    PointHistoryEntity.from(history, LocalDateTime.now(clock))).toDomain();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, IDEMPOTENCY_KEY)) {
                throw new DuplicateGrantException(history.onePickId(), history.reason());
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long sumByUser(Long userId) {
        return repository.sumAmountByUserId(userId);
    }

    private boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
