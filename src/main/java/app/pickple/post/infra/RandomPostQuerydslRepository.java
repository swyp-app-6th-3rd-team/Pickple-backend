package app.pickple.post.infra;

import app.pickple.item.infra.QItemResourceEntity;
import app.pickple.post.domain.PostStore.RandomOptionView;
import app.pickple.post.domain.PostStore.RandomPostView;
import app.pickple.post.domain.PostStore.RandomProductView;
import app.pickple.post.domain.PostType;
import app.pickple.vote.infra.QVoteEntity;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 홈 랜덤 카드를 <b>두 문장</b>으로 읽는다 — 카드에 들어갈 게시글을 확정하는 문장과 그 몇 장에
 * 선택지·상품·내 투표를 붙이는 문장 (ADR-0043 의 분할을 그대로 적용한다).
 *
 * <p><b>먼저 자르고 나중에 붙인다.</b> 옛 네이티브 SQL 은 파생 테이블 {@code FROM (SELECT … LIMIT) page}
 * 한 문장으로 게시글 {@code size + 1} 건을 먼저 정한 뒤 선택지를 붙였다 — 조인 결과를 먼저 제한하면
 * 선택지 행 수가 카드 수로 오인되어 페이지가 반으로 잘린다. QueryDSL-JPA 는 FROM 절 서브쿼리를
 * 지원하지 않으므로 안쪽 질의가 {@link #keys 키 문장}, 바깥 질의가 {@link #rows 행 문장}이 된다.
 *
 * <p><b>시드가 순서를 만든다.</b> {@code CRC32(CONCAT(seed, ':', post.id))} 오름차순, 충돌은 {@code post.id}
 * 가 가른다(SPEC §3.3). 이 표현식이 키 문장의 커서 비교와 정렬, 행 문장의 정렬에 세 번 등장하는데
 * {@link #randomKey 한 객체}를 세 곳에 쓴다 — 같은 시드가 같은 순서를 내야 커서가 이어진다.
 *
 * <p><b>두 문장은 한 스냅샷을 본다.</b> 호출자가 REPEATABLE READ 트랜잭션을 열어야 하며 옛 한 문장에는
 * 없던 전제라 {@link #findSlice} 가 진입 시점에 확인한다. 결과를 {@code Object} 배열로 받아 컬럼 인덱스
 * 상수 16개로 꺼내던 구조는 사라진다 — 그 인덱스는 SELECT 절이 바뀌면 조용히 밀렸다 (이슈 #130).
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.post.domain.PostStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class RandomPostQuerydslRepository {

    private static final QPostEntity POST = QPostEntity.postEntity;
    private static final QPostOptionEntity OPTION = QPostOptionEntity.postOptionEntity;
    /** 선택지가 가리키는 상품 — A/B 만 있다. */
    private static final QPostProductEntity OPTION_PRODUCT = new QPostProductEntity("optionProduct");
    /** 찬반 게시글의 유일한 상품 — 선택지는 상품을 가리키지 않으므로 게시글 쪽에서 붙인다. */
    private static final QPostProductEntity AGREE_PRODUCT = new QPostProductEntity("agreeProduct");
    private static final QVoteEntity OWN_VOTE = new QVoteEntity("ownVote");
    private static final QItemResourceEntity RESOURCE = QItemResourceEntity.itemResourceEntity;
    /** 최초 사진 서브쿼리 안쪽의 두 번째 {@code item_resource}. 바깥 별칭과 겹치면 안 된다. */
    private static final QItemResourceEntity CANDIDATE = new QItemResourceEntity("candidate");

    /** 찬반은 상품이 하나뿐이라 언제나 {@code display_order = 1} 이다 (R-02). */
    private static final byte AGREE_PRODUCT_ORDER = 1;
    /** 게스트의 내 투표 조인은 어떤 회원과도 맞지 않는 값으로 건다 — 문장 모양이 로그인과 같다. */
    private static final long GUEST = -1L;

    private final JPAQueryFactory queryFactory;

    /**
     * 행 문장의 한 줄 — 게시글 한 장의 <b>선택지 하나</b>와 그 선택지의 상품이다. 카드 한 장이 두 줄로 온다.
     * 선택지는 {@code INNER JOIN} 이라 언제나 있으므로 {@link RandomOptionView} 를 프로젝션이 바로 만든다.
     * 상품은 {@code LEFT JOIN} 이라 비어 있을 수 있는데 {@link RandomProductView#displayOrder()} 가 원시
     * {@code int} 라 {@code null} 을 받지 못한다 — 그래서 상품 네 컬럼은 펼쳐 받고 {@link #assemble} 이 접는다.
     * {@code public} 인 이유는 QueryDSL 이 생성자를 {@code getConstructors()} 로 찾기 때문이다 —
     * package-private 이면 첫 조회에서 "No constructor found" 가 난다.
     */
    public record RandomCardRow(
            Long postId, PostType type, String title, String description, Integer voterCount, Long randomKey,
            Long selectedOptionId, RandomOptionView option,
            Long productId, String productName, Byte productOrder, String imageUrl) {
    }

    /** 카드 한 장과 그 커서 값. 랜덤 키는 응답에 없고 커서에만 실리므로 이 패키지를 벗어나지 않는다. */
    record RandomCardEntry(long randomKey, RandomPostView view) {
    }

    /**
     * 한 조각. {@code hasNext} 는 <b>키 문장</b>이 정한다 — 행 문장의 카드 수로 판정하면 두 문장 사이에
     * 게시글이 지워졌을 때 "다음이 있다" 는 사실이 조용히 사라진다.
     */
    record RandomPostSlice(List<RandomCardEntry> cards, boolean hasNext) {
    }

    /**
     * 조각을 읽는다. 키 문장이 <b>{@code size + 1} 건</b>을 읽어 다음 조각의 존재를 알고, 넘치는 한 건은
     * 행 문장에 넘기지 않는다 — 조인 넷과 사진 서브쿼리를 한 장 아낀다.
     *
     * @param viewerId 로그인 사용자. 게스트면 {@code null}
     * @param cursor   첫 조각이면 경계 없이 시드만 든 커서
     */
    RandomPostSlice findSlice(PostType type, Long viewerId, RandomPostCursor cursor, int size) {
        requireSnapshot();

        NumberExpression<Long> randomKey = randomKey(cursor.seed());
        OrderSpecifier<?>[] order = order(randomKey);

        List<Long> ids = keys(type)
                .where(after(randomKey, cursor))
                .orderBy(order)
                .limit(size + 1L)
                .fetch();
        boolean hasNext = ids.size() > size;
        List<Long> page = hasNext ? ids.subList(0, size) : ids;
        if (page.isEmpty()) {
            return new RandomPostSlice(List.of(), false);
        }
        List<RandomCardRow> rows = rows(page, viewerId, randomKey)
                .orderBy(order[0], order[1], OPTION.displayOrder.asc())
                .fetch();
        return new RandomPostSlice(assemble(rows), hasNext);
    }

    /**
     * 두 문장이 한 스냅샷을 보려면 REPEATABLE READ 이상의 트랜잭션 안이어야 한다 (ADR-0043·0045). 격리 수준은
     * 가장 바깥 트랜잭션이 정하고 Spring 은 참여 트랜잭션의 격리를 검증하지 않으므로, 명시하지 않았으면({@code null})
     * MySQL 기본값으로 보고 통과시킨다. 누가 애노테이션을 지우거나 READ COMMITTED 로 열면 조용히 어긋나는 대신 여기서 깨진다.
     */
    private static void requireSnapshot() {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "키 문장과 행 문장이 같은 스냅샷을 보려면 트랜잭션 안이어야 한다");
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        Assert.state(isolation == null
                        || isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                        || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE,
                "키 문장과 행 문장이 같은 스냅샷을 보려면 REPEATABLE READ 이상이어야 한다: " + isolation);
    }

    /**
     * 시드 기반 랜덤 키 {@code CRC32(CONCAT(seed, ':', post.id))}. 같은 {@code seed + post id} 는 언제나 같은
     * 값을 만들고, 32-bit 충돌은 게시글 id 를 두 번째 정렬 키로 두어 누락이나 중복으로 이어지지 않는다.
     * 시드는 파라미터로 바인딩되고 {@code cast(… as string)} 은 MySQL 의 {@code CAST(… AS CHAR)} 로 내려간다 —
     * 옛 네이티브 문장과 같은 문자열을 해시하므로 이미 발급된 커서의 랜덤 키가 그대로 맞는다.
     *
     * <p><b>바깥의 {@code cast(… as Long)} 은 장식이 아니다.</b> Hibernate 의 MySQL 방언은 {@code crc32} 를
     * <b>{@code Integer}</b> 로 등록한다({@code CommonFunctionFactory.crc32}). MySQL 의 CRC32 는 unsigned 32-bit 라
     * 값의 절반이 {@code Integer.MAX_VALUE} 를 넘고, 그대로 두면 프로젝션이 "outside of valid range for
     * java.lang.Integer" 로 깨지며 커서 튜플 비교의 파라미터도 {@code Integer} 로 강제된다(실측 — 카드 절반이
     * 500). {@code function('CRC32' as Long, …)} 도 등록된 서술자의 불변 타입이 이기고, {@code FunctionContributor}
     * 는 방언보다 <b>먼저</b> 돌아 덮어쓰지 못한다(Hibernate 7.4 {@code QueryEngineImpl}). SQL 에는
     * {@code cast(crc32(…) as signed)} 로 내려가며 인덱스가 없는 함수 값이라 계획에는 영향이 없다.
     */
    private static NumberExpression<Long> randomKey(long seed) {
        return Expressions.numberTemplate(Long.class,
                "cast(crc32(concat(cast({0} as string), ':', cast({1} as string))) as Long)",
                Expressions.constant(seed), POST.id);
    }

    /** 정렬 {@code (랜덤 키 ASC, id ASC)}. 두 문장이 <b>같은 배열</b>을 써야 커서와 응답 순서가 어긋나지 않는다. */
    private static OrderSpecifier<?>[] order(NumberExpression<Long> randomKey) {
        return new OrderSpecifier<?>[] {randomKey.asc(), POST.id.asc()};
    }

    /**
     * 키 문장 — 조각에 들어갈 게시글 id 를 확정한다. {@code post} 만 본다 — 선택지를 여기 붙이면 {@code LIMIT}
     * 이 선택지 행을 세어 페이지가 반으로 잘린다. 랜덤 키는 함수 값이라 어떤 인덱스도 정렬을 맡지 못한다 —
     * 유형의 게시글 전체를 정렬하는 Θ(유형 게시글 수) 는 옛 문장과 같은, 시드 랜덤이 받아들인 비용이다.
     */
    private JPAQuery<Long> keys(PostType type) {
        return queryFactory.select(POST.id)
                .from(POST)
                .where(POST.deletedAt.isNull(), POST.type.eq(type));
    }

    /**
     * keyset 조건 {@code (랜덤 키, id) > (?, ?)}. 첫 조각이면 {@code null} 을 돌려 조건에서 빠진다.
     * 템플릿인 이유는 QueryDSL 에 튜플 비교 API 가 없어서다. HQL 튜플 비교는 MySQL 에 옛 네이티브 문장과 같은
     * 행 값 비교로 내려가며 풀어쓴 {@code a > ? OR (a = ? AND b > ?)} 로 바뀌지 않는다 — 실행계획 테스트가
     * 그 형태를 고정한다. 커서 값은 {@code Expressions.constant} 라 파라미터로 바인딩된다.
     */
    private static BooleanExpression after(NumberExpression<Long> randomKey, RandomPostCursor cursor) {
        if (!cursor.hasBoundary()) {
            return null;
        }
        return Expressions.booleanTemplate("({0}, {1}) > ({2}, {3})",
                randomKey, POST.id, Expressions.constant(cursor.randomKey()), Expressions.constant(cursor.id()));
    }

    /**
     * 행 문장 — 이미 확정된 몇 장에만 선택지·상품·내 투표를 붙인다. <b>게시글에서 시작한다.</b>
     * {@code post.id IN (…)} 이 기본 키 범위로 조각 크기만큼만 읽고, 선택지는 {@code uk_option_post_order},
     * 상품은 기본 키와 {@code uk_product_post_order}, 내 투표는 {@code uk_vote_post_user} 로 한 줄씩 붙는다.
     * 실행계획 테스트가 인덱스 이름이 아니라 접근 방식을 고정한다. 상품은 두 갈래다 — A/B 는 선택지가 상품을
     * 가리키고, 찬반은 선택지가 라벨뿐이라 게시글의 유일한 상품을 붙인다. 두 조인을 {@code COALESCE} 로 접는다.
     * {@code deleted_at}·유형을 다시 걸지 않는다 — 키 문장이 같은 스냅샷에서 이미 걸렀고, 여기서 다시 걸면
     * 스냅샷 전제가 깨졌다는 사실이 가려진다.
     */
    private JPAQuery<RandomCardRow> rows(List<Long> ids, Long viewerId, NumberExpression<Long> randomKey) {
        return queryFactory.select(projection(randomKey))
                .from(POST)
                .join(OPTION).on(OPTION.post.id.eq(POST.id))
                .leftJoin(OPTION_PRODUCT).on(OPTION_PRODUCT.id.eq(OPTION.postProductId))
                .leftJoin(AGREE_PRODUCT).on(POST.type.eq(PostType.AGREE),
                        AGREE_PRODUCT.post.id.eq(POST.id),
                        AGREE_PRODUCT.displayOrder.eq(AGREE_PRODUCT_ORDER))
                .leftJoin(OWN_VOTE).on(OWN_VOTE.postId.eq(POST.id),
                        OWN_VOTE.userId.eq(viewerId == null ? GUEST : viewerId))
                .where(POST.id.in(ids));
    }

    /**
     * 행 문장의 프로젝션. 생성자 인자 순서가 {@link RandomCardRow} 와 어긋나면 첫 조회에서
     * {@code ExpressionException} 으로 드러난다 — 옛 컬럼 인덱스 상수처럼 엉뚱한 값이 조용히 들어가지 않는다.
     * 선택지의 표시 순서·득표 수는 스키마가 {@code TINYINT}·{@code INT UNSIGNED} 라 뷰의 {@code int}·{@code long}
     * 으로 넓힌다. 이 {@code intValue()}·{@code longValue()} 는 SQL 에 {@code cast} 로 내려가므로
     * <b>정렬에는 쓰지 않는다</b> — 선택지 정렬은 원래 경로 {@code OPTION.displayOrder} 다.
     */
    private static Expression<RandomCardRow> projection(NumberExpression<Long> randomKey) {
        return Projections.constructor(RandomCardRow.class,
                POST.id,
                POST.type,
                title(),
                POST.description,
                POST.voteCount,
                randomKey,
                OWN_VOTE.postOptionId,
                Projections.constructor(RandomOptionView.class, OPTION.id, OPTION.label, OPTION.postProductId,
                        OPTION.displayOrder.intValue(), OPTION.voteCount.longValue()),
                OPTION_PRODUCT.id.coalesce(AGREE_PRODUCT.id),
                OPTION_PRODUCT.name.coalesce(AGREE_PRODUCT.name),
                OPTION_PRODUCT.displayOrder.coalesce(AGREE_PRODUCT.displayOrder),
                imageUrl());
    }

    /** 찬반 카드의 제목은 상품명이다 (§3.3). A/B 는 주제를 그대로 쓴다. */
    private static Expression<String> title() {
        return new CaseBuilder()
                .when(POST.type.eq(PostType.AGREE)).then(AGREE_PRODUCT.name)
                .otherwise(POST.title);
    }

    /**
     * 각 상품에서 가장 먼저 등록된 사진 한 장 (§3.3). 스칼라 서브쿼리인 이유는 찬반 상품이 사진을 최대 3장
     * 갖기 때문이다(R-03) — 그냥 조인하면 선택지 행이 사진 수만큼 불어난다. 옛 SQL 의
     * {@code ORDER BY ir.id ASC LIMIT 1} 을 {@code MIN(id)} 로 옮겼다 — 뜻이 같고 서브쿼리 안의 {@code LIMIT}
     * 은 JPQL 에 없다({@code PostListQuerydslRepository} 와 같은 정의). 선택지 행 수(카드 × 2)만큼만 돈다.
     */
    private static Expression<String> imageUrl() {
        NumberExpression<Long> containerId = OPTION_PRODUCT.itemContainerId.coalesce(AGREE_PRODUCT.itemContainerId);
        return JPAExpressions.select(RESOURCE.accessUrl)
                .from(RESOURCE)
                .where(RESOURCE.id.eq(
                        JPAExpressions.select(CANDIDATE.id.min())
                                .from(CANDIDATE)
                                .where(CANDIDATE.container.id.eq(containerId))));
    }

    /** 두 선택지 행과 중복된 찬반 상품을 카드 하나로 접는다. SQL 순서를 그대로 보존한다. */
    private static List<RandomCardEntry> assemble(List<RandomCardRow> rows) {
        Map<Long, CardBuilder> cards = new LinkedHashMap<>();
        for (RandomCardRow row : rows) {
            cards.computeIfAbsent(row.postId(), ignored -> new CardBuilder(row)).add(row);
        }
        return cards.values().stream().map(CardBuilder::build).toList();
    }

    private static final class CardBuilder {

        private final RandomCardRow head;
        private final Map<Long, RandomProductView> products = new LinkedHashMap<>();
        private final List<RandomOptionView> options = new ArrayList<>(2);

        private CardBuilder(RandomCardRow head) {
            this.head = head;
        }

        private void add(RandomCardRow row) {
            if (row.productId() != null) {
                products.putIfAbsent(row.productId(), new RandomProductView(
                        row.productId(), row.productName(), row.productOrder(), row.imageUrl()));
            }
            options.add(row.option());
        }

        private RandomCardEntry build() {
            return new RandomCardEntry(head.randomKey(), new RandomPostView(
                    head.postId(), head.type(), head.title(), head.description(), head.voterCount(),
                    head.selectedOptionId(), List.copyOf(products.values()), List.copyOf(options)));
        }
    }
}
