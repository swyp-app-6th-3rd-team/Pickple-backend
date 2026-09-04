package app.pickple.grade.domain;

/**
 * 승급 판정의 입력값과 도달 등급 (ADR-0030).
 *
 * <p><b>캐시가 아니라 원장에서 읽는다.</b> {@code users.point} 는 랭킹 배치가 5분마다
 * 채우는 캐시라 실시간 게이지(기능명세 §7.3)를 만족하지 못하고,
 * {@code users.vote_count} 는 <b>아무도 채우지 않아</b> 읽으면 전원 0 이다
 * (착수 전 전수 확인). 정본은 {@code point_history} 와 {@code vote} 다 (R-14).
 *
 * <p>랭킹이 배치인 이유(전역 집계)는 여기에 적용되지 않는다 —
 * 등급의 입력값은 {@code user_id} 하나로 좁혀지는 로컬 집계다.
 */
public interface GradeStore {

    /**
     * 승급 판정의 두 입력값을 한 번에 읽는다.
     *
     * <p>둘을 따로 조회하지 않는 이유는 <b>같은 시점의 값이어야</b> 하기 때문이다 (R-15).
     * AND 판정이라 포인트는 지급 후 값인데 투표 횟수는 지급 전 값이면
     * 실제로 존재한 적 없는 조합으로 등급이 정해진다.
     *
     * @return 사용자가 없어도 0·0 이다 — 조회는 판정하지 않는다 (ADR-0019)
     */
    GradeInputs readInputs(Long userId);

    /**
     * 저장된 도달 최고 등급 (R-16).
     *
     * @return 저장된 등급. 가입 시 LV.1 이 기본이라 비어 있지 않다
     */
    Grade readHighestGrade(Long userId);

    /**
     * 도달 등급을 올린다. <b>내리지 않는다</b> (R-16).
     *
     * <p>조건부 갱신이라 낮은 값이 높은 값을 덮지 못한다 — 동시 요청이 겹쳐도
     * 마지막에 쓴 쪽이 이기는 것이 아니라 <b>높은 쪽</b>이 남는다.
     * 응용 계층에서 읽고 비교해 쓰면 그 사이에 끼어든 승급을 되돌린다.
     *
     * @return 실제로 올랐으면 true. 이미 그 등급 이상이면 false
     */
    boolean raiseHighestGrade(Long userId, Grade grade);

    /**
     * 승급 판정의 입력값.
     *
     * @param point     누적 포인트. {@code point_history} 합계다 (R-14)
     * @param voteCount 누적 투표 횟수. {@code vote} 행 수이고 재투표는 행을 늘리지 않는다 (R-22)
     */
    record GradeInputs(long point, long voteCount) {

        public GradeInputs {
            if (point < 0 || voteCount < 0) {
                throw new IllegalArgumentException(
                        "누적값은 음수일 수 없습니다: point=%d, voteCount=%d".formatted(point, voteCount));
            }
        }

        /** 이 입력값으로 도달 가능한 등급 (R-15). */
        public Grade reachedGrade() {
            return Grade.reachedBy(point, voteCount);
        }
    }
}
