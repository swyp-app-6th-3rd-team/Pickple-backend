package app.pickple.grade.domain;

import java.util.Optional;

/**
 * 한 사람의 등급 현황 — 현재 등급과 다음 등급까지의 진행 (기능명세 §11.1).
 *
 * <p>조회 데이터는 "등급 명칭, 현재 총 포인트, 투표 횟수, 다음 등급까지의 달성률" 이다.
 * 넷이 한 시점의 같은 사실이라 한 값으로 묶는다 — 따로 조회하면 그 사이 들어온 지급이
 * 섞여 포인트는 새 값인데 등급은 옛 값인 화면이 나온다.
 *
 * @param grade      현재 등급. 도달한 최고 등급이다 — 계산값보다 낮아지지 않는다 (R-16)
 * @param point      누적 포인트. 원장 합계다 (R-14)
 * @param voteCount  누적 투표 횟수. 재투표는 세지 않는다 (R-22)
 */
public record GradeProgress(Grade grade, long point, long voteCount) {

    public GradeProgress {
        if (grade == null) {
            throw new IllegalArgumentException("등급은 필수입니다.");
        }
        if (point < 0 || voteCount < 0) {
            throw new IllegalArgumentException(
                    "누적값은 음수일 수 없습니다: point=%d, voteCount=%d".formatted(point, voteCount));
        }
    }

    /** 다음 등급. 최고 등급이면 비어 있다. */
    public Optional<Grade> nextGrade() {
        return grade.next();
    }

    /**
     * 다음 등급까지의 달성률 (0~100).
     *
     * <p><b>두 조건 중 덜 채운 쪽이 달성률이다</b> (R-15). 승급이 AND 라서
     * 포인트를 90% 채우고 투표를 10% 채웠다면 실제로 남은 길은 90% 다 —
     * 둘을 평균 내면 50% 로 보여 곧 오를 것처럼 속인다.
     *
     * <p>최고 등급이면 100 이다. 다음이 없는 것을 0% 로 두면 화면의 게이지가 비어
     * 최고 등급 사용자가 아무것도 못 한 것처럼 보인다.
     *
     * <p>진행의 기준선은 <b>현재 등급의 조건</b>이다. 0 에서 재는 게 아니라
     * 이미 지나온 구간을 빼고 남은 구간만 본다 — 그래야 승급 직후 게이지가 0 에서 시작한다.
     */
    public int achievementRate() {
        Optional<Grade> next = nextGrade();
        if (next.isEmpty()) {
            return 100;
        }
        Grade target = next.get();
        int pointRate = rateOf(point, grade.requiredPoint(), target.requiredPoint());
        int voteRate = rateOf(voteCount, grade.requiredVoteCount(), target.requiredVoteCount());
        return Math.min(pointRate, voteRate);
    }

    /**
     * 한 조건의 진행률.
     *
     * <p>정수만으로 계산한다. 부동소수점을 쓰면 경계에서 반올림이 값마다 달라지고,
     * 도메인은 실수형을 쓰지 않는다(ArchitectureTest "금액·수량에 실수형 금지").
     *
     * <p><b>내림이다.</b> 조건을 채우지 못했는데 100% 로 보이면 안 된다 —
     * 화면이 "다 찼는데 안 오른다" 로 읽힌다. 실제로 채웠을 때만 100 이 된다.
     */
    private static int rateOf(long current, long from, long to) {
        long span = to - from;
        if (span <= 0) {
            // 두 등급의 조건이 같으면 이 축으로는 잴 것이 없다. 다른 축이 달성률을 정한다.
            return 100;
        }
        long earned = Math.min(Math.max(current - from, 0), span);
        return (int) (earned * 100 / span);
    }
}
