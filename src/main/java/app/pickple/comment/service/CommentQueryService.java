package app.pickple.comment.service;

import app.pickple.comment.domain.CommentQueryStore;
import app.pickple.comment.domain.OnePickStore;
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
    private final OnePickStore onePickStore;
    private final ActivePostGuard activePost;
    private final Clock clock;

    /**
     * 게시글의 활성 댓글과 조회자의 원픽 상태.
     *
     * <p><b>원픽 상태를 목록 문장에 섞지 않는다.</b> 목록은 {@code deleted_at IS NULL} 로 거르는데
     * 픽은 그 필터를 넘어 살아남아(R-06), 픽한 댓글이 삭제되면 값을 실어 나를 행이 사라진다.
     * 활성 댓글이 0건이면 행 자체가 없어 조인으로는 표현할 길이 아예 없다.
     * <b>게시글에 속한 값이라 게시글 단위로 따로 읽는다</b> — 문장 하나를 더 쓰는 이유다.
     */
    @Transactional(readOnly = true)
    public CommentListResult findAll(Long postId, Long viewerId) {
        activePost.requireActive(postId);
        LocalDateTime now = LocalDateTime.now(clock);
        List<CommentResult> comments = commentQueryStore.findAllByPostId(postId).stream()
                .map(comment -> toResult(comment, viewerId, now))
                .toList();
        return new CommentListResult(
                comments.size(),
                comments,
                onePickStore.findPickedCommentId(viewerId, postId).orElse(null));
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

    /**
     * @param myOnePickCommentId 조회자가 이 게시글에서 픽한 댓글. 픽한 적이 없으면 {@code null} 이다.
     *                           삭제된 댓글을 가리킬 수 있어 {@code comments} 에 없는 값일 수 있다 (R-06)
     */
    public record CommentListResult(
            long commentCount,
            List<CommentResult> comments,
            Long myOnePickCommentId) {
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
