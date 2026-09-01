package app.pickple.post.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostTest {

    private static Post agree() {
        return new Post(1L, PostType.AGREE, PostCategory.FASHION, "이거 살까?", null);
    }

    private static Post ab() {
        return new Post(1L, PostType.A_B, PostCategory.BEAUTY, "A vs B", null);
    }

    private static Post general() {
        return new Post(1L, PostType.GENERAL, PostCategory.ETC, "그냥 잡담", null);
    }

    private static PostProduct product(int order) {
        return new PostProduct((long) order, "상품" + order, 10_000L, null, order);
    }

    @Nested
    @DisplayName("상품 수 (R-02)")
    class ProductCount {

        @Test
        @DisplayName("찬반은 상품이 하나여야 한다")
        void agreeNeedsExactlyOne() {
            Post post = agree();

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("상품은 1개");
        }

        @Test
        @DisplayName("찬반에 상품 둘은 거부한다")
        void agreeRejectsTwo() {
            Post post = agree().addProduct(product(1)).addProduct(product(2))
                    .addOption(PostOption.ofLabel("사자", 1))
                    .addOption(PostOption.ofLabel("말자", 2));

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("현재 2개");
        }

        @Test
        @DisplayName("A/B 는 상품이 둘이어야 한다")
        void abNeedsExactlyTwo() {
            Post post = ab().addProduct(product(1))
                    .addOption(PostOption.ofProduct(1L, 1))
                    .addOption(PostOption.ofProduct(2L, 2));

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("상품은 2개");
        }

        @Test
        @DisplayName("일반은 상품을 갖지 않는다")
        void generalHasNoProduct() {
            Post post = general().addProduct(product(1));

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("상품은 0개");
        }

        @Test
        @DisplayName("상품 순서가 겹치면 거부한다")
        void duplicateOrderRejected() {
            // uk_product_post_order 가 DB 에서도 막지만, 저장 왕복 전에 잡는다.
            Post post = ab().addProduct(product(1)).addProduct(product(1))
                    .addOption(PostOption.ofProduct(1L, 1))
                    .addOption(PostOption.ofProduct(2L, 2));

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("표시 순서가 중복");
        }
    }

    @Nested
    @DisplayName("선택지 (R-04)")
    class Options {

        @Test
        @DisplayName("찬반은 선택지가 정확히 둘이다")
        void agreeNeedsTwoOptions() {
            Post post = agree().addProduct(product(1))
                    .addOption(PostOption.ofLabel("사자", 1));

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("선택지는 2개");
        }

        @Test
        @DisplayName("일반은 선택지를 갖지 않는다")
        void generalHasNoOption() {
            Post post = general().addOption(PostOption.ofLabel("사자", 1));

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("선택지는 0개");
        }

        @Test
        @DisplayName("찬반의 선택지가 상품을 가리키면 거부한다")
        void agreeRejectsProductOption() {
            Post post = agree().addProduct(product(1))
                    .addOption(PostOption.ofProduct(1L, 1))
                    .addOption(PostOption.ofProduct(2L, 2));

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("라벨이어야");
        }

        @Test
        @DisplayName("A/B 의 선택지가 라벨이면 거부한다")
        void abRejectsLabelOption() {
            Post post = ab().addProduct(product(1)).addProduct(product(2))
                    .addOption(PostOption.ofLabel("사자", 1))
                    .addOption(PostOption.ofLabel("말자", 2));

            assertThatThrownBy(post::verifyPublishable)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("상품을 가리켜야");
        }

        @Test
        @DisplayName("제대로 갖추면 통과한다")
        void wellFormedPasses() {
            Post post = agree().addProduct(product(1))
                    .addOption(PostOption.ofLabel("사자", 1))
                    .addOption(PostOption.ofLabel("말자", 2));

            assertThatCode(post::verifyPublishable).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("사진 장수 (R-03)")
    class PhotoCount {

        @Test
        @DisplayName("찬반은 1~3장이다")
        void agreeAllowsUpToThree() {
            Post post = agree().addProduct(product(1));

            assertThatCode(() -> post.verifyPhotoCount(p -> 3)).doesNotThrowAnyException();
            assertThatThrownBy(() -> post.verifyPhotoCount(p -> 4))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1~3장");
        }

        @Test
        @DisplayName("A/B 는 상품마다 한 장이다")
        void abAllowsExactlyOne() {
            Post post = ab().addProduct(product(1)).addProduct(product(2));

            assertThatThrownBy(() -> post.verifyPhotoCount(p -> 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1~1장");
        }

        @Test
        @DisplayName("사진이 없으면 거부한다")
        void zeroPhotosRejected() {
            Post post = agree().addProduct(product(1));

            assertThatThrownBy(() -> post.verifyPhotoCount(p -> 0))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("유형 불변 (R-01)")
    class TypeImmutability {

        @Test
        @DisplayName("수정해도 유형은 그대로다")
        void editDoesNotChangeType() {
            // edit 에 유형 파라미터 자체가 없다. 바꿀 방법을 두지 않는 것이 규칙이다.
            Post post = agree();

            post.edit("바뀐 제목", "설명", PostCategory.LIVING);

            assertThat(post.type()).isEqualTo(PostType.AGREE);
            assertThat(post.title()).isEqualTo("바뀐 제목");
            assertThat(post.category()).isEqualTo(PostCategory.LIVING);
        }
    }

    @Nested
    @DisplayName("생성과 삭제")
    class Lifecycle {

        @Test
        @DisplayName("제목이 30자를 넘으면 거부한다")
        void titleTooLong() {
            assertThatThrownBy(() -> new Post(1L, PostType.GENERAL, PostCategory.ETC, "가".repeat(31), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("30자");
        }

        @Test
        @DisplayName("작성자가 없으면 만들 수 없다")
        void authorRequired() {
            assertThatThrownBy(() -> new Post(null, PostType.GENERAL, PostCategory.ETC, "제목", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("작성자");
        }

        @Test
        @DisplayName("두 번 삭제할 수 없다")
        void deleteTwiceRejected() {
            Post post = general();
            post.delete();

            assertThatThrownBy(post::delete).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("인기순은 투표 인원과 댓글 인원의 합이다 (R-24)")
        void popularityIsSumOfPeople() {
            Post post = Post.restore(1L, 1L, PostType.AGREE, PostCategory.ETC, "t", null,
                    java.util.List.of(), java.util.List.of(), 7L, 3L, 12L, false);

            // 댓글 건수(12)가 아니라 댓글 인원(3)을 더한다.
            assertThat(post.popularityScore()).isEqualTo(10L);
        }
    }
}
