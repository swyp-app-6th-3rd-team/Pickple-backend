package app.pickple.auth.infra;

import app.pickple.auth.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * package-private 이라 infra 패키지를 벗어날 수 없다.
 * 바깥에는 도메인의 {@code UserStore} 인터페이스만 노출된다.
 *
 * <p>중첩 인터페이스로 두면 Spring Data 가 리포지토리 빈을 만들지 않으므로
 * 반드시 최상위 타입이어야 한다.
 */
interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByProviderAndProviderId(SocialProvider provider, String providerId);

    /**
     * 활성 회원의 닉네임 점유 여부를 DB 가 센다.
     *
     * <p>생성 컬럼 {@code active_nickname} 을 조회 대상으로 삼는 이유는 두 가지다.
     * 유니크 인덱스가 걸려 있어 인덱스만 보고 끝나고, "활성 회원만" 이라는 조건이
     * 컬럼 정의에 이미 들어 있어 조회 쪽에서 state 조건을 빠뜨릴 수 없다.
     *
     * <p>동등성 판정은 컬럼 콜레이션(utf8mb4_0900_ai_ci)이 한다 —
     * 대소문자를 구분하지 않으므로 유니크 제약이 거부하는 값과 이 조회 결과가 일치한다.
     */
    @Query(value = "SELECT COUNT(*) FROM users WHERE active_nickname = :nickname", nativeQuery = true)
    long countActiveNickname(@Param("nickname") String nickname);

    /**
     * 인가 관문이 요청마다 부르는 존재 확인.
     *
     * <p>{@code SELECT 1} 로 컬럼을 읽지 않는다. 조건이 PK 하나라 실행계획이
     * {@code type=const} / {@code key=PRIMARY} / {@code rows=1} 로 끝난다(실측 0.03ms warm).
     * 엔티티를 로딩하면 컬럼 전체를 읽고 영속성 컨텍스트에 올리므로 그만큼 비싸진다.
     */
    @Query(value = "SELECT 1 FROM users WHERE id = :id AND state = 'ACTIVE'", nativeQuery = true)
    Optional<Integer> findActiveMarkerById(@Param("id") Long id);

    /** 닉네임의 현재 주인. 본인이 쓰던 닉네임을 다시 내는 경우를 중복과 가르는 데 쓴다. */
    @Query(value = "SELECT id FROM users WHERE active_nickname = :nickname", nativeQuery = true)
    Optional<Long> findIdByActiveNickname(@Param("nickname") String nickname);

    /**
     * 프로필을 쓴다. 닉네임 유일성은 {@code uk_users_active_nickname} 이 판정한다.
     *
     * <p><b>왜 조건 없는 UPDATE 인가.</b> {@code NOT EXISTS} 로 점유를 먼저 확인하면
     * 그 서브쿼리가 유니크 인덱스에 갭 잠금을 잡는다. 같은 닉네임으로 동시에 몰리면
     * 여러 트랜잭션이 서로 다른 순서로 잠금을 얻어 <b>데드락</b>이 난다(에러 1213).
     * 실측에서 8개 동시 요청 중 7개가 그렇게 죽었다.
     *
     * <p>조건을 빼면 각 트랜잭션은 자기 행만 잠그고 유니크 인덱스 항목 하나만 다툰다 —
     * 승자 하나가 잡고 나머지는 곧바로 제약 위반으로 떨어진다. 판정을 인덱스에 맡기는 편이
     * 갭 잠금을 걸고 스스로 판정하려는 것보다 경합이 적다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE users
               SET nickname = :nickname,
                   profile_image_url = :profileImageUrl,
                   updated_at = :updatedAt
             WHERE id = :id
               AND state = 'ACTIVE'
            """, nativeQuery = true)
    int updateProfile(@Param("id") Long id,
                      @Param("nickname") String nickname,
                      @Param("profileImageUrl") String profileImageUrl,
                      @Param("updatedAt") LocalDateTime updatedAt);
}
