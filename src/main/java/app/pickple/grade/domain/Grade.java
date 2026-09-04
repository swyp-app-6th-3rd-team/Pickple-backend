package app.pickple.grade.domain;

import java.util.List;

/**
 * 등급과 승급 조건 (정책 요약표 §2).
 *
 * <p><b>임계값의 정본이 여기다</b> — 기준 테이블을 두지 않는다 (ADR-0030).
 * DB 에 두면 정책 요약표와 DB 두 곳이 정본이 되고, 복제본은 어긋날 수 있다.
 * 5개 고정값이고 릴리스 주기에 묶여 있어 코드가 정본에 더 가깝다.
 *
 * <p><b>승급은 AND 조건이다</b> (R-15). 포인트만 채워도 오르지 않는다.
 * 이 판정이 enum 안에 있는 이유는 애그리거트 하나의 상태(포인트·투표 횟수)만으로
 * 결정되기 때문이다 — 규칙이 객체 안에 있으면 그 객체를 쓰는 모든 경로가 자동으로 지킨다.
 */
public enum Grade {

    /** 가입 시 기본 부여. 조건이 0 이라 아무도 이 아래로 갈 수 없다. */
    LV1(1, "LV.1", 0L, 0L),
    LV2(2, "LV.2", 200L, 20L),
    LV3(3, "LV.3", 1_000L, 100L),
    LV4(4, "LV.4", 3_500L, 300L),
    LV5(5, "LV.5", 10_000L, 1_000L);

    private static final List<Grade> ORDERED = List.of(values());

    private final int level;
    private final String displayName;
    private final long requiredPoint;
    private final long requiredVoteCount;

    Grade(int level, String displayName, long requiredPoint, long requiredVoteCount) {
        this.level = level;
        this.displayName = displayName;
        this.requiredPoint = requiredPoint;
        this.requiredVoteCount = requiredVoteCount;
    }

    /** 낮은 등급부터 순서대로. 전체 등급 조회(§11.2)가 이 순서를 그대로 쓴다. */
    public static List<Grade> ordered() {
        return ORDERED;
    }

    /**
     * 레벨 번호로 되찾는다. 저장된 {@code users.highest_grade} 를 복원할 때만 쓴다.
     *
     * <p>모르는 값이면 예외다. 조용히 LV.1 로 떨어뜨리면 저장된 등급이 사라져
     * R-16(등급은 내려가지 않는다)이 깨지는데, 그 사실이 아무 데도 드러나지 않는다.
     */
    public static Grade ofLevel(int level) {
        for (Grade grade : ORDERED) {
            if (grade.level == level) {
                return grade;
            }
        }
        throw new IllegalArgumentException("알 수 없는 등급입니다: level=" + level);
    }

    /**
     * 누적 포인트와 투표 횟수로 도달 가능한 가장 높은 등급 (R-15).
     *
     * <p>위에서부터 내려오며 처음 충족하는 등급을 고른다. 아래에서 올라가며 찾으면
     * 중간에 한 단계를 건너뛴 상태(예: LV.3 조건은 되는데 LV.2 조건은 아닌)에서
     * 멈춰버리는데, 임계값이 단조 증가라 그런 상태는 없다 — 그럼에도 위에서 내려오는 쪽이
     * "충족하는 것 중 가장 높은 것" 이라는 정의를 그대로 옮긴 형태다.
     */
    public static Grade reachedBy(long point, long voteCount) {
        for (int i = ORDERED.size() - 1; i >= 0; i--) {
            Grade grade = ORDERED.get(i);
            if (grade.isSatisfiedBy(point, voteCount)) {
                return grade;
            }
        }
        // LV.1 의 조건이 0P·0회라 여기까지 오지 않는다. 임계값이 바뀌어도
        // 등급 없는 사용자를 만들지 않기 위해 최저 등급으로 닫는다.
        return LV1;
    }

    /**
     * 이 등급의 승급 조건을 충족하는가 (R-15).
     *
     * <p><b>AND 다.</b> 둘 중 하나만 채운 상태는 충족이 아니다 —
     * 이슈 #25 의 완료 판정이 200P·19회와 199P·20회 두 경우를 모두 요구한다.
     */
    public boolean isSatisfiedBy(long point, long voteCount) {
        return point >= requiredPoint && voteCount >= requiredVoteCount;
    }

    /** 다음 등급. 최고 등급이면 자기 자신이 아니라 빈 값이다 — "다음이 없다" 를 값으로 말한다. */
    public java.util.Optional<Grade> next() {
        int index = ordinal() + 1;
        return index < ORDERED.size()
                ? java.util.Optional.of(ORDERED.get(index))
                : java.util.Optional.empty();
    }

    /** 둘 중 높은 등급. 도달 등급이 내려가지 않게 하는 데 쓴다 (R-16). */
    public Grade higherOf(Grade other) {
        if (other == null) {
            return this;
        }
        return level >= other.level ? this : other;
    }

    public int level() {
        return level;
    }

    public String displayName() {
        return displayName;
    }

    public long requiredPoint() {
        return requiredPoint;
    }

    public long requiredVoteCount() {
        return requiredVoteCount;
    }
}
