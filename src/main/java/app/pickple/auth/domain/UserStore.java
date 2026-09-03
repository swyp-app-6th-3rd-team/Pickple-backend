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
}
