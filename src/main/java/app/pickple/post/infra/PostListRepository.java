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
 * <p><b>먼저 자르고 나중에 붙인다.</b> 조각에 필요한 {@code post} 행을 인덱스로 먼저
 * 확정한 뒤({@code ORDER BY ... LIMIT}), 그 몇 줄에만 작성자와 대표 사진을 붙인다.
 * 순서를 뒤집어 조인부터 하면 MySQL 이 <b>정렬 전에 조인 결과 전체를 만들어야 해서</b>
 * 인덱스가 무의미해진다 — 실측으로 확인했다(100k 게시글 · 200k 회원).
 *
 * <pre>
 *   조인 먼저, 정렬 나중  454ms   post 100,030행 전량 스캔 후 정렬
 *   자르기 먼저, 조인 나중  0.23ms  idx_post_latest_all 에서 11행
 * </pre>
 *
 * <p>쿼리 <b>횟수</b>가 1회라는 사실만으로는 이 차이가 드러나지 않는다.
 * 두 형태 모두 statement 는 하나다. 대리지표가 가리지 못하는 자리라
 * 실행 계획을 함께 본다.
 *
 * <p><b>왜 네이티브 SQL 인가</b> (JPQL·QueryDSL 이 아니라)
 * <ul>
 *   <li>{@code post.popularity_score} 는 MySQL 생성 컬럼이라 {@code PostEntity} 에
 *       매핑하지 않는다 — 매핑하면 하이버네이트가 쓰기를 시도해 {@code ERROR 3105} 가 난다.
 *       매핑이 없으니 JPQL 로는 이 컬럼을 정렬에 쓸 수 없다.</li>
 *   <li>keyset 조건을 <b>행 값 비교</b> {@code (a, b) < (?, ?)} 로 써야 동률에서
 *       행이 새지 않는다. JPQL 은 튜플 비교를 지원하지 않아
 *       {@code a < ? OR (a = ? AND b < ?)} 로 풀어써야 하고, 그러면 옵티마이저가
 *       인덱스 범위 스캔으로 접지 못한다.</li>
 * </ul>
 *
 * <p><b>작성자 랭킹이 공짜인 이유</b> — {@code u.ranking} 은 배치가 미리 채워둔 값이고
 * (ADR-0028), 작성자 조인은 원래도 그 행을 {@code PRIMARY} 로 한 건 읽고 있었다.
 * 컬럼 하나가 얹힐 뿐이라 실행계획이 랭킹 없던 때와 같다 —
 * 실측 0.123ms(랭킹 없음) → 0.158ms(랭킹 포함). 조회 시점에 세면 97.6ms 다.
 * "먼저 자르고 나중에 붙인다" 가 여기서 배당금을 낸다: 조인은 이미 11행으로
 * 좁혀진 뒤에 일어나므로 붙는 컬럼의 비용이 조각 크기에만 비례한다.
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.post.domain.PostQueryStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class PostListRepository {

    /**
     * 대표 사진 1장 (§4.2) — 찬반은 상품이 하나뿐이고, A/B 는 A 상품(display_order = 1)이다.
     * 두 경우 모두 {@code display_order = 1} 로 잡히므로 유형 분기가 필요 없다.
     *
     * <p><b>스칼라 서브쿼리인 이유</b> — 찬반 상품은 사진을 최대 3장 갖는다(R-03).
     * {@code item_resource} 를 그냥 조인하면 게시글 한 줄이 사진 수만큼 불어나
     * 조각 크기가 어긋나고 GROUP BY 가 필요해진다. 서브쿼리는 게시글당 정확히 한 값이고,
     * 이미 잘라낸 10줄에만 도므로 조각 크기에 비례한다.
     *
     * <p>"가장 처음 등록한 사진" 은 {@code item_resource.id} 최소값으로 정한다 —
     * 같은 컨테이너 안에서 id 순서가 곧 등록 순서이고, {@code created_at} 은
     * 한 번의 업로드에서 모두 같은 값이라 순서를 가르지 못한다.
     *
     * <p>{@code item_container.access_urls} 는 쓰지 않는다. 스키마에 있지만
     * {@code ItemContainerEntity} 가 매핑하지 않아 <b>값이 채워지지 않는 컬럼</b>이다.
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

    /**
     * 조각을 확정하는 안쪽 질의.
     *
     * <p>{@code post} 만 본다. 다른 테이블을 여기 끌어들이면 인덱스가 정렬을 못 맡는다.
     */
    private static final String PAGE = """
            SELECT p.id, p.type, p.category, p.title, p.description,
                   p.vote_count, p.comment_count, p.created_at, p.popularity_score, p.user_id
              FROM post p
             WHERE p.deleted_at IS NULL
            """;

    private final EntityManager entityManager;

    /**
     * 조각을 읽는다. 다음 조각의 존재를 알기 위해 <b>{@code size + 1} 건</b>을 읽고
     * 넘치는 한 건은 버린다 — 별도 count 쿼리를 내지 않기 위해서다.
     */
    List<Object[]> findSlice(PostCategory category, PostSort sort, PostListCursor cursor, int size) {
        StringBuilder page = new StringBuilder(PAGE);
        if (category != null) {
            page.append(" AND p.category = :category");
        }
        if (cursor != null) {
            // 행 값 비교. (정렬키, id) 를 한 튜플로 놓아야 정렬키 동률에서 행이 새지 않는다.
            page.append(" AND (").append(sortColumn(sort)).append(", p.id) < (:sortValue, :cursorId)");
        }
        page.append(" ORDER BY ").append(sortColumn(sort)).append(" DESC, p.id DESC LIMIT :limit");

        String sql = """
                SELECT page.id, page.type, page.category, page.title, page.description,
                       page.vote_count, page.comment_count, page.created_at, page.popularity_score,
                       %s AS thumbnail_url,
                       page.user_id,
                       COALESCE(NULLIF(u.nickname, ''), NULLIF(u.name, ''), '알 수 없음') AS author_nickname,
                       u.ranking AS author_ranking
                  FROM (%s) page
                  JOIN users u ON u.id = page.user_id
                 ORDER BY page.%s DESC, page.id DESC
                """.formatted(THUMBNAIL, page, sortColumn(sort).substring(2));

        Query query = entityManager.createNativeQuery(sql);
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
