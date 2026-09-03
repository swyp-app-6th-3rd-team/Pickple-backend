package app.pickple.auth.service;

import app.pickple.config.ProfileProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 프로필 이미지를 등록하지 않은 사용자에게 줄 기본 이미지를 고른다 (명세 §1.5).
 *
 * <p>난수를 도메인에 넣지 않는 이유 — {@code User.registerProfile()} 이 스스로 이미지를
 * 고르면 같은 입력이 매번 다른 결과를 내어 도메인을 검증할 수 없게 된다.
 * 도메인은 받은 URL 을 그대로 쓰고, 무엇을 줄지는 여기서 정한다.
 */
@Component
public class DefaultProfileImages {

    private final List<String> candidates;
    private final RandomGenerator random;

    public DefaultProfileImages(ProfileProperties properties, RandomGenerator random) {
        List<String> configured = properties.defaultImageUrls();
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException(
                    "app.profile.default-image-urls 가 비어 있습니다. 기본 프로필을 고를 수 없습니다.");
        }
        this.candidates = List.copyOf(configured);
        this.random = random;
    }

    public String pick() {
        return candidates.get(random.nextInt(candidates.size()));
    }
}
