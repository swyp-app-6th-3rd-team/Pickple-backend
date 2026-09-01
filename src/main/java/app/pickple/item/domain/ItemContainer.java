package app.pickple.item.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 파일 묶음. 상품 사진이나 댓글 사진을 담는다.
 *
 * <p>컨테이너는 <b>자기가 어디에 붙는지 모른다.</b> 부착 대상(상품·댓글)이
 * {@code item_container_id} 를 들고, 용도({@link AttachType})를 쌍으로 참조해
 * 잘못된 곳에 붙는 것을 막는다. 이 방향이면 polymorphic FK 없이도
 * 진짜 FK 제약을 걸 수 있다. 근거는 ADR-0008 과 ERD 2차 2.1.
 *
 * <p>장수 제약(R-03)은 유형마다 다르고 이 객체 혼자서는 게시글 유형을 알 수 없다.
 * 그래서 {@link #verifyPhotoCount(int, int)} 로 상한·하한을 받아 검증한다.
 */
public class ItemContainer {

    /** 사진은 최소 한 장이다. 빈 컨테이너를 상품에 붙이면 사진 없는 상품이 된다. */
    public static final int MIN_PHOTOS = 1;

    private final Long id;
    private final Long ownerId;
    private final AttachType attachType;
    private final List<ItemResource> resources;

    public ItemContainer(Long ownerId, AttachType attachType) {
        this(null, ownerId, attachType, List.of());
    }

    private ItemContainer(Long id, Long ownerId, AttachType attachType, List<ItemResource> resources) {
        if (ownerId == null) {
            throw new IllegalArgumentException("업로더는 필수입니다.");
        }
        if (attachType == null) {
            throw new IllegalArgumentException("컨테이너 용도는 필수입니다.");
        }
        this.id = id;
        this.ownerId = ownerId;
        this.attachType = attachType;
        this.resources = new ArrayList<>(resources);
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static ItemContainer restore(Long id, Long ownerId, AttachType attachType,
                                        List<ItemResource> resources) {
        return new ItemContainer(id, ownerId, attachType, resources);
    }

    /** 파일을 담는다. */
    public ItemContainer add(ItemResource resource) {
        if (resource == null) {
            throw new IllegalArgumentException("담을 파일이 없습니다.");
        }
        resources.add(resource);
        return this;
    }

    /**
     * 장수가 범위 안인지 확인한다 (R-03).
     *
     * <p>찬반 상품은 1~3장, A/B 상품은 각 1장, 댓글은 0~1장이다.
     * 몇 장이어야 하는지는 게시글 유형이 정하므로 호출자가 범위를 넘긴다 —
     * 컨테이너가 게시글 유형을 알면 두 애그리거트가 얽힌다.
     */
    public void verifyPhotoCount(int min, int max) {
        int count = resources.size();
        if (count < min || count > max) {
            throw new IllegalStateException(
                    "사진은 %d~%d장이어야 합니다. 현재 %d장입니다.".formatted(min, max, count));
        }
    }

    /** 이 컨테이너를 그 용도로 쓸 수 있는지 확인한다. */
    public void verifyUsableAs(AttachType required) {
        if (this.attachType != required) {
            throw new IllegalStateException(
                    "%s 용 컨테이너를 %s 에 붙일 수 없습니다.".formatted(attachType, required));
        }
    }

    public boolean isEmpty() {
        return resources.isEmpty();
    }

    public int photoCount() {
        return resources.size();
    }

    public Long id() {
        return id;
    }

    public Long ownerId() {
        return ownerId;
    }

    public AttachType attachType() {
        return attachType;
    }

    public List<ItemResource> resources() {
        return Collections.unmodifiableList(resources);
    }
}
