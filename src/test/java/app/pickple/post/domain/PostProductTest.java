package app.pickple.post.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostProductTest {

    @Test
    void keepsLinkTextWithoutBusinessLengthLimit() {
        String longUrl = "https://example.test/products/" + "a".repeat(70_000);

        PostProduct product = new PostProduct(1L, "상품", null, longUrl, 1);

        assertThat(product.linkUrl()).isEqualTo(longUrl);
    }

    @Test
    void keepsLinkTextWithoutFormatRestriction() {
        String relativeText = "example.test/products/1";

        PostProduct product = new PostProduct(1L, "상품", null, relativeText, 1);

        assertThat(product.linkUrl()).isEqualTo(relativeText);
    }
}
