package app.pickple.item.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.file.cleanup", name = "enabled", havingValue = "true")
public class ItemCleanupScheduler {

    private final ItemOrphanCleanup cleanup;

    @Scheduled(cron = "${app.file.cleanup.cron}", scheduler = "itemCleanupTaskScheduler")
    public void clean() {
        cleanup.clean();
    }
}
