package app.pickple.comment.infra;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** package-private. 바깥은 {@link app.pickple.comment.domain.CommentStore} 만 본다. */
interface CommentRepository extends JpaRepository<CommentEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CommentEntity c WHERE c.id = :id")
    Optional<CommentEntity> findByIdForUpdate(@Param("id") Long id);

    /** 작성자와 원픽 수를 한 SQL로 읽어 댓글 수에 따른 ORM N+1을 막는다. */
    @Query(value = """
            SELECT c.id AS id,
                   c.user_id AS authorId,
                   u.profile_image_url AS profileImageUrl,
                   COALESCE(NULLIF(u.nickname, ''), NULLIF(u.name, ''), '알 수 없음') AS nickname,
                   c.created_at AS createdAt,
                   c.content AS content,
                   COUNT(cp.id) AS onePickCount
              FROM comment c
              JOIN users u ON u.id = c.user_id
              LEFT JOIN comment_pick cp ON cp.comment_id = c.id
             WHERE c.post_id = :postId
               AND c.deleted_at IS NULL
             GROUP BY c.id, c.user_id, u.profile_image_url, u.nickname, u.name,
                      c.created_at, c.content
             ORDER BY c.created_at ASC, c.id ASC
            """, nativeQuery = true)
    List<CommentListRow> findAllActiveWithAuthorAndPickCount(@Param("postId") Long postId);

    interface CommentListRow {

        Long getId();

        Long getAuthorId();

        String getProfileImageUrl();

        String getNickname();

        LocalDateTime getCreatedAt();

        String getContent();

        Long getOnePickCount();
    }
}
