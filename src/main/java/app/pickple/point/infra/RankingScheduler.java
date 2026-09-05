package app.pickple.point.infra;

import app.pickple.point.domain.RankingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 포인트와 투표 수를 동기화한 뒤 랭킹을 갱신한다 (ADR-0028, ADR-0032). */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ranking", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RankingScheduler {

    private final RankingStore rankingStore;

    /** 각 저장소 호출은 별도 트랜잭션이며, 실패한 실행은 다음 주기에 처음부터 재시도한다. */
    @Scheduled(cron = "${app.ranking.cron}", scheduler = "taskScheduler")
    public void refresh() {
        long startedAt = System.nanoTime();
        try {
            int pointsSynced = rankingStore.syncPointsFromLedger();
            int voteCountsSynced = rankingStore.syncVoteCountsFromVotes();
            int rankingsChanged = rankingStore.recalculateRankings();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            log.info("랭킹 재계산 완료: 포인트 갱신 {}명, 투표 수 갱신 {}명, 순위 변동 {}명, {}ms",
                    pointsSynced, voteCountsSynced, rankingsChanged, elapsedMs);
        } catch (RuntimeException e) {
            // 실패를 기록하고 다음 예약 실행에서 세 단계를 다시 시도한다.
            log.error("랭킹 재계산 실패. 순위가 갱신되지 않았습니다.", e);
        }
    }
}
