package app.pickple.error;

import app.pickple.comment.domain.DuplicatePickException;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.item.domain.ItemContainerNotAttachableException;
import app.pickple.post.domain.PostNotPublishableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException e) {
        if (e.code().status().is5xxServerError()) {
            log.error("[{}] {}", e.code(), e.getMessage(), e);
        } else {
            log.warn("[{}] {}", e.code(), e.getMessage());
        }
        return ResponseEntity.status(e.code().status()).body(ApiResponse.error(e.code()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception e) {
        // rejected value에는 authorization code나 token이 들어갈 수 있으므로 상세 메시지를 기록하지 않는다.
        log.warn("요청 검증 실패: {}", e.getClass().getSimpleName());
        return status(ResponseCode.INVALID_REQUEST);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("업로드 용량 초과: {}", e.getMessage());
        return status(ResponseCode.IMAGE_TOO_LARGE);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        // 요청 경로도 사용자 입력이므로 민감 값이 섞일 가능성을 피하고 상세를 남기지 않는다.
        log.warn("리소스를 찾을 수 없습니다");
        return status(ResponseCode.NOT_FOUND);
    }

    /**
     * 이 게시글에서 이미 원픽했다 (R-05).
     *
     * <p>도메인 예외를 여기서 번역한다 — {@code DuplicatePickException} 은
     * {@code ApiException} 이 아니라 {@code RuntimeException} 을 직접 상속한다.
     * 도메인 계층이 HTTP 개념({@code ResponseCode})을 알면 안 되기 때문이다(ADR-0008).
     *
     * <p>요청 자체는 올바른데 <b>상태가 충돌</b>한 경우라 409 다.
     * 재시도해도 결과가 같으므로 클라이언트는 400 처럼 요청을 고쳐 보낼 것이 없다.
     */
    @ExceptionHandler(DuplicatePickException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicatePick(DuplicatePickException e) {
        log.warn("원픽 중복: {}", e.getMessage());
        return status(ResponseCode.ALREADY_PICKED);
    }

    /**
     * 도메인 생성자·상태 전이가 던지는 불변식 위반.
     * 여기까지 왔다는 것은 컨트롤러 단계의 요청 검증이 뚫렸다는 뜻이므로 warn 으로 남긴다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("도메인 불변식 위반이 요청 검증을 통과했습니다: {}", e.getMessage());
        return status(ResponseCode.INVALID_REQUEST);
    }

    /** 요청으로 만든 게시글 구성이 R-02·R-04 등의 발행 규칙을 어겼다. */
    @ExceptionHandler(PostNotPublishableException.class)
    public ResponseEntity<ApiResponse<Void>> handlePostNotPublishable(PostNotPublishableException e) {
        log.warn("게시글 발행 조건 위반: {}", e.getMessage());
        return status(ResponseCode.INVALID_REQUEST);
    }

    /** 요청한 부착 대상과 이미지 컨테이너의 용도가 맞지 않는다. */
    @ExceptionHandler(ItemContainerNotAttachableException.class)
    public ResponseEntity<ApiResponse<Void>> handleItemContainerNotAttachable(
            ItemContainerNotAttachableException e) {
        log.warn("이미지 컨테이너 용도 불일치: {}", e.getMessage());
        return status(ResponseCode.INVALID_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return status(ResponseCode.SYSTEM_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> status(ResponseCode code) {
        return ResponseEntity.status(code.status()).body(ApiResponse.error(code));
    }
}
