package app.pickple.activity.infra;

import app.pickple.activity.domain.ActivitySort;
import app.pickple.activity.domain.ActivityType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 활동 목록을 <b>SQL 한 번</b>으로 읽는다.
 *
 * <p><b>먼저 자르고 나중에 붙인다.</b> 조각에 필요한 행을 인덱스로 먼저 확정한 뒤
 * ({@code ORDER BY … LIMIT}), 그 몇 줄에만 대표 사진을 붙인다. 순서를 뒤집어
 * 조인부터 하면 MySQL 이 <b>정렬 전에 조인 결과 전체를 만들어야 해서</b> 인덱스가
 * 무의미해진다 — {@code PostListRepository} 가 실측으로 확인한 것과 같은 함정이다
 * (100k 게시글 · 200k 회원: 454ms → 0.28ms).
 *
 * <p><b>여기서는 그 함정이 한 겹 더 있다.</b> 활동 목록은 게시글을 활동으로 좁히므로
 * 조인이 하나 더 필요하다. 안쪽 질의가 <b>활동 테이블에서 시작</b>해야
 * {@code (user_id, created_at)} 인덱스가 정렬을 맡는다. {@code post} 에서 시작해
 * {@code EXISTS} 로 좁히면 옵티마이저가 게시글 전체를 훑는다 —
 * 내 활동은 전체 게시글의 극히 일부라 방향이 결정적이다.
 *
 * <p><b>왜 네이티브 SQL 인가</b> ({@code PostListRepository} 와 같은 이유)
 * <ul>
 *   <li>{@code post.popularity_score} 는 MySQL 생성 컬럼이라 {@code PostEntity} 에
 *       매핑하지 않는다 — 매핑하면 하이버네이트가 쓰기를 시도해 {@code ERROR 3105} 가 난다.
 *       매핑이 없으니 JPQL 로는 이 컬럼을 정렬에 쓸 수 없다.</li>
 *   <li>keyset 조건을 <b>행 값 비교</b> {@code (a, b) < (?, ?)} 로 써야 동률에서
 *       행이 새지 않는다. JPQL 은 튜플 비교를 지원하지 않는다.</li>
 * </ul>
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.activity.domain.ActivityQueryStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class ActivityListRepository {

    /**
     * 대표 사진 1장 (§9.2) — {@code PostListRepository} 와 같은 정의다.
     * 찬반은 상품이 하나뿐이고 A/B 는 A 상품이라 둘 다 {@code display_order = 1} 이다.
     *
     * <p>스칼라 서브쿼리인 이유는 찬반 상품이 사진을 최대 3장 갖기 때문이다(R-03).
     * 그냥 조인하면 게시글 한 줄이 사진 수만큼 불어나 조각 크기가 어긋난다.
     */
    private static final String THUMBNAIL = """
            (SELECT ir.access_url
               FROM post_product pp
               JOIN item_resource ir ON ir.item_container_id = pp.item_container_id
              WHERE pp.post_id = page.id
                AND pp.display_order = 1
              ORDER BY ir.id ASC
              LIMIT 1)
            """;

    /** 바깥 질의. 이미 잘라낸 몇 줄에만 대표 사진을 붙인다. */
    private static final String OUTER = """
            SELECT page.id, page.type, page.category, page.title, page.description,
                   page.vote_count, page.comment_count, page.created_at,
                   %s AS thumbnail_url,
                   page.activity_at, page.popularity_score
              FROM (%s) page
             ORDER BY %s
            """;

    private final EntityManager entityManager;

    /**
     * 조각을 읽는다. 다음 조각의 존재를 알기 위해 <b>{@code size + 1} 건</b>을 읽고
     * 넘치는 한 건은 버린다 — 별도 count 쿼리를 내지 않기 위해서다.
     */
    List<Object[]> findSlice(
            Long userId, ActivityType type, ActivitySort sort, ActivityListCursor cursor, int size) {

        StringBuilder page = new StringBuilder(inner(type));
        if (cursor != null) {
            // 행 값 비교. (정렬키, id) 를 한 튜플로 놓아야 정렬키 동률에서 행이 새지 않는다.
            page.append(" AND (").append(sortColumn(type, sort))
                    .append(", ").append(idColumn(type)).append(") ")
                    .append(sort.ascending() ? ">" : "<")
                    .append(" (:sortValue, :cursorId)");
        }
        page.append(" ORDER BY ").append(orderBy(sortColumn(type, sort), idColumn(type), sort))
                .append(" LIMIT :limit");

        String sql = OUTER.formatted(THUMBNAIL, page, orderBy(pageSortColumn(sort), "page.id", sort));

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("userId", userId);
        if (cursor != null) {
            query.setParameter("sortValue", cursor.sortValue());
            query.setParameter("cursorId", cursor.id());
        }
        query.setParameter("limit", size + 1);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /**
     * 최근에 올린 투표 게시글 (§7.4). 커서가 없는 고정 개수 목록이다.
     *
     * <p>경계는 <b>반열린 구간</b> {@code created_at > since} 이다. 기준 시각을
     * 서비스가 정해 넘기므로 이 자리는 비교만 한다 — {@code NOW()} 를 쓰면
     * DB 세션 타임존이 하루를 정해 애플리케이션이 보는 시각과 갈린다(SPEC §5.1 과 같은 이유).
     */
    List<Object[]> findRecentVotePosts(Long userId, LocalDateTime since, int limit) {
        String page = """
                SELECT p.id, p.type, p.category, p.title, p.description,
                       p.vote_count, p.comment_count, p.created_at,
                       p.created_at AS activity_at, p.popularity_score
                  FROM post p
                 WHERE p.user_id = :userId
                   AND p.deleted_at IS NULL
                   AND p.type <> 'GENERAL'
                   AND p.created_at > :since
                 ORDER BY p.created_at DESC, p.id DESC
                 LIMIT :limit
                """;

        Query query = entityManager.createNativeQuery(
                OUTER.formatted(THUMBNAIL, page, "page.created_at DESC, page.id DESC"));
        query.setParameter("userId", userId);
        query.setParameter("since", since);
        query.setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /**
     * 활동 갯수 요약 (§7.2). 세 값을 <b>한 문장</b>으로 읽는다.
     *
     * <p>세 번 나눠 물으면 왕복이 셋이 되는데, 세 값은 언제나 함께 쓰이고
     * 각각이 인덱스 한 범위를 세는 가벼운 질의라 합치는 편이 낫다.
     *
     * <p><b>세 값 모두 {@code DISTINCT} 가 없다.</b> 스키마가 이미 인원으로 세고 있다 —
     * {@code vote} 는 {@code UNIQUE(post_id, user_id)} 라 재투표가 UPDATE 이고(R-22),
     * {@code post_commenter} 는 {@code UNIQUE(post_id, user_id)} 라 게시글당 한 행이다(R-25).
     * 여기서 다시 세면 정본이 둘이 되어 어긋날 자리를 만든다.
     */
    Object[] summarize(Long userId) {
        String sql = """
                SELECT (SELECT COUNT(*) FROM vote v WHERE v.user_id = :userId),
                       (SELECT COUNT(*) FROM post_commenter pc WHERE pc.user_id = :userId),
                       (SELECT COUNT(*) FROM post p
                         WHERE p.user_id = :userId AND p.deleted_at IS NULL)
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("userId", userId);
        return (Object[]) query.getSingleResult();
    }

    /**
     * 조각을 확정하는 안쪽 질의. <b>활동 테이블에서 시작</b>한다.
     *
     * <p>세 유형이 같은 모양을 낸다 — {@code post} 의 컬럼들 + {@code activity_at}.
     * {@code POST} 만 활동 테이블이 게시글 자신이라 조인이 없고,
     * 그래서 {@code activity_at} 이 게시글 작성 시각과 같다.
     *
     * <p><b>삭제된 게시글은 빼낸다.</b> 내가 투표한 글을 작성자가 지웠다면
     * 그 카드는 탭했을 때 갈 곳이 없다 — 목록에서 사라지는 것이 맞다.
     */
    private static String inner(ActivityType type) {
        String columns = """
                SELECT p.id, p.type, p.category, p.title, p.description,
                       p.vote_count, p.comment_count, p.created_at,
                       %s AS activity_at, p.popularity_score
                """;

        return switch (type) {
            case VOTE -> columns.formatted("v.created_at") + """
                      FROM vote v
                      JOIN post p ON p.id = v.post_id AND p.deleted_at IS NULL
                     WHERE v.user_id = :userId
                    """;
            case COMMENT -> columns.formatted("pc.created_at") + """
                      FROM post_commenter pc
                      JOIN post p ON p.id = pc.post_id AND p.deleted_at IS NULL
                     WHERE pc.user_id = :userId
                    """;
            case POST -> columns.formatted("p.created_at") + """
                      FROM post p
                     WHERE p.user_id = :userId
                       AND p.deleted_at IS NULL
                    """;
        };
    }

    /**
     * 정렬 컬럼. 클라이언트 입력이 여기 닿지 않는다 — {@code ActivitySort.from} 과
     * {@code ActivityType.from} 이 허용 목록으로 걸러낸 enum 만 들어오므로
     * SQL 주입 경로가 없다.
     *
     * <p>{@code POST} 의 활동 시각이 {@code p.created_at} 인 것은 우연이 아니다 —
     * 내가 올린 글은 활동이 곧 작성이다.
     */
    private static String sortColumn(ActivityType type, ActivitySort sort) {
        if (!sort.byActivityTime()) {
            return "p.popularity_score";
        }
        return switch (type) {
            case VOTE -> "v.created_at";
            case COMMENT -> "pc.created_at";
            case POST -> "p.created_at";
        };
    }

    /**
     * 정렬 튜플의 두 번째 자리. <b>게시글 id 를 활동 테이블 쪽에서 읽는다.</b>
     *
     * <p>값은 {@code p.id} 와 같지만(조인 조건이 {@code p.id = v.post_id})
     * <b>어느 테이블에서 읽느냐가 실행계획을 가른다.</b> {@code p.id} 로 쓰면
     * 정렬 키 둘이 서로 다른 테이블에 있어 인덱스 하나로 정렬이 완결되지 않고,
     * MySQL 이 내 활동 전체를 읽어 filesort 로 정렬한다 — 활동 500건 실측 4.29ms.
     * 활동 테이블 쪽으로 맞추면 {@code idx_vote_user_activity} 가 정렬을 통째로
     * 맡아 11행만 읽는다(0.070ms). 활동 5,000건에서도 읽는 행은 11이다.
     *
     * <p>이 한 글자가 Θ(내 활동 수) 와 Θ(조각 크기) 를 가른다.
     */
    private static String idColumn(ActivityType type) {
        return switch (type) {
            case VOTE -> "v.post_id";
            case COMMENT -> "pc.post_id";
            case POST -> "p.id";
        };
    }

    /**
     * 바깥 질의는 안쪽이 이미 이름 붙인 별칭을 쓴다.
     *
     * <p>바깥에서 다시 정렬하는 이유는 <b>대표 사진 서브쿼리가 순서를 보장하지 않기
     * 때문</b>이다. 안쪽이 이미 {@code LIMIT} 으로 줄을 확정했으므로 이 정렬은
     * 조각 크기(최대 50)에만 도는 비용이고, 안쪽 인덱스 정렬을 대신하지 않는다.
     *
     * <p>{@code page.id} 는 안쪽의 {@code p.id} 다 — 안쪽 정렬이 활동 테이블의
     * {@code post_id} 를 쓰지만 <b>조인 조건이 두 값을 같게 만들므로</b> 순서가 같다.
     */
    private static String pageSortColumn(ActivitySort sort) {
        return sort.byActivityTime() ? "page.activity_at" : "page.popularity_score";
    }

    private static String orderBy(String sortColumn, String idColumn, ActivitySort sort) {
        String direction = sort.ascending() ? "ASC" : "DESC";
        return sortColumn + " " + direction + ", " + idColumn + " " + direction;
    }

    /** 조회 결과 한 행의 컬럼 위치. 순서가 곧 계약이라 상수로 고정한다. */
    static final class Column {
        static final int ID = 0;
        static final int TYPE = 1;
        static final int CATEGORY = 2;
        static final int TITLE = 3;
        static final int DESCRIPTION = 4;
        static final int VOTE_COUNT = 5;
        static final int COMMENT_COUNT = 6;
        static final int CREATED_AT = 7;
        static final int THUMBNAIL_URL = 8;
        static final int ACTIVITY_AT = 9;
        static final int POPULARITY_SCORE = 10;

        private Column() {
        }
    }
}
