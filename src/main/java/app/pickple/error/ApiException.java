package app.pickple.error;

import app.pickple.common.ResponseCode;

/**
 * 애플리케이션이 의도적으로 던지는 예외.
 * 서브클래스를 늘리지 않고 ResponseCode 로 구분한다.
 */
public class ApiException extends RuntimeException {

    private final ResponseCode code;

    public ApiException(ResponseCode code) {
        super(code.message());
        this.code = code;
    }

    public ApiException(ResponseCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ResponseCode code() {
        return code;
    }
}
