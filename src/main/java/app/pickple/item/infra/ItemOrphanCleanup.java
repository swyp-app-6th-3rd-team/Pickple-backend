package app.pickple.item.infra;

import app.pickple.config.ItemCleanupProperties;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.FileObjectStorage;
import app.pickple.item.domain.ItemOrphanStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 주기 정리의 조정자. S3 호출은 DB 트랜잭션 밖에서 한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemOrphanCleanup {

    private final ItemOrphanStore orphanStore;
    private final FileObjectStorage objectStorage;
    private final ItemCleanupProperties properties;
    private final Clock clock;

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
                for (Long id : ids) {
                    try {
                        // 포트가 반환되면 삭제가 커밋됐다. 실패한 S3 키는 다음 목록 탐색이 회수한다.
                        for (String key : orphanStore.removeIfUnattached(id, lower, upper)) {
                            deleteIfUnreferenced(key);
                        }
                    } catch (RuntimeException failure) {
                        log.warn("고아 컨테이너 정리 실패: containerId={}", id, failure);
                    }
                    afterId = id;
                }
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
                for (var object : page.objects()) {
                    if (object.lastModified().isBefore(managedSince) || !object.lastModified().isBefore(cutoff)) {
                        continue;
                    }
                    deleteIfUnreferenced(object.key());
                }
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

    private void deleteIfUnreferenced(String key) {
        try {
            // 기존 DB에 같은 키를 가진 다른 메타데이터가 있어도 참조를 보호한다.
            if (!orphanStore.containsObjectKey(key)) {
                deleteObject(key);
            }
        } catch (RuntimeException failure) {
            log.warn("고아 객체 확인 실패: key={}", key, failure);
        }
    }
}
