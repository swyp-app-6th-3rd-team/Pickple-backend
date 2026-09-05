package app.pickple.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 스케줄링은 전역 활성화하고 각 스케줄러가 자기 feature flag를 적용한다. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({RankingProperties.class, ItemCleanupProperties.class})
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler("scheduled-");
    }

    @Bean
    public ThreadPoolTaskScheduler itemCleanupTaskScheduler() {
        return scheduler("item-cleanup-");
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        return scheduler;
    }
}
