package app.pickple.point.infra;

import app.pickple.point.domain.PointHistory;
import app.pickple.point.domain.PointHistoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaPointHistoryStore implements PointHistoryStore {

    private final PointHistoryRepository repository;
    private final Clock clock;

    /**
     * 아직 지급하지 않았다면 적립한다 (R-13).
     *
     * <p>멱등키 {@code (comment_pick_id, reason)} 이 최종 방어선이다.
     * 존재 확인과 삽입 사이의 좁은 창에서 겹치면 뒤쪽이 무결성 예외로 실패하고,
     * 그건 재시도로 해결된다 — 조용히 두 번 지급되는 것보다 낫다.
     */
    @Override
    @Transactional
    public Optional<PointHistory> saveIfAbsent(PointHistory history) {
        if (repository.existsByCommentPickIdAndReason(history.onePickId(), history.reason())) {
            return Optional.empty();
        }
        return Optional.of(repository.save(
                PointHistoryEntity.from(history, LocalDateTime.now(clock))).toDomain());
    }

    @Override
    @Transactional(readOnly = true)
    public long sumByUser(Long userId) {
        return repository.sumAmountByUserId(userId);
    }

}
