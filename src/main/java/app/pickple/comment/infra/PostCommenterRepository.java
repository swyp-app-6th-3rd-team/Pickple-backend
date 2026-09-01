package app.pickple.comment.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private. */
interface PostCommenterRepository extends JpaRepository<PostCommenterEntity, Long> {

    /**
     * 첫 댓글이면 행을 만든다. 영향 행 수가 1이면 처음이다.
     *
     * <p>{@code SELECT} 후 {@code INSERT} 하면 동시 댓글에서 둘 다 "처음"으로 판정되어
     * 카운터가 두 번 오른다. {@code ON DUPLICATE KEY UPDATE} 는 유니크 키가
     * 원자적으로 가르므로 그 틈이 없다 (ERD 2차 2.4).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO post_commenter (post_id, user_id, created_at)
            VALUES (:postId, :userId, NOW())
            ON DUPLICATE KEY UPDATE post_id = post_id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("postId") Long postId, @Param("userId") Long userId);

    long countByPostId(Long postId);
}
