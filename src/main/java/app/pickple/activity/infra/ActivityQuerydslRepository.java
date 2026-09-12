package app.pickple.activity.infra;

import app.pickple.activity.domain.ActivityQueryStore.ActivityPostOption;
import app.pickple.activity.domain.ActivityQueryStore.ActivityPostProduct;
import app.pickple.activity.domain.ActivityQueryStore.ActivityPostView;
import app.pickple.activity.domain.ActivityQueryStore.ActivitySummary;
import app.pickple.activity.domain.ActivitySort;
import app.pickple.activity.domain.ActivityType;
import app.pickple.auth.infra.QUserEntity;
import app.pickple.comment.infra.QCommentEntity;
import app.pickple.comment.infra.QOnePickEntity;
import app.pickple.comment.infra.QPostCommenterEntity;
import app.pickple.item.infra.QItemResourceEntity;
import app.pickple.post.domain.PostType;
import app.pickple.post.infra.QPostEntity;
import app.pickple.post.infra.QPostOptionEntity;
import app.pickple.post.infra.QPostProductEntity;
import app.pickple.vote.infra.QVoteEntity;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.DateTimePath;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 내 활동 목록을 <b>두 문장</b>으로 읽는다 — 키를 확정하는 문장과 행을 조립하는 문장 (ADR-0043).
 * <b>유형마다 문장 수가 갈린다.</b> 투표 활동은 넷 — 카드가 선택지별 득표율과 내 선택을 그리므로
 * ({@link #attachVoteDetail} #157) 선택지와 상품을 조각 전체에 한 문장씩 더 붙인다.
 * 댓글 활동은 셋 — 카드가 내 대표 댓글과 그 원픽 수를 그리므로
 * ({@link #attachCommentDetail} #158) 한 문장을 더 붙인다. 내 글은 둘 그대로다.
 * 늘어난 문장은 모두 행 수에 비례하지 않는다 — 조각 크기가 10 이든 50 이든 문장 수는 같다.
 *
 * <p><b>댓글 활동은 모집단도 다르다.</b> 살아있는 내 댓글이 하나도 없는 글은 목록에 나오지
 * 않는다({@link #hasLiveComment} #158) — 같은 조건을 {@link #summarize} 도 쓴다.
 *
 * <p><b>먼저 자르고 나중에 붙인다.</b> 조각에 들어갈 게시글 id 를 활동 테이블의 인덱스로
 * 먼저 확정한 뒤({@code ORDER BY … LIMIT}), 그 몇 줄에만 게시글과 대표 사진을 붙인다.
 * 옛 네이티브 SQL 은 이것을 파생 테이블 {@code FROM (SELECT … LIMIT) page} 한 문장으로
 * 했는데, QueryDSL-JPA 는 FROM 절 서브쿼리를 지원하지 않는다. 그래서 안쪽 질의가
 * {@link #keys 키 문장}이 되고 바깥 질의가 {@link #rows 행 문장}이 된다.
 * 왕복이 하나 늘지만 <b>인덱스가 정렬을 맡는 구간은 키 문장 하나에 그대로 남는다</b> —
 * 판정 기준은 왕복 횟수가 아니라 실행계획이다.
 *
 * <p><b>두 문장은 한 스냅샷을 본다.</b> 호출자가 트랜잭션을 열어야 한다 —
 * InnoDB 의 REPEATABLE READ 에서 첫 읽기가 스냅샷을 잡으므로, 키 문장과 행 문장 사이에
 * 게시글이 지워지거나 인기 점수가 바뀌어도 행 문장이 키 문장과 다른 세상을 보지 않는다.
 * 옛 한 문장에는 없던 전제라 {@link #findSlice} 가 진입 시점에 확인한다.
 *
 * <p><b>안쪽 질의는 활동 테이블에서 시작한다</b> (ADR-0036). {@code post} 에서 시작해
 * {@code EXISTS} 로 좁히면 옵티마이저가 게시글 전체를 훑는다 — 내 활동은 전체 게시글의
 * 극히 일부라 방향이 결정적이다. 세 유형이 갈리는 곳은 {@code FROM} 과 "활동 시각" 뿐이고,
 * 그 분기를 {@code switch} 가 <b>문자열이 아니라 질의 객체로</b> 조립한다. 옛 코드는 SQL 본문을
 * {@code formatted} 로 이어붙였고 "enum 만 들어오므로 안전하다" 는 방어가 호출자 규약에
 * 의존했다 — 이제 그 방어는 타입에 있다.
 *
 * <p><b>왜 더는 네이티브 SQL 이 아닌가</b>
 * <ul>
 *   <li>{@code post.popularity_score} 를 읽기 전용으로 매핑했다(ADR-0041). JPQL 이 정렬·커서에
 *       이 컬럼을 쓸 수 있다.</li>
 *   <li>keyset 의 행 값 비교 {@code (a, b) < (?, ?)} 는 Hibernate 7 HQL 이 튜플 비교로
 *       지원한다. 동률에서 행이 새지 않는 성질이 그대로다.</li>
 *   <li>결과를 {@code Object} 배열 로 받아 컬럼 인덱스 상수로 꺼내던 구조가 사라진다 —
 *       그 인덱스는 SELECT 절이 바뀌면 조용히 밀리고 런타임에야 깨졌다 (이슈 #130).</li>
 * </ul>
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.activity.domain.ActivityQueryStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class ActivityQuerydslRepository {

    private static final QPostEntity POST = QPostEntity.postEntity;
    private static final QVoteEntity VOTE = QVoteEntity.voteEntity;
    private static final QPostCommenterEntity COMMENTER = QPostCommenterEntity.postCommenterEntity;
    private static final QCommentEntity COMMENT = QCommentEntity.commentEntity;
    private static final QOnePickEntity PICK = QOnePickEntity.onePickEntity;
    /** 모집단 조건 안쪽의 두 번째 {@code comment}. 바깥 별칭과 겹치면 안 된다. */
    private static final QCommentEntity ALIVE = new QCommentEntity("alive");
    private static final QUserEntity USER = QUserEntity.userEntity;
    private static final QPostProductEntity PRODUCT = QPostProductEntity.postProductEntity;
    private static final QPostOptionEntity OPTION = QPostOptionEntity.postOptionEntity;
    private static final QItemResourceEntity RESOURCE = QItemResourceEntity.itemResourceEntity;
    /** 대표 사진 서브쿼리 안쪽의 두 번째 {@code item_resource}. 바깥 별칭과 겹치면 안 된다. */
    private static final QItemResourceEntity CANDIDATE = new QItemResourceEntity("candidate");

    /** 찬반은 상품이 하나뿐이고 A/B 는 A 상품이라 대표 사진은 둘 다 {@code display_order = 1} 이다 (§9.2). */
    private static final byte REPRESENTATIVE_PRODUCT = 1;

    private final JPAQueryFactory queryFactory;

    /**
     * 조회 결과 한 행. 화면용 뷰에 <b>커서에만 쓰는</b> 인기 점수를 곁들인다.
     *
     * <p>{@link ActivityPostView} 에 점수를 넣지 않는 이유는 그 타입이 화면 계약이기 때문이다 —
     * 인기순 커서를 만들려고 응답에 없는 값을 도메인 뷰에 끼우면 어느 화면이 무엇을
     * 쓰는지 타입이 말해주지 못한다. 점수는 이 패키지를 벗어나지 않는다.
     *
     * <p>{@code public} 인 이유는 QueryDSL 이 생성자를 {@code getConstructors()} 로 찾기 때문이다 —
     * 레코드의 정규 생성자는 레코드와 접근 수준이 같아, package-private 이면 런타임에
     * "No constructor found" 가 난다. 감싸는 클래스가 package-private 이라 바깥에는 안 보인다.
     */
    public record ActivityRow(ActivityPostView view, Long popularityScore) {
    }

    /**
     * 한 조각. {@code hasNext} 는 <b>키 문장</b>이 정한다 — 행 문장의 행 수로 판정하면
     * 두 문장 사이에 게시글이 지워졌을 때 "다음이 있다" 는 사실이 조용히 사라진다.
     */
    record ActivitySlice(List<ActivityRow> rows, boolean hasNext) {
    }

    /**
     * 조각을 읽는다. 다음 조각의 존재를 알기 위해 키 문장이 <b>{@code size + 1} 건</b>을 읽고,
     * 넘치는 한 건은 행 문장에 넘기지 않는다 — 대표 사진 서브쿼리를 한 번 아낀다.
     */
    ActivitySlice findSlice(
            Long userId, ActivityType type, ActivitySort sort, ActivityListCursor cursor, int size) {

        requireSnapshot();

        ComparableExpressionBase<?> sortKey = sortKey(type, sort);
        NumberPath<Long> postId = postIdOf(type);

        List<Long> ids = keys(type, userId)
                .where(after(sortKey, postId, sort, cursor))
                .orderBy(order(sortKey, sort), order(postId, sort))
                .limit(size + 1L)
                .fetch();
        boolean hasNext = ids.size() > size;
        List<Long> page = hasNext ? ids.subList(0, size) : ids;
        if (page.isEmpty()) {
            return new ActivitySlice(List.of(), false);
        }
        List<ActivityRow> rows = rows(type, userId, page)
                .orderBy(order(sortKey, sort), order(postId, sort))
                .fetch();
        // 선택지·상품은 투표 활동 카드만, 대표 댓글은 댓글 활동 카드만 그린다 (#157 · #158).
        // 유형마다 붙이는 것이 달라 문장 수도 갈린다 — 쓰지 않는 값을 위해 문장을 늘리지 않는다.
        return new ActivitySlice(switch (type) {
            case VOTE -> attachVoteDetail(rows);
            case COMMENT -> attachCommentDetail(rows, userId);
            case POST -> rows;
        }, hasNext);
    }

    /**
     * 최근에 올린 투표 게시글 (§7.4). 커서가 없는 고정 개수 목록이다.
     *
     * <p>경계는 <b>반열린 구간</b> {@code created_at > since} 이다. 기준 시각을
     * 서비스가 정해 넘기므로 이 자리는 비교만 한다 — {@code NOW()} 를 쓰면
     * DB 세션 타임존이 하루를 정해 애플리케이션이 보는 시각과 갈린다(SPEC §5.1 과 같은 이유).
     *
     * <p>여기도 두 문장이다. 옛 네이티브 SQL 도 파생 테이블로 먼저 자른 뒤 대표 사진을 붙였으므로
     * 이 분할은 그 구조를 옮긴 것이지 새 비용이 아니다. JPQL 한 문장으로 쓰려면 대표 사진
     * 서브쿼리를 정렬·LIMIT 과 같은 문장에 두어야 하는데, 이 정렬은 {@code Sort} 가 남는 경로라
     * (ADR-0036 "POST 최신순") 그 서브쿼리가 잘리기 전의 행 전체에 대해 돌 위험이 있다.
     * 두 문장이므로 {@link #findSlice} 와 같은 스냅샷 전제가 있다.
     */
    List<ActivityPostView> findRecentVotePosts(Long userId, LocalDateTime since, int limit) {
        requireSnapshot();

        List<Long> ids = keys(ActivityType.POST, userId)
                .where(POST.type.ne(PostType.GENERAL), POST.createdAt.gt(since))
                .orderBy(POST.createdAt.desc(), POST.id.desc())
                .limit(limit)
                .fetch();
        if (ids.isEmpty()) {
            return List.of();
        }
        return rows(ActivityType.POST, userId, ids)
                .orderBy(POST.createdAt.desc(), POST.id.desc())
                .fetch()
                .stream()
                .map(ActivityRow::view)
                .toList();
    }

    /**
     * 활동 갯수 요약 (§7.2). 세 값을 <b>한 문장</b>으로 읽는다.
     *
     * <p>세 번 나눠 물으면 왕복이 셋이 되는데, 세 값은 언제나 함께 쓰이고
     * 각각이 인덱스 한 범위를 세는 가벼운 질의라 합치는 편이 낫다.
     *
     * <p><b>회원 행에 얹는다.</b> JPQL 은 {@code FROM} 없는 문장을 허용하지 않으므로
     * 스칼라 서브쿼리 셋을 붙일 한 행이 필요하다. 회원의 기본 키 조회는 {@code const} 로
     * 끝나 비용이 없고, "요약은 회원의 것" 이라는 뜻과도 맞는다. 회원 행이 없으면
     * 세 값 모두 0 이다 — 인증을 지난 요청이라 실제로는 닿지 않는 경로다.
     *
     * <p><b>세 값 모두 {@code DISTINCT} 가 없다.</b> 스키마가 이미 인원으로 세고 있다 —
     * {@code vote} 는 {@code UNIQUE(post_id, user_id)} 라 재투표가 UPDATE 이고(R-22),
     * {@code post_commenter} 는 {@code UNIQUE(post_id, user_id)} 라 게시글당 한 행이다(R-25).
     * 여기서 다시 세면 정본이 둘이 되어 어긋날 자리를 만든다.
     *
     * <p><b>삭제된 게시글은 세 값 모두에서 뺀다.</b> 목록의 키 문장({@link #keys})이 삭제된 글을 빼므로
     * 요약이 그것을 세면 "내가 투표한 글 12" 아래 카드 11장이 뜬다. 사용자가 보는 것은 카드이지 행이 아니다.
     * 게시글 삭제(#31)가 생기기 전에는 닿지 않던 불일치다 (ADR-0047 결정 5).
     */
    ActivitySummary summarize(Long userId) {
        ActivitySummary summary = queryFactory
                .select(Projections.constructor(ActivitySummary.class,
                        JPAExpressions.select(VOTE.count()).from(VOTE)
                                .join(POST).on(POST.id.eq(VOTE.postId), POST.deletedAt.isNull())
                                .where(VOTE.userId.eq(userId)),
                        JPAExpressions.select(COMMENTER.count()).from(COMMENTER)
                                .join(POST).on(POST.id.eq(COMMENTER.postId), POST.deletedAt.isNull())
                                .where(COMMENTER.userId.eq(userId),
                                        hasLiveComment(COMMENTER.postId, userId)),
                        JPAExpressions.select(POST.count()).from(POST)
                                .where(POST.userId.eq(userId), POST.deletedAt.isNull())))
                .from(USER)
                .where(USER.id.eq(userId))
                .fetchOne();
        return Optional.ofNullable(summary).orElseGet(() -> new ActivitySummary(0, 0, 0));
    }

    /**
     * <b>살아있는 내 댓글이 하나라도 있는가</b> (#158). 댓글 활동의 모집단을 정하는 조건이다.
     *
     * <p>{@code post_commenter} 는 <b>참여 원장</b>이라 댓글을 지워도 행이 사라지지 않는다 —
     * {@code PostCommenterStore} 에 삭제 경로가 없고({@code recordIfFirst}·{@code countByPost} 뿐),
     * {@code CommentService.delete} 도 건수만 줄이고 인원은 그대로 둔다. 그 append-only 성질이
     * 일부러 남긴 것이라 지울 수 없다 — {@code created_at} 이 "이 사람의 <b>첫</b> 댓글 시각" 이고
     * 그 값이 곧 정렬 키이자 커서다(R-32 · ADR-0036). 행을 지우면 커서가 기준을 잃는다.
     *
     * <p>그래서 "내 댓글을 전부 지운 글은 안 보인다" 는 <b>원장을 고치는 것이 아니라
     * 조건을 더하는 것</b>으로 구현한다. {@code EXISTS} 라 댓글 수만큼 행이 불어나지 않고,
     * {@code idx_comment_post (post_id, deleted_at, created_at, id)} 가 세 열을 그대로 받는다.
     *
     * <p><b>목록과 요약이 같은 조건을 쓴다.</b> 한쪽에만 걸면 칩 숫자와 카드 장수가 어긋난다 —
     * 사용자가 보는 것은 행이 아니라 카드다(요약이 삭제된 게시글을 빼는 것과 같은 이유).
     */
    private static BooleanExpression hasLiveComment(NumberPath<Long> postId, Long userId) {
        return JPAExpressions.selectOne()
                .from(ALIVE)
                .where(ALIVE.postId.eq(postId), ALIVE.userId.eq(userId), ALIVE.deletedAt.isNull())
                .exists();
    }

    /**
     * 두 문장이 한 스냅샷을 보려면 트랜잭션 안이어야 한다 (ADR-0043). 호출자 {@code JpaActivityQueryStore}
     * 의 {@code @Transactional(readOnly = true)} 가 그 트랜잭션이다 — 누가 애노테이션을 지우면
     * 조용히 어긋나는 대신 여기서 즉시 깨진다.
     */
    private static void requireSnapshot() {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "키 문장과 행 문장이 같은 스냅샷을 보려면 트랜잭션 안이어야 한다");
    }

    /**
     * 키 문장 — 조각에 들어갈 게시글 id 를 확정한다. <b>활동 테이블에서 시작한다.</b>
     *
     * <p>세 유형이 갈리는 곳은 {@code FROM} 뿐이다. {@code POST} 만 활동 테이블이 게시글
     * 자신이라 조인이 없다. <b>삭제된 게시글은 빼낸다</b> — 내가 투표한 글을 작성자가
     * 지웠다면 그 카드는 탭했을 때 갈 곳이 없다.
     *
     * <p>{@code SELECT} 는 활동 테이블 쪽의 {@code post_id} 다. 값은 {@code p.id} 와 같지만
     * 정렬 튜플과 같은 테이블에서 읽어야 인덱스 하나로 조회가 끝난다 (커버링).
     * 같은 게시글이 두 번 나올 수 없다 — {@code uk_vote_post_user}·{@code uk_commenter_post_user}
     * 가 한 사람의 활동을 게시글당 한 행으로 묶고, {@code POST} 는 기본 키다. 행 문장의
     * {@code IN} 이 안전한 근거가 이것이다.
     */
    private JPAQuery<Long> keys(ActivityType type, Long userId) {
        return switch (type) {
            case VOTE -> queryFactory.select(VOTE.postId)
                    .from(VOTE)
                    .join(POST).on(POST.id.eq(VOTE.postId), POST.deletedAt.isNull())
                    .where(VOTE.userId.eq(userId));
            case COMMENT -> queryFactory.select(COMMENTER.postId)
                    .from(COMMENTER)
                    .join(POST).on(POST.id.eq(COMMENTER.postId), POST.deletedAt.isNull())
                    .where(COMMENTER.userId.eq(userId), hasLiveComment(COMMENTER.postId, userId));
            case POST -> queryFactory.select(POST.id)
                    .from(POST)
                    .where(POST.userId.eq(userId), POST.deletedAt.isNull());
        };
    }

    /**
     * 행 문장 — 이미 확정된 몇 줄에만 게시글 본문·활동 시각·대표 사진을 붙인다.
     *
     * <p>키 문장과 반대로 <b>게시글에서 시작한다.</b> {@code post.id IN (…)} 이 기본 키 범위로
     * 조각 크기만큼만 읽고, 활동 테이블은 {@code (post_id, user_id)} 유니크 키로 한 줄씩
     * 붙는다({@code eq_ref}). 활동 테이블에서 시작하면 옵티마이저가 통계에 따라
     * {@code idx_*_user_activity} 를 골라 내 활동 전체를 훑고 {@code IN} 으로 거를 수 있다.
     * {@code FROM} 이 조인 순서를 강제하지는 않지만 기본 키 범위가 가장 싼 진입점이라는 근거를 주고,
     * 실행계획 테스트가 그 접근 방식(기본 키 범위 + 유니크 키 단건 조회)을 고정한다.
     *
     * <p>조인 조건의 {@code user_id} 는 장식이 아니다. 빠지면 같은 글에 활동한 <b>다른 사람의</b>
     * 행이 붙어 게시글 한 줄이 그 인원만큼 불어난다. {@code POST} 는 조인이 없어 id 만으로 충분하지만
     * 세 갈래가 같은 조건("내 활동으로 좁힌 게시글")을 말하도록 같은 필터를 둔다 — 기본 키 범위 뒤의
     * 필터라 비용이 없다.
     */
    private JPAQuery<ActivityRow> rows(ActivityType type, Long userId, List<Long> ids) {
        return switch (type) {
            case VOTE -> queryFactory.select(projection(VOTE.createdAt, VOTE.postOptionId))
                    .from(POST)
                    .join(VOTE).on(VOTE.postId.eq(POST.id), VOTE.userId.eq(userId))
                    .where(POST.id.in(ids));
            case COMMENT -> queryFactory.select(projection(COMMENTER.createdAt))
                    .from(POST)
                    .join(COMMENTER).on(COMMENTER.postId.eq(POST.id), COMMENTER.userId.eq(userId))
                    .where(POST.id.in(ids));
            case POST -> queryFactory.select(projection(POST.createdAt))
                    .from(POST)
                    .where(POST.id.in(ids), POST.userId.eq(userId));
        };
    }

    /**
     * 행 문장의 프로젝션. 생성자 인자 순서가 {@link ActivityPostView} 와 어긋나면
     * <b>애플리케이션 기동 시</b>가 아니라 첫 조회에서 {@code ExpressionException} 으로 드러난다 —
     * 그래도 옛 컬럼 인덱스 상수처럼 엉뚱한 값이 조용히 들어가는 일은 없다.
     *
     * <p>카운터와 인기 점수는 스키마가 {@code INT UNSIGNED} 라 {@code Integer} 로 읽고
     * 도메인 계약인 {@code long} 으로 넓힌다. 이 {@code longValue()} 는 SQL 에 {@code cast} 로
     * 내려가므로 <b>정렬·커서에는 쓰지 않는다</b> — 거기서는 원래 경로
     * {@link QPostEntity#popularityScore} 를 써야 인덱스가 산다.
     */
    private static Expression<ActivityRow> projection(DateTimePath<LocalDateTime> activityAt) {
        return projection(activityAt, Expressions.nullExpression(Long.class));
    }

    /**
     * 행 문장의 프로젝션에 <b>내가 고른 선택지</b>를 싣는다. 투표 활동 경로만 쓴다.
     *
     * <p>새 조인이 없다 — {@link #rows} 의 VOTE 분기가 이미 {@code (post_id, user_id)} 유니크 키로
     * {@code vote} 를 한 줄 붙이고 있으므로 {@code post_option_id} 는 그 한 줄에서 한 컬럼 더 읽는
     * 것이다. 재투표가 UPDATE 라(R-22) 그 한 줄이 곧 <b>최신</b> 선택이고, 그래서 정렬이나
     * {@code MAX} 가 필요 없다 — "최신" 을 스키마의 유니크 키가 이미 보장한다.
     */
    private static Expression<ActivityRow> projection(
            DateTimePath<LocalDateTime> activityAt, Expression<Long> selectedOptionId) {
        return Projections.constructor(ActivityRow.class,
                Projections.constructor(ActivityPostView.class,
                        POST.id,
                        POST.type,
                        POST.category,
                        POST.title,
                        POST.description,
                        POST.voteCount.longValue(),
                        POST.commentCount.longValue(),
                        POST.createdAt,
                        thumbnailUrl(),
                        activityAt,
                        selectedOptionId,
                        Expressions.constant(List.<ActivityPostProduct>of()),
                        Expressions.constant(List.<ActivityPostOption>of()),
                        // 대표 댓글은 행 문장이 읽지 않는다 — 조각이 확정된 뒤 배치 한 문장이
                        // 붙인다 (#158 attachCommentDetail). 선택지·상품이 빈 목록으로 자리만
                        // 잡아 두는 것과 같은 이유다. 이 자리를 비우면 레코드 인자 수가 어긋나
                        // 컴파일이 아니라 첫 조회에서 ExpressionException 으로 드러난다.
                        Expressions.nullExpression(String.class),
                        Expressions.constant(0L)),
                POST.popularityScore.longValue());
    }

    /**
     * 선택지와 상품을 <b>조각 전체에 한 문장씩</b> 붙인다 (#157). 투표 활동 경로만 쓴다.
     *
     * <p>행 문장에 조인하지 않는 이유는 <b>팬아웃</b>이다. 선택지는 게시글당 둘(R-04), 상품은
     * 최대 둘(R-02)이라 한 문장에 둘 다 조인하면 게시글 한 줄이 <b>서로를 곱해</b> 최대 넉 줄이
     * 되고 조각 크기가 어긋난다 — 상세가 세 문장으로 나눈 것과 같은 이유다
     * ({@code PostDetailQuerydslRepository}). 그렇다고 게시글마다 따로 물으면 그것이 N+1 이다.
     *
     * <p>그래서 <b>이미 확정된 id 전부를 {@code IN} 하나로</b> 읽고 메모리에서 게시글별로 묶는다.
     * 조각 크기가 10 이든 50 이든 문장은 둘이다 — 늘어나는 것은 {@code IN} 의 인자 수뿐이고
     * 그 접근 경로는 {@code (post_id, display_order)} 유니크 키 범위다.
     *
     * <p>투표 게시글이 하나도 없는 조각이면 두 문장을 건너뛴다. 일반 게시글은 선택지도 상품도
     * 없으므로(R-02·R-04) 물어봐야 빈 결과이고, 상세가 같은 경우에 두 문장을 건너뛰는 것과 같다.
     */
    private List<ActivityRow> attachVoteDetail(List<ActivityRow> rows) {
        List<Long> votingPostIds = rows.stream()
                .map(ActivityRow::view)
                .filter(view -> view.type().hasVoting())
                .map(ActivityPostView::id)
                .toList();
        if (votingPostIds.isEmpty()) {
            return rows;
        }
        Map<Long, List<ActivityPostProduct>> products = products(votingPostIds);
        Map<Long, List<ActivityPostOption>> options = options(votingPostIds);
        return rows.stream()
                .map(row -> new ActivityRow(
                        row.view().withVoteDetail(
                                products.getOrDefault(row.view().id(), List.of()),
                                options.getOrDefault(row.view().id(), List.of())),
                        row.popularityScore()))
                .toList();
    }

    /**
     * 대표 댓글과 그 원픽 수를 <b>조각 전체에 한 문장으로</b> 붙인다 (#158). 댓글 활동 경로만 쓴다.
     *
     * <p>행 문장에 조인하지 않는 이유는 {@link #attachVoteDetail} 과 같은 <b>팬아웃</b>이다.
     * 한 글에 내 댓글이 여럿일 수 있어(R-25) 조인하면 게시글 한 줄이 댓글 수만큼 불어나
     * 조각 크기가 어긋난다. 그렇다고 게시글마다 따로 물으면 그것이 N+1 이다.
     *
     * <p>조각이 비어 있을 수 없다 — 키 문장이 <b>살아있는 내 댓글이 있는 글만</b> 확정하므로
     * ({@link #hasLiveComment}) 모든 행에 대표 댓글이 있다. {@code getOrDefault} 는 그 전제가
     * 깨졌을 때 예외 대신 빈 카드를 주는 방어일 뿐이다.
     */
    private List<ActivityRow> attachCommentDetail(List<ActivityRow> rows, Long userId) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<Long> postIds = rows.stream().map(row -> row.view().id()).toList();
        Map<Long, Representative> representatives = representatives(postIds, userId);
        return rows.stream()
                .map(row -> {
                    Representative representative = representatives.get(row.view().id());
                    return representative == null ? row : new ActivityRow(
                            row.view().withCommentDetail(
                                    representative.content(), representative.onePickCount()),
                            row.popularityScore());
                })
                .toList();
    }

    /**
     * 조각에 든 게시글들에서 <b>내가 쓴 살아있는 댓글</b>과 각각이 받은 원픽 수.
     *
     * <p>대표는 <b>원픽이 가장 많은 한 건, 동률이면 최신</b>이다(기획 확정 2026-09-12).
     * 그 선별을 SQL 이 아니라 메모리에서 하는 이유는 <b>문장 수</b>다 — 게시글별 1위를 SQL 로
     * 고르려면 윈도 함수를 쓰거나 게시글마다 상관 서브쿼리를 돌려야 하는데, 후자는 문장 수에
     * 잡히지 않는 N+1 이다(이 파일과 {@code ActivityControllerIT} 가 함께 경고하는 바로 그 형태).
     * 한 사람이 한 글에 다는 댓글 수는 사람이 손으로 만드는 값이라 상한이 낮아, 조각 전체의
     * 내 댓글을 한 문장에 읽고 자바가 고르는 편이 단순하고 예측 가능하다.
     *
     * <p><b>원픽 수는 대표 댓글의 것이다.</b> 합계가 아니라 그 한 건이라, 집계를 댓글 단위로
     * 묶는다({@code GROUP BY c.id}) — {@code idx_pick_comment (comment_id)} 가 받는다.
     * {@code LEFT JOIN} 이라 원픽이 없는 댓글도 0 으로 남는다.
     *
     * <p>정렬을 {@code post_id} 까지 넓히지 않는 이유는 {@link #products} 와 같다 —
     * 묶는 주체가 SQL 이 아니라 애플리케이션이고, 대표 선별도 자바가 한다.
     */
    private Map<Long, Representative> representatives(List<Long> postIds, Long userId) {
        return queryFactory.select(COMMENT.postId,
                        Projections.constructor(Representative.class,
                                COMMENT.content,
                                PICK.count(),
                                COMMENT.createdAt,
                                COMMENT.id))
                .from(COMMENT)
                .leftJoin(PICK).on(PICK.commentId.eq(COMMENT.id))
                .where(COMMENT.postId.in(postIds),
                        COMMENT.userId.eq(userId),
                        COMMENT.deletedAt.isNull())
                .groupBy(COMMENT.postId, COMMENT.id, COMMENT.content, COMMENT.createdAt)
                .fetch()
                .stream()
                .collect(Collectors.toMap(
                        tuple -> tuple.get(COMMENT.postId),
                        tuple -> tuple.get(1, Representative.class),
                        Representative::better));
    }

    /**
     * 대표 후보 한 건. <b>원픽 최다, 동률이면 최신</b>이 대표다 (#158).
     *
     * <p>{@code createdAt} 과 {@code id} 는 응답에 나가지 않고 <b>대표를 고르는 데만</b> 쓴다 —
     * {@link ActivityPostView} 에 넣지 않는 이유는 {@link ActivityRow} 가 인기 점수를
     * 따로 들고 다니는 이유와 같다(화면 계약에 없는 값을 뷰에 끼우지 않는다).
     *
     * <p>{@code id} 로 한 번 더 가르는 이유는 {@code datetime(0)} 이다 — 시각이 초 단위라
     * 같은 초에 쓴 두 댓글이 동률이 되는데, 그때 {@code id} 가 크면 나중에 쓴 것이다.
     * 이것이 없으면 대표가 실행마다 갈려 테스트가 확률적으로 깨진다.
     *
     * <p>{@code public} 인 이유는 QueryDSL 이 생성자를 {@code getConstructors()} 로 찾기 때문이다 —
     * {@link ActivityRow} 와 같은 제약이다.
     */
    public record Representative(
            String content, long onePickCount, LocalDateTime createdAt, Long id) {

        /** 둘 중 대표. 원픽이 많은 쪽, 같으면 나중에 쓴 쪽이다. */
        static Representative better(Representative left, Representative right) {
            if (left.onePickCount != right.onePickCount) {
                return left.onePickCount > right.onePickCount ? left : right;
            }
            int byTime = left.createdAt.compareTo(right.createdAt);
            if (byTime != 0) {
                return byTime > 0 ? left : right;
            }
            return left.id > right.id ? left : right;
        }
    }

    /**
     * 조각에 든 게시글들의 상품과 그 대표 사진 1장 (§9.2). 표시 순서대로다 — 찬반은 1개,
     * A/B 는 A·B 둘 (R-02).
     *
     * <p>{@code post_id} 를 함께 읽어 게시글별로 묶는다. 정렬을 {@code post_id} 까지 넓히지 않는
     * 이유는 묶는 주체가 SQL 이 아니라 애플리케이션이고, {@code groupingBy} 가 값의 순서를
     * 유지하므로 {@code display_order} 하나면 게시글 안의 순서가 정해지기 때문이다.
     */
    private Map<Long, List<ActivityPostProduct>> products(List<Long> postIds) {
        return queryFactory.select(PRODUCT.post.id,
                        Projections.constructor(ActivityPostProduct.class,
                                PRODUCT.displayOrder.intValue(),
                                imageUrl()))
                .from(PRODUCT)
                .where(PRODUCT.post.id.in(postIds))
                .orderBy(PRODUCT.displayOrder.asc())
                .fetch()
                .stream()
                .collect(Collectors.groupingBy(
                        tuple -> tuple.get(PRODUCT.post.id),
                        Collectors.mapping(
                                tuple -> tuple.get(1, ActivityPostProduct.class), Collectors.toList())));
    }

    /**
     * 조각에 든 게시글들의 선택지와 득표 수 (§9.2). 표시 순서대로이고 게시글당 정확히 둘이다 (R-04).
     *
     * <p><b>득표율로 바꾸지 않는다.</b> 저장소는 읽은 값을 그대로 올리고 퍼센트 환산은 위층이
     * 한다 — 상세가 세운 분담과 같다(ADR-0046). 여기서 계산하면 같은 글의 게이지에 정본이 둘이 된다.
     */
    private Map<Long, List<ActivityPostOption>> options(List<Long> postIds) {
        return queryFactory.select(OPTION.post.id,
                        Projections.constructor(ActivityPostOption.class,
                                OPTION.id,
                                OPTION.label,
                                OPTION.displayOrder.intValue(),
                                OPTION.voteCount.longValue()))
                .from(OPTION)
                .where(OPTION.post.id.in(postIds))
                .orderBy(OPTION.displayOrder.asc())
                .fetch()
                .stream()
                .collect(Collectors.groupingBy(
                        tuple -> tuple.get(OPTION.post.id),
                        Collectors.mapping(
                                tuple -> tuple.get(1, ActivityPostOption.class), Collectors.toList())));
    }

    /**
     * 상품의 대표 사진 — 가장 먼저 등록된 한 장 (§9.2). {@code PostDetailQuerydslRepository} 와
     * 같은 정의다. 상관 조건의 {@code PRODUCT} 는 {@link #products} 의 루트다.
     */
    private static Expression<String> imageUrl() {
        return JPAExpressions.select(RESOURCE.accessUrl)
                .from(RESOURCE)
                .where(RESOURCE.id.eq(
                        JPAExpressions.select(CANDIDATE.id.min())
                                .from(CANDIDATE)
                                .where(CANDIDATE.container.id.eq(PRODUCT.itemContainerId))));
    }

    /**
     * 활동 시각 (R-32). 투표한 시각·처음 댓글을 단 시각·글을 올린 시각이다.
     *
     * <p>{@code POST} 의 활동 시각이 {@code post.created_at} 인 것은 우연이 아니다 —
     * 내가 올린 글은 활동이 곧 작성이다.
     */
    private static DateTimePath<LocalDateTime> activityAtOf(ActivityType type) {
        return switch (type) {
            case VOTE -> VOTE.createdAt;
            case COMMENT -> COMMENTER.createdAt;
            case POST -> POST.createdAt;
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
    private static NumberPath<Long> postIdOf(ActivityType type) {
        return switch (type) {
            case VOTE -> VOTE.postId;
            case COMMENT -> COMMENTER.postId;
            case POST -> POST.id;
        };
    }

    /** 정렬 키. 활동 시각이거나 게시글의 인기 점수다. */
    private static ComparableExpressionBase<?> sortKey(ActivityType type, ActivitySort sort) {
        return sort.byActivityTime() ? activityAtOf(type) : POST.popularityScore;
    }

    /**
     * keyset 조건. 커서가 없으면 {@code null} 을 돌려 조건에서 빠진다 — {@code where} 는 null 을 무시한다.
     *
     * <p><b>행 값 비교</b> {@code (정렬키, id) < (?, ?)} 다. 정렬키 하나로 자르면 같은 값을 가진
     * 행이 조각 경계에서 사라진다 — 초 단위로 끊는 {@code Clock} 때문에 같은 시각은 실제로 생긴다.
     * 오래된순은 부등호가 뒤집힌다.
     *
     * <p>템플릿인 이유는 QueryDSL 에 튜플 비교 API 가 없어서다. HQL 튜플 비교가 MySQL 에는
     * 옛 네이티브 SQL 과 같은 행 값 비교 {@code (a, b) < (?, ?)} 로 내려가며, 풀어쓴
     * {@code a < ? OR (a = ? AND b < ?)} 로 바뀌지 않는다 — 실행계획 테스트가 그 형태를 고정한다.
     * 커서 값은 {@code Expressions.constant} 라 리터럴이 아니라 파라미터로 바인딩된다.
     */
    private static BooleanExpression after(
            ComparableExpressionBase<?> sortKey, NumberPath<Long> postId, ActivitySort sort, ActivityListCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return Expressions.booleanTemplate(
                sort.ascending() ? "({0}, {1}) > ({2}, {3})" : "({0}, {1}) < ({2}, {3})",
                sortKey, postId, Expressions.constant(cursor.sortValue()), Expressions.constant(cursor.id()));
    }

    /** 정렬 방향은 keyset 부등호와 함께 뒤집힌다. */
    private static OrderSpecifier<?> order(ComparableExpressionBase<?> key, ActivitySort sort) {
        return sort.ascending() ? key.asc() : key.desc();
    }

    /**
     * 대표 사진 1장 (§9.2) — {@code PostListRepository} 와 같은 정의다.
     *
     * <p>스칼라 서브쿼리인 이유는 찬반 상품이 사진을 최대 3장 갖기 때문이다(R-03).
     * 그냥 조인하면 게시글 한 줄이 사진 수만큼 불어나 조각 크기가 어긋난다.
     * 옛 SQL 의 {@code ORDER BY ir.id ASC LIMIT 1} 을 {@code MIN(id)} 로 옮겼다 —
     * "가장 처음 등록한 사진" 이라는 뜻이 같고, 서브쿼리 안의 {@code LIMIT} 은 JPQL 에 없다.
     *
     * <p>행 문장에만 붙으므로 조각 크기(최대 50)만큼만 돈다. 키 문장에 붙였다면 정렬이
     * 남는 경로(인기순·내 글)에서 내 활동 전체에 대해 돌았을 것이다.
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
