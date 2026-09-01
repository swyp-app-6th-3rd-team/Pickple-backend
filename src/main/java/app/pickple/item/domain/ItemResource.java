package app.pickple.item.domain;

/**
 * 업로드된 파일 하나. 컨테이너에 담긴다.
 *
 * <p>S3 객체 키와 CloudFront 접근 URL을 함께 갖는다. 키는 삭제·재발급에 쓰고
 * URL은 화면에 내보낸다. 둘 중 하나만 두면 다른 쪽 작업에서 매번 조합해야 한다.
 */
public class ItemResource {

    private final Long id;
    private final long size;
    private final String originalFileName;
    private final String itemKey;
    private final String accessUrl;

    public ItemResource(long size, String originalFileName, String itemKey, String accessUrl) {
        this(null, size, originalFileName, itemKey, accessUrl);
    }

    private ItemResource(Long id, long size, String originalFileName, String itemKey, String accessUrl) {
        if (size <= 0) {
            throw new IllegalArgumentException("파일 크기는 0보다 커야 합니다.");
        }
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("원본 파일명은 필수입니다.");
        }
        if (itemKey == null || itemKey.isBlank()) {
            throw new IllegalArgumentException("S3 객체 키는 필수입니다.");
        }
        if (accessUrl == null || accessUrl.isBlank()) {
            throw new IllegalArgumentException("접근 URL 은 필수입니다.");
        }
        this.id = id;
        this.size = size;
        this.originalFileName = originalFileName;
        this.itemKey = itemKey;
        this.accessUrl = accessUrl;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static ItemResource restore(Long id, long size, String originalFileName,
                                       String itemKey, String accessUrl) {
        return new ItemResource(id, size, originalFileName, itemKey, accessUrl);
    }

    public Long id() {
        return id;
    }

    public long size() {
        return size;
    }

    public String originalFileName() {
        return originalFileName;
    }

    public String itemKey() {
        return itemKey;
    }

    public String accessUrl() {
        return accessUrl;
    }
}
