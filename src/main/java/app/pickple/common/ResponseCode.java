package app.pickple.common;

import org.springframework.http.HttpStatus;

/**
 * HTTP 상태 · 에러 코드 · 사용자 메시지를 한 곳에 묶는다.
 * 예외 클래스를 계층으로 늘리는 대신 이 enum 값으로 분기한다.
 */
public enum ResponseCode {

    // 성공
    OK(HttpStatus.OK, "정상 처리되었습니다."),
    CREATED(HttpStatus.CREATED, "생성되었습니다."),

    // 요청 오류
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),

    // 인증 · 인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    OAUTH2_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다."),

    // 서버
    SYSTEM_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ResponseCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
