package app.pickple.post.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 게시글. 상품과 선택지를 자기 안에 둔다.
 *
 * <p>이 셋은 함께 만들어지고 함께 검증돼야 한다 — 유형이 상품 수와 선택지 수를 정하므로
 * (R-01·R-02·R-04), 따로 저장하면 "찬반인데 상품이 셋"인 상태가 잠깐이라도 존재하게 된다.
 * 그래서 게시글이 애그리거트 루트다.
 *
 * <p><b>투표와 댓글은 밖에 있다.</b> 수가 무한히 늘어나므로 안에 두면 게시글 하나를
 * 읽는 비용이 계속 커진다. 게시글은 이들을 집계 값({@code voteCount}·{@code commenterCount})으로만 안다.
 */
public class Post {

    private static final int MAX_TITLE_LENGTH = 30;
    private static final int MAX_DESCRIPTION_LENGTH = 300;

    private final Long id;
    private final Long authorId;
    private final PostType type;
    private PostCategory category;
    private String title;
    private String description;

    private final List<PostProduct> products;
    private final List<PostOption> options;

    private final long voteCount;
    private final long commenterCount;
    private final long commentCount;

    private boolean deleted;

    public Post(Long authorId, PostType type, PostCategory category, String title, String description) {
        this(null, authorId, type, category, title, description, List.of(), List.of(), 0L, 0L, 0L, false);
    }

    private Post(Long id, Long authorId, PostType type, PostCategory category,
                 String title, String description,
                 List<PostProduct> products, List<PostOption> options,
                 long voteCount, long commenterCount, long commentCount, boolean deleted) {
        if (authorId == null) {
            throw new IllegalArgumentException("작성자는 필수입니다.");
        }
        if (type == null) {
            throw new IllegalArgumentException("게시글 유형은 필수입니다.");
        }
        if (category == null) {
            throw new IllegalArgumentException("카테고리는 필수입니다.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("제목은 필수입니다.");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("제목은 %d자 이내여야 합니다.".formatted(MAX_TITLE_LENGTH));
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("설명은 %d자 이내여야 합니다.".formatted(MAX_DESCRIPTION_LENGTH));
        }
        this.id = id;
        this.authorId = authorId;
        this.type = type;
        this.category = category;
        this.title = title;
        this.description = description;
        this.products = new ArrayList<>(products);
        this.options = new ArrayList<>(options);
        this.voteCount = voteCount;
        this.commenterCount = commenterCount;
        this.commentCount = commentCount;
        this.deleted = deleted;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static Post restore(Long id, Long authorId, PostType type, PostCategory category,
                               String title, String description,
                               List<PostProduct> products, List<PostOption> options,
                               long voteCount, long commenterCount, long commentCount, boolean deleted) {
        return new Post(id, authorId, type, category, title, description,
                products, options, voteCount, commenterCount, commentCount, deleted);
    }

    public Post addProduct(PostProduct product) {
        if (product == null) {
            throw new IllegalArgumentException("추가할 상품이 없습니다.");
        }
        products.add(product);
        return this;
    }

    public Post addOption(PostOption option) {
        if (option == null) {
            throw new IllegalArgumentException("추가할 선택지가 없습니다.");
        }
        options.add(option);
        return this;
    }

    /**
     * 발행 가능한 상태인지 확인한다 (R-02·R-04).
     *
     * <p>행 개수를 세는 제약은 {@code CHECK} 로 표현할 수 없어 스키마가 막지 못한다.
     * 게시글을 저장하기 직전 여기서 검증한다. ERD 2차 미결 1·2번.
     */
    public void verifyPublishable() {
        verifyProductCount();
        verifyOptionCount();
        verifyOptionShape();
        verifyAbOptionTargets();
    }

    private void verifyProductCount() {
        if (products.size() != type.productCount()) {
            throw new IllegalStateException(
                    "%s 게시글의 상품은 %d개여야 합니다. 현재 %d개입니다."
                            .formatted(type, type.productCount(), products.size()));
        }
        long distinctOrders = products.stream().map(PostProduct::displayOrder).distinct().count();
        if (distinctOrders != products.size()) {
            throw new IllegalStateException("상품의 표시 순서가 중복됩니다.");
        }
    }

    private void verifyOptionCount() {
        if (options.size() != type.optionCount()) {
            throw new IllegalStateException(
                    "%s 게시글의 선택지는 %d개여야 합니다. 현재 %d개입니다."
                            .formatted(type, type.optionCount(), options.size()));
        }
        long distinctOrders = options.stream().map(PostOption::displayOrder).distinct().count();
        if (distinctOrders != options.size()) {
            throw new IllegalStateException("선택지의 표시 순서가 중복됩니다.");
        }
    }

    /** 찬반은 라벨형, A/B는 상품참조형이어야 한다. 섞이면 화면이 표현할 수 없다. */
    private void verifyOptionShape() {
        if (type == PostType.AGREE && options.stream().anyMatch(PostOption::pointsToProduct)) {
            throw new IllegalStateException("찬반 게시글의 선택지는 라벨이어야 합니다.");
        }
        if (type == PostType.A_B && options.stream().anyMatch(o -> !o.pointsToProduct())) {
            throw new IllegalStateException("A/B 게시글의 선택지는 상품을 가리켜야 합니다.");
        }
    }

    /** A/B 선택지는 A·B 상품을 각각 한 번씩 가리켜야 한다. */
    private void verifyAbOptionTargets() {
        if (type != PostType.A_B) {
            return;
        }

        boolean allByDisplayOrder = options.stream()
                .allMatch(option -> option.postProductDisplayOrder() != null);
        boolean allById = options.stream()
                .allMatch(option -> option.postProductId() != null);
        if (!allByDisplayOrder && !allById) {
            throw new IllegalStateException("A/B 선택지는 같은 방식으로 상품을 가리켜야 합니다.");
        }

        if (allByDisplayOrder) {
            Set<Integer> productOrders = products.stream()
                    .map(PostProduct::displayOrder)
                    .collect(Collectors.toSet());
            Set<Integer> optionTargets = options.stream()
                    .map(PostOption::postProductDisplayOrder)
                    .collect(Collectors.toSet());
            if (optionTargets.size() != options.size() || !optionTargets.equals(productOrders)) {
                throw new IllegalStateException("A/B 선택지는 A·B 상품을 각각 한 번씩 가리켜야 합니다.");
            }
            return;
        }

        Set<Long> optionTargets = options.stream()
                .map(PostOption::postProductId)
                .collect(Collectors.toSet());
        if (optionTargets.size() != options.size()) {
            throw new IllegalStateException("A/B 선택지는 같은 상품을 중복해서 가리킬 수 없습니다.");
        }
        if (products.stream().anyMatch(product -> product.id() == null)) {
            throw new IllegalStateException("새 A/B 선택지는 상품 표시 순서로 상품을 가리켜야 합니다.");
        }
        Set<Long> productIds = products.stream()
                .map(PostProduct::id)
                .collect(Collectors.toSet());
        if (!optionTargets.equals(productIds)) {
            throw new IllegalStateException("A/B 선택지는 이 게시글의 상품만 가리킬 수 있습니다.");
        }
    }

    /** 상품 사진 장수가 유형에 맞는지 확인한다 (R-03). 컨테이너는 밖에서 조회해 넘긴다. */
    public void verifyPhotoCount(java.util.function.ToIntFunction<PostProduct> photoCounter) {
        for (PostProduct product : products) {
            int count = photoCounter.applyAsInt(product);
            if (count < type.minPhotos() || count > type.maxPhotos()) {
                throw new IllegalStateException(
                        "%s 게시글의 %d번 상품 사진은 %d~%d장이어야 합니다. 현재 %d장입니다."
                                .formatted(type, product.displayOrder(), type.minPhotos(), type.maxPhotos(), count));
            }
        }
    }

    /** 소프트 삭제 (R-20 과 같은 취지 — 남긴 글이 다른 사람의 맥락이다). */
    public void delete() {
        if (deleted) {
            throw new IllegalStateException("이미 삭제된 게시글입니다.");
        }
        this.deleted = true;
    }

    public void edit(String title, String description, PostCategory category) {
        // 유형은 바꾸지 않는다 (R-01). 바꾸면 상품 수와 선택지 구성이 어긋난다.
        if (title != null && !title.isBlank()) {
            if (title.length() > MAX_TITLE_LENGTH) {
                throw new IllegalArgumentException("제목은 %d자 이내여야 합니다.".formatted(MAX_TITLE_LENGTH));
            }
            this.title = title;
        }
        if (description != null) {
            if (description.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("설명은 %d자 이내여야 합니다.".formatted(MAX_DESCRIPTION_LENGTH));
            }
            this.description = description;
        }
        if (category != null) {
            this.category = category;
        }
    }

    /** 인기순 정렬 키 (R-24). 저장은 생성 컬럼이 하고, 여기서는 읽기만 한다. */
    public long popularityScore() {
        return voteCount + commenterCount;
    }

    public boolean isOwnedBy(Long userId) {
        return authorId.equals(userId);
    }

    public Long id() {
        return id;
    }

    public Long authorId() {
        return authorId;
    }

    public PostType type() {
        return type;
    }

    public PostCategory category() {
        return category;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<PostProduct> products() {
        return Collections.unmodifiableList(products);
    }

    public List<PostOption> options() {
        return Collections.unmodifiableList(options);
    }

    public long voteCount() {
        return voteCount;
    }

    public long commenterCount() {
        return commenterCount;
    }

    public long commentCount() {
        return commentCount;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
