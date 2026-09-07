package app.pickple.point.infra;

import app.pickple.auth.infra.QUserEntity;
import app.pickple.point.domain.RankingQueryStore.RankingView;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 피커 랭킹 목록을 읽는다 (§2.5 · §3.1 · §7.3).
 *
 * <p><b>정렬 키는 {@code users.ranking} 이다</b> — 배치가 이미 매겨둔 값이라 조회는 그 순서를
 * <b>읽기만</b> 한다. 정렬을 {@code (point, created_at)} 으로 다시 표현할 수도 있지만
 * 그러면 안 된다는 것이 실측으로 확인돼 있다 (ADR-0032).
 *
 * <p><b>왜 커서가 {@code ranking} 한 컬럼인가</b> — {@code ROW_NUMBER()} 가 만든 값이라
 * 활성 회원 중 중복이 없다(200k 시드에서 {@code COUNT(DISTINCT ranking) = 200000}).
 * 동률이 없으므로 조각 경계에서 행이 새지 않아 {@code PostListCursor} 처럼 튜플로 묶을
 * 필요가 없고, 조건이 {@code ?} 하나로 끝난다.
 *
 * <p><b>{@code ranking IS NOT NULL} 이 곧 활성 필터다.</b> 배치가 탈퇴 회원의 순위를
 * {@code NULL} 로 되돌리기 때문이다({@code CLEAR_INACTIVE}). 그럼에도 {@code state} 를
 * 함께 걸지 않는 이유는, 두 조건이 같은 사실을 말하는데 {@code state} 를 더하면 인덱스
 * 범위 스캔에 필터가 하나 붙기 때문이다. 대신 <b>탈퇴와 배치 사이의 창</b>이 남는다 —
 * 탈퇴 직후 다음 배치 전까지 옛 순위가 목록에 보인다. 이것은 ADR-0028 이 이미 받아들인
 * 지연(최대 5분)과 같은 성질이라 새 계약을 만들지 않는다.
 *
 * <p><b>왜 네이티브 SQL 이 아닌가</b> — {@code users.ranking}·{@code point}·{@code vote_count}
 * 를 읽기 전용으로 매핑해(ADR-0041) QueryDSL 이 정렬·조회에 쓸 수 있게 됐다. 결과를
 * {@code Object[]} 로 받아 컬럼 인덱스 상수로 꺼내던 구조가 사라진다 — 그 인덱스는 SELECT 절이
 * 바뀌면 조용히 밀리고 <b>컴파일이 아니라 런타임에</b> 깨졌다 (이슈 #130).
 *
 * <p>package-private 이다. 바깥은 {@code app.pickple.point.domain.RankingQueryStore} 만 본다.
 *
 * <p><b>옛 이름은 {@code RankingListRepository} 였다.</b> {@code V10__users_ranking_order_index.sql}
 * 의 실측 주석이 그 이름으로 이 쿼리를 가리킨다 — 적용된 마이그레이션은 체크섬이 걸려 있어
 * 주석 한 줄도 고칠 수 없으므로, 추적은 여기서 잇는다.
 */
@Repository
@RequiredArgsConstructor
class RankingQuerydslRepository {

    private static final QUserEntity USER = QUserEntity.userEntity;

    private final JPAQueryFactory queryFactory;

    /**
     * 상위 피커 (§2.5). 커서가 없는 첫 조각과 형태가 같지만 의미가 달라 따로 둔다 —
     * 이쪽은 "5명만 노출" 이라 다음 조각이라는 개념이 없다.
     */
    List<RankingView> findTop(int size) {
        return rankedSlice(null, size);
    }

    /**
     * 전체 랭킹 한 조각 (§3.1).
     *
     * <p>다음 조각의 존재를 알기 위해 <b>{@code size + 1} 건</b>을 읽는다. 넘치는 한 건을
     * 버리는 것은 호출자의 몫이다 — 여기서 버리면 "넘쳤다" 는 사실이 함께 사라진다.
     */
    List<RankingView> findSlice(RankingCursor cursor, int size) {
        return rankedSlice(cursor, size + 1);
    }

    /**
     * 본인 순위 (§7.3).
     *
     * <p>목록과 달리 {@code ranking IS NOT NULL} 을 걸지 않는다 — 아직 순위가 없는 회원도
     * 자기 포인트는 봐야 하고, 그때 {@code ranking} 은 {@code null} 로 올라간다.
     * 대신 {@code state = 'ACTIVE'} 를 직접 건다.
     */
    Optional<RankingView> findByUser(Long userId) {
        return Optional.ofNullable(
                queryFactory.select(projection())
                        .from(USER)
                        .where(USER.id.eq(userId), USER.state.eq(app.pickple.auth.domain.User.State.ACTIVE))
                        .fetchOne());
    }

    private List<RankingView> rankedSlice(RankingCursor cursor, int limit) {
        return queryFactory.select(projection())
                .from(USER)
                .where(USER.ranking.isNotNull(), rankingAfter(cursor))
                .orderBy(USER.ranking.asc())
                .limit(limit)
                .fetch();
    }

    /** 커서가 없으면 {@code null} 을 돌려 조건에서 빠진다 — {@code where} 는 null 을 무시한다. */
    private BooleanExpression rankingAfter(RankingCursor cursor) {
        return cursor == null ? null : USER.ranking.gt(cursor.ranking());
    }

    /**
     * 표시용 닉네임은 {@code nickname → name → "알 수 없음"} 순으로 고른다.
     *
     * <p>빈 문자열도 없는 것으로 친다 — {@code NULLIF} 가 그 일을 한다. 탈퇴 회원의 닉네임이
     * 비워지므로(ADR-0040) 폴백이 실제로 쓰인다.
     */
    private static StringExpression displayNickname() {
        return Expressions.stringTemplate(
                "COALESCE(NULLIF({0}, ''), NULLIF({1}, ''), '알 수 없음')",
                USER.nickname, USER.name);
    }

    /**
     * {@code ranking} 은 {@code null} 을 그대로 올린다 — 0 으로 접으면 "아직 모른다" 가
     * "0위" 라는 거짓이 된다 (ADR-0028). {@code point}·{@code voteCount} 는 스키마가
     * {@code INT UNSIGNED} 라 {@code Integer} 로 읽고 도메인 계약인 {@code long} 으로 넓힌다.
     */
    private static com.querydsl.core.types.Expression<RankingView> projection() {
        return Projections.constructor(RankingView.class,
                USER.id,
                displayNickname(),
                USER.profileImageUrl,
                USER.ranking,
                USER.point.longValue(),
                USER.voteCount.longValue());
    }
}
