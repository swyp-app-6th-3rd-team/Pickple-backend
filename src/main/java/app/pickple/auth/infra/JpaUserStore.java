package app.pickple.auth.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaUserStore implements UserStore {

    private final UserRepository repository;
    private final Clock clock;

    /**
     * 쓰기 트랜잭션 경계를 <b>잡을 수 있는 형태로</b> 연다.
     *
     * <p>{@code @Transactional} 로는 안 된다 — 그 경계는 메서드가 반환된 뒤에 커밋되므로
     * 메서드 안의 catch 는 커밋 실패를 볼 수 없다. execute() 는 반환 전에 커밋·롤백을
     * 끝내므로, 이 호출을 감싼 try/catch 가 결과를 확정된 상태로 받는다.
     */
    private final TransactionTemplate transactionTemplate;

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

    @Override
    public boolean existsActiveNickname(String nickname) {
        return repository.countActiveNickname(nickname) > 0;
    }

    /**
     * 프로필을 쓰고, 닉네임이 이미 점유돼 있었으면 쓰지 않았다는 사실을 돌려준다.
     *
     * <p>먼저 점유를 확인하고 쓴다 — 확인과 쓰기 사이의 틈은
     * {@code uk_users_active_nickname} 이 막는다. 저장소가 사실만 알리고 정책 해석은
     * 위층이 하는 형태다 (docs/domain/계층별 책임.md).
     *
     * <p><b>왜 {@code @Transactional} 이 아니라 {@code TransactionTemplate} 인가.</b>
     * 제약 위반이 나면 스프링은 그 트랜잭션을 rollback-only 로 표시한다.
     * {@code @Transactional} 메서드 안에서 예외를 잡아 정상 반환하면, 메서드를 빠져나갈 때의
     * 커밋이 {@code UnexpectedRollbackException} 으로 다시 터진다 — 잡았는데도 500 이 났다.
     * {@code execute()} 는 반환 전에 커밋·롤백을 끝내므로 그 바깥의 catch 가
     * 확정된 실패를 받아 사실로 바꿀 수 있다.
     *
     * <p><b>사전 확인에 조건부 UPDATE(NOT EXISTS)를 쓰지 않는 이유.</b> 서브쿼리가
     * 유니크 인덱스에 갭 잠금을 잡아, 같은 닉네임에 동시에 몰리면 데드락이 났다(에러 1213).
     * 조건 없이 쓰면 각자 인덱스 항목 하나만 다투므로 승자 하나가 정해지고 나머지는
     * 곧바로 제약 위반으로 떨어진다.
     */
    @Override
    public Optional<User> saveProfileIfNicknameFree(User user) {
        if (user.id() == null || user.nickname() == null) {
            throw new IllegalArgumentException("프로필 저장에는 저장된 사용자와 닉네임이 필요합니다.");
        }
        String nickname = user.nickname().value();
        if (isTakenByAnotherUser(nickname, user.id())) {
            return Optional.empty();
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                int updated = repository.updateProfile(
                        user.id(), nickname, user.profileImageUrl(), LocalDateTime.now(clock));
                if (updated == 0) {
                    throw new IllegalStateException("활성 사용자를 찾을 수 없습니다: userId=" + user.id());
                }
            });
        } catch (DataIntegrityViolationException e) {
            // 확인과 쓰기 사이에 선점당했다. 무결성 위반 전부를 중복으로 뭉뚱그리지 않기 위해
            // 제약 이름으로 좁혀 가른다 — 길이 초과·FK 위반은 그대로 올린다 (ADR-0019).
            if (isActiveNicknameConflict(e)) {
                return Optional.empty();
            }
            throw e;
        }
        return repository.findById(user.id()).map(UserEntity::toDomain);
    }

    private boolean isTakenByAnotherUser(String nickname, Long userId) {
        return repository.findIdByActiveNickname(nickname)
                .filter(owner -> !owner.equals(userId))
                .isPresent();
    }

    private boolean isActiveNicknameConflict(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("uk_users_active_nickname")) {
                return true;
            }
        }
        return false;
    }
}
