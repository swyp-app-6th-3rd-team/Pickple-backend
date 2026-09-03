package app.pickple.post.service;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PostCreationService {

    private static final String PRODUCT_CONTAINER_UNIQUE_KEY = "uk_product_container";

    private final PostStore postStore;
    private final ItemContainerStore itemContainerStore;

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

        Map<Long, ItemContainer> containers = validateContainers(authorId, post);
        post.verifyPhotoCount(product -> containers.get(product.itemContainerId()).photoCount());

        try {
            return postStore.save(post);
        } catch (DataIntegrityViolationException exception) {
            if (!hasConstraint(exception, PRODUCT_CONTAINER_UNIQUE_KEY)) {
                throw exception;
            }
            throw new ApiException(
                    ResponseCode.INVALID_REQUEST,
                    "이미 게시글 상품에 사용된 이미지 컨테이너입니다.",
                    exception);
        }
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
        Map<Long, ItemContainer> containers = new HashMap<>();
        for (PostProduct product : post.products()) {
            Long containerId = product.itemContainerId();
            if (containers.containsKey(containerId)) {
                throw new ApiException(
                        ResponseCode.INVALID_REQUEST,
                        "같은 이미지 컨테이너를 여러 상품에 사용할 수 없습니다.");
            }

            ItemContainer container = itemContainerStore.findById(containerId)
                    .orElseThrow(() -> new ApiException(
                            ResponseCode.NOT_FOUND,
                            "이미지 컨테이너를 찾을 수 없습니다: id=" + containerId));
            if (!container.ownerId().equals(authorId)) {
                throw new ApiException(ResponseCode.FORBIDDEN, "다른 사용자의 이미지를 사용할 수 없습니다.");
            }
            container.verifyUsableAs(AttachType.PRODUCT);
            if (postStore.isItemContainerAttached(containerId)) {
                throw new ApiException(
                        ResponseCode.INVALID_REQUEST,
                        "이미 게시글 상품에 사용된 이미지 컨테이너입니다.");
            }
            containers.put(containerId, container);
        }
        return containers;
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

    private static boolean hasConstraint(Throwable throwable, String constraintName) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
        }
        return false;
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
