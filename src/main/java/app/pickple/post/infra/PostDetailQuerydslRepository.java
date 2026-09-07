package app.pickple.post.infra;

import app.pickple.auth.infra.QUserEntity;
import app.pickple.grade.domain.Grade;
import app.pickple.item.infra.QItemResourceEntity;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore.PostDetailOption;
import app.pickple.post.domain.PostStore.PostDetailProduct;
import app.pickple.post.domain.PostStore.PostDetailView;
import app.pickple.post.domain.PostType;
import app.pickple.vote.infra.QVoteEntity;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 게시글 상세를 <b>고정된 세 문장</b>으로 읽는다 — 본문+작성자+내 투표 / 상품+대표 사진 / 선택지.
 *
 * <p>목록·활동·랜덤과 달리 조각도 커서 정렬 키도 없다(표시 순서 {@code ORDER BY} 는 남는다). 단건이라
 * {@code FROM (SELECT … LIMIT)} 파생 테이블이 필요 없고, 따라서 ADR-0043·0045 의 두 문장 분할도 필요 없다 —
 * {@code Projections.constructor} 를 그대로 쓴다. 대신 다른 위험이 있다 — <b>팬아웃</b>이다. 상품(최대 2)·선택지(정확히 2)·상품 사진(최대 3)을
 * 한 문장으로 조인하면 게시글 한 줄이 최대 12줄로 불어나 같은 값을 여러 번 읽고, 상품과 선택지는
 * <b>서로를 곱한다</b>(2 × 2). 그래서 셋으로 나눈다:
 *
 * <pre>
 *   1. 게시글 + 작성자 + 내 투표   (한 줄 — 기본 키 · users 기본 키 · uk_vote_post_user)
 *   2. 상품 + 대표 사진             (0~2줄 — uk_product_post_order · 사진은 스칼라 서브쿼리)
 *   3. 선택지                       (0~2줄 — uk_option_post_order)
 * </pre>
 *
 * <p><b>세 문장은 상수다.</b> 유형이 무엇이든, 상품이 하나든 둘이든, 사진이 한 장이든 세 장이든 문장 수가
 * 변하지 않는다 — 그것이 N+1 이 없다는 뜻이다. 일반 게시글은 상품도 선택지도 없으므로(R-02·R-04) 뒤의
 * 두 문장을 건너뛰어 하나다. 애그리거트({@code PostStore.findById})로 읽으면 지연 로딩 컬렉션 둘에
 * 작성자·사진·투표 조회가 더 붙는다.
 *
 * <p><b>세 문장은 한 스냅샷을 본다.</b> 총 투표 인원은 1 에서, 선택지별 득표는 3 에서 읽으므로 그 사이에
 * 표가 들어오면 게이지의 합이 어긋난다. 호출자가 REPEATABLE READ 트랜잭션을 열어야 하며
 * {@link #findDetail} 이 진입 시점에 확인한다 — 목록·랜덤 저장소와 같은 규약이다.
 *
 * <p>첫 판(PR #128)은 이것을 네이티브 SQL 3문장 + {@code Object[]} + 컬럼 인덱스 상수 25개로 짰고,
 * 그 구조 자체가 #130 이 없애려는 대상이라 머지하지 않고 닫았다. 문장의 모양과 수는 그때와 같고
 * 결과를 받는 타입만 바뀌었다 — 생성자 인자 순서가 어긋나면 첫 조회에서 {@code ExpressionException}
 * 으로 드러나고, 옛 인덱스 상수처럼 엉뚱한 값이 조용히 들어가지 않는다.
 *
 * <p>package-private 이다. 바깥은 {@link app.pickple.post.domain.PostStore} 만 본다.
 */
@Repository
@RequiredArgsConstructor
class PostDetailQuerydslRepository {

    private static final QPostEntity POST = QPostEntity.postEntity;
    private static final QUserEntity USER = QUserEntity.userEntity;
    private static final QVoteEntity OWN_VOTE = new QVoteEntity("ownVote");
    private static final QPostProductEntity PRODUCT = QPostProductEntity.postProductEntity;
    private static final QPostOptionEntity OPTION = QPostOptionEntity.postOptionEntity;
    private static final QItemResourceEntity RESOURCE = QItemResourceEntity.itemResourceEntity;
    /** 대표 사진 서브쿼리 안쪽의 두 번째 {@code item_resource}. 바깥 별칭과 겹치면 안 된다. */
    private static final QItemResourceEntity CANDIDATE = new QItemResourceEntity("candidate");

    /** 닉네임도 이름도 비어 있는 작성자 — 탈퇴로 개인정보가 파기된 회원이다 (ADR-0040). 목록과 같은 표기다. */
    private static final String UNKNOWN_AUTHOR = "알 수 없음";
    /** 게스트의 내 투표 조인은 어떤 회원과도 맞지 않는 값으로 건다 — 문장 모양이 로그인과 같다. */
    private static final long GUEST = -1L;

    private final JPAQueryFactory queryFactory;

    /**
     * 본문 문장의 한 줄. 상품·선택지 컬렉션은 다른 문장이 채우므로 여기에는 없다 — {@link PostDetailView}
     * 를 직접 프로젝션할 수 없는 이유다. {@code public} 인 이유는 QueryDSL 이 생성자를
     * {@code getConstructors()} 로 찾기 때문이다 — package-private 이면 첫 조회에서 "No constructor found" 가 난다.
     *
     * @param authorGradeLevel {@code users.highest_grade}. 스키마가 {@code TINYINT} 라 {@code Byte} 로 받고
     *                         {@code Grade.ofLevel} 이 복원한다. 가입 시 1 이 기본이라 비지 않는다 (R-16)
     * @param myOptionId       조회자가 고른 선택지. 게스트·미투표자는 {@code null}
     */
    public record DetailRow(
            Long id, PostType type, PostCategory category, String title, String description,
            long voterCount, long commentCount, LocalDateTime createdAt,
            Long authorId, String authorNickname, String authorProfileImageUrl, Integer authorRanking,
            Byte authorGradeLevel, Long myOptionId) {

        private PostDetailView toView(List<PostDetailProduct> products, List<PostDetailOption> options) {
            return new PostDetailView(id, type, category, title, description, voterCount, commentCount, createdAt,
                    authorId, authorNickname, authorProfileImageUrl, authorRanking,
                    Grade.ofLevel(authorGradeLevel), myOptionId, products, options);
        }
    }

    /**
     * 상세 한 건. 없거나 삭제된 글이면 빈 값이다.
     *
     * <p>본문이 없으면 <b>상품·선택지를 조회하지 않고</b> 바로 빈 값이다 — 어차피 쓰지 않을 두 문장을
     * 아끼고, 없는 게시글에 상품이 딸려 나오는 일도 막는다. 일반 게시글도 같은 이유로 두 문장을 건너뛴다.
     *
     * @param viewerId 조회하는 사람. {@code null} 이면 게스트다 — 투표 이력을 가질 수 없으므로(R-11) 언제나 미투표다
     */
    Optional<PostDetailView> findDetail(Long id, Long viewerId) {
        requireSnapshot();

        DetailRow row = queryFactory.select(detail())
                .from(POST)
                .join(USER).on(USER.id.eq(POST.userId))
                .leftJoin(OWN_VOTE).on(OWN_VOTE.postId.eq(POST.id),
                        OWN_VOTE.userId.eq(viewerId == null ? GUEST : viewerId))
                .where(POST.id.eq(id), POST.deletedAt.isNull())
                .fetchOne();
        if (row == null) {
            return Optional.empty();
        }
        boolean hasVoting = row.type().hasVoting();
        return Optional.of(row.toView(
                hasVoting ? products(id) : List.of(),
                hasVoting ? options(id) : List.of()));
    }

    /**
     * 세 문장이 한 스냅샷을 보려면 REPEATABLE READ 이상의 트랜잭션 안이어야 한다 (ADR-0043·0045 의 규약).
     * 격리 수준은 가장 바깥 트랜잭션이 정하고 Spring 은 참여 트랜잭션의 격리를 검증하지 않으므로, 명시하지
     * 않았으면({@code null}) MySQL 기본값으로 보고 통과시킨다. 누가 애노테이션을 지우거나 READ COMMITTED 로
     * 열면 조용히 어긋나는 대신 여기서 깨진다.
     */
    private static void requireSnapshot() {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "본문·상품·선택지 문장이 같은 스냅샷을 보려면 트랜잭션 안이어야 한다");
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        Assert.state(isolation == null
                        || isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                        || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE,
                "본문·상품·선택지 문장이 같은 스냅샷을 보려면 REPEATABLE READ 이상이어야 한다: " + isolation);
    }

    /**
     * 본문 문장의 프로젝션 — 게시글 + 작성자 + 내 투표.
     *
     * <p><b>삭제된 글은 {@link #findDetail} 의 WHERE 가 거른다</b> — 소프트 삭제라 행이 남아 있으므로
     * {@code deleted_at IS NULL} 이 없으면 지운 글이 그대로 보인다. 목록과 같은 기준이다.
     *
     * <p>내 투표는 {@code LEFT JOIN} 이다. 게스트이거나 아직 투표하지 않았으면 {@code myOptionId} 가
     * {@code null} 이고, 그 {@code null} 이 곧 "득표율을 감춘다" 의 입력이 된다 (ADR-0046). 조인 조건에
     * 회원을 넣으므로 게스트여도 행이 사라지지 않고, {@code uk_vote_post_user} 가 최대 한 줄을 보장한다.
     *
     * <p>작성자 조인은 {@code INNER} 다. 탈퇴 회원의 행은 남고 개인정보만 지워지므로(ADR-0040) 글이
     * 사라지지 않는다. 카운터는 스키마가 {@code INT UNSIGNED} 라 {@code Integer} 로 읽고 도메인 계약인
     * {@code long} 으로 넓힌다 — 이 {@code longValue()} 는 SQL 에 {@code cast} 로 내려가며 정렬에는 쓰지 않는다.
     */
    private static Expression<DetailRow> detail() {
        return Projections.constructor(DetailRow.class,
                POST.id,
                POST.type,
                POST.category,
                POST.title,
                POST.description,
                POST.voteCount.longValue(),
                POST.commentCount.longValue(),
                POST.createdAt,
                POST.userId,
                authorNickname(),
                USER.profileImageUrl,
                USER.ranking,
                USER.highestGrade,
                OWN_VOTE.postOptionId);
    }

    /**
     * 작성자 표시명 — 닉네임, 없으면 이름, 둘 다 없으면 {@value #UNKNOWN_AUTHOR}.
     * {@code PostListQuerydslRepository} 와 같은 정의다. 빈 문자열과 폴백 문자열은 파라미터로 바인딩된다.
     */
    private static Expression<String> authorNickname() {
        return USER.nickname.nullif("")
                .coalesce(USER.name.nullif(""), Expressions.constant(UNKNOWN_AUTHOR));
    }

    /**
     * 상품과 그 대표 사진 1장 (§6.3). 표시 순서대로다 — 찬반은 하나, A/B 는 A·B 둘 (R-02).
     *
     * <p>가격은 스키마가 {@code INT} 라 {@code Integer} 로 읽고 도메인 계약인 {@code Long} 으로 넓힌다.
     * 선택 입력이라 {@code null} 일 수 있고 {@code cast} 는 {@code null} 을 그대로 둔다.
     */
    private List<PostDetailProduct> products(Long id) {
        return queryFactory.select(Projections.constructor(PostDetailProduct.class,
                        PRODUCT.id,
                        PRODUCT.name,
                        PRODUCT.price.longValue(),
                        PRODUCT.linkUrl,
                        imageUrl(),
                        PRODUCT.displayOrder.intValue()))
                .from(PRODUCT)
                .where(PRODUCT.post.id.eq(id))
                .orderBy(PRODUCT.displayOrder.asc())
                .fetch();
    }

    /**
     * 상품의 대표 사진 — 가장 먼저 등록된 한 장 (§6.3). 스칼라 서브쿼리인 이유는 찬반 상품이 사진을 최대
     * 3장 갖기 때문이다(R-03) — 그냥 조인하면 상품 행이 사진 수만큼 불어난다. "가장 처음 등록한 사진" 은
     * {@code item_resource.id} 최소값이다 — 같은 컨테이너 안에서 id 순서가 곧 등록 순서이고, {@code created_at}
     * 은 한 번의 업로드에서 모두 같아 순서를 못 가른다. 목록·랜덤 카드와 같은 정의다.
     * A/B 는 상품마다 사진이 1장이라(R-03) 같은 서브쿼리가 그대로 맞는다.
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
     * 선택지 (§6.3). 투표 게시글은 정확히 둘, 일반은 0줄이다 (R-04). 표시 순서대로다.
     *
     * <p><b>득표 수를 여기서 감추지 않는다.</b> 저장소는 읽은 값을 그대로 올리고, 미투표자에게 감추는 판단은
     * 서비스가 한다 (ADR-0046) — 감춤이 화면 계약이지 저장소의 관심사가 아니기 때문이다.
     */
    private List<PostDetailOption> options(Long id) {
        return queryFactory.select(Projections.constructor(PostDetailOption.class,
                        OPTION.id,
                        OPTION.label,
                        OPTION.postProductId,
                        OPTION.displayOrder.intValue(),
                        OPTION.voteCount.longValue()))
                .from(OPTION)
                .where(OPTION.post.id.eq(id))
                .orderBy(OPTION.displayOrder.asc())
                .fetch();
    }
}
