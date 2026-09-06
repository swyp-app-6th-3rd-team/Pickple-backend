package app.pickple.post.infra;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 게시글 상세를 <b>고정된 문장 수</b>로 읽는다.
 *
 * <p>목록과 달리 조각도 커서도 없다. 대신 다른 위험이 있다 — <b>팬아웃</b>이다.
 * 상품(최대 2)·선택지(정확히 2)·상품 사진(최대 3)을 한 질의로 조인하면
 * 게시글 한 줄이 최대 12줄로 불어나 같은 값을 여러 번 읽는다. 그래서 셋으로 나눈다:
 *
 * <pre>
 *   1. 게시글 + 작성자 + 내 투표   (한 줄)
 *   2. 상품 + 대표 사진             (0~2줄)
 *   3. 선택지                       (0~2줄)
 * </pre>
 *
 * <p><b>세 문장은 상수다.</b> 유형이 무엇이든, 상품이 하나든 둘이든, 사진이 한 장이든
 * 세 장이든 문장 수가 변하지 않는다 — 그것이 N+1 이 없다는 뜻이다. 애그리거트로 읽으면
 * 지연 로딩 컬렉션 둘에 작성자·사진·투표 조회가 더 붙는다.
 *
 * <p><b>왜 하나로 합치지 않는가.</b> 팬아웃 12줄을 감수하면 문장 1개가 되지만,
 * 상품과 선택지는 <b>서로를 곱한다</b>(2 × 2). 애플리케이션에서 중복을 걷어내려면
 * 어차피 두 컬렉션으로 갈라야 하고, 그 코드가 조인보다 길어진다.
 * 문장 3개는 모두 PK·유니크 인덱스로 도는 한 줄짜리 조회다.
 *
 * <p><b>왜 네이티브 SQL 인가</b> — {@link PostListRepository} 와 같은 이유에 하나가 더 붙는다.
 * 상품 대표 사진은 스칼라 서브쿼리라야 게시글당 한 값이 되고,
 * {@code item_container.access_urls} 는 매핑되지 않아 값이 없는 컬럼이라 쓸 수 없다.
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.post.domain.PostQueryStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class PostDetailRepository {

    /**
     * 게시글 본문 + 작성자 + 내 투표.
     *
     * <p><b>삭제된 글은 여기서 걸러진다</b> — 소프트 삭제라 행이 남아 있으므로
     * {@code deleted_at IS NULL} 이 없으면 지운 글이 그대로 보인다. 목록과 같은 기준이다.
     *
     * <p>내 투표는 {@code LEFT JOIN} 이다. 게스트({@code :viewerId} 가 null)이거나
     * 아직 투표하지 않았으면 {@code my_option_id} 가 null 이고, 그 null 이 곧
     * "득표율을 감춘다" 의 입력이 된다 (ADR-0041). 조인 조건에 {@code v.user_id = :viewerId}
     * 를 넣으므로 게스트여도 행이 사라지지 않는다.
     *
     * <p>작성자 닉네임의 {@code COALESCE} 는 목록과 같다 — 닉네임을 아직 정하지 않았으면
     * 소셜 이름을, 그것도 없으면 고정 문구를 쓴다.
     */
    private static final String DETAIL = """
            SELECT p.id, p.type, p.category, p.title, p.description,
                   p.vote_count, p.comment_count, p.created_at,
                   p.user_id,
                   COALESCE(NULLIF(u.nickname, ''), NULLIF(u.name, ''), '알 수 없음') AS author_nickname,
                   u.profile_image_url,
                   u.ranking,
                   u.highest_grade,
                   v.post_option_id AS my_option_id
              FROM post p
              JOIN users u ON u.id = p.user_id
              LEFT JOIN vote v ON v.post_id = p.id AND v.user_id = :viewerId
             WHERE p.id = :id
               AND p.deleted_at IS NULL
            """;

    /**
     * 상품과 그 대표 사진 1장 (§6.3).
     *
     * <p>사진은 스칼라 서브쿼리다. {@code item_resource} 를 그냥 조인하면 찬반 상품이
     * 사진 수(최대 3장, R-03)만큼 줄로 불어난다. "가장 처음 등록한 사진" 은
     * {@code item_resource.id} 최소값으로 정한다 — 같은 컨테이너 안에서 id 순서가
     * 곧 등록 순서이고, {@code created_at} 은 한 번의 업로드에서 모두 같아 순서를 못 가른다.
     * 목록의 대표 사진과 같은 규칙이다.
     *
     * <p>A/B 는 상품마다 사진이 1장이라(R-03) 같은 서브쿼리가 그대로 맞는다.
     */
    private static final String PRODUCTS = """
            SELECT pp.id, pp.name, pp.price, pp.link_url, pp.display_order,
                   (SELECT ir.access_url
                      FROM item_resource ir
                     WHERE ir.item_container_id = pp.item_container_id
                     ORDER BY ir.id ASC
                     LIMIT 1) AS image_url
              FROM post_product pp
             WHERE pp.post_id = :id
             ORDER BY pp.display_order ASC
            """;

    /** 선택지 (§6.3). 투표 게시글은 정확히 둘, 일반은 0줄이다 (R-04). */
    private static final String OPTIONS = """
            SELECT po.id, po.label, po.post_product_id, po.display_order, po.vote_count
              FROM post_option po
             WHERE po.post_id = :id
             ORDER BY po.display_order ASC
            """;

    private final EntityManager entityManager;

    /** 게시글 본문 한 줄. 없거나 삭제됐으면 빈 목록이다. */
    List<Object[]> findDetail(Long id, Long viewerId) {
        Query query = entityManager.createNativeQuery(DETAIL);
        query.setParameter("id", id);
        query.setParameter("viewerId", viewerId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    List<Object[]> findProducts(Long id) {
        return rows(PRODUCTS, id);
    }

    List<Object[]> findOptions(Long id) {
        return rows(OPTIONS, id);
    }

    private List<Object[]> rows(String sql, Long id) {
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("id", id);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /** 본문 한 행의 컬럼 위치. 순서가 곧 계약이라 상수로 고정한다. */
    static final class Detail {
        static final int ID = 0;
        static final int TYPE = 1;
        static final int CATEGORY = 2;
        static final int TITLE = 3;
        static final int DESCRIPTION = 4;
        static final int VOTE_COUNT = 5;
        static final int COMMENT_COUNT = 6;
        static final int CREATED_AT = 7;
        static final int AUTHOR_ID = 8;
        static final int AUTHOR_NICKNAME = 9;
        static final int AUTHOR_PROFILE_IMAGE_URL = 10;
        static final int AUTHOR_RANKING = 11;
        static final int AUTHOR_GRADE = 12;
        static final int MY_OPTION_ID = 13;

        private Detail() {
        }
    }

    /** 상품 한 행의 컬럼 위치. */
    static final class Product {
        static final int ID = 0;
        static final int NAME = 1;
        static final int PRICE = 2;
        static final int LINK_URL = 3;
        static final int DISPLAY_ORDER = 4;
        static final int IMAGE_URL = 5;

        private Product() {
        }
    }

    /** 선택지 한 행의 컬럼 위치. */
    static final class Option {
        static final int ID = 0;
        static final int LABEL = 1;
        static final int PRODUCT_ID = 2;
        static final int DISPLAY_ORDER = 3;
        static final int VOTE_COUNT = 4;

        private Option() {
        }
    }
}
