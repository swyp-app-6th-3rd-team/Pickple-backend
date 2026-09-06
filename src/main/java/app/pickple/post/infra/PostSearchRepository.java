package app.pickple.post.infra;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 검색 일치 집합·전체 건수·최신순 11개 후보를 한 SQL snapshot에서 읽는다. */
@Repository
@RequiredArgsConstructor
class PostSearchRepository {

    private static final String MATCHED = """
            WITH matched AS (
                SELECT p.id, p.created_at
                  FROM post p
                 WHERE p.deleted_at IS NULL
                   AND p.type IN ('GENERAL', 'A_B')
                   AND p.title LIKE :pattern ESCAPE '!'
                UNION
                SELECT p.id, p.created_at
                  FROM post p
                  JOIN post_product pp ON pp.post_id = p.id
                 WHERE p.deleted_at IS NULL
                   AND p.type IN ('AGREE', 'A_B')
                   AND pp.name LIKE :pattern ESCAPE '!'
            ), page AS (
                SELECT id, created_at
                  FROM matched
                %s
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
            ), total AS (
                SELECT COUNT(*) AS total_count FROM matched
            )
            """;

    private static final String PROJECTION = """
            SELECT total.total_count,
                   p.id,
                   p.type,
                   CASE WHEN p.type = 'AGREE' THEN
                       (SELECT pp.name
                          FROM post_product pp
                         WHERE pp.post_id = p.id
                           AND pp.display_order = 1)
                       ELSE p.title
                   END AS title,
                   p.vote_count,
                   p.comment_count,
                   p.created_at,
                   (SELECT ir.access_url
                      FROM post_product pp
                      JOIN item_resource ir ON ir.item_container_id = pp.item_container_id
                     WHERE pp.post_id = p.id
                       AND pp.display_order = 1
                     ORDER BY ir.id ASC
                     LIMIT 1) AS thumbnail_url
              FROM total
              LEFT JOIN page ON TRUE
              LEFT JOIN post p ON p.id = page.id
             ORDER BY page.created_at DESC, page.id DESC
            """;

    private final EntityManager entityManager;

    List<Object[]> search(String keyword, PostSearchCursor cursor, int size) {
        String boundary = cursor.hasBoundary()
                ? " WHERE (created_at, id) < (:createdAt, :cursorId)"
                : "";
        Query query = entityManager.createNativeQuery(MATCHED.formatted(boundary) + PROJECTION);
        query.setParameter("pattern", literalContainsPattern(keyword));
        if (cursor.hasBoundary()) {
            query.setParameter("createdAt", cursor.createdAt());
            query.setParameter("cursorId", cursor.id());
        }
        query.setParameter("limit", size + 1);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /** {@code !}를 escape 문자로 고정하고 LIKE 메타문자를 모두 literal로 만든다. */
    static String literalContainsPattern(String keyword) {
        return "%" + keyword
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    static final class Column {
        static final int TOTAL_COUNT = 0;
        static final int ID = 1;
        static final int TYPE = 2;
        static final int TITLE = 3;
        static final int VOTE_COUNT = 4;
        static final int COMMENT_COUNT = 5;
        static final int CREATED_AT = 6;
        static final int THUMBNAIL_URL = 7;

        private Column() {
        }
    }
}
