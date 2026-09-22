package app.pickple.auth.domain;

import java.util.Optional;

public interface QaAccountStore {
    Optional<QaAccount> findByLoginId(String loginId);

    void deleteByUserId(Long userId);
}
