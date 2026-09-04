package app.pickple.badge.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** package-private. 바깥은 {@link app.pickple.badge.domain.UserBadgeStore} 만 본다. */
interface UserBadgeRepository extends JpaRepository<UserBadgeEntity, Long> {

    /**
     * 가진 뱃지의 id 만 읽는다.
     *
     * <p>엔티티를 통째로 가져오지 않는 이유는 획득/미획득을 가르는 데 id 면 충분하기
     * 때문이다. 획득 시각은 이 경로에서 쓰지 않는다.
     */
    @Query("SELECT ub.badgeId FROM UserBadgeEntity ub WHERE ub.userId = :userId")
    List<Long> findBadgeIdsByUserId(@Param("userId") Long userId);

    /**
     * 없으면 지급하고, 이미 있으면 아무것도 바꾸지 않는다 (R-17).
     *
     * <p><b>확인 후 삽입이 아니라 원자적 삽입이다.</b> 조회로 거른 뒤 저장하면 동시 요청이
     * 모두 "없다" 를 보고 모두 삽입을 시도한다. 실측했다 — 같은 회원이 8개 요청으로 임계값을
     * 동시에 넘겼을 때 <b>1건만 성공하고 7건이 무결성 위반으로 500</b> 이 됐다.
     * 뱃지는 투표의 부가 효과인데 그 실패가 투표를 통째로 죽인 것이다.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 는 <b>중복 키에만</b> 반응하고 예외를 만들지 않아
     * 트랜잭션이 rollback-only 로 표시되지 않는다. {@code INSERT IGNORE} 는 쓰지 않는다 —
     * 그쪽은 FK 위반까지 조용히 삼켜, 없는 회원에게 뱃지를 주려던 버그가 흔적 없이 사라진다.
     *
     * <p>중복일 때 {@code user_id} 를 자기 값으로 덮어쓴다. 실제로 바뀌는 값이 없어야
     * 이미 받은 사람의 획득 시각이 밀리지 않는다.
     *
     * <p><b>영향 행 수를 지급 여부로 해석하지 않는다.</b> MySQL 은 삽입이면 1, 갱신이면 2,
     * 값이 그대로면 0 을 돌려주는데 이 값은 드라이버·설정에 따라 갈린다
     * (ERD 3차 §1.3 에서 첫 댓글 판정이 이 함정에 물렸다).
     * "이번에 새로 받았는가" 가 필요하면 호출 전 보유 목록으로 판단한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_badge (user_id, badge_id, acquired_at)
            VALUES (:userId, :badgeId, :acquiredAt)
            ON DUPLICATE KEY UPDATE user_id = user_id
            """, nativeQuery = true)
    void grantIfAbsent(@Param("userId") Long userId,
                       @Param("badgeId") Long badgeId,
                       @Param("acquiredAt") LocalDateTime acquiredAt);
}
