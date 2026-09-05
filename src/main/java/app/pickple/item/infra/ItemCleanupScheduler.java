package app.pickple.item.infra;

import app.pickple.config.ItemCleanupProperties;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.FileObjectStorage;
import app.pickple.item.domain.ItemOrphanStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 미부착 아이템과 고아 저장 객체를 주기적으로 정리한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.file.cleanup", name = "enabled", havingValue = "true")
public class ItemCleanupScheduler {

    private final ItemOrphanStore orphanStore;
    private final FileObjectStorage objectStorage;
    private final ItemCleanupProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${app.file.cleanup.cron}", scheduler = "itemCleanupTaskScheduler")
    public void clean() {
        if (!properties.enabled()) {
            return;
        }
        Instant cutoff = clock.instant().minus(properties.gracePeriod()).truncatedTo(ChronoUnit.SECONDS);
        Instant managedSince = properties.managedSince();
        cleanContainers(managedSince, cutoff);
        for (AttachType type : AttachType.values()) {
            cleanObjects(type.keyPrefix() + "/", managedSince, cutoff);
        }
    }

    private void cleanContainers(Instant managedSince, Instant cutoff) {
        LocalDateTime lower = LocalDateTime.ofInstant(managedSince, clock.getZone());
        LocalDateTime upper = LocalDateTime.ofInstant(cutoff, clock.getZone());
        long afterId = 0;
        try {
            while (true) {
                var ids = orphanStore.findCandidates(lower, upper, afterId, properties.batchSize());
                if (ids.isEmpty()) {
                    return;
                }
                List<String> removedKeys = new ArrayList<>();
                for (Long id : ids) {
                    try {
                        // 포트가 반환되면 삭제가 커밋됐다. 실패한 S3 키는 다음 목록 탐색이 회수한다.
                        removedKeys.addAll(orphanStore.removeIfUnattached(id, lower, upper));
                    } catch (RuntimeException failure) {
                        log.warn("고아 컨테이너 정리 실패: containerId={}", id, failure);
                    }
                    afterId = id;
                }
                deleteIfUnreferenced(removedKeys);
            }
        } catch (RuntimeException failure) {
            log.warn("고아 컨테이너 조회 실패", failure);
        }
    }

    private void cleanObjects(String prefix, Instant managedSince, Instant cutoff) {
        String token = null;
        try {
            do {
                var page = objectStorage.list(prefix, token, properties.batchSize());
                var keys = page.objects().stream()
                        .filter(object -> !object.lastModified().isBefore(managedSince)
                                && object.lastModified().isBefore(cutoff))
                        .map(FileObjectStorage.StoredObject::key)
                        .toList();
                deleteIfUnreferenced(keys);
                token = page.nextToken();
            } while (token != null);
        } catch (RuntimeException failure) {
            log.warn("고아 객체 목록 조회 실패: prefix={}", prefix, failure);
        }
    }

    private void deleteObject(String key) {
        try {
            objectStorage.delete(key);
        } catch (RuntimeException failure) {
            log.warn("고아 객체 삭제 실패. 다음 목록 탐색에서 재시도합니다: key={}", key, failure);
        }
    }

    private void deleteIfUnreferenced(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        try {
            var referencedKeys = orphanStore.findReferencedObjectKeys(keys);
            for (String key : keys) {
                if (!referencedKeys.contains(key)) {
                    deleteObject(key);
                }
            }
        } catch (RuntimeException failure) {
            log.warn("고아 객체 참조 확인 실패. 해당 묶음은 다음 주기에 재시도합니다: count={}", keys.size(), failure);
        }
    }
}
