package app.pickple.comment.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** package-private. */
interface PostCommenterRepository extends JpaRepository<PostCommenterEntity, Long> {

    /**
     * 첫 댓글이면 행을 만든다. 영향 행이 1이면 처음이다.
     *
     * <p>{@code SELECT} 후 {@code INSERT} 하면 동시 댓글에서 둘 다 "처음"으로 판정되어
     * 카운터가 두 번 오른다. 유니크 키가 원자적으로 가르므로 그 틈이 없다 (ERD 2차 2.4).
     *
     * <p><b>{@code ON DUPLICATE KEY UPDATE} 가 아니라 {@code INSERT IGNORE} 를 쓴다.</b>
     * ODKU 로 {@code post_id = post_id} 를 넣으면 값이 그대로라 MySQL 이 이를
     * "변경 없음" 으로 볼지 "갱신함" 으로 볼지가 드라이버·설정에 따라 갈려
     * 영향 행 수를 첫 댓글 판정에 쓸 수 없다. {@code INSERT IGNORE} 는
     * 실제 삽입되면 1, 유니크로 걸리면 0 으로 명확하다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO post_commenter (post_id, user_id, created_at)
            VALUES (:postId, :userId, NOW())
            """, nativeQuery = true)
    int insertIfAbsent(@Param("postId") Long postId, @Param("userId") Long userId);

    long countByPostId(Long postId);
}
