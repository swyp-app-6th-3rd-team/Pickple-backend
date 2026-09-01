package app.pickple.item.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemContainerTest {

    private static ItemResource photo(String key) {
        return new ItemResource(1024L, "photo.jpg", key, "https://cdn.example.com/" + key);
    }

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("업로더가 없으면 만들 수 없다")
        void ownerIsRequired() {
            assertThatThrownBy(() -> new ItemContainer(null, AttachType.PRODUCT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("업로더");
        }

        @Test
        @DisplayName("용도가 없으면 만들 수 없다")
        void attachTypeIsRequired() {
            // 용도가 비면 부착 측 복합 FK 가 참조할 대상이 없어진다.
            assertThatThrownBy(() -> new ItemContainer(1L, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("용도");
        }

        @Test
        @DisplayName("갓 만든 컨테이너는 비어 있다")
        void startsEmpty() {
            assertThat(new ItemContainer(1L, AttachType.PRODUCT).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("장수 제약 (R-03)")
    class PhotoCount {

        @Test
        @DisplayName("빈 컨테이너는 상품에 붙일 수 없다")
        void emptyContainerRejected() {
            // 사진 없는 상품이 되는 것을 막는다. DB 는 컨테이너의 존재만 강제하고
            // 그 안에 파일이 있는지는 강제하지 못한다 (ERD 2차 2.2).
            ItemContainer container = new ItemContainer(1L, AttachType.PRODUCT);

            assertThatThrownBy(() -> container.verifyPhotoCount(1, 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("현재 0장");
        }

        @Test
        @DisplayName("찬반 상품은 1~3장이다")
        void agreeAllowsOneToThree() {
            ItemContainer container = new ItemContainer(1L, AttachType.PRODUCT)
                    .add(photo("a")).add(photo("b")).add(photo("c"));

            assertThatCode(() -> container.verifyPhotoCount(1, 3)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("4장째는 거부한다")
        void fourthPhotoRejected() {
            ItemContainer container = new ItemContainer(1L, AttachType.PRODUCT)
                    .add(photo("a")).add(photo("b")).add(photo("c")).add(photo("d"));

            assertThatThrownBy(() -> container.verifyPhotoCount(1, 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("현재 4장");
        }

        @Test
        @DisplayName("A/B 상품은 정확히 한 장이다")
        void abAllowsExactlyOne() {
            ItemContainer two = new ItemContainer(1L, AttachType.PRODUCT)
                    .add(photo("a")).add(photo("b"));

            assertThatThrownBy(() -> two.verifyPhotoCount(1, 1))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("용도 확인")
    class UsableAs {

        @Test
        @DisplayName("상품용 컨테이너를 댓글에 붙일 수 없다")
        void productContainerCannotAttachToComment() {
            // 스키마의 복합 FK 가 최종 방어선이지만, 도메인에서도 먼저 막아
            // 무의미한 왕복을 줄인다.
            ItemContainer product = new ItemContainer(1L, AttachType.PRODUCT);

            assertThatThrownBy(() -> product.verifyUsableAs(AttachType.COMMENT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("붙일 수 없습니다");
        }

        @Test
        @DisplayName("용도가 맞으면 통과한다")
        void matchingTypePasses() {
            ItemContainer comment = new ItemContainer(1L, AttachType.COMMENT);

            assertThatCode(() -> comment.verifyUsableAs(AttachType.COMMENT)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("파일")
    class Resources {

        @Test
        @DisplayName("담은 목록은 밖에서 바꿀 수 없다")
        void resourcesAreUnmodifiable() {
            ItemContainer container = new ItemContainer(1L, AttachType.PRODUCT).add(photo("a"));

            assertThatThrownBy(() -> container.resources().add(photo("b")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("빈 파일은 담을 수 없다")
        void nullResourceRejected() {
            ItemContainer container = new ItemContainer(1L, AttachType.PRODUCT);

            assertThatThrownBy(() -> container.add(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
