package app.pickple.point.infra;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 피커 랭킹 목록을 읽는다 (§2.5 · §3.1 · §7.3).
 *
 * <p><b>정렬 키는 {@code users.ranking} 이다</b> — 배치가 이미 매겨둔 값이라
 * 조회는 그 순서를 <b>읽기만</b> 한다. 정렬을 {@code (point, created_at)} 으로 다시
 * 표현할 수도 있지만 그러면 안 된다는 것을 실측으로 확인했다(아래).
 *
 * <p><b>왜 커서가 {@code ranking} 한 컬럼인가</b> — {@code ROW_NUMBER()} 가 만든 값이라
 * 활성 회원 사이에 중복이 없다(200k 시드에서 {@code COUNT(DISTINCT ranking) = 200000}).
 * 동률이 없으므로 조각 경계에서 행이 새지 않아 {@code PostListCursor} 처럼
 * 튜플로 묶을 필요가 없고, 조건이 {@code ranking > ?} 하나로 끝난다.
 *
 * <p><b>실측 (회원 200,000 · MySQL 8.4 · 깊은 조각 {@code ranking > 150000})</b>
 *
 * <pre>
 *   ranking 커서 · 인덱스 없음      33 ms    Table scan 200,000행 + Sort
 *   (point,created_at,id) 튜플 커서 114 ms   idx_users_ranking 을 타지만 149,456행을 훑는다
 *   ranking 커서 · idx_users_ranking_order  0.060 ms  Index range scan (150000 &lt; ranking) 11행
 * </pre>
 *
 * 튜플 커서가 <b>인덱스를 타면서도 더 느린</b> 것이 핵심이다. {@code idx_users_ranking} 은
 * {@code (point DESC, created_at)} 이라 튜플의 세 번째 키 {@code id} 가 인덱스에 없고,
 * 그래서 행 값 비교가 범위 <b>시작점</b>으로 접히지 못한다 — 인덱스를 위에서부터 훑으며
 * 거른다. 조각이 깊을수록 훑는 양이 늘어 OFFSET 과 같은 성질이 되므로,
 * 무한 스크롤이 피하려던 바로 그 형태다.
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.point.domain.RankingQueryStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class RankingListRepository {

    /**
     * 조회 데이터는 세 화면이 같다 — 순위/프로필 사진/닉네임/등급명칭/포인트.
     *
     * <p>등급명칭은 컬럼이 아니라 {@code point}·{@code vote_count} 로 판정하므로
     * (ADR-0032) 두 값을 함께 읽는다. 이미 읽는 행의 컬럼이라 비용이 붙지 않는다.
     *
     * <p>닉네임이 비어 있을 수 있다 — 가입 직후 프로필 등록 전이다.
     * 목록 화면이 빈 칸을 그리지 않도록 {@code PostListRepository} 와 같은 폴백을 쓴다.
     */
    private static final String COLUMNS = """
            u.id,
            COALESCE(NULLIF(u.nickname, ''), NULLIF(u.name, ''), '알 수 없음') AS nickname,
            u.profile_image_url,
            u.ranking,
            u.point,
            u.vote_count
            """;

    /**
     * 순위가 매겨진 활성 회원만 목록에 오른다.
     *
     * <p>{@code ranking IS NOT NULL} 이 곧 활성 조건이기도 하다 — 배치가 탈퇴자의
     * 순위를 {@code NULL} 로 되돌리기 때문이다({@code CLEAR_INACTIVE}).
     * 그럼에도 {@code state} 를 함께 걸지 않는 이유는, 두 조건이 같은 사실을 말하는데
     * {@code state} 를 더하면 인덱스 범위 스캔에 필터가 하나 붙기 때문이다.
     *
     * <p>대신 <b>탈퇴와 배치 사이의 창</b>이 남는다 — 탈퇴 직후 다음 배치 전까지
     * 옛 순위가 목록에 보인다. 이것은 ADR-0028 이 이미 받아들인 지연(최대 5분)과
     * 같은 성질이라 새 계약을 만들지 않는다.
     */
    private static final String RANKED = " FROM users u WHERE u.ranking IS NOT NULL";

    private final EntityManager entityManager;

    /**
     * 상위 피커 (§2.5). 커서가 없는 첫 조각과 형태가 같지만 의미가 달라 따로 둔다 —
     * 이쪽은 "5명만 노출" 이라 다음 조각이라는 개념이 없다.
     */
    List<Object[]> findTop(int size) {
        return execute(query(null), null, size);
    }

    /**
     * 전체 랭킹 한 조각 (§3.1).
     *
     * <p>다음 조각의 존재를 알기 위해 <b>{@code size + 1} 건</b>을 읽고 넘치는 한 건은
     * 버린다 — 별도 count 쿼리를 내지 않기 위해서다.
     */
    List<Object[]> findSlice(RankingCursor cursor, int size) {
        return execute(query(cursor), cursor, size + 1);
    }

    /**
     * 본인 랭킹 (§7.3).
     *
     * <p>목록과 달리 {@code ranking IS NOT NULL} 을 걸지 않는다. 순위가 아직 없어도
     * 포인트와 등급은 존재하므로, 행을 주고 순위 자리만 비운다.
     *
     * <p>대신 {@code state} 를 직접 본다 — 목록은 {@code ranking} 이 활성 여부를
     * 대신 말해주지만 여기서는 그 조건이 없기 때문이다.
     */
    List<Object[]> findByUser(Long userId) {
        Query query = entityManager.createNativeQuery(
                "SELECT " + COLUMNS + " FROM users u WHERE u.id = :userId AND u.state = 'ACTIVE'");
        query.setParameter("userId", userId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /**
     * 순위 오름차순. 1위가 앞이다.
     *
     * <p>{@code ranking} 인덱스가 이 정렬을 그대로 제공하므로 정렬 연산이 사라진다
     * (실행계획의 {@code Sort} 가 없다). 커서 조건도 같은 인덱스의 범위 시작점이 된다.
     */
    private static String query(RankingCursor cursor) {
        return "SELECT " + COLUMNS + RANKED
                + (cursor == null ? "" : " AND u.ranking > :cursor")
                + " ORDER BY u.ranking ASC LIMIT :limit";
    }

    private List<Object[]> execute(String sql, RankingCursor cursor, int limit) {
        Query query = entityManager.createNativeQuery(sql);
        if (cursor != null) {
            query.setParameter("cursor", cursor.ranking());
        }
        query.setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /** 조회 결과 한 행의 컬럼 위치. 순서가 곧 계약이라 상수로 고정한다. */
    static final class Column {
        static final int ID = 0;
        static final int NICKNAME = 1;
        static final int PROFILE_IMAGE_URL = 2;
        static final int RANKING = 3;
        static final int POINT = 4;
        static final int VOTE_COUNT = 5;

        private Column() {
        }
    }
}
