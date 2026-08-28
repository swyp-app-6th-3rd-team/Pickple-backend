package app.pickple.auth.infra;

import app.pickple.auth.domain.SocialProvider;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
