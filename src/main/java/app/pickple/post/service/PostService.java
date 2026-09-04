package app.pickple.post.service;

import app.pickple.common.CursorCodec;
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
import app.pickple.post.domain.PostQueryStore;
import app.pickple.post.domain.PostSort;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 게시글 작성과 목록 조회 유스케이스를 제공한다. */
@Service
@RequiredArgsConstructor
public class PostService {

    /** 무한 스크롤 조각 크기 (§4.2). 클라이언트가 더 크게 요청해도 이 값으로 자른다. */
    public static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;
    private final PostStore postStore;
    private final ItemContainerStore itemContainerStore;
    private final PostQueryStore postQueryStore;

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
    public Window<PostQueryStore.PostListView> findSlice(
            PostCategory category, String sort, String cursor, Integer size) {

        ScrollPosition position = CursorCodec.decode(cursor);
        return postQueryStore.findSlice(category, PostSort.from(sort), position, sliceSize(size));
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
