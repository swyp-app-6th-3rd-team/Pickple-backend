package app.pickple.auth.apple;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AppleWebRandomValueGenerator {

    private static final int RANDOM_BYTES = 32;
    private final SecureRandom secureRandom;

    public AppleWebRandomValueGenerator() {
        this(new SecureRandom());
    }

    AppleWebRandomValueGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String next() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
