package app.pickple.comment.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 이 사람이 이 게시글에서 픽한 댓글의 식별자.
     *
     * <p>{@code uk_pick_user_post} 가 최대 한 행을 보장하므로 {@code Optional} 이 성립한다.
     * 대상 댓글의 {@code deleted_at} 은 보지 않는다 — 삭제된 댓글을 가리키는 픽도
     * "이 글에서 이미 썼다" 는 사실이라 그대로 돌려준다 (R-06).
     */
    @Query("SELECT p.commentId FROM OnePickEntity p WHERE p.userId = :userId AND p.postId = :postId")
    Optional<Long> findPickedCommentId(@Param("userId") Long userId, @Param("postId") Long postId);

    long countByCommentId(Long commentId);

    long countByPostId(Long postId);
}
