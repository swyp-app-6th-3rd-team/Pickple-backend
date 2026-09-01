package app.pickple.point.domain;

/**
 * 포인트 원장.
 *
 * <p>중복 지급 판정은 애플리케이션이 아니라 {@code UNIQUE(comment_pick_id, reason)} 이 한다 (R-13).
 * 조회 후 삽입하면 동시 요청에서 두 번 지급된다.
 */
public interface PointHistoryStore {

    /**
     * 아직 지급하지 않았다면 기록한다.
     *
     * <p>저장소는 "적립됐는가" 라는 사실만 알린다. 그것이 재지급 시도라는 해석은
     * 서비스의 몫이다 (ADR-0019).
     *
     * @return 저장된 이력. 이미 지급됐으면 빈 값
     */
    java.util.Optional<PointHistory> saveIfAbsent(PointHistory history);

    /** 사용자의 누적 포인트. 저장된 값이 아니라 원장의 합계다 (R-14). */
    long sumByUser(Long userId);
}
