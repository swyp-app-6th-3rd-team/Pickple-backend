package app.pickple.comment.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/** package-private. */
interface OnePickRepository extends JpaRepository<OnePickEntity, Long> {

    /**
     * 이 사람이 이 게시글에서 이미 픽했는가 (R-05).
     *
     * <p>대상 댓글은 조건에 넣지 않는다 — 넣으면 "다른 댓글을 픽하는 경우" 를 못 잡는다.
     * 세는 단위는 댓글이 아니라 게시글이다.
     */
    boolean existsByUserIdAndPostId(Long userId, Long postId);

    long countByCommentId(Long commentId);

    long countByPostId(Long postId);
}
