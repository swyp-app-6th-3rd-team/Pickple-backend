package app.pickple.post.infra;

import app.pickple.post.domain.PostType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 홈 랜덤 카드의 게시글·상품·선택지·내 투표를 SQL 한 번으로 읽는다. */
@Repository
@RequiredArgsConstructor
class RandomPostRepository {

    /**
     * 같은 {@code seed + post id}는 언제나 같은 값을 만든다. 32-bit 충돌은 게시글 id를
     * 두 번째 정렬 키로 두어 순서를 유일하게 만들면 누락이나 중복으로 이어지지 않는다.
     */
    private static final String RANDOM_KEY =
            "CRC32(CONCAT(CAST(:seed AS CHAR), ':', CAST(p.id AS CHAR)))";

    /** 각 상품에서 가장 먼저 등록된 사진 한 장. */
    private static final String FIRST_IMAGE = """
            (SELECT ir.access_url
               FROM item_resource ir
              WHERE ir.item_container_id =
                    COALESCE(option_product.item_container_id, agree_product.item_container_id)
              ORDER BY ir.id ASC
              LIMIT 1)
            """;

    private final EntityManager entityManager;

    /**
     * 먼저 {@code size + 1}개의 게시글을 정한 뒤 각 게시글의 두 선택지와 상품을 붙인다.
     * 조인 결과를 먼저 제한하면 선택지 행 수가 카드 수로 오인되어 페이지가 반으로 잘린다.
     */
    List<Object[]> findSlice(
            PostType type, Long viewerId, RandomPostCursor cursor, int size) {

        StringBuilder page = new StringBuilder("""
                SELECT p.id, p.type, p.title, p.description, p.vote_count,
                       %s AS random_key
                  FROM post p
                 WHERE p.deleted_at IS NULL
                   AND p.type = :type
                """.formatted(RANDOM_KEY));
        if (cursor.hasBoundary()) {
            page.append(" AND (").append(RANDOM_KEY)
                    .append(", p.id) > (:randomKey, :cursorId)");
        }
        page.append(" ORDER BY random_key ASC, p.id ASC LIMIT :limit");

        String sql = """
                SELECT page.id, page.type,
                       CASE WHEN page.type = 'AGREE' THEN agree_product.name ELSE page.title END AS title,
                       page.description, page.vote_count,
                       page.random_key, own_vote.post_option_id,
                       po.id, po.label, po.display_order, po.vote_count,
                       option_product.id,
                       COALESCE(option_product.id, agree_product.id) AS product_id,
                       COALESCE(option_product.name, agree_product.name) AS product_name,
                       COALESCE(option_product.display_order, agree_product.display_order) AS product_order,
                       %s AS image_url
                  FROM (%s) page
                  JOIN post_option po ON po.post_id = page.id
                  LEFT JOIN post_product option_product ON option_product.id = po.post_product_id
                  LEFT JOIN post_product agree_product
                    ON page.type = 'AGREE'
                   AND agree_product.post_id = page.id
                   AND agree_product.display_order = 1
                  LEFT JOIN vote own_vote
                    ON own_vote.post_id = page.id
                   AND own_vote.user_id = :viewerId
                 ORDER BY page.random_key ASC, page.id ASC, po.display_order ASC
                """.formatted(FIRST_IMAGE, page);

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("type", type.name());
        query.setParameter("seed", cursor.seed());
        query.setParameter("viewerId", viewerId == null ? -1L : viewerId);
        if (cursor.hasBoundary()) {
            query.setParameter("randomKey", cursor.randomKey());
            query.setParameter("cursorId", cursor.id());
        }
        query.setParameter("limit", size + 1);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /** 네이티브 결과 한 행의 컬럼 위치. */
    static final class Column {
        static final int ID = 0;
        static final int TYPE = 1;
        static final int TITLE = 2;
        static final int DESCRIPTION = 3;
        static final int VOTER_COUNT = 4;
        static final int RANDOM_KEY = 5;
        static final int SELECTED_OPTION_ID = 6;
        static final int OPTION_ID = 7;
        static final int OPTION_LABEL = 8;
        static final int OPTION_ORDER = 9;
        static final int OPTION_VOTE_COUNT = 10;
        static final int OPTION_PRODUCT_ID = 11;
        static final int PRODUCT_ID = 12;
        static final int PRODUCT_NAME = 13;
        static final int PRODUCT_ORDER = 14;
        static final int IMAGE_URL = 15;

        private Column() {
        }
    }
}
