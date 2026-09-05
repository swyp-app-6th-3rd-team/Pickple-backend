package app.pickple.point.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ranking", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RankingScheduler {

    private final RankingBatchService batchService;

    @Scheduled(cron = "${app.ranking.cron}")
    public void refresh() {
        batchService.refresh();
    }
}
