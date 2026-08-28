package app.pickple.common;

/**
 * 모든 HTTP 응답의 공통 봉투.
 * 성공과 실패가 같은 모양이라 클라이언트가 분기를 단순하게 유지할 수 있다.
 */
public record ApiResponse<T>(String code, String message, T returnObject) {

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
