package app.pickple.comment.service;

import app.pickple.comment.domain.CommentQueryStore;
import app.pickple.post.service.ActivePostGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommentQueryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-09-03T03:00:00Z");

    @Mock
    private CommentQueryStore commentQueryStore;
    @Mock
    private ActivePostGuard activePost;

    private CommentQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new CommentQueryService(
                commentQueryStore, activePost, Clock.fixed(NOW, ZONE));
    }

    @Test
    void mapsViewerOwnershipAndCount() {
        LocalDateTime createdAt = LocalDateTime.ofInstant(NOW, ZONE).minusMinutes(7);
        given(commentQueryStore.findAllByPostId(10L)).willReturn(List.of(
                new CommentQueryStore.CommentView(
                        1L, 20L, "https://image.example/profile.png", "피커", createdAt,
                        "도움이 돼요", 2L)));

        CommentQueryService.CommentListResult result = queryService.findAll(10L, 20L);

        verify(activePost).requireActive(10L);
        assertThat(result.commentCount()).isEqualTo(1L);
        assertThat(result.comments()).singleElement().satisfies(comment -> {
            assertThat(comment.onePickCount()).isEqualTo(2L);
            assertThat(comment.createdAgo()).isEqualTo("7분 전");
            assertThat(comment.mine()).isTrue();
        });
    }

    @Test
    void guestNeverOwnsComment() {
        LocalDateTime createdAt = LocalDateTime.ofInstant(NOW, ZONE);
        given(commentQueryStore.findAllByPostId(10L)).willReturn(List.of(
                new CommentQueryStore.CommentView(
                        1L, 20L, null, "피커", createdAt, "내용", 0L)));

        assertThat(queryService.findAll(10L, null).comments().getFirst().mine()).isFalse();
    }

    @Test
    void formatsRelativeTimeAtUnitBoundaries() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);

        assertThat(CommentQueryService.relativeTime(now.plusSeconds(1), now)).isEqualTo("0분 전");
        assertThat(CommentQueryService.relativeTime(now.minusSeconds(59), now)).isEqualTo("0분 전");
        assertThat(CommentQueryService.relativeTime(now.minusMinutes(59), now)).isEqualTo("59분 전");
        assertThat(CommentQueryService.relativeTime(now.minusMinutes(60), now)).isEqualTo("1시간 전");
        assertThat(CommentQueryService.relativeTime(now.minusHours(23), now)).isEqualTo("23시간 전");
        assertThat(CommentQueryService.relativeTime(now.minusHours(24), now)).isEqualTo("1일 전");
        assertThat(CommentQueryService.relativeTime(now.minusDays(364), now)).isEqualTo("364일 전");
        assertThat(CommentQueryService.relativeTime(now.minusDays(365), now)).isEqualTo("1년 전");
    }
}
