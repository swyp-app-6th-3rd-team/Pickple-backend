package app.pickple.auth.domain;

/** 외부 소셜 인증으로 검증된 신원의 제공자. 자체 QA 인증은 포함하지 않는다. */
public enum SocialProvider {
    GOOGLE,
    KAKAO,
    NAVER,
    APPLE
}
