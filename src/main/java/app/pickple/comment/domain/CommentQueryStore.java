package app.pickple.comment.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 댓글 목록 화면에 필요한 읽기 모델 저장소. */
public interface CommentQueryStore {

    /**
     * 삭제되지 않은 댓글을 생성 시각과 식별자 오름차순으로 조회한다.
     *
     * <p><b>뷰어를 받지 않는다.</b> 내 원픽 상태는 댓글 행이 아니라 게시글에 속해
     * 이 문장으로는 표현되지 않는다 — 근거는 {@link OnePickStore#findPickedCommentId}.
     */
    List<CommentView> findAllByPostId(Long postId);

    record CommentView(
            Long id,
            Long authorId,
            String profileImageUrl,
            String nickname,
            LocalDateTime createdAt,
            String content,
            long onePickCount) {
    }
}
