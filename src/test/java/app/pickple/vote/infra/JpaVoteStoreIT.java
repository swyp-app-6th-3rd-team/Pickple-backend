package app.pickple.vote.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.vote.domain.Vote;
import app.pickple.vote.domain.VoteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class JpaVoteStoreIT {

    @Autowired
    private VoteStore voteStore;

    @Autowired
    private PostStore postStore;

    @Autowired
    private ItemContainerStore containerStore;

    @Autowired
    private UserStore userStore;

    private Long authorId;
    private Long voterId;

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        authorId = userStore.save(
                new User(SocialProvider.GOOGLE, "vote-author-" + seed, null, "글쓴이")).id();
        voterId = userStore.save(
                new User(SocialProvider.GOOGLE, "voter-" + seed, null, "투표자")).id();
    }

    /** 찬반 게시글 하나를 만들고 저장된 상태로 돌려준다. */
    private Post agreePost() {
        Long containerId = containerStore.save(new ItemContainer(authorId, AttachType.PRODUCT)
                .add(new ItemResource(1L, "p.jpg", "s3/" + System.nanoTime(), "https://cdn/x"))).id();
        return postStore.save(
                new Post(authorId, PostType.AGREE, PostCategory.ETC, "투표 대상", null)
                        .addProduct(new PostProduct(containerId, "상품", 1000L, null, 1))
                        .addOption(PostOption.ofLabel("사자", 1))
                        .addOption(PostOption.ofLabel("말자", 2)));
    }

    @Test
    @DisplayName("투표가 저장되고 인원으로 세어진다")
    void savesAndCounts() {
        Post post = agreePost();
        Long optionId = post.options().getFirst().id();

        voteStore.save(new Vote(post.id(), optionId, voterId));

        assertThat(voteStore.countByPost(post.id())).isEqualTo(1L);
        assertThat(voteStore.findByPostAndVoter(post.id(), voterId)).isPresent();
    }

    @Test
    @DisplayName("같은 사람이 같은 게시글에 두 번 투표할 수 없다 (R-09)")
    void duplicateVoteRejected() {
        // 응용 계층 검증만으로는 동시 요청에서 뚫린다. UNIQUE(post_id, user_id) 가 막는다.
        Post post = agreePost();
        Long optionId = post.options().getFirst().id();
        voteStore.save(new Vote(post.id(), optionId, voterId));

        assertThatThrownBy(() -> voteStore.save(new Vote(post.id(), optionId, voterId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("재투표는 사람 수를 늘리지 않는다 (R-22)")
    void revoteDoesNotIncreaseCount() {
        Post post = agreePost();
        Long first = post.options().get(0).id();
        Long second = post.options().get(1).id();
        Vote saved = voteStore.save(new Vote(post.id(), first, voterId));

        saved.changeTo(second);
        voteStore.save(saved);

        assertThat(voteStore.countByPost(post.id())).isEqualTo(1L);
        assertThat(voteStore.findByPostAndVoter(post.id(), voterId).orElseThrow().postOptionId())
                .isEqualTo(second);
    }

    @Test
    @DisplayName("다른 게시글의 선택지에는 투표할 수 없다 (R-10)")
    void crossPostOptionRejected() {
        // 복합 FK (post_option_id, post_id) 가 막는다. 단순 FK 였다면 통과했다.
        Post postA = agreePost();
        Post postB = agreePost();
        Long optionOfB = postB.options().getFirst().id();

        assertThatThrownBy(() -> voteStore.save(new Vote(postA.id(), optionOfB, voterId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
