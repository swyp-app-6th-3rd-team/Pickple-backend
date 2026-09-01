package app.pickple.point.domain;

/**
 * 포인트 원장.
 *
 * <p>중복 지급 판정은 애플리케이션이 아니라 {@code UNIQUE(comment_pick_id, reason)} 이 한다 (R-13).
 * 조회 후 삽입하면 동시 요청에서 두 번 지급된다.
 */
public interface PointHistoryStore {

    /**
     * 적립을 기록한다.
     *
     * @throws DuplicateGrantException 같은 원픽·사유로 이미 지급됐을 때
     */
    PointHistory save(PointHistory history);

    /** 사용자의 누적 포인트. 저장된 값이 아니라 원장의 합계다 (R-14). */
    long sumByUser(Long userId);
}
