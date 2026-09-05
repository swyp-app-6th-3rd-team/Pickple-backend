package app.pickple.config;

import app.pickple.item.infra.ItemCleanupScheduler;
import app.pickple.item.infra.ItemOrphanCleanup;
import app.pickple.point.service.RankingBatchService;
import app.pickple.point.service.RankingScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

class SchedulingConfigIT {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, ItemCleanupScheduler.class, RankingScheduler.class)
            .withBean(ItemOrphanCleanup.class, () -> mock(ItemOrphanCleanup.class))
            .withBean(RankingBatchService.class, () -> mock(RankingBatchService.class))
            .withPropertyValues("app.ranking.cron=-", "app.file.cleanup.cron=-",
                    "app.file.cleanup.grace-period=24h", "app.file.cleanup.batch-size=100");

    @Test
    void cleanupCanBeEnabledWithRankingDisabled() {
        context.withPropertyValues("app.ranking.enabled=false", "app.file.cleanup.enabled=true",
                        "app.file.cleanup.managed-since=2026-09-05T00:00:00Z")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ItemCleanupScheduler.class)
                        .doesNotHaveBean(RankingScheduler.class).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void rankingCanRunWithCleanupDisabledByDefault() {
        context.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(RankingScheduler.class)
                .doesNotHaveBean(ItemCleanupScheduler.class));
    }

    @Test
    void bothSchedulersCanBeDisabled() {
        context.withPropertyValues("app.ranking.enabled=false", "app.file.cleanup.enabled=false")
                .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(RankingScheduler.class)
                        .doesNotHaveBean(ItemCleanupScheduler.class));
    }

    @Test
    void enabledCleanupRequiresManagementStart() {
        context.withPropertyValues("app.file.cleanup.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void rejectsUnsafeGracePeriodAndPageSize() {
        context.withPropertyValues("app.file.cleanup.grace-period=0s")
                .run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("app.file.cleanup.batch-size=1001")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void slowCleanupDoesNotBlockScheduledRanking() {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch rankingRanWhileCleanupBlocked = new CountDownLatch(1);
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, ItemCleanupScheduler.class, RankingScheduler.class)
                .withPropertyValues("app.file.cleanup.grace-period=24h", "app.file.cleanup.batch-size=100",
                        "app.ranking.enabled=true", "app.file.cleanup.enabled=true",
                        "app.file.cleanup.managed-since=2026-09-05T00:00:00Z",
                        "app.ranking.cron=*/1 * * * * *", "app.file.cleanup.cron=*/1 * * * * *")
                .withBean(ItemOrphanCleanup.class, () -> {
                    ItemOrphanCleanup cleanup = mock(ItemOrphanCleanup.class);
                    doAnswer(call -> {
                        cleanupStarted.countDown();
                        releaseCleanup.await(10, TimeUnit.SECONDS);
                        return null;
                    }).when(cleanup).clean();
                    return cleanup;
                })
                .withBean(RankingBatchService.class, () -> {
                    RankingBatchService ranking = mock(RankingBatchService.class);
                    doAnswer(call -> {
                        if (cleanupStarted.getCount() == 0) rankingRanWhileCleanupBlocked.countDown();
                        return null;
                    }).when(ranking).refresh();
                    return ranking;
                })
                .run(ctx -> {
                    try {
                        assertThat(ctx).hasNotFailed();
                        assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(rankingRanWhileCleanupBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                    } finally {
                        releaseCleanup.countDown();
                    }
                });
    }
}
