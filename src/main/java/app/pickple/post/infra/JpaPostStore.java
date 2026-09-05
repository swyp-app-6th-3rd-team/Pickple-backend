package app.pickple.post.infra;

import app.pickple.post.domain.ItemContainerAlreadyAttachedException;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostStore;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class JpaPostStore implements PostStore {

    private static final String PRODUCT_CONTAINER_UNIQUE_KEY = "uk_product_container";

    private final PostRepository repository;
    private final PostProductRepository productRepository;
    private final Clock clock;

    /**
     * 저장 전에 불변식을 강제한다 (R-02·R-04).
     *
     * <p>개수 제약은 {@code CHECK} 로 표현할 수 없어 스키마가 막지 못한다.
     * 호출자가 {@code verifyPublishable()} 을 잊으면 "찬반인데 상품 3개" 가
     * 그대로 저장되므로, 마지막 관문을 여기에 둔다.
     */
    @Override
    @Transactional
    public Post save(Post post) {
        post.verifyPublishable();
        LocalDateTime now = LocalDateTime.now(clock);
        if (post.id() == null) {
            PostEntity entity = PostEntity.fromWithoutOptions(post, now);
            repository.saveAndFlush(entity);
            entity.addInitialOptions(post, now);
            repository.flush();
            return entity.toDomain();
        }
        PostEntity entity = repository.findById(post.id())
                .orElseThrow(() -> new PostPersistenceException("게시글을 찾을 수 없습니다: id=" + post.id()));
        entity.applyState(post, now);
        return entity.toDomain();
    }

    @Override
    @Transactional
    public Post saveIfContainerFree(Post post) {
        try {
            return save(post);
        } catch (DataIntegrityViolationException exception) {
            if (!hasConstraint(exception, PRODUCT_CONTAINER_UNIQUE_KEY)) {
                throw exception;
            }
            throw new ItemContainerAlreadyAttachedException(exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Post> findById(Long id) {
        return repository.findById(id).map(PostEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsActiveById(Long id) {
        return repository.existsByIdAndDeletedAtIsNull(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findAttachedItemContainerIds(Collection<Long> itemContainerIds) {
        if (itemContainerIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(productRepository.findAttachedItemContainerIds(itemContainerIds));
    }

    private static boolean hasConstraint(Throwable throwable, String constraintName) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && sameConstraint(violation.getConstraintName(), constraintName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameConstraint(String actualName, String expectedName) {
        if (actualName == null) {
            return false;
        }
        String unquoted = actualName
                .replace("`", "")
                .replace("\"", "")
                .replace("'", "");
        int qualifierSeparator = unquoted.lastIndexOf('.');
        String simpleName = qualifierSeparator < 0
                ? unquoted
                : unquoted.substring(qualifierSeparator + 1);
        return simpleName.equalsIgnoreCase(expectedName);
    }
}
