package app.pickple.post.infra;

import app.pickple.item.infra.QItemResourceEntity;
import app.pickple.post.domain.PostStore.PostSearchView;
import app.pickple.post.domain.PostType;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.util.List;

/**
 * 검색 결과를 count, key, row의 세 QueryDSL 문장으로 읽는다.
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
    private static final QPostProductEntity DISPLAY_PRODUCT =
            new QPostProductEntity("displayProduct");
    private static final QPostProductEntity IMAGE_PRODUCT =
            new QPostProductEntity("imageProduct");
    private static final QItemResourceEntity RESOURCE =
            new QItemResourceEntity("searchResource");
    private static final QItemResourceEntity CANDIDATE =
            new QItemResourceEntity("searchCandidate");

    private static final byte FIRST_PRODUCT = 1;

    private final JPAQueryFactory queryFactory;

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

        List<PostSearchView> rows = queryFactory.select(projection())
                .from(POST)
                .where(POST.id.in(page))
                .orderBy(POST.createdAt.desc(), POST.id.desc())
                .fetch();
        assertSameSnapshot(page, rows);
        return new PostSearchSlice(totalCount, rows, hasNext);
    }

    private static BooleanExpression matches(String keyword) {
        String pattern = literalContainsPattern(keyword);
        BooleanExpression titleMatch = POST.type.in(PostType.GENERAL, PostType.A_B)
                .and(POST.title.like(pattern, '!'));
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

    private static Expression<PostSearchView> projection() {
        return Projections.constructor(
                PostSearchView.class,
                POST.id,
                POST.type,
                displayTitle(),
                POST.voteCount.longValue(),
                POST.commentCount.longValue(),
                POST.createdAt,
                thumbnailUrl());
    }

    private static Expression<String> displayTitle() {
        return new CaseBuilder()
                .when(POST.type.eq(PostType.AGREE))
                .then(JPAExpressions.select(DISPLAY_PRODUCT.name)
                        .from(DISPLAY_PRODUCT)
                        .where(
                                DISPLAY_PRODUCT.post.id.eq(POST.id),
                                DISPLAY_PRODUCT.displayOrder.eq(FIRST_PRODUCT)))
                .otherwise(POST.title);
    }

    /** 현재 연결된 모든 상품 사진 중 DB에 가장 먼저 등록된 한 장. */
    private static Expression<String> thumbnailUrl() {
        return JPAExpressions.select(RESOURCE.accessUrl)
                .from(RESOURCE)
                .where(RESOURCE.id.eq(
                        JPAExpressions.select(CANDIDATE.id.min())
                                .from(IMAGE_PRODUCT)
                                .join(CANDIDATE)
                                .on(CANDIDATE.container.id.eq(IMAGE_PRODUCT.itemContainerId))
                                .where(IMAGE_PRODUCT.post.id.eq(POST.id))));
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
                "검색 count, key, row가 같은 스냅샷을 보려면 트랜잭션 안이어야 한다");
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        Assert.state(isolation == null
                        || isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                        || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE,
                "검색 count, key, row가 같은 스냅샷을 보려면 REPEATABLE READ 이상이어야 한다: "
                        + isolation);
    }
}
