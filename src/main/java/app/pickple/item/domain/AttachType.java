package app.pickple.item.domain;

/**
 * 컨테이너의 용도. 생성 시점에 정해지고 바뀌지 않는다.
 *
 * <p>부착 측이 {@code (id, attach_type)} 쌍으로 참조하므로, 상품용 컨테이너를
 * 댓글에 붙이는 경로가 스키마 차원에서 막힌다. 근거는 ERD 2차 2.1.
 */
public enum AttachType {

    /** 상품 사진. 상품에는 반드시 컨테이너가 있다. */
    PRODUCT,

    /** 댓글 사진. 댓글에는 없을 수 있다. */
    COMMENT
}
