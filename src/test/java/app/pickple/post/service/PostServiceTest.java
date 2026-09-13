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
import app.pickple.post.service.PostService.UpdateCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    @Mock
    private Clock clock;
    @InjectMocks
    private PostService service;

    @Test
    @DisplayName("검색어는 Unicode 양끝 공백만 제거하고 내부 공백과 고정 10건을 보존한다")
    void normalizesSearchKeyword() {
        PostStore.PostSearchResult stored = emptySearchResult();
        given(postStore.search("에어  팟", ScrollPosition.keyset(), 10))
                .willReturn(stored);
        given(clock.instant()).willReturn(Instant.parse("2026-09-10T03:00:00Z"));
        given(clock.getZone()).willReturn(ZoneId.of("Asia/Seoul"));

        PostService.PostSearchResult result =
                service.search("\u00a0\u3000에어  팟\u3000\u00a0", " ");

        assertThat(result.totalCount()).isZero();
        verify(postStore).search("에어  팟", ScrollPosition.keyset(), 10);
    }

    @Test
    @DisplayName("누락·빈 값·31 code point·제어문자 검색어는 DB 조회 전에 400이다")
    void rejectsInvalidSearchKeywordBeforeQuery() {
        for (String keyword : new String[] {
                null, "", " \u00a0 ", "가".repeat(31), "😀".repeat(31), "가\u0000나"
        }) {
            assertThatThrownBy(() -> service.search(keyword, null))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(ResponseCode.INVALID_REQUEST));
        }

        verifyNoInteractions(postStore);
    }

    @Test
    @DisplayName("과대·빈 JSON·깨진 검색 커서는 DB 조회 전에 400이다")
    void rejectsInvalidSearchCursorBeforeQuery() {
        String emptyJsonCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(StandardCharsets.UTF_8));

        for (String cursor : new String[] {
                "a".repeat(1_025), emptyJsonCursor, "not-a-cursor"
        }) {
            assertThatThrownBy(() -> service.search("검색", cursor))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(ResponseCode.INVALID_REQUEST));
        }

        verifyNoInteractions(postStore);
    }

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
    @DisplayName("인기 Top 10 은 인기 카드 전용 경로에 상한 10건을 전달한다")
    void popularTopFixesEveryParameter() {
        given(postStore.findPopularTop(10)).willReturn(List.of());

        service.findPopularTop();

        verify(postStore).findPopularTop(10);
    }

    @Test
    @DisplayName("인기 Top 10 은 게시글이 없으면 빈 목록이다")
    void popularTopReturnsEmptyList() {
        given(postStore.findPopularTop(10)).willReturn(List.of());

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

    // --- 수정·삭제 (R-33 · ADR-0047) ------------------------------------------

    private static Post storedGeneral(Long id, Long authorId, boolean deleted) {
        return Post.restore(id, authorId, PostType.GENERAL, PostCategory.ETC, "제목", "설명",
                List.of(), List.of(), 0L, 0L, 0L, deleted);
    }

    @Test
    @DisplayName("수정은 잠금 조회 뒤 카테고리·제목·설명만 바꾸고 저장한다")
    void updatesWhitelistedFieldsAfterLockingRead() {
        Post stored = storedGeneral(10L, 7L, false);
        given(postStore.findByIdForUpdate(10L)).willReturn(Optional.of(stored));
        given(postStore.save(stored)).willReturn(stored);

        Post result = service.update(10L, 7L, new UpdateCommand(PostCategory.LIVING, "새 제목", "새 설명"));

        assertThat(result.category()).isEqualTo(PostCategory.LIVING);
        assertThat(result.title()).isEqualTo("새 제목");
        assertThat(result.description()).isEqualTo("새 설명");
        assertThat(result.type()).isEqualTo(PostType.GENERAL);
        verify(postStore).findByIdForUpdate(10L);
        verify(postStore, never()).findById(any());
    }

    @Test
    @DisplayName("빈 설명은 비움, null 은 유지다")
    void blankDescriptionClearsAndNullKeeps() {
        Post stored = storedGeneral(10L, 7L, false);
        given(postStore.findByIdForUpdate(10L)).willReturn(Optional.of(stored));
        given(postStore.save(stored)).willReturn(stored);

        service.update(10L, 7L, new UpdateCommand(null, null, null));
        assertThat(stored.description()).isEqualTo("설명");

        service.update(10L, 7L, new UpdateCommand(null, null, "   "));
        assertThat(stored.description()).isNull();
    }

    @Test
    @DisplayName("작성자가 아니면 403 이고 아무것도 저장하지 않는다")
    void rejectsNonAuthorWithForbidden() {
        Post stored = storedGeneral(10L, 7L, false);
        given(postStore.findByIdForUpdate(10L)).willReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.update(10L, 8L, new UpdateCommand(null, "남이 수정", null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ResponseCode.FORBIDDEN);
        assertThatThrownBy(() -> service.delete(10L, 8L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ResponseCode.FORBIDDEN);

        assertThat(stored.title()).isEqualTo("제목");
        assertThat(stored.isDeleted()).isFalse();
        verify(postStore, never()).save(any());
    }

    @Test
    @DisplayName("없거나 삭제된 게시글은 작성자 판정보다 먼저 404 다")
    void missingOrDeletedPostIsNotFoundBeforeAuthorCheck() {
        given(postStore.findByIdForUpdate(1L)).willReturn(Optional.empty());
        given(postStore.findByIdForUpdate(2L)).willReturn(Optional.of(storedGeneral(2L, 7L, true)));

        // 남(8L)이 요청해도 404 — 지운 글의 존재를 알리지 않는다.
        assertThatThrownBy(() -> service.update(1L, 8L, new UpdateCommand(null, "x", null)))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ResponseCode.NOT_FOUND);
        assertThatThrownBy(() -> service.delete(2L, 8L))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ResponseCode.NOT_FOUND);
        assertThatThrownBy(() -> service.delete(2L, 7L))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ResponseCode.NOT_FOUND);
        verify(postStore, never()).save(any());
    }

    @Test
    @DisplayName("삭제는 소프트 삭제로 저장하고 카운터·컨테이너는 건드리지 않는다")
    void deleteMarksAndSaves() {
        Post stored = storedGeneral(10L, 7L, false);
        given(postStore.findByIdForUpdate(10L)).willReturn(Optional.of(stored));
        given(postStore.save(stored)).willReturn(stored);

        service.delete(10L, 7L);

        assertThat(stored.isDeleted()).isTrue();
        verify(postStore).save(stored);
        verifyNoInteractions(itemContainerStore);
    }

    @Test
    @DisplayName("요청자가 없으면 조회 전에 401 이다")
    void rejectsAnonymousBeforeLookup() {
        assertThatThrownBy(() -> service.update(10L, null, new UpdateCommand(null, "x", null)))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ResponseCode.UNAUTHORIZED);
        assertThatThrownBy(() -> service.delete(10L, null))
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ResponseCode.UNAUTHORIZED);
        verifyNoInteractions(postStore);
    }

    private static PostStore.PostSearchResult emptySearchResult() {
        Window<PostStore.PostSearchView> window =
                Window.from(List.of(), index -> ScrollPosition.keyset(), false);
        return new PostStore.PostSearchResult(0L, window);
    }
}
