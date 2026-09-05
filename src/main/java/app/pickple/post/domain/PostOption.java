package app.pickple.post.domain;

/**
 * 투표에서 고를 수 있는 항목. 게시글당 정확히 둘이거나 없다 (R-04).
 *
 * <p>두 형태가 있고 <b>둘 중 하나여야 한다</b>(배타).
 * <ul>
 *   <li>찬반 — 상품 참조 없이 라벨만 갖는다 ("사자" / "말자")
 *   <li>A/B — 라벨 없이 상품을 가리킨다
 * </ul>
 * 스키마의 {@code ck_option_target} 이 같은 배타를 강제한다.
 */
public class PostOption {

    private static final int MAX_LABEL_LENGTH = 20;

    private final Long id;
    private final Long postProductId;
    private final Integer postProductDisplayOrder;
    private final String label;
    private final int displayOrder;
    private final long voteCount;

    /** 찬반 선택지 — 라벨만 갖는다. */
    public static PostOption ofLabel(String label, int displayOrder) {
        return new PostOption(null, null, null, label, displayOrder, 0L);
    }

    /** A/B 선택지 — 상품을 가리킨다. */
    public static PostOption ofProduct(Long postProductId, int displayOrder) {
        return new PostOption(null, postProductId, null, null, displayOrder, 0L);
    }

    /**
     * 아직 DB 식별자가 없는 새 A/B 상품을 표시 순서로 가리킨다.
     * 저장소가 상품을 먼저 영속화한 뒤 실제 식별자로 치환한다.
     */
    public static PostOption ofProductDisplayOrder(int postProductDisplayOrder, int displayOrder) {
        return new PostOption(null, null, postProductDisplayOrder, null, displayOrder, 0L);
    }

    private PostOption(Long id, Long postProductId, Integer postProductDisplayOrder,
                       String label, int displayOrder, long voteCount) {
        boolean hasProductId = postProductId != null;
        boolean hasProductOrder = postProductDisplayOrder != null;
        if (hasProductId && hasProductOrder) {
            throw new IllegalArgumentException("선택지는 상품 id와 임시 표시 순서를 동시에 가질 수 없습니다.");
        }
        boolean hasProduct = hasProductId || hasProductOrder;
        boolean hasLabel = label != null && !label.isBlank();
        if (hasProduct == hasLabel) {
            throw new IllegalArgumentException("선택지는 상품을 가리키거나 라벨을 갖거나, 둘 중 하나여야 합니다.");
        }
        if (hasLabel && label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("라벨은 %d자 이내여야 합니다.".formatted(MAX_LABEL_LENGTH));
        }
        if (hasProductOrder && postProductDisplayOrder != 1 && postProductDisplayOrder != 2) {
            throw new IllegalArgumentException("선택지가 가리킬 상품 순서는 1(A) 또는 2(B)여야 합니다.");
        }
        if (displayOrder != 1 && displayOrder != 2) {
            throw new IllegalArgumentException("표시 순서는 1 또는 2여야 합니다.");
        }
        if (voteCount < 0) {
            throw new IllegalArgumentException("투표 수는 음수가 될 수 없습니다.");
        }
        this.id = id;
        this.postProductId = postProductId;
        this.postProductDisplayOrder = postProductDisplayOrder;
        this.label = label;
        this.displayOrder = displayOrder;
        this.voteCount = voteCount;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static PostOption restore(Long id, Long postProductId, String label,
                                     int displayOrder, long voteCount) {
        return new PostOption(id, postProductId, null, label, displayOrder, voteCount);
    }

    public boolean pointsToProduct() {
        return postProductId != null || postProductDisplayOrder != null;
    }

    public Long id() {
        return id;
    }

    public Long postProductId() {
        return postProductId;
    }

    public Integer postProductDisplayOrder() {
        return postProductDisplayOrder;
    }

    public String label() {
        return label;
    }

    public int displayOrder() {
        return displayOrder;
    }

    public long voteCount() {
        return voteCount;
    }
}
