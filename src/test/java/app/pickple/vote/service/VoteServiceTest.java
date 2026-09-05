package app.pickple.vote.service;

import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostCounters;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.post.service.ActivePostGuard;
import app.pickple.vote.domain.Vote;
import app.pickple.vote.domain.VoteActivityRecorder;
import app.pickple.vote.domain.VoteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VoteServiceTest {

    private static final long POST_ID = 10L;
    private static final long OPTION_ID = 20L;
    private static final long VOTER_ID = 30L;

    @Mock
    private VoteStore voteStore;
    @Mock
    private PostStore postStore;
    @Mock
    private ActivePostGuard activePost;
    @Mock
    private PostCounters counters;
    @Mock
    private VoteActivityRecorder badges;

    private VoteService service;

    @BeforeEach
    void setUp() {
        service = new VoteService(voteStore, postStore, activePost, counters, badges);
    }

    @Test
    @DisplayName("활성 게시글 검증 직후 게시글이 없으면 내부 일관성 오류로 분류한다")
    void missingPostAfterActiveGuardIsConsistencyFailure() {
        given(postStore.findById(POST_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.castOrChange(POST_ID, OPTION_ID, VOTER_ID))
                .isInstanceOf(VoteConsistencyException.class)
                .hasMessageContaining("활성 게시글 검증 뒤")
                .hasMessageContaining("id=10");

        verify(voteStore, never()).findByPostAndVoter(anyLong(), anyLong());
    }

    @Test
    @DisplayName("투표 반영 뒤 게시글을 다시 읽지 못하면 내부 일관성 오류로 분류한다")
    void missingPostDuringTallyIsConsistencyFailure() {
        Post post = votablePost();
        given(postStore.findById(POST_ID))
                .willReturn(Optional.of(post))
                .willReturn(Optional.empty());
        given(voteStore.findByPostAndVoter(POST_ID, VOTER_ID))
                .willReturn(Optional.of(Vote.restore(40L, POST_ID, OPTION_ID, VOTER_ID)));

        assertThatThrownBy(() -> service.castOrChange(POST_ID, OPTION_ID, VOTER_ID))
                .isInstanceOf(VoteConsistencyException.class)
                .hasMessageContaining("투표 반영 뒤")
                .hasMessageContaining("id=10");

        verify(voteStore, never()).countByPost(anyLong());
    }

    private Post votablePost() {
        return Post.restore(
                POST_ID,
                1L,
                PostType.AGREE,
                PostCategory.ETC,
                "게시글",
                null,
                List.of(),
                List.of(
                        PostOption.restore(OPTION_ID, null, "사자", 1, 0L),
                        PostOption.restore(21L, null, "말자", 2, 0L)),
                0L,
                0L,
                0L,
                false);
    }
}
