package app.pickple.comment.domain;

/**
 * 원픽 저장소.
 *
 * <p>중복 픽 판정은 애플리케이션이 아니라 {@code UNIQUE(user_id, comment_id)} 가 한다 (R-26).
 * 확인 후 삽입하는 방식은 동시 요청에서 뚫리므로, 유니크 위반을 잡아
 * {@link DuplicatePickException} 으로 옮긴다.
 */
public interface OnePickStore {

    /**
     * 원픽을 기록한다.
     *
     * @return 저장된 픽의 식별자. 포인트 지급의 멱등키가 된다 (R-13)
     * @throws DuplicatePickException 이미 픽한 댓글일 때
     */
    Long save(OnePick pick);

    /** 댓글이 받은 원픽 수. */
    long countByComment(Long commentId);

    /** 게시글에 달린 원픽 수. {@code post_id} 비정규화 덕에 조인이 없다. */
    long countByPost(Long postId);
}
