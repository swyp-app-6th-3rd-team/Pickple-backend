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
     * <p>원자적 삽입 한 문장이다. 확인 후 저장하면 동시 요청이 모두 "없다" 를 보고 모두
     * 삽입을 시도한다 — 실측에서 8개 동시 요청 중 1건만 성공하고 7건이 무결성 위반으로
     * 500 이 됐다. 뱃지는 투표의 부가 효과인데 그 실패가 투표를 통째로 죽인 것이다.
     *
     * <p>이제 중복은 예외가 아니라 "아무것도 바꾸지 않음" 이라 트랜잭션이 살아 있고,
     * 두 요청이 같은 임계값을 동시에 넘겨도 둘 다 투표에 성공한다.
     * R-17 은 여전히 {@code UNIQUE(user_id, badge_id)} 가 지킨다 — 달라진 것은
     * 그 제약이 <b>예외를 던지는 대신 삽입을 무시</b>하게 만든 것뿐이다.
     */
    @Override
    @Transactional
    public void grantIfAbsent(Long userId, Long badgeId) {
        repository.grantIfAbsent(userId, badgeId, LocalDateTime.now(clock));
    }
}
