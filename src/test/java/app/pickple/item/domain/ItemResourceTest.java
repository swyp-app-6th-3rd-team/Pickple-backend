package app.pickple.item.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemResourceTest {

    @Test
    @DisplayName("크기가 0 이하면 만들 수 없다")
    void sizeMustBePositive() {
        assertThatThrownBy(() -> new ItemResource(0L, "a.jpg", "key", "https://cdn/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("파일 크기");
    }

    @Test
    @DisplayName("원본 파일명이 비면 만들 수 없다")
    void originalFileNameRequired() {
        assertThatThrownBy(() -> new ItemResource(1L, "  ", "key", "https://cdn/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("원본 파일명");
    }

    @Test
    @DisplayName("S3 키가 비면 만들 수 없다")
    void itemKeyRequired() {
        // 키가 없으면 나중에 삭제·재발급을 할 수 없다.
        assertThatThrownBy(() -> new ItemResource(1L, "a.jpg", null, "https://cdn/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("S3");
    }

    @Test
    @DisplayName("접근 URL 이 비면 만들 수 없다")
    void accessUrlRequired() {
        assertThatThrownBy(() -> new ItemResource(1L, "a.jpg", "key", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }
}
