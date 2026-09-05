package app.pickple.comment.service;

import app.pickple.comment.domain.*;
import app.pickple.point.domain.PointHistoryStore;
import app.pickple.post.service.ActivePostGuard;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OnePickServiceTest {
    @Test
    void rejectsExistingPickBeforeSavingOrGrantingPoints() {
        CommentStore comments = mock(CommentStore.class);
        OnePickStore picks = mock(OnePickStore.class);
        PointHistoryStore points = mock(PointHistoryStore.class);
        ActivePostGuard posts = mock(ActivePostGuard.class);
        Comment comment = Comment.restore(10L, 20L, 30L, "댓글", null, false);
        when(comments.findById(10L)).thenReturn(Optional.of(comment));
        when(picks.findByPickerIdAndPostId(40L, 20L)).thenReturn(Optional.of(new OnePick(11L, 20L, 40L)));

        assertThatThrownBy(() -> new OnePickService(comments, posts, picks, points).pick(10L, 40L))
                .isInstanceOf(DuplicatePickException.class);

        verify(picks, never()).save(any());
        verifyNoInteractions(points);
    }
}
