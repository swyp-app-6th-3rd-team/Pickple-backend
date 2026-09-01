package app.pickple.vote.service;

import app.pickple.auth.domain.*;
import app.pickple.post.domain.*;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class DeletedPostInteractionIT {
    @Autowired private VoteService voteService;
    @Autowired private PostStore postStore;
    @Autowired private UserStore userStore;

    @Test
    @DisplayName("삭제된 게시글에는 투표할 수 없다")
    void cannotVoteOnDeletedPost() {
        long seed = System.nanoTime();
        Long author = userStore.save(new User(SocialProvider.GOOGLE, "del-a-" + seed, null, "글쓴이")).id();
        Long voter = userStore.save(new User(SocialProvider.GOOGLE, "del-v-" + seed, null, "투표자")).id();
        Post post = postStore.save(new Post(author, PostType.GENERAL, PostCategory.ETC, "지울 글", null));
        Post loaded = postStore.findById(post.id()).orElseThrow();
        loaded.delete();
        postStore.save(loaded);

        assertThatThrownBy(() -> voteService.castOrChange(post.id(), 1L, voter))
                .isInstanceOf(IllegalStateException.class);
    }
}
