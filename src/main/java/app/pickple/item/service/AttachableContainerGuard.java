package app.pickple.item.service;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerNotAttachableException;
import app.pickple.item.domain.ItemContainerStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 컨테이너를 붙이기 전에 그 용도가 맞는지 확인한다.
 *
 * <p>컨테이너는 생성 시점에 용도({@link AttachType})가 정해진다. 상품용으로 만든 것을
 * 댓글에 붙이면 안 되는데, 붙이는 쪽은 식별자만 받으므로 스스로는 알 수 없다.
 *
 * <p><b>스키마가 이미 막는데 왜 또 확인하나</b> — 복합 FK
 * {@code (item_container_id, attach_type)} 가 최종 방어선이긴 하다. 다만 거기까지 가면
 * {@code DataIntegrityViolationException} 하나로 올라와 원인이 뭉개진다. 같은 예외에
 * 다른 제약 위반도 섞이므로 "용도가 어긋났다" 를 되찾을 수 없다
 * (실제로 복합 FK 위반을 "이미 원픽한 댓글" 로 잘못 보고한 적이 있다).
 * 여기서 먼저 걸러 호출자가 뜻이 통하는 4xx 를 받게 한다.
 *
 * <p><b>도메인이 아니라 서비스인 이유</b>: 저장소에 의존해 다른 애그리거트를 조회한다.
 * 판정 자체는 {@link ItemContainer#verifyUsableAs}가 들고 있고, 이 클래스는 조회만 맡는다.
 * ({@code ActivePostGuard} 와 같은 구조다.)
 *
 * <p><b>한계</b>: 확인과 부착 사이에 컨테이너가 바뀌면 막지 못한다. 다만 용도는 생성
 * 시점에 정해지고 바뀌지 않으므로(불변) 이 창으로 뚫릴 여지는 없다. 컨테이너가
 * 그 사이 삭제되는 경우는 복합 FK 가 잡는다.
 */
@Component
@RequiredArgsConstructor
public class AttachableContainerGuard {

    private final ItemContainerStore containerStore;

    /**
     * 그 용도로 쓸 수 있는 컨테이너면 통과, 아니면 거부한다.
     *
     * <p>{@code containerId} 가 {@code null} 이면 부착하지 않겠다는 뜻이므로 통과시킨다.
     * 필수 여부는 도메인 규칙(R-03 등)이 각자 판단할 몫이지 이 관문의 일이 아니다.
     */
    public void requireUsableAs(Long containerId, AttachType required) {
        if (containerId == null) {
            return;
        }
        ItemContainer container = containerStore.findById(containerId)
                .orElseThrow(() -> new ApiException(
                        ResponseCode.NOT_FOUND, "이미지 컨테이너를 찾을 수 없습니다."));
        try {
            container.verifyUsableAs(required);
        } catch (ItemContainerNotAttachableException e) {
            // 도메인은 자기 언어로 던진다. 경계에서 API 계약의 언어로 옮긴다.
            throw new ApiException(ResponseCode.INVALID_REQUEST, e.getMessage());
        }
    }
}
