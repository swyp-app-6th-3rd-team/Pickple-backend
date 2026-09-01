package app.pickple.item.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 컨테이너 저장·조회 왕복. 스키마가 실제로 도메인을 담아내는지 확인한다.
 *
 * <p>{@code ddl-auto: validate} 라 엔티티와 테이블이 어긋나면 컨텍스트 기동에서 먼저 걸린다.
 * 여기서는 그 다음 — <b>값이 왕복에서 보존되는지</b>를 본다.
 */
@IntegrationTest
@Transactional
class JpaItemContainerStoreIT {

    @Autowired
    private ItemContainerStore store;

    @Autowired
    private UserStore userStore;

    private Long ownerId;

    @BeforeEach
    void setUp() {
        // item_container.user_id 에 FK 가 걸려 있어 실제 사용자가 필요하다.
        User owner = userStore.save(new User(SocialProvider.GOOGLE, "item-owner", null, "업로더"));
        ownerId = owner.id();
    }

    @Test
    @DisplayName("파일을 담아 저장하면 그대로 돌아온다")
    void savesAndRestoresResources() {
        ItemContainer container = new ItemContainer(ownerId, AttachType.PRODUCT)
                .add(new ItemResource(2048L, "원본 이름.jpg", "s3/key/1", "https://cdn.example.com/1"))
                .add(new ItemResource(4096L, "두번째.png", "s3/key/2", "https://cdn.example.com/2"));

        ItemContainer saved = store.save(container);

        assertThat(saved.id()).isNotNull();
        ItemContainer found = store.findById(saved.id()).orElseThrow();
        assertThat(found.ownerId()).isEqualTo(ownerId);
        assertThat(found.attachType()).isEqualTo(AttachType.PRODUCT);
        assertThat(found.photoCount()).isEqualTo(2);
        assertThat(found.resources())
                .extracting(ItemResource::itemKey, ItemResource::originalFileName)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("s3/key/1", "원본 이름.jpg"),
                        org.assertj.core.groups.Tuple.tuple("s3/key/2", "두번째.png"));
    }

    @Test
    @DisplayName("용도가 왕복에서 보존된다")
    void attachTypeSurvivesRoundTrip() {
        // 용도가 뒤집히면 부착 측 복합 FK 가 엉뚱한 곳에 붙는 것을 허용하게 된다.
        ItemContainer comment = new ItemContainer(ownerId, AttachType.COMMENT)
                .add(new ItemResource(1L, "c.jpg", "s3/c", "https://cdn.example.com/c"));

        ItemContainer saved = store.save(comment);

        assertThat(store.findById(saved.id()).orElseThrow().attachType())
                .isEqualTo(AttachType.COMMENT);
    }

    @Test
    @DisplayName("빈 컨테이너도 저장된다 — 파일은 나중에 붙는다")
    void emptyContainerIsPersisted() {
        // 업로드 세션을 먼저 열고 파일을 나중에 올리는 흐름을 막지 않는다.
        // "사진이 최소 한 장" 은 상품에 붙이는 시점에 검증한다 (R-03).
        ItemContainer saved = store.save(new ItemContainer(ownerId, AttachType.PRODUCT));

        assertThat(store.findById(saved.id()).orElseThrow().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("없는 id 는 빈 값이다")
    void missingIdReturnsEmpty() {
        assertThat(store.findById(-1L)).isEmpty();
    }
}
