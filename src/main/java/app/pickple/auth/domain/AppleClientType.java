package app.pickple.auth.domain;

/** Apple authorization code와 provider refresh token을 발급한 client 구분. */
public enum AppleClientType {
    NATIVE,
    WEB
}
