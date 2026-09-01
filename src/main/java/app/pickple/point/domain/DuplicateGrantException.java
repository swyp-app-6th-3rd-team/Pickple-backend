package app.pickple.point.domain;

/** 같은 원픽으로 같은 사유의 포인트를 두 번 주려 했다 (R-13). */
public class DuplicateGrantException extends RuntimeException {

    public DuplicateGrantException(Long onePickId, PointReason reason) {
        super("이미 지급된 포인트입니다: onePickId=%d, reason=%s".formatted(onePickId, reason));
    }
}
