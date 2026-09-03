package app.pickple.post.service;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.post.service.PostCreationService.CreateCommand;
import app.pickple.post.service.PostCreationService.ProductCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostCreationServiceTest {

    @Mock
    private PostStore postStore;
    @Mock
    private ItemContainerStore itemContainerStore;
    @InjectMocks
    private PostCreationService service;

    @Test
    @DisplayName("찬반 게시글은 상품명을 제목으로 쓰고 서버가 선택지 둘을 만든다")
    void createsAgreePost() {
        given(itemContainerStore.findById(10L)).willReturn(Optional.of(container(1L, AttachType.PRODUCT, 1)));
        given(postStore.save(any(Post.class))).willAnswer(invocation -> invocation.getArgument(0));

        Post created = service.create(1L, new CreateCommand(
                PostType.AGREE,
                PostCategory.FASHION,
                "클라이언트 제목",
                "설명",
                List.of(new ProductCommand(10L, "검정 가방", 89_000L, null))));

        assertThat(created.title()).isEqualTo("검정 가방");
        assertThat(created.products()).hasSize(1);
        assertThat(created.options()).extracting(option -> option.label())
                .containsExactly("사자", "말자");
        verify(postStore).save(created);
    }

    @Test
    @DisplayName("새 A/B 선택지는 두 상품의 표시 순서를 각각 가리킨다")
    void createsAbPost() {
        given(itemContainerStore.findById(10L)).willReturn(Optional.of(container(1L, AttachType.PRODUCT, 1)));
        given(itemContainerStore.findById(20L)).willReturn(Optional.of(container(1L, AttachType.PRODUCT, 1)));
        given(postStore.save(any(Post.class))).willAnswer(invocation -> invocation.getArgument(0));

        Post created = service.create(1L, new CreateCommand(
                PostType.A_B,
                PostCategory.BEAUTY,
                "A vs B",
                null,
                List.of(
                        new ProductCommand(10L, "A 상품", null, null),
                        new ProductCommand(20L, "B 상품", null, null))));

        assertThat(created.options())
                .extracting(option -> option.postProductDisplayOrder())
                .containsExactly(1, 2);
        assertThat(created.options()).allMatch(option -> option.pointsToProduct());
    }

    @Test
    @DisplayName("다른 사용자의 이미지 컨테이너는 저장 전에 거부한다")
    void rejectsForeignContainer() {
        given(itemContainerStore.findById(10L)).willReturn(Optional.of(container(2L, AttachType.PRODUCT, 1)));

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.FORBIDDEN));
        verify(postStore, never()).save(any());
    }

    @Test
    @DisplayName("게시글 유형의 사진 장수와 맞지 않으면 저장 전에 거부한다")
    void rejectsWrongPhotoCount() {
        given(itemContainerStore.findById(10L)).willReturn(Optional.of(container(1L, AttachType.PRODUCT, 4)));

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1~3장");
        verify(postStore, never()).save(any());
    }

    @Test
    @DisplayName("같은 요청에서 이미지 컨테이너를 두 상품에 중복 사용할 수 없다")
    void rejectsDuplicateContainerInRequest() {
        given(itemContainerStore.findById(10L)).willReturn(Optional.of(container(1L, AttachType.PRODUCT, 1)));

        CreateCommand command = new CreateCommand(
                PostType.A_B,
                PostCategory.ETC,
                "A vs B",
                null,
                List.of(
                        new ProductCommand(10L, "A", null, null),
                        new ProductCommand(10L, "B", null, null)));

        assertThatThrownBy(() -> service.create(1L, command))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.INVALID_REQUEST));
        verify(postStore, never()).save(any());
    }

    @Test
    @DisplayName("이미 상품에 붙은 컨테이너는 재사용할 수 없다")
    void rejectsAttachedContainer() {
        given(itemContainerStore.findById(10L)).willReturn(Optional.of(container(1L, AttachType.PRODUCT, 1)));
        given(postStore.isItemContainerAttached(10L)).willReturn(true);

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.INVALID_REQUEST));
        verify(postStore, never()).save(any());
    }

    @Test
    @DisplayName("동시 재사용의 컨테이너 유일성 충돌만 400 계약으로 변환한다")
    void translatesContainerRaceToInvalidRequest() {
        DataIntegrityViolationException conflict = new DataIntegrityViolationException(
                "insert failed",
                new SQLException("Duplicate entry for key 'post_product.uk_product_container'"));
        given(itemContainerStore.findById(10L)).willReturn(Optional.of(container(1L, AttachType.PRODUCT, 1)));
        given(postStore.save(any(Post.class))).willThrow(conflict);

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ResponseCode.INVALID_REQUEST);
                    assertThat(exception.getCause()).isSameAs(conflict);
                });
    }

    @Test
    @DisplayName("다른 무결성 위반은 잘못된 요청으로 숨기지 않는다")
    void rethrowsUnrelatedDataIntegrityViolation() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException(
                "insert failed",
                new SQLException("foreign key fk_post_user failed"));
        given(itemContainerStore.findById(10L)).willReturn(Optional.of(container(1L, AttachType.PRODUCT, 1)));
        given(postStore.save(any(Post.class))).willThrow(failure);

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L))).isSameAs(failure);
    }

    private CreateCommand agreeCommand(Long containerId) {
        return new CreateCommand(
                PostType.AGREE,
                PostCategory.ETC,
                null,
                null,
                List.of(new ProductCommand(containerId, "상품", null, null)));
    }

    private ItemContainer container(Long ownerId, AttachType attachType, int photoCount) {
        ItemContainer container = new ItemContainer(ownerId, attachType);
        for (int index = 0; index < photoCount; index++) {
            container.add(new ItemResource(
                    1L,
                    "image-" + index + ".png",
                    "test/image-" + index,
                    "https://images.test/image-" + index));
        }
        return container;
    }
}
