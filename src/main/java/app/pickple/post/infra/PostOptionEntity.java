package app.pickple.post.infra;

import app.pickple.post.domain.PostOption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "post_option")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostOptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private PostEntity post;

    /**
     * 상품 참조는 복합 FK {@code (post_product_id, post_id)} 라 연관관계로 매핑하지 않는다.
     * {@code @ManyToOne} 으로 매핑하면 하이버네이트가 단일 컬럼 FK 를 기대해
     * 스키마와 어긋난다. 식별자만 들고 있는다.
     */
    @Column(name = "post_product_id")
    private Long postProductId;

    @Column(name = "label", length = 20)
    private String label;

    @Column(name = "display_order", nullable = false)
    private Byte displayOrder;

    @Column(name = "vote_count", nullable = false)
    private Integer voteCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private PostOptionEntity(PostEntity post, PostOption option, LocalDateTime now) {
        this.id = option.id();
        this.post = post;
        this.postProductId = option.postProductId();
        this.label = option.label();
        this.displayOrder = (byte) option.displayOrder();
        this.voteCount = (int) option.voteCount();
        this.createdAt = now;
    }

    static PostOptionEntity from(PostEntity post, PostOption option, LocalDateTime now) {
        return new PostOptionEntity(post, option, now);
    }

    PostOption toDomain() {
        return PostOption.restore(id, postProductId, label, displayOrder, voteCount);
    }
}
