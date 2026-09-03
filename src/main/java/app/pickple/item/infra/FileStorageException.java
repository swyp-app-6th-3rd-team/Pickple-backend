package app.pickple.item.infra;

/** S3 요청 실패를 SDK 예외 타입과 분리해 상위 계층으로 전달한다. */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
