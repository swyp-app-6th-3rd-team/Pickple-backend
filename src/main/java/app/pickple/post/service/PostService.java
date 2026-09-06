package app.pickple.post.service;

import app.pickple.common.CursorCodec;
import app.pickple.common.RelativeTime;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.post.domain.ItemContainerAlreadyAttachedException;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostSort;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.vote.domain.VotePercentage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/** 게시글 작성과 목록·상세 조회 유스케이스를 제공한다. */
@Service
@RequiredArgsConstructor
public class PostService {

    /** 무한 스크롤 조각 크기 (§4.2). 클라이언트가 더 크게 요청해도 이 값으로 자른다. */
    public static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;

    /** 홈 화면 인기 게시글의 고정 건수 (§2.4). 조각 크기와 달리 클라이언트가 바꾸지 못한다. */
    private static final int POPULAR_TOP_SIZE = 10;

    /** 홈 랜덤 투표 카드의 고정 조각 크기 (§2.2). */
    private static final int RANDOM_SLICE_SIZE = 10;

    private final PostStore postStore;
    private final ItemContainerStore itemContainerStore;
    private final RandomGenerator randomGenerator;
    private final Clock clock;

    /** 업로드된 상품 사진 컨테이너를 검증하고 게시글 애그리거트를 한 트랜잭션으로 발행한다. */
    @Transactional
    public Post create(Long authorId, CreateCommand command) {
        if (authorId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        if (command == null || command.type() == null) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "게시글 유형은 필수입니다.");
        }

        List<ProductCommand> productCommands = command.products() == null
                ? List.of()
                : command.products();
        Post post = assemble(authorId, command, productCommands);
        post.verifyPublishable();

        try {
            Map<Long, ItemContainer> containers = validateContainers(authorId, post);
            post.verifyPhotoCount(product -> containers.get(product.itemContainerId()).photoCount());
            return postStore.saveIfContainerFree(post);
        } catch (ItemContainerAlreadyAttachedException exception) {
            throw new ApiException(
                    ResponseCode.ITEM_CONTAINER_ALREADY_IN_USE,
                    exception.getMessage(),
                    exception);
        }
    }

    /**
     * 게시글 목록을 화면용 읽기 모델로 조회한다 (§4.1 · §4.2).
     *
     * @param category 없으면 전체 (§4.1 기본값)
     * @param sort     없거나 모르는 값이면 최신순
     * @param cursor   없으면 첫 조각
     */
    @Transactional(readOnly = true)
    public Window<PostStore.PostListView> findSlice(
            PostCategory category, String sort, String cursor, Integer size) {

        ScrollPosition position = CursorCodec.decode(cursor);
        return postStore.findSlice(category, PostSort.from(sort), position, sliceSize(size));
    }

    /**
     * 홈 화면의 인기 게시글 Top 10 (§2.4).
     *
     * <p>커서 없는 인기순 첫 조각을 그대로 사용하되, Top 10 계약에는 다음 조각이 없으므로
     * 커서 봉투를 벗기고 내용만 반환한다. 더 보기는 {@code GET /posts?sort=POPULAR} 로 간다.
     */
    @Transactional(readOnly = true)
    public List<PostStore.PostListView> findPopularTop() {
        return postStore
                .findSlice(null, PostSort.POPULAR, ScrollPosition.keyset(), POPULAR_TOP_SIZE)
                .getContent();
    }

    /**
     * 홈 랜덤 투표 카드 한 조각을 조회한다 (§2.1 · §2.2).
     * 첫 요청에서만 시드를 만들고 후속 요청은 커서에 담긴 시드와 정렬 경계를 이어간다.
     */
    @Transactional(readOnly = true)
    public Window<PostStore.RandomPostView> findRandomSlice(
            PostType type, String cursor, Long viewerId) {
        if (type == null || !type.hasVoting()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "랜덤 카드 유형은 AGREE 또는 A_B여야 합니다.");
        }
        ScrollPosition position = CursorCodec.decode(cursor);
        long initialSeed = position.isInitial() ? randomGenerator.nextLong() : 0L;
        return postStore.findRandomSlice(type, viewerId, position, RANDOM_SLICE_SIZE, initialSeed);
    }

    /**
     * 게시글 상세 (§6.2·§6.3). 응답 계약은 ADR-0040 이 정한다.
     *
     * <p>게스트도 부르는 화면이라 {@code viewerId} 가 {@code null} 일 수 있다.
     * 그 값은 "이미 투표했는가" 를 가르는 데만 쓰이며, 게스트는 투표 이력을 가질 수
     * 없으므로(R-11) 언제나 미투표로 답한다 — 기능 저하가 아니라 정확한 답이다.
     *
     * <p><b>없거나 삭제된 게시글은 404 다.</b> 소프트 삭제라 행은 남아 있지만
     * 화면에는 없는 글이므로 "찾을 수 없다" 가 맞다. 삭제됐다는 사실 자체를 알리지 않아
     * 지운 글의 존재가 새어 나가지도 않는다.
     *
     * <p><b>득표율은 투표한 사람에게만 준다.</b> 감춤을 여기서 하는 이유는 그것이
     * 저장소의 관심사가 아니라 화면 계약이기 때문이다 — 저장소는 읽은 값을 그대로 올린다.
     */
    @Transactional(readOnly = true)
    public PostDetail findDetail(Long id, Long viewerId) {
        PostStore.PostDetailView view = postStore.findDetail(id, viewerId)
                .orElseThrow(() -> new ApiException(
                        ResponseCode.NOT_FOUND, "게시글을 찾을 수 없습니다: id=" + id));

        return new PostDetail(view, LocalDateTime.now(clock), viewerId);
    }

    /**
     * 상세 화면에 내려보낼 값 (§6.2·§6.3).
     *
     * <p>읽기 모델({@link PostStore.PostDetailView})을 그대로 쓰지 않고 한 겹 두는 이유는
     * <b>감춤과 계산이 여기서 일어나기 때문</b>이다 — 미투표자에게 선택지별 집계를 지우고
     * (ADR-0040), 상대 시각을 만들고, 내 글인지 판정한다. 저장소가 읽은 값과
     * 화면이 볼 값이 다르므로 타입도 나눈다.
     */
    public record PostDetail(
            PostStore.PostDetailView view,
            LocalDateTime now,
            Long viewerId) {

        /** 화면용 상대 시각 (§6.2). 댓글과 같은 정본을 쓴다. */
        public String createdAgo() {
            return RelativeTime.of(view.createdAt(), now);
        }

        /** 내가 쓴 글인가. 게스트는 언제나 거짓이다. */
        public boolean mine() {
            return viewerId != null && viewerId.equals(view.authorId());
        }

        /** 투표 영역이 있는 게시글인가. 일반 게시글은 없다 (R-04). */
        public boolean hasVoting() {
            return view.type().hasVoting();
        }

        /**
         * 선택지별 득표 현황. <b>투표한 사람에게만 값이 있다</b> (ADR-0040).
         *
         * <p>미투표자에게는 각 선택지의 {@code voteCount} 와 {@code percentage} 를
         * <b>둘 다</b> 지운다. 하나만 지우면 선택지가 정확히 둘이고(R-04) 1인 1표라(R-09)
         * {@code 나머지 = voterCount − 준 값} 으로 완전히 복원된다.
         */
        public List<OptionTally> options() {
            boolean voted = view.voted();
            return view.options().stream()
                    .map(option -> new OptionTally(
                            option.id(),
                            option.label(),
                            option.productId(),
                            option.displayOrder(),
                            voted ? option.voteCount() : null,
                            voted ? VotePercentage.calculate(option.voteCount(), view.voterCount()) : null))
                    .toList();
        }

    }

    /**
     * 선택지 하나의 득표 현황 (§6.3).
     *
     * @param voteCount  득표 수. <b>미투표자에게는 {@code null}</b> 이다 (ADR-0040)
     * @param percentage 득표율. <b>미투표자에게는 {@code null}</b> 이다.
     *                   0 으로 채우지 않는다 — "자격 없음" 과 "정말 0표" 가 구분되지 않는다
     */
    public record OptionTally(
            Long optionId,
            String label,
            Long productId,
            int displayOrder,
            Long voteCount,
            Integer percentage) {
    }

    private Post assemble(Long authorId, CreateCommand command, List<ProductCommand> products) {
        Post post = new Post(
                authorId,
                command.type(),
                command.category(),
                resolveTitle(command.type(), command.title(), products),
                nullIfBlank(command.description()));

        for (int index = 0; index < products.size(); index++) {
            ProductCommand product = products.get(index);
            if (product == null) {
                throw new ApiException(ResponseCode.INVALID_REQUEST, "상품 정보가 비어 있습니다.");
            }
            post.addProduct(new PostProduct(
                    product.itemContainerId(),
                    product.name(),
                    product.price(),
                    nullIfBlank(product.linkUrl()),
                    index + 1));
        }

        switch (command.type()) {
            case AGREE -> post
                    .addOption(PostOption.ofLabel("사자", 1))
                    .addOption(PostOption.ofLabel("말자", 2));
            case A_B -> post
                    .addOption(PostOption.ofProductDisplayOrder(1, 1))
                    .addOption(PostOption.ofProductDisplayOrder(2, 2));
            case GENERAL -> {
                // 일반 게시글에는 선택지가 없다.
            }
        }
        return post;
    }

    private Map<Long, ItemContainer> validateContainers(Long authorId, Post post) {
        List<Long> containerIds = post.products().stream()
                .map(PostProduct::itemContainerId)
                .toList();
        if (containerIds.isEmpty()) {
            return Map.of();
        }

        Set<Long> uniqueContainerIds = Set.copyOf(containerIds);
        if (uniqueContainerIds.size() != containerIds.size()) {
            throw new ApiException(
                    ResponseCode.INVALID_REQUEST,
                    "같은 이미지 컨테이너를 여러 상품에 사용할 수 없습니다.");
        }

        Map<Long, ItemContainer> containers = itemContainerStore.findAllByIds(uniqueContainerIds);
        Set<Long> attachedContainerIds = postStore.findAttachedItemContainerIds(uniqueContainerIds);

        for (Long containerId : containerIds) {
            ItemContainer container = containers.get(containerId);
            if (container == null) {
                throw new ApiException(
                        ResponseCode.NOT_FOUND,
                        "이미지 컨테이너를 찾을 수 없습니다: id=" + containerId);
            }
            if (!container.ownerId().equals(authorId)) {
                throw new ApiException(ResponseCode.FORBIDDEN, "다른 사용자의 이미지를 사용할 수 없습니다.");
            }
            container.verifyUsableAs(AttachType.PRODUCT);
            if (attachedContainerIds.contains(containerId)) {
                throw new ItemContainerAlreadyAttachedException(containerId);
            }
        }
        return containers;
    }

    private static int sliceSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private static String resolveTitle(PostType type, String requestedTitle, List<ProductCommand> products) {
        if (type == PostType.AGREE && !products.isEmpty() && products.getFirst() != null) {
            return products.getFirst().name();
        }
        return requestedTitle;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CreateCommand(
            PostType type,
            PostCategory category,
            String title,
            String description,
            List<ProductCommand> products
    ) {
    }

    public record ProductCommand(
            Long itemContainerId,
            String name,
            Long price,
            String linkUrl
    ) {
    }
}
