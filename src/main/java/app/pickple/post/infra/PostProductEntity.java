package app.pickple.post.infra;

import app.pickple.post.domain.PostProduct;
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
import org.hibernate.Length;

import java.time.LocalDateTime;

/**
 * {@code post_product} 한 행.
 *
 * <p>{@code container_type} 은 DB 생성 컬럼({@code GENERATED ALWAYS AS ('PRODUCT') STORED})이라
 * 매핑하지 않는다. 매핑하면 하이버네이트가 INSERT 에 포함시켜 {@code ERROR 3105} 가 난다.
 * 이 컬럼의 존재 이유는 복합 FK {@code (item_container_id, container_type)} 의 자식 쪽이라
 * 애플리케이션이 값을 정할 여지가 없다는 점이다.
 */
@Getter
@Entity
@Table(name = "post_product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private PostEntity post;

    @Column(name = "item_container_id", nullable = false)
    private Long itemContainerId;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    @Column(name = "price")
    private Integer price;

    @Column(name = "link_url", length = Length.LONG32)
    private String linkUrl;

    @Column(name = "display_order", nullable = false)
    private Byte displayOrder;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private PostProductEntity(PostEntity post, PostProduct product, LocalDateTime now) {
        this.id = product.id();
        this.post = post;
        this.itemContainerId = product.itemContainerId();
        this.name = product.name();
        this.price = product.price() == null ? null : product.price().intValue();
        this.linkUrl = product.linkUrl();
        this.displayOrder = (byte) product.displayOrder();
        this.createdAt = now;
        this.updatedAt = now;
    }

    static PostProductEntity from(PostEntity post, PostProduct product, LocalDateTime now) {
        return new PostProductEntity(post, product, now);
    }

    PostProduct toDomain() {
        return PostProduct.restore(id, itemContainerId, name,
                price == null ? null : price.longValue(), linkUrl, displayOrder);
    }
}
