package app.pickple.auth.service;

import app.pickple.config.FileStorageProperties;
import app.pickple.config.ProfileProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

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

    public DefaultProfileImages(ProfileProperties properties, FileStorageProperties storage,
                                RandomGenerator random) {
        List<String> configured = properties.defaultImageUrls();
        if (configured == null || configured.isEmpty()) {
            String baseUrl = storage.s3().publicBaseUrl();
            requireImageUrl(baseUrl, "FILE_PUBLIC_BASE_URL 또는 PROFILE_DEFAULT_IMAGE_URLS");
            String base = baseUrl.replaceAll("/+$", "");
            configured = IntStream.rangeClosed(1, 4)
                    .mapToObj(index -> base + "/defaults/profile-" + index + ".png")
                    .toList();
        }
        configured.forEach(url -> requireImageUrl(url, "PROFILE_DEFAULT_IMAGE_URLS"));
        this.candidates = List.copyOf(configured);
        this.random = random;
    }

    private static void requireImageUrl(String value, String setting) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && value.length() <= 500) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // 설정값 자체는 로그에 노출하지 않는다.
        }
        throw new IllegalStateException(setting + "에 쿼리·fragment·사용자 정보 없는 HTTP(S) 이미지 주소를 설정해야 합니다.");
    }

    public String pick() {
        return candidates.get(random.nextInt(candidates.size()));
    }

    public boolean contains(String imageUrl) {
        return candidates.contains(imageUrl);
    }

    /** 과거 기본 URL 네 개만 복구한다. 별도 후보가 더 적으면 순서대로 순환 대응한다. */
    public Map<String, String> legacyReplacements() {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (int index = 0; index < 4; index++) {
            replacements.put("https://images.pickple.app/defaults/profile-" + (index + 1) + ".png",
                    candidates.get(index % candidates.size()));
        }
        return Map.copyOf(replacements);
    }

    public String resolveLegacy(String url) {
        return url == null ? null : legacyReplacements().getOrDefault(url, url);
    }
}
