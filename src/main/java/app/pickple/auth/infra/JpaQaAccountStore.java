package app.pickple.auth.infra;

import app.pickple.auth.domain.QaAccount;
import app.pickple.auth.domain.QaAccountStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaQaAccountStore implements QaAccountStore {
    private final QaAccountRepository repository;

    @Override
    public Optional<QaAccount> findByLoginId(String loginId) {
        return repository.findById(loginId).map(QaAccountEntity::toDomain);
    }

    @Override
    @Transactional
    public void deleteByUserId(Long userId) {
        repository.deleteByUserId(userId);
    }
}
