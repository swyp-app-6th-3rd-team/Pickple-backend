package app.pickple.error;

import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException e) {
        log.warn("[{}] {}", e.code(), e.getMessage());
        return ResponseEntity.status(e.code().status()).body(ApiResponse.error(e.code()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception e) {
        // rejected value에는 authorization code나 token이 들어갈 수 있으므로 상세 메시지를 기록하지 않는다.
        log.warn("요청 검증 실패: {}", e.getClass().getSimpleName());
        return status(ResponseCode.INVALID_REQUEST);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return status(ResponseCode.NOT_FOUND);
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

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        log.warn("도메인 상태 전이 위반: {}", e.getMessage());
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
