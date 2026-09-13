package app.pickple.post.infra;

import app.pickple.auth.infra.QUserEntity;
import app.pickple.item.infra.QItemResourceEntity;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostSort;
import app.pickple.post.domain.PostStore.PopularPostView;
import app.pickple.post.domain.PostStore.PopularProductView;
import app.pickple.post.domain.PostStore.PostListView;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 게시글 목록을 <b>두 문장</b>으로 읽는다 — 키를 확정하는 문장과 행을 조립하는 문장 (ADR-0045).
 * 인기 카드는 확정된 게시글의 상품 사진 배치 조회를 더해 세 문장으로 읽는다 (§2.4).
 *
 * <p><b>먼저 자르고 나중에 붙인다.</b> 조각에 들어갈 게시글 id 를 {@code post} 의 정렬 인덱스로
 * 먼저 확정한 뒤({@code ORDER BY … LIMIT}), 그 몇 줄에만 작성자와 대표 사진을 붙인다.
 * 순서를 뒤집어 조인부터 하면 MySQL 이 정렬 전에 조인 결과 전체를 만들어야 해서 인덱스가
 * 무의미해진다 — 옛 네이티브 SQL 의 실측이다(100k 게시글 · 200k 회원).
 *
 * <pre>
 *   조인 먼저, 정렬 나중  454ms   post 100,030행 전량 스캔 후 정렬
 *   자르기 먼저, 조인 나중  0.23ms  idx_post_latest_all 에서 11행
 * </pre>
 *
 * <p>옛 SQL 은 이것을 파생 테이블 {@code FROM (SELECT … LIMIT) page} 한 문장으로 했는데,
 * QueryDSL-JPA 는 FROM 절 서브쿼리를 지원하지 않는다. 그래서 안쪽 질의가
 * {@link #keys 키 문장}이 되고 바깥 질의가 {@link #rows 행 문장}이 된다(ADR-0043 과 같은 분할).
 * 왕복이 하나 늘지만 <b>인덱스가 정렬을 맡는 구간은 키 문장 하나에 그대로 남는다</b> —
 * 판정 기준은 왕복 횟수가 아니라 실행계획이다.
 *
 * <p><b>두 문장은 한 스냅샷을 본다.</b> 호출자가 트랜잭션을 열어야 한다 —
 * InnoDB 의 REPEATABLE READ 에서 첫 읽기가 스냅샷을 잡으므로, 키 문장과 행 문장 사이에
 * 게시글이 지워지거나 인기 점수가 바뀌어도 행 문장이 키 문장과 다른 세상을 보지 않는다.
 * 옛 한 문장에는 없던 전제라 {@link #findSlice} 가 진입 시점에 확인한다.
 *
 * <p><b>정렬 키가 둘이다.</b> 최신순은 {@code created_at}, 인기순은 생성 컬럼
 * {@code popularity_score} 다. 옛 코드는 컬럼명을 {@code sortColumn(sort)} 로 세 군데에
 * 이어붙였고 바깥 질의에서는 {@code substring(2)} 로 별칭 접두사를 잘라 다시 끼웠다.
 * 이제 {@link #sortKey} 가 <b>경로 객체</b>를 돌려주고 정렬·커서·두 문장이 같은 객체를 쓴다 —
 * 문자열이 갈릴 자리가 없다.
 *
 * <p><b>왜 더는 네이티브 SQL 이 아닌가</b> — 옛 javadoc 이 든 두 이유가 모두 사라졌다.
 * <ul>
 *   <li>{@code post.popularity_score} 를 읽기 전용으로 매핑했다(ADR-0041). JPQL 이 정렬·커서에
 *       이 컬럼을 쓸 수 있다.</li>
 *   <li>keyset 의 행 값 비교 {@code (a, b) < (?, ?)} 는 Hibernate 7 HQL 이 튜플 비교로
 *       지원한다. 풀어쓴 {@code a < ? OR (a = ? AND b < ?)} 로 바뀌지 않아 인덱스 범위가
 *       그대로 접힌다 — 실행계획 테스트가 그 형태를 고정한다.</li>
 * </ul>
 * 결과를 {@code Object} 배열로 받아 컬럼 인덱스 상수로 꺼내던 구조도 함께 사라진다 —
 * 그 인덱스는 SELECT 절이 바뀌면 조용히 밀리고 런타임에야 깨졌다 (이슈 #130).
 *
 * <p><b>작성자 랭킹이 공짜인 이유</b> — {@code u.ranking} 은 배치가 미리 채워둔 값이고
 * (ADR-0028), 작성자 조인은 원래도 그 행을 {@code PRIMARY} 로 한 건 읽고 있었다.
 * 조인은 이미 조각 크기로 좁혀진 행 문장에서만 일어나므로 붙는 컬럼의 비용이 조각 크기에만 비례한다.
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.post.domain.PostStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class PostListQuerydslRepository {

    private static final QPostEntity POST = QPostEntity.postEntity;
    private static final QUserEntity USER = QUserEntity.userEntity;
    private static final QPostProductEntity PRODUCT = QPostProductEntity.postProductEntity;
    private static final QItemResourceEntity RESOURCE = QItemResourceEntity.itemResourceEntity;
    /** 대표 사진 서브쿼리 안쪽의 두 번째 {@code item_resource}. 바깥 별칭과 겹치면 안 된다. */
    private static final QItemResourceEntity CANDIDATE = new QItemResourceEntity("candidate");

    /** 찬반은 상품이 하나뿐이고 A/B 는 A 상품이라 대표 사진은 둘 다 {@code display_order = 1} 이다 (§4.2). */
    private static final byte REPRESENTATIVE_PRODUCT = 1;

    /** 닉네임도 이름도 비어 있는 작성자 — 탈퇴로 개인정보가 파기된 회원이다 (ADR-0040). */
    private static final String UNKNOWN_AUTHOR = "알 수 없음";

    private final JPAQueryFactory queryFactory;

    /**
     * 조회 결과 한 행. 화면용 뷰에 <b>커서에만 쓰는</b> 인기 점수를 곁들인다.
     *
     * <p>{@link PostListView} 에 점수를 넣지 않는 이유는 그 타입이 화면 계약이기 때문이다 —
     * 인기순 커서를 만들려고 응답에 없는 값을 도메인 뷰에 끼우면 어느 화면이 무엇을
     * 쓰는지 타입이 말해주지 못한다. 점수는 이 패키지를 벗어나지 않는다.
     *
     * <p>{@code public} 인 이유는 QueryDSL 이 생성자를 {@code getConstructors()} 로 찾기 때문이다 —
     * 레코드의 정규 생성자는 레코드와 접근 수준이 같아, package-private 이면 런타임에
     * "No constructor found" 가 난다. 감싸는 클래스가 package-private 이라 바깥에는 안 보인다.
     */
    public record PostListRow(PostListView view, Integer popularityScore) {
    }

    /** 인기 카드에만 필요한 댓글 작성자 수를 목록 필드와 함께 투영한다. */
    public record PopularPostRow(PostListView view, long commenterCount) {
    }

    /** Top 10에 속한 상품을 게시글별로 묶기 위한 배치 조회 행이다. */
    public record PopularProductRow(Long postId, PopularProductView product) {
    }

    /**
     * 한 조각. {@code hasNext} 는 <b>키 문장</b>이 정한다 — 행 문장의 행 수로 판정하면
     * 두 문장 사이에 게시글이 지워졌을 때 "다음이 있다" 는 사실이 조용히 사라진다.
     */
    record PostListSlice(List<PostListRow> rows, boolean hasNext) {
    }

    /**
     * 조각을 읽는다. 다음 조각의 존재를 알기 위해 키 문장이 <b>{@code size + 1} 건</b>을 읽고,
     * 넘치는 한 건은 행 문장에 넘기지 않는다 — 대표 사진 서브쿼리를 한 번 아낀다.
     *
     * @param category 필터. {@code null} 이면 전체다
     * @param cursor   첫 조각이면 {@code null}
     */
    PostListSlice findSlice(PostCategory category, PostSort sort, PostListCursor cursor, int size) {
        requireSnapshot();

        ComparableExpressionBase<?> sortKey = sortKey(sort);
        OrderSpecifier<?>[] order = order(sortKey);

        List<Long> ids = keys(category)
                .where(after(sortKey, cursor))
                .orderBy(order)
                .limit(size + 1L)
                .fetch();
        boolean hasNext = ids.size() > size;
        List<Long> page = hasNext ? ids.subList(0, size) : ids;
        if (page.isEmpty()) {
            return new PostListSlice(List.of(), false);
        }
        List<PostListRow> rows = rows(page)
                .orderBy(order)
                .fetch();
        return new PostListSlice(rows, hasNext);
    }

    /**
     * 인기 카드: 키 → 행·댓글 인원 → 상품 사진의 세 문장을 같은 스냅샷에서 읽는다.
     * 상품은 Top N이 확정된 뒤에만 붙이므로 A/B 두 상품이 순위나 카드 수를 바꾸지 않는다.
     */
    List<PopularPostView> findPopularTop(int size) {
        requireSnapshot();
        OrderSpecifier<?>[] order = order(sortKey(PostSort.POPULAR));
        List<Long> ids = keys(null).orderBy(order).limit(size).fetch();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<PopularPostRow> rows = queryFactory.select(
                        Projections.constructor(PopularPostRow.class,
                                listViewProjection(), POST.commenterCount.longValue()))
                .from(POST)
                .join(USER).on(USER.id.eq(POST.userId))
                .where(POST.id.in(ids))
                .orderBy(order)
                .fetch();
        Map<Long, List<PopularProductView>> products = popularProducts(ids);
        return rows.stream()
                .map(row -> new PopularPostView(row.view(), row.commenterCount(),
                        products.getOrDefault(row.view().id(), List.of())))
                .toList();
    }

    /** 상품마다 대표 사진 한 장만 읽고, 상품 표시 순서를 보존해 게시글별로 묶는다. */
    private Map<Long, List<PopularProductView>> popularProducts(List<Long> ids) {
        return queryFactory.select(Projections.constructor(PopularProductRow.class,
                        PRODUCT.post.id,
                        Projections.constructor(PopularProductView.class,
                                PRODUCT.displayOrder.intValue(), productImageUrl())))
                .from(PRODUCT)
                .where(PRODUCT.post.id.in(ids))
                .orderBy(PRODUCT.post.id.asc(), PRODUCT.displayOrder.asc())
                .fetch().stream()
                .collect(Collectors.groupingBy(PopularProductRow::postId,
                        Collectors.mapping(PopularProductRow::product, Collectors.toList())));
    }

    /** 상품의 등록 순서는 사진 id로 결정한다. 같은 업로드의 작성 시각은 같을 수 있다 (R-03). */
    private static Expression<String> productImageUrl() {
        return JPAExpressions.select(RESOURCE.accessUrl)
                .from(RESOURCE)
                .where(RESOURCE.id.eq(
                        JPAExpressions.select(CANDIDATE.id.min())
                                .from(CANDIDATE)
                                .where(CANDIDATE.container.id.eq(PRODUCT.itemContainerId))));
    }

    /**
     * 두 문장이 한 스냅샷을 보려면 트랜잭션 안이어야 한다 (ADR-0043·0045). 누가 애노테이션을 지우면
     * 조용히 어긋나는 대신 여기서 즉시 깨진다.
     *
     * <p>격리 수준도 본다. Spring 은 참여 트랜잭션의 격리를 검증하지 않으므로 가장 바깥이 정한 값이
     * 그대로 흘러온다 — 명시하지 않았으면({@code null}) MySQL 기본값 REPEATABLE READ 이고,
     * 명시했다면 REPEATABLE READ 이상이어야 한다. 누가 바깥에서 READ COMMITTED 로 열면 여기서 깨진다.
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
     * 키 문장 — 조각에 들어갈 게시글 id 를 확정한다.
     *
     * <p>{@code post} 만 본다. 다른 테이블을 여기 끌어들이면 인덱스가 정렬을 못 맡는다.
     * 카테고리가 있으면 {@code idx_post_latest}·{@code idx_post_popular}, 없으면
     * {@code *_all} 인덱스가 {@code deleted_at} 범위 안에서 정렬까지 맡아 {@code LIMIT} 만큼만 읽는다.
     */
    private JPAQuery<Long> keys(PostCategory category) {
        return queryFactory.select(POST.id)
                .from(POST)
                .where(POST.deletedAt.isNull(), categoryEq(category));
    }

    /**
     * 행 문장 — 이미 확정된 몇 줄에만 작성자와 대표 사진을 붙인다.
     *
     * <p><b>게시글에서 시작한다.</b> {@code post.id IN (…)} 이 기본 키 범위로 조각 크기만큼만
     * 읽고, 작성자는 {@code users} 기본 키로 한 줄씩 붙는다({@code eq_ref}). 회원에서 시작하면
     * 옵티마이저가 통계에 따라 회원을 훑고 게시글을 {@code IN} 으로 거를 수 있다 — 옛 한 문장의
     * 실측에서 실제로 {@code Table scan on u} 로 시작했다. 실행계획 테스트가 인덱스 이름이 아니라
     * 접근 방식(기본 키 범위 + 기본 키 단건 조회)을 고정한다.
     *
     * <p>{@code deleted_at}·카테고리를 다시 걸지 않는다. 키 문장이 같은 스냅샷에서 이미 걸렀고,
     * 여기서 다시 걸면 두 문장 사이에 삭제된 글이 "행은 없는데 {@code hasNext} 는 참" 인 채로
     * 빠지는 대신 스냅샷 전제가 깨졌다는 사실이 가려진다.
     *
     * <p>작성자 조인은 {@code INNER} 다. 탈퇴 회원의 행은 남고 개인정보만 지워지므로(ADR-0040)
     * 글이 사라지지 않는다 — {@code PostControllerIT} 가 그 성질을 지킨다.
     */
    private JPAQuery<PostListRow> rows(List<Long> ids) {
        return queryFactory.select(projection())
                .from(POST)
                .join(USER).on(USER.id.eq(POST.userId))
                .where(POST.id.in(ids));
    }

    /**
     * 행 문장의 프로젝션. 생성자 인자 순서가 {@link PostListView} 와 어긋나면
     * <b>애플리케이션 기동 시</b>가 아니라 첫 조회에서 {@code ExpressionException} 으로 드러난다 —
     * 그래도 옛 컬럼 인덱스 상수처럼 엉뚱한 값이 조용히 들어가는 일은 없다.
     *
     * <p>카운터는 스키마가 {@code INT UNSIGNED} 라 {@code Integer} 로 읽고 도메인 계약인
     * {@code long} 으로 넓힌다. 이 {@code longValue()} 는 SQL 에 {@code cast} 로 내려가므로
     * <b>정렬·커서에는 쓰지 않는다</b>. 인기 점수는 커서에 실리는 값이라 <b>넓히지도 않는다</b> —
     * 컬럼 매핑({@code Integer})과 같은 타입으로 커서를 만들어야 다음 요청의 튜플 비교에서 형이 어긋나지
     * 않는다 ({@link PostListCursor} 가 같은 타입으로 되돌린다).
     *
     * <p>랭킹은 <b>없을 수 있다</b> — 가입 직후 다음 배치까지, 그리고 탈퇴 회원이 그렇다.
     * {@code Integer} 그대로 넘겨 미산정을 {@code null} 로 올려보내고 화면이 비운다. 0 으로 접으면
     * "아직 모른다" 가 "0위" 라는 거짓이 된다.
     */
    private static Expression<PostListRow> projection() {
        return Projections.constructor(PostListRow.class,
                listViewProjection(),
                POST.popularityScore);
    }

    /** 목록의 기존 필드를 인기 카드에서도 같은 의미로 유지한다. */
    private static Expression<PostListView> listViewProjection() {
        return Projections.constructor(PostListView.class,
                POST.id,
                POST.type,
                POST.category,
                POST.title,
                POST.description,
                POST.voteCount.longValue(),
                POST.commentCount.longValue(),
                POST.createdAt,
                thumbnailUrl(),
                POST.userId,
                authorNickname(),
                USER.ranking);
    }

    /** 정렬 키. 작성 시각이거나 게시글의 인기 점수(생성 컬럼)다. */
    private static ComparableExpressionBase<?> sortKey(PostSort sort) {
        return switch (sort) {
            case LATEST -> POST.createdAt;
            case POPULAR -> POST.popularityScore;
        };
    }

    /**
     * 정렬 {@code (정렬키 DESC, id DESC)}. 두 문장이 <b>같은 배열</b>을 쓴다 — 키 문장의 순서를
     * 행 문장이 그대로 재현해야 커서와 응답 순서가 어긋나지 않는다.
     *
     * <p>{@code id} 의 방향이 정렬키와 같아야 인덱스 {@code (…, 정렬키 DESC, id DESC)} 가 정렬을
     * 통째로 맡는다. 한쪽만 뒤집으면 filesort 로 떨어진다 — 실행계획 테스트가 그것을 잡는다.
     */
    private static OrderSpecifier<?>[] order(ComparableExpressionBase<?> sortKey) {
        return new OrderSpecifier<?>[] {sortKey.desc(), POST.id.desc()};
    }

    /** 카테고리 필터. 없으면 {@code null} 을 돌려 조건에서 빠진다 — {@code where} 는 null 을 무시한다. */
    private static BooleanExpression categoryEq(PostCategory category) {
        return category == null ? null : POST.category.eq(category);
    }

    /**
     * keyset 조건. 커서가 없으면 {@code null} 을 돌려 조건에서 빠진다.
     *
     * <p><b>행 값 비교</b> {@code (정렬키, id) < (?, ?)} 다. 정렬키 하나로 자르면 같은 값을 가진
     * 행이 조각 경계에서 사라진다 — 초 단위로 끊는 {@code Clock} 때문에 같은 시각은 실제로 생기고,
     * 인기 점수는 작은 정수라 동률이 흔하다.
     *
     * <p>템플릿인 이유는 QueryDSL 에 튜플 비교 API 가 없어서다. 커서 값은 {@code Expressions.constant}
     * 라 리터럴이 아니라 파라미터로 바인딩된다. Hibernate 가 파라미터를 좌변 컬럼 타입으로 강제하므로
     * 커서 값은 컬럼 매핑과 같은 타입이어야 한다 — 인기 점수가 {@code Integer} 인 이유다.
     * 범위를 벗어난 값은 {@link PostListCursor} 가 400 으로 거른다.
     */
    private static BooleanExpression after(ComparableExpressionBase<?> sortKey, PostListCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return Expressions.booleanTemplate("({0}, {1}) < ({2}, {3})",
                sortKey, POST.id, Expressions.constant(cursor.sortValue()), Expressions.constant(cursor.id()));
    }

    /**
     * 작성자 표시명 — 닉네임, 없으면 이름, 둘 다 없으면 {@value #UNKNOWN_AUTHOR}.
     *
     * <p>탈퇴는 닉네임과 이름을 빈 값으로 파기한다(ADR-0040). 그 폴백이 {@code JOIN users} 위에
     * 얹혀 있어 <b>행이 남아 있을 때만</b> 동작한다. 빈 문자열과 폴백 문자열은 리터럴이 아니라 파라미터로
     * 바인딩된다 — 이 저장소에는 SQL 로 이어붙이는 문자열이 없다.
     */
    private static Expression<String> authorNickname() {
        return USER.nickname.nullif("")
                .coalesce(USER.name.nullif(""), Expressions.constant(UNKNOWN_AUTHOR));
    }

    /**
     * 대표 사진 1장 (§4.2) — {@code ActivityQuerydslRepository} 와 같은 정의다.
     *
     * <p>스칼라 서브쿼리인 이유는 찬반 상품이 사진을 최대 3장 갖기 때문이다(R-03).
     * 그냥 조인하면 게시글 한 줄이 사진 수만큼 불어나 조각 크기가 어긋난다.
     * 옛 SQL 의 {@code ORDER BY ir.id ASC LIMIT 1} 을 {@code MIN(id)} 로 옮겼다 —
     * "가장 처음 등록한 사진" 이라는 뜻이 같고, 서브쿼리 안의 {@code LIMIT} 은 JPQL 에 없다.
     *
     * <p>행 문장에만 붙으므로 조각 크기(최대 50)만큼만 돈다.
     * 상관 조건 {@code pp.post_id = p.id} 의 {@code p} 는 행 문장의 루트 {@code post} 다.
     */
    private static Expression<String> thumbnailUrl() {
        return JPAExpressions.select(RESOURCE.accessUrl)
                .from(RESOURCE)
                .where(RESOURCE.id.eq(
                        JPAExpressions.select(CANDIDATE.id.min())
                                .from(PRODUCT)
                                .join(CANDIDATE).on(CANDIDATE.container.id.eq(PRODUCT.itemContainerId))
                                .where(PRODUCT.post.id.eq(POST.id),
                                        PRODUCT.displayOrder.eq(REPRESENTATIVE_PRODUCT))));
    }
}
