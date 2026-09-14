package app.pickple.auth.service;

import app.pickple.config.FileStorageProperties;
import app.pickple.config.ProfileProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DefaultProfileImagesTest {

    private final RandomGenerator random = mock(RandomGenerator.class);

    @Test
    void reusesStorageCdnForAllFourCandidatesAndPreservesBasePath() {
        DefaultProfileImages images = images(List.of(), "https://cdn.example.com/images///");
        for (int index = 0; index < 4; index++) {
            given(random.nextInt(4)).willReturn(index);
            assertThat(images.pick()).isEqualTo(
                    "https://cdn.example.com/images/defaults/profile-" + (index + 1) + ".png");
        }
    }

    @Test
    void explicitCandidatesWorkWithoutStorageCdn() {
        DefaultProfileImages images = images(List.of("https://assets.example.com/custom.png"), "");
        given(random.nextInt(1)).willReturn(0);
        assertThat(images.pick()).isEqualTo("https://assets.example.com/custom.png");
        assertThat(images.legacyReplacements().values())
                .containsOnly("https://assets.example.com/custom.png");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "/images", "not-configured", "file:///profile.png", "https://cdn.example.com?token=x"})
    void rejectsMissingOrInvalidCdnWhenNoCandidatesWereConfigured(String base) {
        assertThatThrownBy(() -> images(List.of(), base)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILE_PUBLIC_BASE_URL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "/defaults/profile.png", "ftp://cdn.example.com/profile.png",
            "https://user:password@cdn.example.com/profile.png", "https://cdn.example.com/a.png#fragment"})
    void rejectsMalformedExplicitCandidatesInsteadOfReturningBrokenUrls(String url) {
        assertThatThrownBy(() -> images(List.of(url), "https://cdn.example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROFILE_DEFAULT_IMAGE_URLS");
    }

    @Test
    void repairsOnlyTheExactLegacyUrlsWithoutChoosingAgain() {
        DefaultProfileImages images = images(null, "https://cdn.example.com");
        for (int index = 1; index <= 4; index++) {
            assertThat(images.resolveLegacy("https://images.pickple.app/defaults/profile-" + index + ".png"))
                    .isEqualTo("https://cdn.example.com/defaults/profile-" + index + ".png");
        }
        for (String url : List.of("https://uploads.example.com/mine.png",
                "https://images.pickple.app/defaults/profile-2.png?custom=true",
                "https://images.pickple.app/defaults/profile-5.png", "")) {
            assertThat(images.resolveLegacy(url)).isEqualTo(url);
        }
        assertThat(images.resolveLegacy(null)).isNull();
        verifyNoInteractions(random);
    }

    private DefaultProfileImages images(List<String> candidates, String base) {
        return new DefaultProfileImages(new ProfileProperties(candidates),
                new FileStorageProperties(null, new FileStorageProperties.S3(null, null, null, base)), random);
    }
}
