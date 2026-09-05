package app.pickple.item.domain;

import java.time.Instant;
import java.util.List;

/**
 * 이미지 원본을 보관하는 외부 저장소 포트.
 *
 * <p>서비스 계층은 S3 SDK를 직접 알지 않는다. 운영에서는 S3가, 통합 테스트에서는
 * 같은 포트 뒤의 LocalStack S3가 동작한다.
 */
public interface FileObjectStorage {

    /** 객체를 쓰지 않고 안정적인 접근 URL을 계산한다. put의 반환 URL과 같아야 한다. */
    String accessUrl(String itemKey);

    /** 객체를 저장하고 클라이언트가 사용할 안정적인 접근 URL을 돌려준다. */
    String put(String itemKey, byte[] content, String contentType);

    /** 실패 보상이나 삭제 흐름에서 객체를 제거한다. 없는 키 삭제도 성공으로 취급한다. */
    void delete(String itemKey);

    /** 지정한 접두어의 한 페이지. 다음 토큰이 null이면 마지막 페이지다. */
    ObjectPage list(String prefix, String continuationToken, int pageSize);

    record StoredObject(String key, Instant lastModified) {
    }

    record ObjectPage(List<StoredObject> objects, String nextToken) {
        public ObjectPage {
            objects = List.copyOf(objects);
        }
    }
}
