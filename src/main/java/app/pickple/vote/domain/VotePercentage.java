package app.pickple.vote.domain;

/**
 * 선택지 득표 수를 화면에 표시할 정수 퍼센트로 바꾼다.
 *
 * <p>투표 직후 응답과 조회 응답이 같은 계산을 써야 사용자가 화면을 다시 열었을 때
 * 게이지 값이 달라지지 않는다. 부동소수점 대신 정수 연산으로 반올림하며,
 * 선택지별 반올림 때문에 두 값의 합이 100이 아닐 수 있다.
 */
public final class VotePercentage {

    private VotePercentage() {
    }

    /** 아무도 투표하지 않았으면 0, 그 외에는 가장 가까운 정수 퍼센트를 반환한다. */
    public static int calculate(long optionVoteCount, long voterCount) {
        if (voterCount <= 0) {
            return 0;
        }
        return (int) ((optionVoteCount * 200 + voterCount) / (voterCount * 2));
    }
}
