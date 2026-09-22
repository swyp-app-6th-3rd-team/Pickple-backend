package app.pickple.auth.domain;

/**
 * 사용자 인증 출처. QA는 운영자가 발급하고 자체 아이디·비밀번호로 인증하는 테스터 계정이다.
 */
public enum AuthProvider {
    GOOGLE,
    KAKAO,
    NAVER,
    APPLE,
    QA
}
