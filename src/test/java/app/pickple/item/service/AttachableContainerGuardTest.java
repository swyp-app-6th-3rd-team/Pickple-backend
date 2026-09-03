package app.pickple.item.service;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AttachableContainerGuardTest {

    private static final long OWNER_ID = 1L;
    private static final long CONTAINER_ID = 7L;

    @Mock
    private ItemContainerStore containerStore;

    @InjectMocks
    private AttachableContainerGuard guard;

    @Test
    @DisplayName("용도가 맞으면 통과한다")
    void passesWhenPurposeMatches() {
        given(containerStore.findById(CONTAINER_ID))
                .willReturn(Optional.of(new ItemContainer(OWNER_ID, AttachType.COMMENT)));

        assertThatCode(() -> guard.requireUsableAs(CONTAINER_ID, AttachType.COMMENT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("상품용 컨테이너를 댓글에 붙이면 400 으로 거부한다")
    void rejectsMismatchedPurpose() {
        given(containerStore.findById(CONTAINER_ID))
                .willReturn(Optional.of(new ItemContainer(OWNER_ID, AttachType.PRODUCT)));

        assertThatThrownBy(() -> guard.requireUsableAs(CONTAINER_ID, AttachType.COMMENT))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ResponseCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("없는 컨테이너는 404 로 거부한다")
    void rejectsMissingContainer() {
        given(containerStore.findById(CONTAINER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireUsableAs(CONTAINER_ID, AttachType.COMMENT))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ResponseCode.NOT_FOUND));
    }

    @Test
    @DisplayName("붙일 컨테이너가 없으면 조회조차 하지 않는다")
    void skipsWhenNoContainerRequested() {
        assertThatCode(() -> guard.requireUsableAs(null, AttachType.COMMENT))
                .doesNotThrowAnyException();

        // 사진 없는 댓글이 매번 저장소를 때리면 안 된다.
        verifyNoInteractions(containerStore);
    }
}
