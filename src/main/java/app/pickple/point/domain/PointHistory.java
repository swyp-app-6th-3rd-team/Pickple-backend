package app.pickple.point.domain;

/**
 * 포인트 적립 이력. 사용자의 포인트는 이 원장의 합계다 (R-14).
 *
 * <p>잔액만 두지 않는 이유는 <b>왜 그 값이 됐는지</b>를 설명할 수 없기 때문이다.
 * 저장된 {@code users.point} 는 캐시이고 근거는 여기 있다.
 *
 * <p>원픽 한 번이 두 사람에게 지급한다 (R-12) — 댓글 작성자 +10P, 픽한 사람 +5P.
 * 그래서 멱등키는 픽 하나가 아니라 {@code (원픽, 사유)} 쌍이다 (R-13).
 */
public class PointHistory {

    private final Long id;
    private final Long userId;
    private final int amount;
    private final PointReason reason;
    private final Long onePickId;

    /** 원픽으로 인한 적립을 만든다. 금액은 사유가 정한다 — 호출자가 임의로 못 정한다. */
    public static PointHistory forPick(Long userId, PointReason reason, Long onePickId) {
        return new PointHistory(null, userId, reason.amount(), reason, onePickId);
    }

    private PointHistory(Long id, Long userId, int amount, PointReason reason, Long onePickId) {
        if (userId == null) {
            throw new IllegalArgumentException("적립 대상이 필요합니다.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("적립 사유가 필요합니다.");
        }
        if (onePickId == null) {
            // 멱등키의 구성 요소다. 비면 유니크 키가 NULL 을 서로 다르게 취급해
            // 같은 적립이 무한히 들어간다 (ERD 2차 4장 주석).
            throw new IllegalArgumentException("출처 원픽이 필요합니다. 멱등키가 성립하지 않습니다.");
        }
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.reason = reason;
        this.onePickId = onePickId;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static PointHistory restore(Long id, Long userId, int amount,
                                       PointReason reason, Long onePickId) {
        return new PointHistory(id, userId, amount, reason, onePickId);
    }

    public Long id() {
        return id;
    }

    public Long userId() {
        return userId;
    }

    public int amount() {
        return amount;
    }

    public PointReason reason() {
        return reason;
    }

    public Long onePickId() {
        return onePickId;
    }
}
