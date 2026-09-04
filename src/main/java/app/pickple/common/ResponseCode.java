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
    APPLE_MANUAL_REVOCATION_REQUIRED(
            HttpStatus.OK,
            "회원 탈퇴는 완료되었습니다. Apple 계정 설정에서 Pickple 연결을 직접 해제해 주세요."),

    // 요청 오류
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "JPEG 또는 PNG 이미지 파일만 업로드할 수 있습니다."),
    IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "이미지 파일 크기가 허용 범위를 초과했습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    NICKNAME_ALREADY_IN_USE(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    ALREADY_PICKED(HttpStatus.CONFLICT, "이 게시글에서 이미 원픽했습니다."),

    // 인증 · 인가
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 토큰입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    OAUTH2_FAILED(HttpStatus.UNAUTHORIZED, "소셜 로그인에 실패했습니다."),
    APPLE_LOGIN_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Apple 로그인을 현재 사용할 수 없습니다."),
    KAKAO_LOGIN_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Kakao 로그인을 현재 사용할 수 없습니다."),
    APPLE_ACCOUNT_REVOCATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "Apple 계정 연결 해제를 완료할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    KAKAO_ACCOUNT_REVOCATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "Kakao 계정 연결 해제를 완료할 수 없습니다. 잠시 후 다시 시도해 주세요."),

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
