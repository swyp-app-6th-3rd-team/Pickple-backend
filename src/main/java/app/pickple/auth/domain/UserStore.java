package app.pickple.auth.domain;

import java.util.Optional;

public interface UserStore {

    User save(User user);

    Optional<User> findById(Long id);

    /**
     * 이 식별자의 회원이 <b>지금 활성인지</b>.
     *
     * <p>인가 관문이 요청마다 부른다. {@code findById} 로 {@code User} 를 통째 로딩하지
     * 않는 이유는 관문이 필요로 하는 사실이 "살아 있는가" 하나뿐이기 때문이다 —
     * 엔티티 로딩은 컬럼 전체를 읽고 영속성 컨텍스트에 올리므로 요청마다 도는 조회로는 비싸다.
     *
     * <p>반환값은 <b>사실</b>이다 — "활성이다 / 아니다". 그것이 401 인지 403 인지는
     * 위층이 해석한다 (ADR-0019).
     *
     * <p>조회 자체가 실패하면(DB 장애·풀 타임아웃) 이 메서드는 <b>false 를 돌려주지 않고
     * 예외를 전파한다.</b> 장애를 "비활성" 으로 바꾸면 DB 가 죽었을 때 전 사용자가
     * 탈퇴자로 취급된다 (ADR-0035 결정 3).
     */
    boolean existsActiveById(Long id);

    /** 소셜 신원으로 조회. 조회 키는 반드시 (provider, providerId) 쌍이다. */
    Optional<User> findByProviderAndProviderId(SocialProvider provider, String providerId);

    /**
     * 활성 회원이 이 닉네임을 쓰고 있는지 (R-23).
     *
     * <p>전건을 읽어 애플리케이션에서 거르지 않는다 — 회원 수만큼 비용이 늘고,
     * 콜레이션이 판정하는 동등성(대소문자 무시)을 애플리케이션이 재현하다 어긋난다.
     * 판정은 소스에서 한다.
     *
     * <p>이 결과는 <b>입력 단계 피드백</b>일 뿐이다. 확인과 저장 사이의 틈은
     * 유니크 제약이 막는다.
     */
    boolean existsActiveNickname(String nickname);

    /**
     * 닉네임이 비어 있으면 프로필을 저장하고, 이미 점유돼 있으면 저장하지 않는다.
     *
     * <p>반환값은 <b>사실</b>이다 — "저장됐다 / 저장되지 않았다". 그것이
     * "이미 쓰는 닉네임입니다" 라는 정책인지는 위층이 해석한다 (ADR-0019).
     *
     * <p>존재 확인과 저장 사이의 틈은 {@code uk_users_active_nickname} 이 막는다.
     * 구현은 반드시 이 메서드 <b>안에서</b> flush 해야 한다 —
     * 커밋까지 미루면 제약 위반이 이 경계 밖에서 터져 사실로 바꿀 수 없다.
     */
    Optional<User> saveProfileIfNicknameFree(User user);
}
