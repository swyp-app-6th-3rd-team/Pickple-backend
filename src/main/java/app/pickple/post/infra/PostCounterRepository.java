package app.pickple.post.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 카운터 전용 원자 갱신. package-private.
 *
 * <p>{@code GREATEST(.., 0)} 은 {@code INT UNSIGNED} 언더플로를 막는다 —
 * 0 에서 1 을 빼면 MySQL 이 {@code ERROR 1690} 을 낸다.
 */
interface PostCounterRepository extends JpaRepository<PostEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE post SET vote_count = vote_count + 1 WHERE id = :postId", nativeQuery = true)
    void increaseVoteCount(@Param("postId") Long postId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE post SET commenter_count = commenter_count + 1 WHERE id = :postId", nativeQuery = true)
    void increaseCommenterCount(@Param("postId") Long postId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE post SET comment_count = comment_count + 1 WHERE id = :postId", nativeQuery = true)
    void increaseCommentCount(@Param("postId") Long postId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE post SET comment_count = GREATEST(CAST(comment_count AS SIGNED) - 1, 0) WHERE id = :postId",
            nativeQuery = true)
    void decreaseCommentCount(@Param("postId") Long postId);
}
