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

    /**
     * 도메인 상태를 저장한다.
     *
     * <p><b>활성 상태를 쓰는 경우와 탈퇴를 쓰는 경우를 가른다.</b> 로그인이 회원을 읽고
     * 활성인지 확인한 뒤 저장하기까지의 사이에 탈퇴가 커밋되면, 읽어둔 옛 값이 그대로 쓰여
     * <b>파기한 개인정보와 {@code ACTIVE} 상태가 되살아난다</b>(ADR-0040).
     * 그래서 활성 상태를 쓸 때는 <b>행이 아직 활성일 때만</b> 쓰는 조건부 UPDATE 를 쓴다.
     *
     * <p>변경 감지에 맡기지 않는 이유는 영속성 컨텍스트가 <b>읽은 시점</b>의 엔티티를
     * 1차 캐시로 돌려주기 때문이다 — 엔티티의 {@code state} 를 봐도 옛 값이라
     * 자바 쪽 가드로는 이 틈을 막지 못한다. 쓰기 시점의 행을 아는 것은 DB 뿐이다.
     *
     * <p>탈퇴({@code INACTIVE} 쓰기)는 조건을 걸지 않는다. 그것이 이 전이의 목적이고,
     * 중복 호출은 {@code AccountWithdrawalPersistenceService} 가 이미 막는다.
     */
    @Override
    public User save(User user) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (user.id() == null) {
            return repository.save(UserEntity.from(user, now)).toDomain();
        }
        if (user.state() == User.State.ACTIVE) {
            int updated = repository.syncActiveProfile(
                    user.id(), user.email(), user.name(), now);
            if (updated == 0) {
                // 활성 회원이 없다 — 행이 사라졌거나 탈퇴가 먼저 커밋됐다.
                // 두 원인을 가르려면 조회를 한 번 더 해야 하는데, 어느 쪽이든
                // "이 저장은 성립하지 않는다" 는 같은 결론이라 나누지 않는다.
                throw new UserPersistenceException(
                        "활성 사용자를 찾을 수 없습니다: userId=" + user.id());
            }
            return repository.findById(user.id())
                    .map(UserEntity::toDomain)
                    .orElseThrow(() -> new UserPersistenceException(
                            "저장 뒤 사용자를 찾을 수 없습니다: userId=" + user.id()));
        }
        UserEntity entity = repository.findById(user.id())
                .orElseThrow(() -> new UserPersistenceException("사용자를 찾을 수 없습니다: userId=" + user.id()));
        entity.applyState(user, now);
        return entity.toDomain();
    }

    @Override
    public Optional<User> findById(Long id) {
        return repository.findById(id).map(UserEntity::toDomain);
    }

    /**
     * 활성 여부만 확인한다. 조회 실패는 삼키지 않고 그대로 올린다 —
     * 관문이 "비활성" 과 "확인 불가" 를 구분해야 하기 때문이다 (ADR-0035 결정 3).
     */
    @Override
    public boolean existsActiveById(Long id) {
        return repository.findActiveMarkerById(id).isPresent();
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
                    throw new UserPersistenceException("활성 사용자를 찾을 수 없습니다: userId=" + user.id());
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
        User saved = repository.findById(user.id())
                .map(UserEntity::toDomain)
                .orElseThrow(() -> new UserPersistenceException(
                        "프로필 저장 뒤 사용자를 찾을 수 없습니다: userId=" + user.id()));
        return Optional.of(saved);
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
