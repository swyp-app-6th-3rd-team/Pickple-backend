package app.pickple.comment.service;

import app.pickple.comment.domain.CommentQueryStore;
import app.pickple.post.service.ActivePostGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 댓글 목록을 화면용 읽기 모델로 조립한다. */
@Service
@RequiredArgsConstructor
public class CommentQueryService {

    private final CommentQueryStore commentQueryStore;
    private final ActivePostGuard activePost;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CommentListResult findAll(Long postId, Long viewerId) {
        activePost.requireActive(postId);
        LocalDateTime now = LocalDateTime.now(clock);
        List<CommentResult> comments = commentQueryStore.findAllByPostId(postId).stream()
                .map(comment -> toResult(comment, viewerId, now))
                .toList();
        return new CommentListResult(comments.size(), comments);
    }

    private CommentResult toResult(
            CommentQueryStore.CommentView comment,
            Long viewerId,
            LocalDateTime now) {
        return new CommentResult(
                comment.id(),
                comment.authorId(),
                comment.profileImageUrl(),
                comment.nickname(),
                comment.createdAt(),
                relativeTime(comment.createdAt(), now),
                comment.content(),
                comment.onePickCount(),
                viewerId != null && viewerId.equals(comment.authorId()));
    }

    static String relativeTime(LocalDateTime createdAt, LocalDateTime now) {
        Duration elapsed = Duration.between(createdAt, now);
        if (elapsed.isNegative()) {
            elapsed = Duration.ZERO;
        }

        long minutes = elapsed.toMinutes();
        if (minutes < 60) {
            return minutes + "분 전";
        }

        long hours = elapsed.toHours();
        if (hours < 24) {
            return hours + "시간 전";
        }

        long days = elapsed.toDays();
        if (days < 365) {
            return days + "일 전";
        }
        return days / 365 + "년 전";
    }

    public record CommentListResult(long commentCount, List<CommentResult> comments) {
    }

    public record CommentResult(
            Long id,
            Long authorId,
            String profileImageUrl,
            String nickname,
            LocalDateTime createdAt,
            String createdAgo,
            String content,
            long onePickCount,
            boolean mine) {
    }
}
