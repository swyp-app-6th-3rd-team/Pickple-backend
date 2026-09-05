package app.pickple.post.service;

import app.pickple.common.CursorCodec;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.domain.ItemContainerAlreadyAttachedException;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostSort;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.post.service.PostService.CreateCommand;
import app.pickple.post.service.PostService.ProductCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private PostStore postStore;
    @Mock
    private ItemContainerStore itemContainerStore;
    @Mock
    private RandomGenerator randomGenerator;
    @InjectMocks
    private PostService service;

    @Test
    @DisplayName("랜덤 카드 첫 요청은 새 시드와 유형·사용자·10건 크기를 전달한다")
    void startsRandomSliceWithNewSeed() {
        given(randomGenerator.nextLong()).willReturn(314L);
        Window<PostStore.RandomPostView> empty =
                Window.from(List.of(), index -> ScrollPosition.keyset(), false);
        given(postStore.findRandomSlice(PostType.AGREE, 7L, ScrollPosition.keyset(), 10, 314L))
                .willReturn(empty);

        assertThat(service.findRandomSlice(PostType.AGREE, null, 7L)).isSameAs(empty);
        verify(randomGenerator).nextLong();
    }

    @Test
    @DisplayName("랜덤 카드 후속 요청은 시드를 다시 만들지 않고 커서를 전달한다")
    void continuesRandomSliceWithoutReseeding() {
        KeysetScrollPosition position = ScrollPosition.forward(Map.of(
                "randomSeed", 314, "postType", "A_B", "randomKey", 123, "id", 45));

        service.findRandomSlice(PostType.A_B, CursorCodec.encode(position), null);

        verify(postStore).findRandomSlice(PostType.A_B, null, position, 10, 0L);
        verifyNoInteractions(randomGenerator);
    }

    @Test
    @DisplayName("투표 유형이 없거나 일반 유형이면 랜덤 조회 전에 400으로 거부한다")
    void rejectsInvalidRandomType() {
        for (PostType type : new PostType[]{null, PostType.GENERAL}) {
            assertThatThrownBy(() -> service.findRandomSlice(type, null, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.code()).isEqualTo(ResponseCode.INVALID_REQUEST));
        }
        verifyNoInteractions(postStore, randomGenerator);
    }

    @Test
    @DisplayName("깨진 랜덤 커서는 DB 조회나 시드 생성 전에 400으로 거부한다")
    void rejectsMalformedRandomCursorBeforeQuery() {
        assertThatThrownBy(() -> service.findRandomSlice(PostType.AGREE, "not-a-cursor", null))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.INVALID_REQUEST));
        verifyNoInteractions(postStore, randomGenerator);
    }

    @Test
    @DisplayName("찬반 게시글은 상품명을 제목으로 쓰고 서버가 선택지 둘을 만든다")
    void createsAgreePost() {
        given(itemContainerStore.findAllByIds(Set.of(10L)))
                .willReturn(Map.of(10L, container(1L, AttachType.PRODUCT, 1)));
        given(postStore.findAttachedItemContainerIds(Set.of(10L))).willReturn(Set.of());
        given(postStore.saveIfContainerFree(any(Post.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

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
        verify(postStore).saveIfContainerFree(created);
    }

    @Test
    @DisplayName("새 A/B 선택지는 두 상품의 표시 순서를 각각 가리킨다")
    void createsAbPost() {
        Set<Long> containerIds = Set.of(10L, 20L);
        given(itemContainerStore.findAllByIds(containerIds)).willReturn(Map.of(
                10L, container(1L, AttachType.PRODUCT, 1),
                20L, container(1L, AttachType.PRODUCT, 1)));
        given(postStore.findAttachedItemContainerIds(containerIds)).willReturn(Set.of());
        given(postStore.saveIfContainerFree(any(Post.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

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
        verify(itemContainerStore).findAllByIds(containerIds);
        verify(postStore).findAttachedItemContainerIds(containerIds);
        verify(itemContainerStore, never()).findById(any());
    }

    @Test
    @DisplayName("다른 사용자의 이미지 컨테이너는 저장 전에 거부한다")
    void rejectsForeignContainer() {
        given(itemContainerStore.findAllByIds(Set.of(10L)))
                .willReturn(Map.of(10L, container(2L, AttachType.PRODUCT, 1)));
        given(postStore.findAttachedItemContainerIds(Set.of(10L))).willReturn(Set.of());

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ResponseCode.FORBIDDEN));
        verify(postStore, never()).saveIfContainerFree(any());
    }

    @Test
    @DisplayName("게시글 유형의 사진 장수와 맞지 않으면 저장 전에 거부한다")
    void rejectsWrongPhotoCount() {
        given(itemContainerStore.findAllByIds(Set.of(10L)))
                .willReturn(Map.of(10L, container(1L, AttachType.PRODUCT, 4)));
        given(postStore.findAttachedItemContainerIds(Set.of(10L))).willReturn(Set.of());

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1~3장");
        verify(postStore, never()).saveIfContainerFree(any());
    }

    @Test
    @DisplayName("같은 요청에서 이미지 컨테이너를 두 상품에 중복 사용할 수 없다")
    void rejectsDuplicateContainerInRequest() {
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
        verify(itemContainerStore, never()).findAllByIds(any());
        verify(postStore, never()).findAttachedItemContainerIds(any());
        verify(postStore, never()).saveIfContainerFree(any());
    }

    @Test
    @DisplayName("이미 상품에 붙은 컨테이너는 재사용할 수 없다")
    void rejectsAttachedContainer() {
        given(itemContainerStore.findAllByIds(Set.of(10L)))
                .willReturn(Map.of(10L, container(1L, AttachType.PRODUCT, 1)));
        given(postStore.findAttachedItemContainerIds(Set.of(10L))).willReturn(Set.of(10L));

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(ResponseCode.ITEM_CONTAINER_ALREADY_IN_USE));
        verify(postStore, never()).saveIfContainerFree(any());
    }

    @Test
    @DisplayName("저장 중 확인된 컨테이너 재사용도 409 계약으로 변환한다")
    void translatesAttachedContainerToConflict() {
        ItemContainerAlreadyAttachedException conflict =
                new ItemContainerAlreadyAttachedException(new RuntimeException("unique constraint"));
        given(itemContainerStore.findAllByIds(Set.of(10L)))
                .willReturn(Map.of(10L, container(1L, AttachType.PRODUCT, 1)));
        given(postStore.findAttachedItemContainerIds(Set.of(10L))).willReturn(Set.of());
        given(postStore.saveIfContainerFree(any(Post.class))).willThrow(conflict);

        assertThatThrownBy(() -> service.create(1L, agreeCommand(10L)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ResponseCode.ITEM_CONTAINER_ALREADY_IN_USE);
                    assertThat(exception.getCause()).isSameAs(conflict);
                });
    }

    @Test
    @DisplayName("정렬·크기를 넘기지 않으면 최신순 10개다")
    void appliesQueryDefaults() {
        service.findSlice(null, null, null, null);

        verify(postStore).findSlice(
                isNull(), eq(PostSort.LATEST), any(ScrollPosition.class), eq(PostService.DEFAULT_SIZE));
    }

    @Test
    @DisplayName("카테고리와 정렬을 그대로 조회 저장소에 넘긴다")
    void passesQueryFiltersDown() {
        service.findSlice(PostCategory.BEAUTY, "POPULAR", null, null);

        verify(postStore).findSlice(
                eq(PostCategory.BEAUTY), eq(PostSort.POPULAR), eq(ScrollPosition.keyset()), eq(PostService.DEFAULT_SIZE));
    }

    @Test
    @DisplayName("조회 조각 크기는 1 미만이면 기본값, 50을 넘으면 50으로 자른다")
    void clampsSliceSize() {
        service.findSlice(null, null, null, 0);
        service.findSlice(null, null, null, -5);
        service.findSlice(null, null, null, 100_000);
        service.findSlice(null, null, null, 25);

        verify(postStore, times(2)).findSlice(
                isNull(), eq(PostSort.LATEST), eq(ScrollPosition.keyset()), eq(PostService.DEFAULT_SIZE));
        verify(postStore).findSlice(
                isNull(), eq(PostSort.LATEST), eq(ScrollPosition.keyset()), eq(50));
        verify(postStore).findSlice(
                isNull(), eq(PostSort.LATEST), eq(ScrollPosition.keyset()), eq(25));
    }

    @Test
    @DisplayName("커서가 없으면 첫 조각 위치를 넘긴다")
    void decodesAbsentCursorAsFirstSlice() {
        service.findSlice(null, null, null, null);

        verify(postStore).findSlice(
                isNull(), eq(PostSort.LATEST), eq(ScrollPosition.keyset()), eq(PostService.DEFAULT_SIZE));
    }

    @Test
    @DisplayName("인기 Top 10 은 전체·인기순·첫 조각·10건으로 조회한다")
    void popularTopFixesEveryParameter() {
        given(postStore.findSlice(
                isNull(), eq(PostSort.POPULAR), eq(ScrollPosition.keyset()), eq(10)))
                .willReturn(emptyWindow());

        service.findPopularTop();

        verify(postStore).findSlice(
                isNull(), eq(PostSort.POPULAR), eq(ScrollPosition.keyset()), eq(10));
    }

    @Test
    @DisplayName("인기 Top 10 은 게시글이 없으면 빈 목록이다")
    void popularTopReturnsEmptyList() {
        given(postStore.findSlice(
                isNull(), eq(PostSort.POPULAR), eq(ScrollPosition.keyset()), eq(10)))
                .willReturn(emptyWindow());

        assertThat(service.findPopularTop()).isEmpty();
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

    private Window<PostStore.PostListView> emptyWindow() {
        return Window.from(List.of(), index -> ScrollPosition.keyset(), false);
    }
}
