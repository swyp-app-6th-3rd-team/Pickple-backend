package app.pickple.point.domain;

/**
 * 랭킹 사전 계산 저장소 (ADR-0028).
 *
 * <p><b>왜 저장소가 계산하는가</b> — 순위는 "앞에 몇 명이 있는가" 라 전역 집계다.
 * 20만 명을 애플리케이션으로 끌어올려 정렬하면 그 자체가 비용이고, DB 는 이미
 * {@code idx_users_ranking} 으로 그 순서를 알고 있다. 필터를 소스에서 거는 것과 같은 이유로
 * 집계도 소스에서 한다 — 여기서 도메인이 정하는 것은 <b>무엇을 계산하는가</b>(포인트 순,
 * 동점이면 가입이 빠른 쪽)이고, 그 순서를 만드는 방법은 인프라의 몫이다.
 *
 * <p>두 단계인 이유는 포인트의 정본이 원장이기 때문이다 (R-14).
 * {@code users.point} 는 {@code point_history} 에서 유도한 캐시라
 * 순위를 매기기 전에 원장과 맞춰야 한다.
 */
public interface RankingStore {

    /**
     * 포인트 원장 합계를 {@code users.point} 에 반영한다.
     *
     * <p>{@code point_history} 가 정본이고 {@code users.point} 는 그 캐시다.
     * 지급 경로({@code OnePickService})는 원장에만 쓰므로 이 단계 없이 순위를 매기면
     * 아무도 채우지 않는 컬럼을 정렬하게 된다.
     *
     * @return 합계가 실제로 달라져 갱신된 회원 수
     */
    int syncPointsFromLedger();

    /**
     * 활성 회원의 순위를 다시 매긴다.
     *
     * <p>포인트 내림차순, 동점이면 가입이 빠른 쪽이 앞선다(용어사전 "랭킹").
     * 동점을 공동 순위로 묶지 않고 전순서를 만든다 — "앞선다" 는 공동 1위가 아니다.
     *
     * <p>탈퇴 회원은 순위에서 빠지고 {@code ranking} 이 비워진다.
     *
     * @return 순위가 실제로 달라져 갱신된 회원 수. 변동이 없으면 0
     */
    int recalculateRankings();
}
