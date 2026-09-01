package app.pickple.post.domain;

/**
 * 게시글이 의견을 묻는 대상. 찬반은 하나, A/B는 둘이다.
 *
 * <p>상품에는 <b>사진이 반드시 있다</b>(R-03). 그래서 컨테이너 참조가 필수다 —
 * 일반 게시글은 이 객체 자체가 만들어지지 않으므로 "사진 없는 상품"이라는 상태가 없다.
 * 근거는 ERD 2차 2.2.
 */
public class PostProduct {

    /** 금액 상한. 정책표 §5. */
    public static final long MAX_PRICE = 999_999_999L;

    private static final int MAX_NAME_LENGTH = 30;

    private final Long id;
    private final Long itemContainerId;
    private final String name;
    private final Long price;
    private final String linkUrl;
    private final int displayOrder;

    public PostProduct(Long itemContainerId, String name, Long price, String linkUrl, int displayOrder) {
        this(null, itemContainerId, name, price, linkUrl, displayOrder);
    }

    private PostProduct(Long id, Long itemContainerId, String name, Long price,
                        String linkUrl, int displayOrder) {
        if (itemContainerId == null) {
            throw new IllegalArgumentException("상품에는 사진 컨테이너가 필요합니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("상품명은 필수입니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("상품명은 %d자 이내여야 합니다.".formatted(MAX_NAME_LENGTH));
        }
        if (price != null && (price < 0 || price > MAX_PRICE)) {
            throw new IllegalArgumentException("가격은 0 이상 %d 이하여야 합니다.".formatted(MAX_PRICE));
        }
        if (displayOrder != 1 && displayOrder != 2) {
            throw new IllegalArgumentException("표시 순서는 1(A) 또는 2(B)여야 합니다.");
        }
        this.id = id;
        this.itemContainerId = itemContainerId;
        this.name = name;
        this.price = price;
        this.linkUrl = linkUrl;
        this.displayOrder = displayOrder;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static PostProduct restore(Long id, Long itemContainerId, String name, Long price,
                                      String linkUrl, int displayOrder) {
        return new PostProduct(id, itemContainerId, name, price, linkUrl, displayOrder);
    }

    public Long id() {
        return id;
    }

    public Long itemContainerId() {
        return itemContainerId;
    }

    public String name() {
        return name;
    }

    public Long price() {
        return price;
    }

    public String linkUrl() {
        return linkUrl;
    }

    public int displayOrder() {
        return displayOrder;
    }
}
