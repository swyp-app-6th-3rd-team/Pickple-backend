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
    private final String label;
    private final int displayOrder;
    private final long voteCount;

    /** 찬반 선택지 — 라벨만 갖는다. */
    public static PostOption ofLabel(String label, int displayOrder) {
        return new PostOption(null, null, label, displayOrder, 0L);
    }

    /** A/B 선택지 — 상품을 가리킨다. */
    public static PostOption ofProduct(Long postProductId, int displayOrder) {
        return new PostOption(null, postProductId, null, displayOrder, 0L);
    }

    private PostOption(Long id, Long postProductId, String label, int displayOrder, long voteCount) {
        boolean hasProduct = postProductId != null;
        boolean hasLabel = label != null && !label.isBlank();
        if (hasProduct == hasLabel) {
            throw new IllegalArgumentException("선택지는 상품을 가리키거나 라벨을 갖거나, 둘 중 하나여야 합니다.");
        }
        if (hasLabel && label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("라벨은 %d자 이내여야 합니다.".formatted(MAX_LABEL_LENGTH));
        }
        if (displayOrder != 1 && displayOrder != 2) {
            throw new IllegalArgumentException("표시 순서는 1 또는 2여야 합니다.");
        }
        if (voteCount < 0) {
            throw new IllegalArgumentException("투표 수는 음수가 될 수 없습니다.");
        }
        this.id = id;
        this.postProductId = postProductId;
        this.label = label;
        this.displayOrder = displayOrder;
        this.voteCount = voteCount;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static PostOption restore(Long id, Long postProductId, String label,
                                     int displayOrder, long voteCount) {
        return new PostOption(id, postProductId, label, displayOrder, voteCount);
    }

    public boolean pointsToProduct() {
        return postProductId != null;
    }

    public Long id() {
        return id;
    }

    public Long postProductId() {
        return postProductId;
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
