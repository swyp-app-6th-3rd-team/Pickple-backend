package app.pickple.badge.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 일별 활동 집계의 원자 갱신과 조회. package-private.
 *
 * <p>날짜를 전부 인자로 받는다. {@code CURRENT_DATE} 를 쓰면 DB 세션 타임존이 하루를
 * 정하게 되어, 자정 근처에서 사용자가 보는 하루와 집계의 하루가 갈린다.
 * 애플리케이션이 {@code Asia/Seoul} {@code Clock} 으로 계산해 넘긴다.
 */
interface UserDailyActivityRepository extends JpaRepository<UserDailyActivityEntity, Long> {

    /**
     * 그날의 투표 수를 하나 올린다. 없으면 만든다.
     *
     * <p>{@code UNIQUE(user_id, activity_date)} 가 충돌 지점이라 UPSERT 한 문장으로
     * 멱등하게 누적된다. 조회 후 삽입으로 나누면 같은 사용자의 동시 투표 둘이
     * 각각 "행이 없다" 를 보고 둘 다 삽입을 시도한다.
     *
     * <p>Java 에서 읽고 더해 쓰지 않는 이유도 같다 — 두 요청이 같은 값을 읽고
     * 각자 +1 하면 하나가 사라진다(lost update).
     *
     * <p><b>영향 행 수를 판정에 쓰지 않는다.</b> MySQL 은 UPSERT 에서 삽입이면 1,
     * 갱신이면 2 를 돌려주지만 이 값은 드라이버·설정에 따라 갈린다.
     * 실제로 이 저장소가 첫 댓글 판정에서 물렸다 (ERD 3차 §1.3).
     * 여기서는 반환값을 쓰지 않고, 갱신된 값이 필요하면 다시 읽는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_daily_activity (user_id, activity_date, vote_count, created_at, updated_at)
            VALUES (:userId, :date, 1, NOW(), NOW())
            ON DUPLICATE KEY UPDATE vote_count = vote_count + 1, updated_at = NOW()
            """, nativeQuery = true)
    void increaseVoteCount(@Param("userId") Long userId, @Param("date") LocalDate date);

    /**
     * 누적 투표 횟수 — 일별 집계의 합계.
     *
     * <p>{@code users.vote_count} 를 쓰지 않는 이유는 그 컬럼을 아무도 채우지 않기
     * 때문이다(V3 에 있지만 매핑조차 되어 있지 않다). 같은 사실을 두 곳이 표현하면
     * 어긋나므로 별도 카운터도 두지 않는다 — 일별 행이 이미 그날의 수를 갖고 있어
     * 누적은 그것의 합계이지 별개의 사실이 아니다.
     *
     * <p>비용은 <b>활동 일수</b>에 비례한다(투표 수가 아니다). 매일 투표해도 1년에 365행이고
     * {@code uk_daily_user_date (user_id, activity_date)} 가 covering 으로 걸린다.
     * 한 사용자의 행만 읽는 지역 집계라, 전역 정렬이 필요했던 랭킹(ADR-0028)과
     * 성격이 다르다 — 그 선례를 여기 끌어오면 잘못된 유추다.
     */
    @Query("SELECT COALESCE(SUM(a.voteCount), 0) FROM UserDailyActivityEntity a WHERE a.userId = :userId")
    long sumVoteCountByUser(@Param("userId") Long userId);

    @Query("""
            SELECT COALESCE(a.voteCount, 0) FROM UserDailyActivityEntity a
             WHERE a.userId = :userId AND a.activityDate = :date
            """)
    Integer findVoteCountOn(@Param("userId") Long userId, @Param("date") LocalDate date);

    /**
     * 최근 활동일을 최신순으로 읽는다. 연속 일수를 세는 입력이다.
     *
     * <p><b>{@code limit} 이 있는 이유</b> — 연속 뱃지의 최대 임계값이 30일이라
     * 31번째 행은 판정에 쓰이지 않는다. 미션 목록도 미해제 뱃지만 보여주므로
     * 30 을 넘는 실제 연속일수를 표시할 일이 없다. 상한이 없으면
     * 3년 매일 투표한 사용자의 판정이 1,000행을 읽는다.
     *
     * <p>{@code activityDate DESC} 정렬이 {@code uk_daily_user_date} 의 컬럼 순서와
     * 맞아 인덱스 역방향 스캔으로 끝난다.
     */
    @Query("""
            SELECT a.activityDate FROM UserDailyActivityEntity a
             WHERE a.userId = :userId AND a.activityDate <= :from
             ORDER BY a.activityDate DESC
             LIMIT :limit
            """)
    List<LocalDate> findRecentActivityDates(
            @Param("userId") Long userId, @Param("from") LocalDate from, @Param("limit") int limit);
}
