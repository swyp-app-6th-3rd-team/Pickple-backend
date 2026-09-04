package app.pickple.badge.infra;

import app.pickple.badge.domain.UserBadgeStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class JpaUserBadgeStore implements UserBadgeStore {

    private final UserBadgeRepository repository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findOwnedBadgeIds(Long userId) {
        return Set.copyOf(repository.findBadgeIdsByUserId(userId));
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>유일성을 사전 확인하고 저장한다 — 무결성 예외를 잡지 않는다.</b>
     * 이 지급은 투표 트랜잭션 안에서 일어난다. {@code @Transactional} 안에서 무결성 예외가
     * 나면 스프링이 트랜잭션을 rollback-only 로 표시하므로, 예외를 삼켜
     * "이미 있으니 정상" 으로 돌려줘도 커밋 시점에 {@code UnexpectedRollbackException} 이
     * 나고 <b>투표가 통째로 실패한다.</b> 뱃지는 투표의 부가 효과라 인질로 잡으면 안 된다.
     *
     * <p>{@code INSERT IGNORE} 도 답이 아니다 — FK 위반까지 조용히 삼켜, 없는 회원에게
     * 뱃지를 주려던 버그가 아무 흔적 없이 사라진다.
     *
     * <p>확인과 삽입 사이의 좁은 창은 {@code UNIQUE(user_id, badge_id)} 가 막는다(R-17).
     * 그 경합은 같은 사람이 같은 순간에 두 번 투표하는 희귀 경로뿐이고, 그때 나는 예외는
     * 삼키지 않고 위로 올린다 — 조용히 잘못된 상태를 남기는 것보다 실패가 낫다.
     */
    @Override
    @Transactional
    public boolean grantIfAbsent(Long userId, Long badgeId) {
        if (repository.existsByUserIdAndBadgeId(userId, badgeId)) {
            return false;
        }
        repository.save(UserBadgeEntity.of(userId, badgeId, LocalDateTime.now(clock)));
        return true;
    }
}
