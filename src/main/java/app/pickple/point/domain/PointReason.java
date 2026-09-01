package app.pickple.point.domain;

/** 적립 사유와 금액 (정책표 §1). */
public enum PointReason {

    /** 내 댓글이 원픽으로 선정됨. 댓글 작성자가 받는다. */
    PICKED(10),

    /** 내가 댓글을 픽함. 픽한 사람이 받는다. */
    PICKING(5);

    private final int amount;

    PointReason(int amount) {
        this.amount = amount;
    }

    public int amount() {
        return amount;
    }
}
