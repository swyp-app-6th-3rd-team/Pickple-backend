package app.pickple.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 모든 HTTP 응답의 공통 봉투.
 * 성공과 실패가 같은 모양이라 클라이언트가 분기를 단순하게 유지할 수 있다.
 */
public record ApiResponse<T>(
        @Schema(description = "처리 결과 코드. 실패는 ResponseCode 의 이름이다", example = "OK") String code,
        @Schema(description = "사용자에게 그대로 보여줄 메시지", example = "정상 처리되었습니다.") String message,
        @Schema(description = "실제 응답 본문. 실패면 null") T returnObject) {

    public static <T> ApiResponse<T> success(T returnObject) {
        return new ApiResponse<>(ResponseCode.OK.name(), ResponseCode.OK.message(), returnObject);
    }

    public static <T> ApiResponse<T> of(ResponseCode code, T returnObject) {
        return new ApiResponse<>(code.name(), code.message(), returnObject);
    }

    public static ApiResponse<Void> error(ResponseCode code) {
        return new ApiResponse<>(code.name(), code.message(), null);
    }
}
