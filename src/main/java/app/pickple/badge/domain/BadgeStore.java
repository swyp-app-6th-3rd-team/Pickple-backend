package app.pickple.badge.domain;

import java.util.List;

/**
 * 뱃지 정의 조회 (정책 요약표 §3).
 *
 * <p>8행뿐이고 운영 중 늘어나지 않지만 테이블에서 읽는다 — 표시명이 "추후 수정" 을
 * 예고한 값이라 코드가 아니라 데이터로 두기 위해서다. 자세한 근거는 {@link Badge}.
 */
public interface BadgeStore {

    /** 전체 뱃지를 노출 순서대로. 3X3 목록(기능명세 §12.2)이 이 순서를 그대로 쓴다. */
    List<Badge> findAllOrdered();
}
