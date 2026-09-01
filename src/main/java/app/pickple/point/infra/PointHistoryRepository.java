package app.pickple.point.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private. */
interface PointHistoryRepository extends JpaRepository<PointHistoryEntity, Long> {

    /** 원장의 합계. 저장된 캐시가 아니라 이력에서 센다 (R-14). */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PointHistoryEntity p WHERE p.userId = :userId")
    long sumAmountByUserId(@Param("userId") Long userId);
}
