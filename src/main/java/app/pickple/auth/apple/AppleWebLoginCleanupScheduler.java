package app.pickple.auth.apple;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료 handoff 정리를 작은 batch로 반복해 로그인 요청 지연과 분리한다. */
@Component
@RequiredArgsConstructor
public class AppleWebLoginCleanupScheduler {

    private static final int BATCH_SIZE = 100;
    private final AppleWebLoginCleanupService cleanupService;

    @Scheduled(fixedDelayString = "${app.oauth.apple.web.cleanup-interval:PT1M}",
            initialDelayString = "${app.oauth.apple.web.cleanup-initial-delay:PT1M}")
    public void cleanup() {
        for (int i = 0; i < BATCH_SIZE && cleanupService.cleanupOne(); i++) {
            // 각 대상의 독립 트랜잭션을 batch 상한까지 반복한다.
        }
    }
}
