package app.pickple.auth.domain;

/**
 * 소셜 프로바이더에서 검증을 마친 사용자 신원.
 *
 * <p>브라우저 OAuth2와 네이티브 Apple/Kakao 로그인이 같은 가입·로그인 로직을
 * 재사용하도록 전송 방식과 무관한 최소 정보만 정의한다.
 */
public interface SocialIdentity {

    SocialProvider provider();

    String providerId();

    String email();

    String name();
}
