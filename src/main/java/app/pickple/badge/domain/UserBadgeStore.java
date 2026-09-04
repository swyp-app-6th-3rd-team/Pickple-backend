package app.pickple.badge.domain;

import java.util.Set;

/**
 * 회원이 가진 뱃지.
 *
 * <p>R-17(같은 뱃지를 두 번 획득하지 않는다)의 최종 방어선은
 * {@code UNIQUE(user_id, badge_id)} 다. 판정이 "가졌는지 확인 → 없으면 지급" 형태라
 * 확인과 삽입 사이에 틈이 있고, 동시 투표 둘이 같은 임계값을 함께 넘기면
 * 응용 검증만으로는 뚫린다.
 *
 * <p><b>저장소는 그 사실을 정책으로 해석하지 않는다</b> (ADR-0019).
 * "삽입됐는가" 만 알리고, 그것이 재획득 시도라는 판단은 서비스가 한다.
 */
public interface UserBadgeStore {

    /** 이 회원이 가진 뱃지 id 집합. 획득/미획득을 가르는 데 쓴다. */
    Set<Long> findOwnedBadgeIds(Long userId);

    /**
     * 아직 없으면 지급한다.
     *
     * <p><b>유일성을 사전 확인하고 저장한다</b> — 무결성 예외를 잡아 삼키지 않는다.
     * 이 지급은 투표 트랜잭션 안에서 일어나는데, {@code @Transactional} 안에서
     * 무결성 예외가 나면 스프링이 트랜잭션을 rollback-only 로 표시한다.
     * 예외를 삼켜 "이미 있으니 정상" 으로 처리해도 커밋 시점에
     * {@code UnexpectedRollbackException} 이 나고 <b>투표가 통째로 실패한다.</b>
     * 뱃지는 투표의 부가 효과이므로 그걸 인질로 잡으면 안 된다.
     *
     * <p>확인과 삽입 사이의 좁은 창은 유니크 키가 막는다. 그 경합은
     * "같은 사람이 같은 순간에 두 번 투표" 라는 희귀 경로뿐이고, 예외는 삼키지 않고
     * 위로 올려 재시도로 해결한다 — 조용히 잘못된 상태를 남기는 것보다 낫다.
     *
     * @return 실제로 지급했으면 {@code true}, 이미 갖고 있었으면 {@code false}
     */
    boolean grantIfAbsent(Long userId, Long badgeId);
}
