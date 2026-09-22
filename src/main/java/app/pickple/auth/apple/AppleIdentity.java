package app.pickple.auth.apple;

import app.pickple.auth.domain.SocialIdentity;
import app.pickple.auth.domain.AuthProvider;

/** 검증된 Apple ID token에서 얻은 신원. */
public record AppleIdentity(String providerId, String email, String name) implements SocialIdentity {

    @Override
    public AuthProvider provider() {
        return AuthProvider.APPLE;
    }
}
