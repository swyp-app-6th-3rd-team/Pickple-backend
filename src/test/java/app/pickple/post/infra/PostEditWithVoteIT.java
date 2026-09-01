package app.pickple.post.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.domain.*;
import app.pickple.support.IntegrationTest;
import app.pickple.vote.service.VoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Codex 리뷰 지적 1번 재현 — 투표가 있는 게시글을 수정할 수 있는가. */
@IntegrationTest
class PostEditWithVoteIT {

    @Autowired private PostStore postStore;
    @Autowired private VoteService voteService;
    @Autowired private ItemContainerStore containerStore;
    @Autowired private UserStore userStore;

    private Long authorId;
    private Long voterId;

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        authorId = userStore.save(new User(SocialProvider.GOOGLE, "edit-a-" + seed, null, "글쓴이")).id();
        voterId = userStore.save(new User(SocialProvider.GOOGLE, "edit-v-" + seed, null, "투표자")).id();
    }

    @Test
    @DisplayName("투표가 달린 게시글의 제목을 수정할 수 있다")
    void canEditPostThatHasVotes() {
        Long c = containerStore.save(new ItemContainer(authorId, AttachType.PRODUCT)
                .add(new ItemResource(1L, "p.jpg", "s3/" + System.nanoTime(), "https://cdn/x"))).id();
        Post post = postStore.save(new Post(authorId, PostType.AGREE, PostCategory.ETC, "원래 제목", null)
                .addProduct(new PostProduct(c, "상품", 1000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2)));
        Long optionId = post.options().getFirst().id();
        voteService.castOrChange(post.id(), optionId, voterId);

        Post loaded = postStore.findById(post.id()).orElseThrow();
        loaded.edit("바뀐 제목", null, null);
        postStore.save(loaded);

        Post reloaded = postStore.findById(post.id()).orElseThrow();
        assertThat(reloaded.title()).isEqualTo("바뀐 제목");
        // 선택지 id 가 유지돼야 투표가 가리키는 대상이 안 깨진다
        assertThat(reloaded.options().getFirst().id()).isEqualTo(optionId);
    }
}
