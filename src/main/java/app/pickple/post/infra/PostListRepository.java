package app.pickple.post.infra;

import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 게시글 목록을 <b>SQL 한 번</b>으로 읽는다.
 *
 * <p>목록 한 줄에 필요한 것은 게시글 본문 외에 셋이다 — 작성자 닉네임, 작성자 랭킹,
 * 대표 상품 사진. 이 셋을 따로 조회하면 10건짜리 조각 하나에 31번의 쿼리가 나간다.
 * 전부 조인으로 접어 <b>조각 크기와 무관하게 1회</b>로 만든다.
 *
 * <p><b>왜 네이티브 SQL 인가</b> (JPQL·QueryDSL 이 아니라)
 * <ul>
 *   <li>{@code post.popularity_score} 는 MySQL 생성 컬럼이라 {@code PostEntity} 에
 *       매핑하지 않는다 — 매핑하면 하이버네이트가 쓰기를 시도해 {@code ERROR 3105} 가 난다.
 *       매핑이 없으니 JPQL 로는 이 컬럼을 정렬에 쓸 수 없다.</li>
 *   <li>작성자 랭킹은 윈도 함수({@code RANK() OVER}) 다. JPQL 에 대응물이 없다.</li>
 *   <li>keyset 조건을 <b>행 값 비교</b> {@code (a, b) < (?, ?)} 로 써야 동률에서
 *       행이 새지 않는다. JPQL 은 튜플 비교를 지원하지 않아
 *       {@code a < ? OR (a = ? AND b < ?)} 로 풀어써야 하고, 그러면 옵티마이저가
 *       인덱스 범위 스캔으로 접지 못한다.</li>
 * </ul>
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.post.domain.PostQueryStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class PostListRepository {

    /**
     * 작성자 랭킹 — 포인트 내림차순, 동점이면 가입이 빠른 쪽 (용어사전 · {@code idx_users_ranking}).
     *
     * <p>순위는 <b>전역</b> 값이라 목록에 실린 사람만으로는 계산할 수 없다.
     * 그래서 users 전체에 한 번 순위를 매긴 뒤 조인한다 — 목록 행마다 세는 상관 서브쿼리는
     * 조각당 O(N·M) 이 되므로 쓰지 않는다.
     *
     * <p>{@code RANK} 를 쓰므로 동점자는 같은 순위를 갖고 다음 순위는 건너뛴다
     * (1, 1, 3). 동점 자체는 {@code created_at} 이 갈라주므로 드물다.
     */
    private static final String AUTHOR_RANKING = """
            SELECT u.id AS user_id,
                   RANK() OVER (ORDER BY u.point DESC, u.created_at ASC) AS ranking
              FROM users u
             WHERE u.deleted_at IS NULL
            """;

    /**
     * 대표 사진 1장 (§4.2) — 찬반은 상품이 하나뿐이고, A/B 는 A 상품(display_order = 1)이다.
     * 두 경우 모두 {@code display_order = 1} 로 잡히므로 유형 분기가 필요 없다.
     *
     * <p>사진 URL 은 {@code item_container.access_urls} 에서 첫 항목을 꺼낸다.
     * {@code item_resource} 를 조인하면 상품 하나가 사진 3장을 가질 때(찬반, R-03)
     * 게시글 행이 3배로 불어나 GROUP BY 가 필요해진다. 컨테이너에 이미 비정규화돼 있으므로
     * 조인 하나를 아낀다.
     */
    private static final String SELECT_LIST = """
            SELECT p.id                AS id,
                   p.type              AS type,
                   p.category          AS category,
                   p.title             AS title,
                   p.description       AS description,
                   p.vote_count        AS voteCount,
                   p.comment_count     AS commentCount,
                   p.created_at        AS createdAt,
                   p.popularity_score  AS popularityScore,
                   SUBSTRING_INDEX(c.access_urls, ',', 1) AS thumbnailUrl,
                   p.user_id           AS authorId,
                   COALESCE(NULLIF(u.nickname, ''), NULLIF(u.name, ''), '알 수 없음') AS authorNickname,
                   COALESCE(r.ranking, 0) AS authorRanking
              FROM post p
              JOIN users u ON u.id = p.user_id
              LEFT JOIN (%s) r ON r.user_id = p.user_id
              LEFT JOIN post_product pp ON pp.post_id = p.id AND pp.display_order = 1
              LEFT JOIN item_container c ON c.id = pp.item_container_id
             WHERE p.deleted_at IS NULL
            """.formatted(AUTHOR_RANKING);

    private final EntityManager entityManager;

    /**
     * 조각을 읽는다. 다음 조각의 존재를 알기 위해 <b>{@code size + 1} 건</b>을 읽고
     * 넘치는 한 건은 버린다 — 별도 count 쿼리를 내지 않기 위해서다.
     */
    List<Object[]> findSlice(PostCategory category, PostSort sort, PostListCursor cursor, int size) {
        StringBuilder sql = new StringBuilder(SELECT_LIST);
        if (category != null) {
            sql.append(" AND p.category = :category");
        }
        if (cursor != null) {
            // 행 값 비교. (정렬키, id) 를 한 튜플로 놓아야 정렬키 동률에서 행이 새지 않는다.
            sql.append(" AND (").append(sortColumn(sort)).append(", p.id) < (:sortValue, :cursorId)");
        }
        sql.append(" ORDER BY ").append(sortColumn(sort)).append(" DESC, p.id DESC LIMIT :limit");

        Query query = entityManager.createNativeQuery(sql.toString());
        if (category != null) {
            query.setParameter("category", category.name());
        }
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
     * 정렬 컬럼은 {@link PostSort} 가 정한 값만 문자열로 이어붙인다.
     * 클라이언트 입력이 여기 닿지 않는다 — {@code PostSort.from(...)} 이 허용 목록으로
     * 걸러낸 enum 만 들어오므로 SQL 주입 경로가 없다.
     */
    private static String sortColumn(PostSort sort) {
        return switch (sort) {
            case LATEST -> "p.created_at";
            case POPULAR -> "p.popularity_score";
        };
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
        static final int POPULARITY_SCORE = 8;
        static final int THUMBNAIL_URL = 9;
        static final int AUTHOR_ID = 10;
        static final int AUTHOR_NICKNAME = 11;
        static final int AUTHOR_RANKING = 12;

        private Column() {
        }
    }

    /** 네이티브 결과의 {@code createdAt} 은 드라이버에 따라 타입이 갈린다. */
    static LocalDateTime toLocalDateTime(Object raw) {
        if (raw instanceof LocalDateTime value) {
            return value;
        }
        if (raw instanceof java.sql.Timestamp value) {
            return value.toLocalDateTime();
        }
        return LocalDateTime.parse(raw.toString().replace(' ', 'T'));
    }
}
