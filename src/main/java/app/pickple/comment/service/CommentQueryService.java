package app.pickple.comment.service;

import app.pickple.comment.domain.CommentQueryStore;
import app.pickple.common.RelativeTime;
import app.pickple.post.service.ActivePostGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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

    /**
     * 화면용 상대 시각.
     *
     * <p>계산은 {@link RelativeTime} 이 한다 — 게시글 상세(§6.2)가 같은 문구를 쓰는데
     * 여기에 두면 정본이 둘이 되고 경계값에서 갈라진다. 이 메서드는 기존 호출과
     * 경계값 테스트를 그대로 두기 위한 위임이다.
     */
    static String relativeTime(LocalDateTime createdAt, LocalDateTime now) {
        return RelativeTime.of(createdAt, now);
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
