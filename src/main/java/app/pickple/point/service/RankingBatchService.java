package app.pickple.point.service;

import app.pickple.point.domain.RankingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 랭킹을 주기적으로 다시 매긴다 (ADR-0028).
 *
 * <p><b>왜 주기 배치인가</b> — 순위는 전역 값이라 한 사람의 포인트가 오르면
 * 그 사람만 바뀌는 게 아니라 밀려난 사람 전부가 바뀐다. 실측(200k)에서 회원 1명이
 * 최상위로 점프하자 75,747행의 순위가 이동했다. 이걸 지급 트랜잭션 안에서 처리하면
 * 사용자가 버튼 하나를 누르는 동안 대량 UPDATE 가 붙고 {@code users} 행 락을
 * 로그인 경로와 함께 잡는다. 정확도를 위해 쓰기 경로를 인질로 잡는 거래라 택하지 않았다.
 *
 * <p>대신 <b>순위가 최대 한 주기만큼 낡는다</b>. 이 지연이 이 결정의 대가이고,
 * 응답 계약의 일부다.
 *
 * <p><b>실패가 조용하다</b> — 배치가 죽어도 목록 조회는 낡은 순위로 정상 응답한다.
 * 헬스체크로는 드러나지 않으므로 성공·실패를 남겨 이 실패가 보이게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingBatchService {

    private final RankingStore rankingStore;

    /**
     * 캐시 컬럼을 정본과 맞춘 뒤 순위를 다시 매긴다.
     *
     * <p>동기화가 두 갈래인 이유는 화면이 읽는 값이 서로 다른 정본에서 오기 때문이다 —
     * 포인트는 {@code point_history} 원장에서(R-14), 누적 투표 수는 {@code vote} 에서
     * (ADR-0032). 둘 다 순위·등급을 판정하기 <b>전에</b> 최신이어야 한다.
     *
     * <p>세 단계는 각각 별도 트랜잭션이다. 앞 단계까지만 끝나고 뒤가 실패해도
     * 다음 주기가 같은 자리에서 이어받는다 — 세 연산 모두 <b>현재 상태에서 다시 계산</b>하는
     * 형태라 중간에 끊겨도 어긋난 상태가 남지 않는다.
     *
     * <p>{@code fixedDelay} 가 아니라 cron 인 이유는 지연 상한을 벽시계로 말하기 위해서다.
     * 다만 실행이 겹치지 않는다는 보장은 스케줄러의 단일 스레드 기본값에 기대고 있다 —
     * 배치가 주기보다 오래 걸리면 다음 실행이 밀린다(겹쳐 도는 것보다 낫다).
     */
    @Scheduled(cron = "${app.ranking.cron}")
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
            // 삼키지 않는다. 스케줄러가 다음 주기에 다시 부르므로 한 번의 실패는 회복되지만,
            // 계속 실패하면 순위가 조용히 낡는다 — 그 사실이 로그에 남아야 한다.
            log.error("랭킹 재계산 실패. 순위가 갱신되지 않았습니다.", e);
        }
    }
}
