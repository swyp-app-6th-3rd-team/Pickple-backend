package app.pickple.item.infra;

import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code item_container} 한 행.
 *
 * <p>{@code uk_container_id_type (id, attach_type)} 은 부착 측 복합 FK 의 대상이다.
 * 이 유니크 키가 없으면 {@code post_product}·{@code comment} 의 복합 FK 를 걸 수 없다.
 */
@Getter
@Entity
@Table(name = "item_container", uniqueConstraints =
        @UniqueConstraint(name = "uk_container_id_type", columnNames = {"id", "attach_type"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemContainerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "attach_type", nullable = false, length = 20)
    private AttachType attachType;

    @OneToMany(mappedBy = "container", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItemResourceEntity> resources = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private ItemContainerEntity(ItemContainer container, LocalDateTime now) {
        this.id = container.id();
        this.userId = container.ownerId();
        this.attachType = container.attachType();
        this.createdAt = now;
        this.updatedAt = now;
        container.resources().forEach(r -> this.resources.add(ItemResourceEntity.from(this, r, now)));
    }

    public static ItemContainerEntity from(ItemContainer container, LocalDateTime now) {
        return new ItemContainerEntity(container, now);
    }

    /**
     * 담긴 파일을 새 목록으로 맞춘다.
     *
     * <p>{@code orphanRemoval} 이 켜져 있으므로 컬렉션에서 빠진 행은 함께 지워진다.
     * 컬렉션 자체를 새 인스턴스로 바꾸면 하이버네이트가 추적을 잃으므로
     * 기존 컬렉션을 비우고 다시 채운다.
     */
    public void applyResources(ItemContainer container, LocalDateTime now) {
        this.resources.clear();
        container.resources().forEach(r -> this.resources.add(ItemResourceEntity.from(this, r, now)));
        this.updatedAt = now;
    }

    public ItemContainer toDomain() {
        return ItemContainer.restore(id, userId, attachType,
                resources.stream().map(ItemResourceEntity::toDomain).toList());
    }
}
