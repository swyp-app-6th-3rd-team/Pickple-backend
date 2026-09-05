package app.pickple.comment.domain;

import java.util.Optional;

/** 원픽 조회와 저장을 위한 포트. */
public interface OnePickStore {

    Optional<OnePick> findByPickerIdAndPostId(Long pickerId, Long postId);

    /**
     * 원픽을 기록한다. 식별자는 포인트 멱등키가 된다 (R-13).
     *
     * @throws DuplicatePickException 같은 사용자가 같은 게시글에 이미 픽을 저장한 경우
     */
    Long save(OnePick pick);

    /** 댓글이 받은 원픽 수. */
    long countByComment(Long commentId);

    /** 게시글에 달린 원픽 수. {@code post_id} 비정규화 덕에 조인이 없다. */
    long countByPost(Long postId);
}
