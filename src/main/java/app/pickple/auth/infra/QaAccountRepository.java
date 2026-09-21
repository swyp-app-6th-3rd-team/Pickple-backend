package app.pickple.auth.infra;

import org.springframework.data.jpa.repository.JpaRepository;

interface QaAccountRepository extends JpaRepository<QaAccountEntity, String> {
    void deleteByUserId(Long userId);
}
