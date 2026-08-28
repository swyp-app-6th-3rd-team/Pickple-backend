package app.pickple.auth.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaUserStore implements UserStore {

    private final UserRepository repository;
    private final Clock clock;

    @Override
    public User save(User user) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (user.id() == null) {
            return repository.save(UserEntity.from(user, now)).toDomain();
        }
        UserEntity entity = repository.findById(user.id())
                .orElseThrow(() -> new IllegalStateException("사용자를 찾을 수 없습니다: userId=" + user.id()));
        entity.applyState(user, now);
        return entity.toDomain();
    }

    @Override
    public Optional<User> findById(Long id) {
        return repository.findById(id).map(UserEntity::toDomain);
    }

    @Override
    public Optional<User> findByProviderAndProviderId(SocialProvider provider, String providerId) {
        return repository.findByProviderAndProviderId(provider, providerId).map(UserEntity::toDomain);
    }
}
