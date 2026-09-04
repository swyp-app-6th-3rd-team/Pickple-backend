package app.pickple.badge.service;

import app.pickple.badge.domain.DailyActivityStore;
import app.pickple.vote.domain.VoteActivityRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 투표가 일어났다는 사실을 뱃지 도메인이 받아 처리한다.
 *
 * <p>{@code vote} 가 선언한 포트({@link VoteActivityRecorder})를 {@code badge} 가 구현하므로
 * 컴파일 의존은 {@code badge → vote} 한 방향이다. 투표는 보상 도메인을 알지 않는다.
 *
 * <p><b>같은 트랜잭션에서 동기로 돈다.</b> 커밋 후로 미루면(@code AFTER_COMMIT} 이든
 * {@code @Async} 든) 투표는 커밋되고 활동 기록만 유실되어, 일일·연속 판정의 정본인
 * 집계가 조용히 어긋난다(R-19). 되돌릴 방법도 없다 —
 * {@code vote} 행에서 재계산할 수는 있지만 그 사실을 아무도 알아채지 못한다.
 *
 * <p>반대 방향의 위험(뱃지 실패가 투표를 죽이는 것)은 판정을 단순하게 유지해 줄인다.
 * 이 경로가 하는 일은 UPSERT 하나와 임계값 비교뿐이라 예외가 날 지점이 사실상 없다.
 * {@code RankingBatchService} 가 "쓰기 경로를 인질로 잡는 거래" 를 거부한 것과 결이 같지만,
 * 그쪽은 20만 행 재계산이고 이쪽은 자기 행 몇 개다 — 비용이 다르면 판단도 다르다.
 */
@Component
@RequiredArgsConstructor
public class BadgeVoteActivityRecorder implements VoteActivityRecorder {

    private final DailyActivityStore dailyActivityStore;
    private final BadgeService badgeService;
    private final Clock clock;

    /**
     * {@inheritDoc}
     *
     * <p>기록이 먼저고 판정이 나중이다. 순서가 바뀌면 방금 한 투표가 판정에 빠져
     * "20번째 투표를 했는데 뱃지가 다음 투표에서 나오는" 한 박자 밀림이 생긴다.
     * 명세가 "투표 시 미션 2의 상태바가 즉시 변경됨" 을 요구하므로 그 밀림은 계약 위반이다.
     */
    @Override
    @Transactional
    public void recordVoteAndEvaluate(Long voterId) {
        dailyActivityStore.increaseVoteCount(voterId, LocalDate.now(clock));
        badgeService.evaluate(voterId);
    }
}
