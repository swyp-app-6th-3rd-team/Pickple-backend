package app.pickple.item.domain;

/**
 * 이미지 원본을 보관하는 외부 저장소 포트.
 *
 * <p>서비스 계층은 S3 SDK를 직접 알지 않는다. 운영에서는 S3가, 통합 테스트에서는
 * 같은 포트 뒤의 LocalStack S3가 동작한다.
 */
public interface ImageObjectStorage {

    /** 객체를 저장하고 클라이언트가 사용할 안정적인 접근 URL을 돌려준다. */
    String put(String itemKey, byte[] content, String contentType);

    /** 실패 보상이나 삭제 흐름에서 객체를 제거한다. 없는 키 삭제도 성공으로 취급한다. */
    void delete(String itemKey);
}
