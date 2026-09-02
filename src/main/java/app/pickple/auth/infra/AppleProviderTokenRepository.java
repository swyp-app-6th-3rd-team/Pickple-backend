package app.pickple.auth.infra;

import org.springframework.data.jpa.repository.JpaRepository;

interface AppleProviderTokenRepository extends JpaRepository<AppleProviderTokenEntity, Long> {
}
