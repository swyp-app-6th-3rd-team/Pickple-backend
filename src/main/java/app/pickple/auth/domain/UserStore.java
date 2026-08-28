package app.pickple.auth.domain;

import java.util.Optional;

public interface UserStore {

    User save(User user);

    Optional<User> findById(Long id);

    /** 소셜 신원으로 조회. 조회 키는 반드시 (provider, providerId) 쌍이다. */
    Optional<User> findByProviderAndProviderId(SocialProvider provider, String providerId);
}
