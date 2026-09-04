package app.pickple.post.infra;

import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostEntityTest {

    @Test
    void rejectsProductOptionBeforeProductIdWasGenerated() {
        Post post = new Post(1L, PostType.A_B, PostCategory.ETC, "A vs B", null)
                .addProduct(new PostProduct(10L, "A", null, null, 1))
                .addProduct(new PostProduct(20L, "B", null, null, 2))
                .addOption(PostOption.ofProductDisplayOrder(1, 1))
                .addOption(PostOption.ofProductDisplayOrder(2, 2));
        PostEntity entity = PostEntity.fromWithoutOptions(post, LocalDateTime.of(2026, 9, 4, 0, 0));

        assertThatThrownBy(() -> entity.addInitialOptions(post, LocalDateTime.of(2026, 9, 4, 0, 0)))
                .isInstanceOf(PostPersistenceException.class)
                .hasMessageContaining("상품 id가 아직 생성되지 않았습니다");
    }
}
