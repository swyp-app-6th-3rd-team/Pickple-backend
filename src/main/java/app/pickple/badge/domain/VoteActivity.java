package app.pickple.badge.domain;

/**
 * 뱃지 판정의 입력 — 한 회원의 투표 활동을 세 유형이 요구하는 형태로 요약한 값.
 *
 * <p><b>왜 세 수를 한 객체로 묶는가</b> — 뱃지 8종은 유형이 셋이지만(R-18)
 * 전부 "임계값을 넘었는가" 하나를 묻는다. 판정할 때마다 유형을 보고 어느 수를
 * 가져올지 고르게 하면, 그 분기가 획득 판정과 미션 진행률 두 곳에 각각 생긴다.
 * 여기서 한 번 모아두면 {@link Badge} 가 자기 유형에 맞는 수를 꺼내 쓴다.
 *
 * <p>세 수의 출처는 전부 {@code user_daily_activity} 다 (R-19).
 * 투표 이력 전체를 훑지 않기 위해 날짜별로 접어 둔 값이고,
 * 재투표는 애초에 이 집계를 늘리지 않으므로 (R-22) 세 수 모두 자동으로 그 규칙을 따른다.
 *
 * @param totalVoteCount  누적 투표 횟수. 일별 집계의 합계
 * @param todayVoteCount  오늘 투표 수. 오늘 행이 없으면 0
 * @param streakDays      오늘(또는 어제)부터 거슬러 하루도 빠지지 않은 날 수.
 *                        끊기면 처음부터 다시 센다
 */
public record VoteActivity(long totalVoteCount, long todayVoteCount, long streakDays) {

    public VoteActivity {
        if (totalVoteCount < 0 || todayVoteCount < 0 || streakDays < 0) {
            throw new IllegalArgumentException(
                    "투표 활동 수는 음수일 수 없습니다: total=%d, today=%d, streak=%d"
                            .formatted(totalVoteCount, todayVoteCount, streakDays));
        }
    }

    /** 아직 한 번도 투표하지 않은 회원. 가입 직후가 이 상태다. */
    public static VoteActivity none() {
        return new VoteActivity(0, 0, 0);
    }

    /**
     * 이 유형이 세는 수를 돌려준다.
     *
     * <p>{@link Badge} 만 쓴다. 유형별 분기를 여기 한 곳에 가둬,
     * 유형이 늘어날 때 고칠 자리가 하나가 되게 한다.
     */
    long countFor(BadgeConditionType type) {
        return switch (type) {
            case TOTAL_VOTE -> totalVoteCount;
            case DAILY_VOTE -> todayVoteCount;
            case STREAK_VOTE -> streakDays;
        };
    }
}
