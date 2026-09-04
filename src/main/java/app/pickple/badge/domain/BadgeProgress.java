package app.pickple.badge.domain;

/**
 * 미해제 미션 하나의 진행 상황 (기능명세 §2.3).
 *
 * <p>명세의 조회 데이터가 {@code 누적 투표 10회 달성 (0/10)} 형태라
 * <b>현재값과 목표값 두 수를 모두</b> 들고 다닌다. 퍼센트 하나로 줄이면
 * 클라이언트가 "0/1000" 을 렌더할 수 없다 — 명세가 요구하는 표기다.
 *
 * @param badge   대상 뱃지. 문구({@code description})와 표시명이 여기 있다
 * @param current 현재값. 목표를 넘어도 목표에서 자른다
 * @param goal    목표값. 뱃지의 임계값과 같다
 */
public record BadgeProgress(Badge badge, long current, long goal) {

    public BadgeProgress {
        if (badge == null) {
            throw new IllegalArgumentException("진행률의 대상 뱃지는 필수입니다.");
        }
        if (goal <= 0) {
            throw new IllegalArgumentException("목표값은 1 이상이어야 합니다: " + goal);
        }
        if (current < 0 || current > goal) {
            throw new IllegalArgumentException(
                    "현재값은 0 이상 목표 이하여야 합니다: current=%d, goal=%d".formatted(current, goal));
        }
    }
}
