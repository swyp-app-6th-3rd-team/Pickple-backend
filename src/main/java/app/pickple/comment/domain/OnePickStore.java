package app.pickple.comment.domain;

/**
 * 원픽 저장소.
 *
 * <p>중복 판정은 애플리케이션이 아니라 {@code UNIQUE(user_id, post_id)} 가 한다 (R-05) —
 * 확인 후 삽입하는 방식은 동시 요청에서 뚫린다.
 *
 * <p><b>저장소는 "삽입됐는가" 라는 사실만 알린다.</b> 그것이 "이미 픽했다" 는
 * 정책 위반이라는 해석은 서비스의 몫이다 (ADR-0019).
 */
public interface OnePickStore {

    /**
     * 아직 픽하지 않았다면 기록한다.
     *
     * @return 저장된 픽의 식별자. 이미 픽했으면 빈 값. 식별자는 포인트 멱등키가 된다 (R-13)
     */
    java.util.Optional<Long> saveIfAbsent(OnePick pick);

    /** 댓글이 받은 원픽 수. */
    long countByComment(Long commentId);

    /** 게시글에 달린 원픽 수. {@code post_id} 비정규화 덕에 조인이 없다. */
    long countByPost(Long postId);
}
