package app.pickple.comment.infra;

import app.pickple.comment.domain.Comment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class JpaCommentStoreTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CommentRepository repository;

    private JpaCommentStore store;

    @BeforeEach
    void setUp() {
        store = new JpaCommentStore(repository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("존재하지 않는 댓글 갱신은 영속화 오류로 분류한다")
    void missingCommentOnUpdateIsPersistenceFailure() {
        Comment comment = Comment.restore(17L, 10L, 20L, "댓글", null, false);
        given(repository.findById(17L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> store.save(comment))
                .isInstanceOf(CommentPersistenceException.class)
                .hasMessageContaining("id=17");
    }
}
