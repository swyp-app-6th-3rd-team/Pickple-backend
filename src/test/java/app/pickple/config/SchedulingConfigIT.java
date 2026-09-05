package app.pickple.config;

import app.pickple.item.domain.FileObjectStorage;
import app.pickple.item.domain.ItemOrphanStore;
import app.pickple.item.infra.ItemCleanupScheduler;
import app.pickple.point.domain.RankingStore;
import app.pickple.point.infra.RankingScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchedulingConfigIT {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, ItemCleanupScheduler.class, RankingScheduler.class)
            .withBean(ItemOrphanStore.class, () -> mock(ItemOrphanStore.class))
            .withBean(FileObjectStorage.class, () -> mock(FileObjectStorage.class))
            .withBean(RankingStore.class, () -> mock(RankingStore.class))
            .withBean(Clock.class, Clock::systemUTC)
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
        context.withPropertyValues("app.file.cleanup.grace-period=0s").run(ctx -> assertThat(ctx).hasFailed());
        context.withPropertyValues("app.file.cleanup.batch-size=1001").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void disabledFlagsRegisterNoTasksEvenWithValidCrons() {
        context.withPropertyValues("app.ranking.enabled=false", "app.file.cleanup.enabled=false",
                        "app.ranking.cron=*/1 * * * * *", "app.file.cleanup.cron=*/1 * * * * *")
                .run(ctx -> assertThat(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class)
                        .getScheduledTasks()).isEmpty());
    }

    @Test
    void slowCleanupDoesNotBlockScheduledRanking() {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch rankingRanWhileCleanupBlocked = new CountDownLatch(1);
        AtomicReference<String> cleanupThread = new AtomicReference<>();
        AtomicReference<String> rankingThread = new AtomicReference<>();
        new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, ItemCleanupScheduler.class, RankingScheduler.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("app.file.cleanup.grace-period=24h", "app.file.cleanup.batch-size=100",
                        "app.ranking.enabled=true", "app.file.cleanup.enabled=true",
                        "app.file.cleanup.managed-since=2026-09-05T00:00:00Z",
                        "app.ranking.cron=*/1 * * * * *", "app.file.cleanup.cron=*/1 * * * * *")
                .withBean(ItemOrphanStore.class, () -> {
                    ItemOrphanStore store = mock(ItemOrphanStore.class);
                    when(store.findCandidates(any(), any(), anyLong(), anyInt())).thenAnswer(call -> {
                        cleanupThread.set(Thread.currentThread().getName());
                        cleanupStarted.countDown();
                        releaseCleanup.await(10, TimeUnit.SECONDS);
                        return List.of();
                    });
                    return store;
                })
                .withBean(FileObjectStorage.class, () -> {
                    FileObjectStorage storage = mock(FileObjectStorage.class);
                    when(storage.list(anyString(), any(), anyInt()))
                            .thenReturn(new FileObjectStorage.ObjectPage(List.of(), null));
                    return storage;
                })
                .withBean(RankingStore.class, () -> {
                    RankingStore store = mock(RankingStore.class);
                    when(store.recalculateRankings()).thenAnswer(call -> {
                        rankingThread.set(Thread.currentThread().getName());
                        if (cleanupStarted.getCount() == 0) rankingRanWhileCleanupBlocked.countDown();
                        return 0;
                    });
                    return store;
                })
                .run(ctx -> {
                    try {
                        assertThat(ctx).hasNotFailed();
                        assertThat(ctx.getBeansOfType(ThreadPoolTaskScheduler.class))
                                .containsOnlyKeys("taskScheduler", "itemCleanupTaskScheduler");
                        assertThat(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(2);
                        assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(rankingRanWhileCleanupBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(cleanupThread.get()).startsWith("item-cleanup-");
                        assertThat(rankingThread.get()).startsWith("scheduled-");
                    } finally {
                        releaseCleanup.countDown();
                    }
                });
    }
}
