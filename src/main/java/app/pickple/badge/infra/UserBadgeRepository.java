package app.pickple.badge.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    boolean existsByUserIdAndBadgeId(Long userId, Long badgeId);
}
