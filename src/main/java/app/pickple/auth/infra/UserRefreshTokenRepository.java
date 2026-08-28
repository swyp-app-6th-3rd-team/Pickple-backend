package app.pickple.auth.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface UserRefreshTokenRepository extends JpaRepository<UserRefreshTokenEntity, Long> {

    Optional<UserRefreshTokenEntity> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
