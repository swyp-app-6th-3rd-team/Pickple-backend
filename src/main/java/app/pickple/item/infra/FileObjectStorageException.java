package app.pickple.item.infra;

/** S3 요청 실패를 SDK 예외 타입과 분리해 상위 계층으로 전달한다. */
public class FileObjectStorageException extends RuntimeException {

    public FileObjectStorageException(String message) {
        super(message);
    }

    public FileObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
