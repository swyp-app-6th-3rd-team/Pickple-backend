package app.pickple.item.infra;

import app.pickple.item.domain.ItemOrphanStore;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Collections;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class JdbcItemOrphanStore implements ItemOrphanStore {

    private final JdbcTemplate jdbc;

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findCandidates(LocalDateTime managedSince, LocalDateTime cutoff, long afterId, int limit) {
        return jdbc.queryForList("""
                SELECT c.id FROM item_container c
                WHERE c.id > ? AND c.created_at >= ? AND c.updated_at < ?
                  AND NOT EXISTS (SELECT 1 FROM post_product p WHERE p.item_container_id = c.id)
                  AND NOT EXISTS (SELECT 1 FROM comment m WHERE m.item_container_id = c.id)
                ORDER BY c.id LIMIT ?
                """, Long.class, afterId, managedSince, cutoff, limit);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public List<String> removeIfUnattached(Long containerId, LocalDateTime managedSince, LocalDateTime cutoff) {
        // FK 삽입의 부모 공유 잠금과 직렬화한다. soft delete 여부는 보지 않는다.
        var ids = jdbc.queryForList("""
                SELECT id FROM item_container
                WHERE id = ? AND created_at >= ? AND updated_at < ? FOR UPDATE
                """, Long.class, containerId, managedSince, cutoff);
        if (ids.isEmpty() || referencedBy("post_product", containerId) || referencedBy("comment", containerId)) {
            return List.of();
        }
        var keys = jdbc.queryForList(
                "SELECT item_key FROM item_resource WHERE item_container_id = ?", String.class, containerId);
        // item_resource는 ON DELETE CASCADE. 부착 FK가 삭제의 마지막 방어선이다.
        jdbc.update("DELETE FROM item_container WHERE id = ?", containerId);
        return keys;
    }

    private boolean referencedBy(String table, Long containerId) {
        // table은 위 두 내부 상수만 사용한다. current read로 잠금 대기 후 최신 참조를 본다.
        return !jdbc.queryForList("SELECT id FROM " + table + " WHERE item_container_id = ? FOR SHARE",
                Long.class, containerId).isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Set<String> findReferencedObjectKeys(List<String> itemKeys) {
        if (itemKeys.isEmpty()) {
            return Set.of();
        }
        // 일반 스냅샷 조회는 아직 커밋되지 않은 업로드를 '없음'으로 오판한다.
        String placeholders = String.join(",", Collections.nCopies(itemKeys.size(), "?"));
        return Set.copyOf(jdbc.queryForList(
                "SELECT item_key FROM item_resource WHERE item_key IN (" + placeholders + ") FOR SHARE",
                String.class, itemKeys.toArray()));
    }
}
