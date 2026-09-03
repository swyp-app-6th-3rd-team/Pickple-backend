package app.pickple.item.domain;

/**
 * 컨테이너의 용도. 생성 시점에 정해지고 바뀌지 않는다.
 *
 * <p>부착 측이 {@code (id, attach_type)} 쌍으로 참조하므로, 상품용 컨테이너를
 * 댓글에 붙이는 경로가 스키마 차원에서 막힌다. 근거는 ERD 2차 2.1.
 *
 * <p>객체 키 접두어를 상수마다 들고 있다. 서비스에서 문자열을 조립하면 새 용도를
 * 추가할 때 접두어를 빠뜨려도 컴파일이 통과하지만, 여기 두면 컴파일러가 요구한다.
 */
public enum AttachType {

    /** 상품 사진. 상품에는 반드시 컨테이너가 있다. */
    PRODUCT("product-images"),

    /** 댓글 사진. 댓글에는 없을 수 있다. */
    COMMENT("comment-images");

    /**
     * S3 객체 키의 최상위 접두어.
     *
     * <p>⚠️ 이미 올라간 객체의 키는 바뀌지 않는다. 값을 고치면 그 시점 이후 업로드만
     * 새 접두어를 쓰고 기존 객체는 옛 접두어에 남아, 정리·조회 경로가 갈라진다.
     */
    private final String keyPrefix;

    AttachType(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String keyPrefix() {
        return keyPrefix;
    }
}
