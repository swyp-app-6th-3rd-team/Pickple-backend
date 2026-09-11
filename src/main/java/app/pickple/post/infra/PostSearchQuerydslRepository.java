package app.pickple.post.infra;

import app.pickple.item.infra.QItemResourceEntity;
import app.pickple.post.domain.PostStore.PostSearchView;
import app.pickple.post.domain.PostType;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 검색 결과를 count, key, row, decoration의 네 QueryDSL 문장으로 읽는다.
 *
 * <p>일치 조건을 SQL에서 먼저 적용하고 게시글 id를 {@code size + 1}개 확정한 뒤,
 * 화면 필드는 최대 {@code size}개에만 붙인다. 결과는 typed projection으로 받아
 * 컬럼 순번이나 배열 기반 결과 변환을 사용하지 않는다.
 */
@Repository
@RequiredArgsConstructor
class PostSearchQuerydslRepository {

    private static final QPostEntity POST = QPostEntity.postEntity;
    private static final QPostProductEntity MATCHING_PRODUCT =
            new QPostProductEntity("matchingProduct");
    private static final QPostProductEntity PAGE_PRODUCT =
            new QPostProductEntity("pageProduct");
    private static final QItemResourceEntity CANDIDATE =
            new QItemResourceEntity("searchCandidate");

    private static final byte FIRST_PRODUCT = 1;

    private final JPAQueryFactory queryFactory;

    /** QueryDSL 생성자 프로젝션으로 받는 검색 본문. 찬반 제목과 사진은 별도 문장에서 붙인다. */
    public record SearchRow(
            Long id,
            PostType type,
            String title,
            long voteCount,
            long commentCount,
            LocalDateTime createdAt) {
    }

    /** 별도 장식 문장의 한 행. 상품명은 찬반 제목에, 사진은 게시글 썸네일에 쓴다. */
    public record ProductDecorationRow(
            Long postId,
            String productName,
            Byte displayOrder,
            String accessUrl) {
    }

    private record PageDecorations(
            Map<Long, String> agreeTitles,
            Map<Long, String> thumbnailUrls) {
    }

    record PostSearchSlice(long totalCount, List<PostSearchView> rows, boolean hasNext) {
    }

    PostSearchSlice search(String keyword, PostSearchCursor cursor, int size) {
        requireSnapshot();

        Long counted = queryFactory.select(POST.id.count())
                .from(POST)
                .where(matches(keyword))
                .fetchOne();
        long totalCount = counted == null ? 0L : counted;
        if (totalCount == 0L) {
            return new PostSearchSlice(0L, List.of(), false);
        }

        List<Long> ids = queryFactory.select(POST.id)
                .from(POST)
                .where(matches(keyword), after(cursor))
                .orderBy(POST.createdAt.desc(), POST.id.desc())
                .limit(size + 1L)
                .fetch();

        boolean hasNext = ids.size() > size;
        List<Long> page = hasNext ? ids.subList(0, size) : ids;
        if (page.isEmpty()) {
            return new PostSearchSlice(totalCount, List.of(), false);
        }

        List<SearchRow> searchRows = queryFactory.select(projection())
                .from(POST)
                .where(POST.id.in(page))
                .orderBy(POST.createdAt.desc(), POST.id.desc())
                .fetch();
        List<PostSearchView> rows = attachDecorations(
                searchRows, pageDecorations(page));
        assertSameSnapshot(page, rows);
        return new PostSearchSlice(totalCount, rows, hasNext);
    }

    private static BooleanExpression matches(String keyword) {
        String pattern = literalContainsPattern(keyword);
        BooleanExpression titleMatch = POST.type.in(PostType.GENERAL, PostType.A_B)
                .and(POST.title.like(pattern, '!'));
        /*
         * 상품명 매칭을 별도 조회로 끊으면 LIMIT 전에 제목 결과와 합치려 전체 상품 매칭 id를
         * 애플리케이션에 올려야 한다. EXISTS는 post 한 행을 유지해 A/B 양 상품이 맞아도
         * count와 key가 중복되지 않으므로 검색 조건으로 남긴다.
         */
        BooleanExpression productMatch = POST.type.in(PostType.AGREE, PostType.A_B)
                .and(JPAExpressions.selectOne()
                        .from(MATCHING_PRODUCT)
                        .where(
                                MATCHING_PRODUCT.post.id.eq(POST.id),
                                MATCHING_PRODUCT.name.like(pattern, '!'))
                        .exists());
        return POST.deletedAt.isNull().and(titleMatch.or(productMatch));
    }

    static String literalContainsPattern(String keyword) {
        return "%" + keyword
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private static BooleanExpression after(PostSearchCursor cursor) {
        if (!cursor.hasBoundary()) {
            return null;
        }
        return Expressions.booleanTemplate(
                "({0}, {1}) < ({2}, {3})",
                POST.createdAt,
                POST.id,
                Expressions.constant(cursor.createdAt()),
                Expressions.constant(cursor.id()));
    }

    private static Expression<SearchRow> projection() {
        return Projections.constructor(
                SearchRow.class,
                POST.id,
                POST.type,
                POST.title,
                POST.voteCount.longValue(),
                POST.commentCount.longValue(),
                POST.createdAt);
    }

    /** 페이지의 찬반 제목과 상품 사진을 한 번에 읽어 게시글별 장식 값으로 조립한다. */
    private PageDecorations pageDecorations(List<Long> postIds) {
        List<ProductDecorationRow> candidates = queryFactory
                .select(Projections.constructor(
                        ProductDecorationRow.class,
                        PAGE_PRODUCT.post.id,
                        PAGE_PRODUCT.name,
                        PAGE_PRODUCT.displayOrder,
                        CANDIDATE.accessUrl))
                .from(PAGE_PRODUCT)
                .leftJoin(CANDIDATE)
                .on(CANDIDATE.container.id.eq(PAGE_PRODUCT.itemContainerId))
                .where(PAGE_PRODUCT.post.id.in(postIds))
                .orderBy(CANDIDATE.id.asc())
                .fetch();

        Map<Long, String> agreeTitles = new LinkedHashMap<>();
        Map<Long, String> thumbnails = new LinkedHashMap<>();
        for (ProductDecorationRow candidate : candidates) {
            if (candidate.displayOrder() == FIRST_PRODUCT) {
                agreeTitles.put(candidate.postId(), candidate.productName());
            }
            if (candidate.accessUrl() != null) {
                thumbnails.putIfAbsent(candidate.postId(), candidate.accessUrl());
            }
        }
        return new PageDecorations(agreeTitles, thumbnails);
    }

    private static List<PostSearchView> attachDecorations(
            List<SearchRow> rows, PageDecorations decorations) {
        return rows.stream()
                .map(row -> new PostSearchView(
                        row.id(),
                        row.type(),
                        row.type() == PostType.AGREE
                                ? decorations.agreeTitles().get(row.id())
                                : row.title(),
                        row.voteCount(),
                        row.commentCount(),
                        row.createdAt(),
                        decorations.thumbnailUrls().get(row.id())))
                .toList();
    }

    private static void assertSameSnapshot(List<Long> ids, List<PostSearchView> rows) {
        Assert.state(ids.size() == rows.size(),
                "검색 key와 row가 같은 스냅샷을 보지 못했습니다.");
        for (int index = 0; index < ids.size(); index++) {
            Assert.state(ids.get(index).equals(rows.get(index).id()),
                    "검색 key와 row 순서가 같은 스냅샷에서 어긋났습니다.");
        }
    }

    private static void requireSnapshot() {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "검색 count, key, row, decoration이 같은 스냅샷을 보려면 트랜잭션 안이어야 한다");
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        Assert.state(isolation == null
                        || isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                        || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE,
                "검색 count, key, row, decoration이 같은 스냅샷을 보려면 REPEATABLE READ 이상이어야 한다: "
                        + isolation);
    }
}
