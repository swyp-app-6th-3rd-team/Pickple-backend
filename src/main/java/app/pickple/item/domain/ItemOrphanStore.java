package app.pickple.item.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 미부착 메타데이터 회수와 객체 키 존재 확인을 위한 포트. */
public interface ItemOrphanStore {

    List<Long> findCandidates(LocalDateTime managedSince, LocalDateTime cutoff, long afterId, int limit);

    /**
     * 참조와 유예시간을 다시 확인해 미부착 컨테이너를 삭제한다.
     * 반환 시 DB 삭제는 커밋돼 있다. 경합에서 부착이 먼저 완료됐으면 빈 목록이다.
     */
    List<String> removeIfUnattached(Long containerId, LocalDateTime managedSince, LocalDateTime cutoff);

    /** 진행 중인 메타데이터 쓰기의 커밋/롤백을 확인한다. 확인 실패는 예외로 알린다. */
    boolean containsObjectKey(String itemKey);
}
