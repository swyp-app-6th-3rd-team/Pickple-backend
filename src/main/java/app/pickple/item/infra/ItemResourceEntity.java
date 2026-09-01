package app.pickple.item.infra;

import app.pickple.item.domain.ItemResource;
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
@Table(name = "item_resource")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemResourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_container_id", nullable = false)
    private ItemContainerEntity container;

    @Column(name = "size", nullable = false)
    private Long size;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "item_key", nullable = false, length = 500)
    private String itemKey;

    @Column(name = "access_url", nullable = false, length = 500)
    private String accessUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private ItemResourceEntity(ItemContainerEntity container, ItemResource resource, LocalDateTime now) {
        this.id = resource.id();
        this.container = container;
        this.size = resource.size();
        this.originalFileName = resource.originalFileName();
        this.itemKey = resource.itemKey();
        this.accessUrl = resource.accessUrl();
        this.createdAt = now;
        this.updatedAt = now;
    }

    static ItemResourceEntity from(ItemContainerEntity container, ItemResource resource, LocalDateTime now) {
        return new ItemResourceEntity(container, resource, now);
    }

    ItemResource toDomain() {
        return ItemResource.restore(id, size, originalFileName, itemKey, accessUrl);
    }
}
