package app.pickple.post.infra;

import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaPostStore implements PostStore {

    private final PostRepository repository;
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
            return repository.save(PostEntity.from(post, now)).toDomain();
        }
        PostEntity entity = repository.findById(post.id())
                .orElseThrow(() -> new IllegalStateException("게시글을 찾을 수 없습니다: id=" + post.id()));
        entity.applyState(post, now);
        return entity.toDomain();
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
}
