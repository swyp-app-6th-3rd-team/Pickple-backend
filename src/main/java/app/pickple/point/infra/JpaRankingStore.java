package app.pickple.point.infra;

import app.pickple.point.domain.RankingStore;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 랭킹을 SQL 로 다시 매긴다 (ADR-0028).
 *
 * <p><b>왜 네이티브 SQL 인가</b> — 순위를 매기는 것은 윈도 함수이고 JPQL 에 없다.
 * 20만 행을 애플리케이션으로 끌어올려 정렬한 뒤 다시 쓰는 대안은 왕복 비용과
 * 메모리를 함께 치른다. {@code users.ranking} 은 매핑하지 않는다 —
 * {@code UserEntity} 가 이 컬럼을 들고 있으면 프로필 저장 같은 평범한 쓰기가
 * 배치가 계산한 값을 덮어쓸 수 있다. 유도 컬럼은 유도하는 쪽만 만진다.
 */
@Component
@RequiredArgsConstructor
public class JpaRankingStore implements RankingStore {

    /**
     * 원장 합계를 캐시 컬럼으로 옮긴다.
     *
     * <p>{@code LEFT JOIN} 이라 이력이 없는 회원도 대상이다. 포인트를 한 번도 받지 못한
     * 회원은 합계가 없으므로 {@code COALESCE} 로 0 이 되고, 이미 0 이면 아래 조건에서 걸러진다.
     *
     * <p>{@code point} 는 {@code INT UNSIGNED} 라 원장 합계가 음수면 저장이 실패한다.
     * 현재 지급 사유는 적립뿐이라(PICKED +10 · PICKING +5) 음수가 나올 경로가 없지만,
     * 회수가 생기면 {@code GREATEST(..., 0)} 가 컬럼 제약과 원장 사이의 완충이 된다.
     */
    private static final String SYNC_POINTS = """
            UPDATE users u
              LEFT JOIN (SELECT ph.user_id, SUM(ph.amount) AS total
                           FROM point_history ph
                          GROUP BY ph.user_id) l ON l.user_id = u.id
               SET u.point = GREATEST(COALESCE(l.total, 0), 0)
             WHERE u.point <> GREATEST(COALESCE(l.total, 0), 0)
            """;

    /**
     * 투표 정본에서 누적 투표 횟수를 캐시 컬럼으로 옮긴다 (ADR-0032).
     *
     * <p>{@code SYNC_POINTS} 와 같은 형태다 — 다른 것은 정본이 원장이 아니라
     * {@code vote} 테이블이고, 집계가 {@code SUM} 이 아니라 {@code COUNT} 라는 점뿐이다.
     *
     * <p>{@code COUNT(*)} 로 충분한 근거는 {@code uk_vote_post_user (post_id, user_id)} 다.
     * 재투표는 선택지만 바꾸는 UPDATE 라 (R-22) 한 게시글당 한 행을 넘지 않는다.
     * 곧 행 수가 곧 투표 횟수다 — 선택을 여러 번 바꿔도 값이 부풀지 않는다.
     *
     * <p>{@code idx_vote_user_created (user_id, created_at)} 가 있어
     * {@code GROUP BY user_id} 가 인덱스 순서를 그대로 쓴다.
     *
     * <p>{@code LEFT JOIN} 이라 한 번도 투표하지 않은 회원도 대상이다 —
     * 합계가 없으면 {@code COALESCE} 로 0 이 되고, 이미 0 이면 아래 조건에서 걸러진다.
     * {@code SYNC_POINTS} 와 달리 {@code GREATEST} 를 쓰지 않는다:
     * {@code COUNT(*)} 는 음수가 될 수 없어 {@code INT UNSIGNED} 와 충돌할 경로가 없다.
     */
    private static final String SYNC_VOTE_COUNTS = """
            UPDATE users u
              LEFT JOIN (SELECT v.user_id, COUNT(*) AS total
                           FROM vote v
                          GROUP BY v.user_id) t ON t.user_id = u.id
               SET u.vote_count = COALESCE(t.total, 0)
             WHERE u.vote_count <> COALESCE(t.total, 0)
            """;

    /**
     * 활성 회원에게 순위를 매긴다.
     *
     * <p><b>{@code ROW_NUMBER} 이지 {@code RANK} 가 아니다.</b> 용어사전은 동점에서
     * "가입이 빠른 쪽이 <b>앞선다</b>" 고 쓴다 — 공동 순위가 아니라 전순서다.
     * {@code id} 를 마지막 정렬키로 덧붙여, 같은 초에 가입한 동점자가 생겨도
     * 실행마다 순위가 뒤바뀌지 않게 한다.
     *
     * <p><b>변경된 행만 쓴다.</b> {@code ranking <> rk} 조건이 없으면 아무 변동이 없어도
     * 주기마다 회원 전체를 다시 써서 redo·undo·binlog 를 만든다. 그 부담은
     * 로그인 경로가 함께 쓰는 {@code users} 테이블에 얹힌다.
     * 실측(200k): 무조건 갱신 550ms/200,000행 → 변동 없을 때 233ms/0행.
     */
    private static final String RECALCULATE = """
            UPDATE users u
              JOIN (SELECT id,
                           ROW_NUMBER() OVER (ORDER BY point DESC, created_at ASC, id ASC) AS rk
                      FROM users
                     WHERE state = 'ACTIVE') r ON r.id = u.id
               SET u.ranking = r.rk
             WHERE u.ranking IS NULL OR u.ranking <> r.rk
            """;

    /** 탈퇴 회원은 순위를 갖지 않는다. 남겨두면 목록에서 옛 순위가 계속 나간다. */
    private static final String CLEAR_INACTIVE = """
            UPDATE users SET ranking = NULL
             WHERE state <> 'ACTIVE' AND ranking IS NOT NULL
            """;

    private final EntityManager entityManager;

    @Override
    @Transactional
    public int syncPointsFromLedger() {
        return entityManager.createNativeQuery(SYNC_POINTS).executeUpdate();
    }

    @Override
    @Transactional
    public int syncVoteCountsFromVotes() {
        return entityManager.createNativeQuery(SYNC_VOTE_COUNTS).executeUpdate();
    }

    @Override
    @Transactional
    public int recalculateRankings() {
        entityManager.createNativeQuery(CLEAR_INACTIVE).executeUpdate();
        return entityManager.createNativeQuery(RECALCULATE).executeUpdate();
    }
}
