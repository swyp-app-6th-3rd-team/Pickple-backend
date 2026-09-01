package app.pickple.post.infra;

import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code post} 한 행과 그에 딸린 상품·선택지.
 *
 * <p>{@code popularity_score} 는 DB 생성 컬럼이라 매핑하지 않는다 —
 * 매핑하면 하이버네이트가 쓰기를 시도해 {@code ERROR 3105} 가 난다.
 * 정렬은 인덱스가 걸린 그 컬럼으로 하고, 값이 필요하면 두 카운터를 더해 읽는다.
 */
@Getter
@Entity
@Table(name = "post")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private PostType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private PostCategory category;

    @Column(name = "title", nullable = false, length = 30)
    private String title;

    @Column(name = "description", length = 300)
    private String description;

    // 카운터는 읽기 전용이다. 게시글을 수정하는 UPDATE 가 SET 절에 이 컬럼을 넣으면,
    // 그 사이 원자적으로 증가한 값(vote_count = vote_count + 1)을 오래된 스냅샷으로 덮어쓴다.
    // 증가는 전용 UPDATE 로만 한다 (PostCounterUpdater).
    @Column(name = "vote_count", nullable = false, insertable = false, updatable = false)
    private Integer voteCount;

    @Column(name = "commenter_count", nullable = false, insertable = false, updatable = false)
    private Integer commenterCount;

    @Column(name = "comment_count", nullable = false, insertable = false, updatable = false)
    private Integer commentCount;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder")
    private List<PostProductEntity> products = new ArrayList<>();

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder")
    private List<PostOptionEntity> options = new ArrayList<>();

    private PostEntity(Post post, LocalDateTime now) {
        this.id = post.id();
        this.userId = post.authorId();
        this.type = post.type();
        this.category = post.category();
        this.title = post.title();
        this.description = post.description();
        this.deletedAt = post.isDeleted() ? now : null;
        this.createdAt = now;
        this.updatedAt = now;
        post.products().forEach(p -> this.products.add(PostProductEntity.from(this, p, now)));
        post.options().forEach(o -> this.options.add(PostOptionEntity.from(this, o, now)));
    }

    public static PostEntity from(Post post, LocalDateTime now) {
        return new PostEntity(post, now);
    }

    /**
     * 수정 가능한 필드만 반영한다.
     *
     * <p>유형(R-01)과 작성자는 건드리지 않는다. 집계 카운터도 여기서 손대지 않는다 —
     * 투표·댓글이 자기 트랜잭션에서 원자적으로 올린다.
     */
    public void applyState(Post post, LocalDateTime now) {
        this.category = post.category();
        this.title = post.title();
        this.description = post.description();
        if (post.isDeleted() && this.deletedAt == null) {
            this.deletedAt = now;
        }
        syncChildren(post, now);
        this.updatedAt = now;
    }

    /**
     * 상품·선택지를 도메인 상태에 맞춘다.
     *
     * <p>이 동기화가 없으면 기존 게시글에 상품을 추가하고 저장해도 조용히 사라진다 —
     * 하이버네이트가 추적하는 컬렉션에 변화가 없어 cascade 할 대상이 없기 때문이다.
     *
     * <p>{@code orphanRemoval} 이 켜져 있어 컬렉션에서 빠진 행은 함께 지워진다.
     * 컬렉션 인스턴스를 바꾸면 추적을 잃으므로 비우고 다시 채운다.
     */
    private void syncChildren(Post post, LocalDateTime now) {
        this.products.clear();
        post.products().forEach(p -> this.products.add(PostProductEntity.from(this, p, now)));
        this.options.clear();
        post.options().forEach(o -> this.options.add(PostOptionEntity.from(this, o, now)));
    }

    public Post toDomain() {
        return Post.restore(id, userId, type, category, title, description,
                products.stream().map(PostProductEntity::toDomain).toList(),
                options.stream().map(PostOptionEntity::toDomain).toList(),
                voteCount == null ? 0L : voteCount,
                commenterCount == null ? 0L : commenterCount,
                commentCount == null ? 0L : commentCount,
                deletedAt != null);
    }
}
