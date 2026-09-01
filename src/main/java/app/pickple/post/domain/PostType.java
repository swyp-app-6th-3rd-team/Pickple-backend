package app.pickple.post.domain;

/**
 * 게시글 유형. 만들 때 정해지고 바뀌지 않는다 (R-01).
 *
 * <p>유형이 상품 수와 선택지 구성을 결정하므로(R-02·R-04),
 * 나중에 바꾸면 이미 달린 상품·선택지·투표가 전부 어긋난다.
 */
public enum PostType {

    /** 일반 게시글. 상품도 선택지도 없다. */
    GENERAL(0, 0, 0),

    /** 찬반 PICK. 상품 하나에 사진 1~3장, 선택지는 사자/말자. */
    AGREE(1, 1, 3),

    /** A/B PICK. 상품 둘에 각 사진 1장, 선택지는 상품 둘. */
    A_B(2, 1, 1);

    private final int productCount;
    private final int minPhotos;
    private final int maxPhotos;

    PostType(int productCount, int minPhotos, int maxPhotos) {
        this.productCount = productCount;
        this.minPhotos = minPhotos;
        this.maxPhotos = maxPhotos;
    }

    /** 이 유형이 가져야 할 상품 수 (R-02). */
    public int productCount() {
        return productCount;
    }

    /** 상품마다 필요한 최소 사진 수 (R-03). */
    public int minPhotos() {
        return minPhotos;
    }

    /** 상품마다 허용되는 최대 사진 수 (R-03). */
    public int maxPhotos() {
        return maxPhotos;
    }

    /** 투표를 받는 유형인가. 일반 게시글만 아니다. */
    public boolean hasVoting() {
        return this != GENERAL;
    }

    /** 선택지 수 (R-04). 투표가 있으면 정확히 둘, 없으면 0. */
    public int optionCount() {
        return hasVoting() ? 2 : 0;
    }
}
