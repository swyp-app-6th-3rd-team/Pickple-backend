package app.pickple.auth.infra;

import app.pickple.auth.domain.AppleClientType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface AppleProviderTokenRepository extends JpaRepository<AppleProviderTokenEntity, AppleProviderTokenEntity.Key> {

    Optional<AppleProviderTokenEntity> findByUserIdAndClientType(Long userId, AppleClientType clientType);

    List<AppleProviderTokenEntity> findAllByUserId(Long userId);

    void deleteAllByUserId(Long userId);
}
