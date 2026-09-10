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
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/** 게시글 작성·수정·삭제와 목록·상세 조회 유스케이스를 제공한다. */
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

    /** 검색 결과는 기능명세 §4.5에 따라 항상 10건씩 읽는다. */
    private static final int SEARCH_SLICE_SIZE = 10;
    private static final int SEARCH_KEYWORD_MAX_CODE_POINTS = 30;
    private static final int SEARCH_CURSOR_MAX_LENGTH = 1_024;

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
     * 게시글을 수정한다 (§6.1 `[더보기]` · R-33). 작성자만 할 수 있다.
     *
     * <p>순서는 <b>잠금 조회 → 없거나 삭제됨 404 → 작성자 아님 403 → 도메인 전이 → 저장</b> 으로 댓글과 같다.
     * 404 가 403 보다 먼저인 것은 지운 글의 존재를 남에게 알리지 않기 위해서다. 그 앞의 401 가드는 댓글에는
     * 없고 {@link #create} 에는 있는 방어선이다 — 인가 필터가 먼저 막으므로 HTTP 로는 닿지 않는다.
     *
     * <p>잠그는 이유는 {@link PostStore#findByIdForUpdate} 참조 — 삭제와 경합한 수정이 지운 글을 되살리지 않게.
     * 잠금은 이 트랜잭션이 끝날 때 풀리므로 조회와 저장이 한 트랜잭션 안에 있어야 한다.
     *
     * <p>바꿀 수 있는 것은 카테고리·제목·설명뿐이고 커맨드에 그 셋만 있다. 상품과 유형은 파라미터 자체가 없다
     * (ADR-0047). 빈 설명은 작성과 같이 여기서 정규화한다 — {@code ""} 는 비움, {@code null} 은 유지.
     */
    @Transactional
    public Post update(Long postId, Long requesterId, UpdateCommand command) {
        if (requesterId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        Post post = findActiveForUpdate(postId);
        requireAuthor(post, requesterId);

        String description = command.description();
        if (description != null && description.isBlank()) {
            post.clearDescription();
            description = null;
        }
        post.edit(command.title(), description, command.category());
        return postStore.save(post);
    }

    /**
     * 게시글을 지운다 (§6.1 `[더보기]`). 작성자만 할 수 있다.
     *
     * <p>소프트 삭제라 행·상품·선택지·투표·댓글은 남는다. 모든 조회가 {@code deleted_at IS NULL} 로 거르고
     * 투표·댓글·원픽은 {@code ActivePostGuard} 를 지나므로 그 뒤로 새 상호작용이 생기지 않는다.
     *
     * <p><b>카운터를 줄이지 않는다.</b> 댓글 삭제와 다르다 — 게시글이 조회에서 빠지면 그 카운터를 읽을 곳이 없고,
     * 복구 경로가 없어 되돌릴 일도 없다. 지운 글의 이미지 컨테이너도 풀지 않는다 — 재사용하지 않기로 했다
     * (#31 코멘트, PR #76 리뷰).
     */
    @Transactional
    public void delete(Long postId, Long requesterId) {
        if (requesterId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        Post post = findActiveForUpdate(postId);
        requireAuthor(post, requesterId);
        post.delete();
        postStore.save(post);
    }

    private Post findActiveForUpdate(Long postId) {
        Post post = postStore.findByIdForUpdate(postId)
                .orElseThrow(() -> new ApiException(
                        ResponseCode.NOT_FOUND, "게시글을 찾을 수 없습니다: id=" + postId));
        if (post.isDeleted()) {
            throw new ApiException(ResponseCode.NOT_FOUND, "삭제된 게시글입니다: id=" + postId);
        }
        return post;
    }

    private static void requireAuthor(Post post, Long requesterId) {
        if (!post.isOwnedBy(requesterId)) {
            throw new ApiException(
                    ResponseCode.FORBIDDEN,
                    "게시글 작성자만 수정하거나 삭제할 수 있습니다: id=" + post.id());
        }
    }

    /**
     * 게시글 목록을 화면용 읽기 모델로 조회한다 (§4.1 · §4.2).
     *
     * @param category 없으면 전체 (§4.1 기본값)
     * @param sort     없거나 모르는 값이면 최신순
     * @param cursor   없으면 첫 조각
     *
     * <p><b>REPEATABLE READ 를 여기서 선언한다.</b> 저장소가 키 문장과 행 문장을 나눠 내므로(ADR-0045)
     * 둘이 한 스냅샷을 봐야 하는데, 격리 수준은 <b>가장 바깥 트랜잭션</b>이 정한다 — 이 메서드가 그 경계다.
     * {@code JpaPostStore} 의 같은 선언은 여기에 참여하므로 효력이 없고, 저장소는 이 값이 낮춰지지 않았음을 단언한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
     * 목록과 같은 두 문장 경로라 격리 수준도 같이 선언한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PostStore.PostListView> findPopularTop() {
        return postStore
                .findSlice(null, PostSort.POPULAR, ScrollPosition.keyset(), POPULAR_TOP_SIZE)
                .getContent();
    }

    /**
     * 홈 랜덤 투표 카드 한 조각을 조회한다 (§2.1 · §2.2).
     * 첫 요청에서만 시드를 만들고 후속 요청은 커서에 담긴 시드와 정렬 경계를 이어간다.
     * 목록과 같은 두 문장 경로라 격리 수준도 같이 선언한다 — 이 메서드가 가장 바깥 경계다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Window<PostStore.RandomPostView> findRandomSlice(
            PostType type, String cursor, Long viewerId) {
        if (type == null || !type.hasVoting()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "랜덤 카드 유형은 AGREE 또는 A_B여야 합니다.");
        }
        ScrollPosition position = CursorCodec.decode(cursor);
        long initialSeed = position.isInitial() ? randomGenerator.nextLong() : 0L;
        return postStore.findRandomSlice(type, viewerId, position, RANDOM_SLICE_SIZE, initialSeed);
    }

    /** 상품명·A/B 주제·일반 제목을 검색하고 정확한 전체 건수와 최신순 조각을 반환한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PostSearchResult search(String keyword, String cursor) {
        String normalizedKeyword = normalizeSearchKeyword(keyword);
        ScrollPosition position = decodeSearchCursor(cursor);
        PostStore.PostSearchResult stored =
                postStore.search(normalizedKeyword, position, SEARCH_SLICE_SIZE);
        LocalDateTime now = LocalDateTime.now(clock);
        List<PostSearchItem> content = stored.window().getContent().stream()
                .map(view -> new PostSearchItem(
                        view, RelativeTime.of(view.createdAt(), now)))
                .toList();
        Window<PostSearchItem> window = Window.from(
                content, stored.window()::positionAt, stored.window().hasNext());
        return new PostSearchResult(stored.totalCount(), window);
    }

    public record PostSearchResult(long totalCount, Window<PostSearchItem> window) {
    }

    public record PostSearchItem(PostStore.PostSearchView view, String createdAgo) {
    }

    /**
     * 게시글 상세 (§6.2·§6.3). 응답 계약은 ADR-0046 이 정한다.
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
     *
     * <p>세 문장 경로라 목록·랜덤과 같이 REPEATABLE READ 를 여기서 선언한다 — 이 메서드가 가장 바깥
     * 경계이고, 총 투표 인원과 선택지별 득표가 다른 스냅샷을 보면 게이지의 합이 어긋난다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
     * (ADR-0046), 상대 시각을 만들고, 내 글인지 판정한다. 저장소가 읽은 값과
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

        /** 투표 영역이 있는 게시글인가. 일반 게시글은 없다 (R-04). 유형은 불변이라(R-01) 이 답도 바뀌지 않는다. */
        public boolean hasVoting() {
            return view.type().hasVoting();
        }

        /**
         * 선택지별 득표 현황. <b>투표한 사람에게만 값이 있다</b> (ADR-0046).
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
     * @param voteCount  득표 수. <b>미투표자에게는 {@code null}</b> 이다 (ADR-0046)
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

    private static String normalizeSearchKeyword(String raw) {
        if (raw == null) {
            throw invalidSearchKeyword();
        }
        String keyword = stripEdgeWhitespace(raw);
        int codePoints = keyword.codePointCount(0, keyword.length());
        if (codePoints < 1
                || codePoints > SEARCH_KEYWORD_MAX_CODE_POINTS
                || keyword.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidSearchKeyword();
        }
        return keyword;
    }

    private static String stripEdgeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isEdgeWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isEdgeWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isEdgeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static ScrollPosition decodeSearchCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return ScrollPosition.keyset();
        }
        if (cursor.length() > SEARCH_CURSOR_MAX_LENGTH) {
            throw invalidSearchCursor();
        }
        ScrollPosition position = CursorCodec.decode(cursor);
        if (position instanceof KeysetScrollPosition keyset && keyset.getKeys().isEmpty()) {
            throw invalidSearchCursor();
        }
        return position;
    }

    private static ApiException invalidSearchKeyword() {
        return new ApiException(
                ResponseCode.INVALID_REQUEST,
                "검색어는 양끝 공백을 제외하고 1~30자여야 하며 제어 문자를 포함할 수 없습니다.");
    }

    private static ApiException invalidSearchCursor() {
        return new ApiException(ResponseCode.INVALID_REQUEST, "검색 커서 형식이 올바르지 않습니다.");
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

    /**
     * 수정 커맨드 (R-33). 세 필드뿐이다 — 상품과 유형은 여기 없다 (ADR-0047).
     *
     * @param title       A/B 는 주제, 일반은 제목. 찬반은 상품명이라 바꿀 수 없다. {@code null} 이면 유지
     * @param description {@code null} 이면 유지, 빈 문자열이면 비움
     * @param category    {@code null} 이면 유지
     */
    public record UpdateCommand(
            PostCategory category,
            String title,
            String description
    ) {
    }
}
