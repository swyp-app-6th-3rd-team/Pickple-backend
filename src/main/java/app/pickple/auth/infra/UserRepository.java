package app.pickple.auth.infra;

import app.pickple.auth.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
