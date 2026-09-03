package app.pickple.auth.domain;

import java.util.Optional;

public interface UserStore {

    User save(User user);

    Optional<User> findById(Long id);

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
