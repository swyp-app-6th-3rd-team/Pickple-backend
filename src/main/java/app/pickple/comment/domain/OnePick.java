package app.pickple.comment.domain;

/**
 * 원픽 — 사용자가 댓글 하나를 고른 <b>행위의 결과</b>다.
 *
 * <p>동사는 {@link Comment#pick(Long)} 가 갖는다. 테이블 이름({@code comment_pick})을
 * 그대로 클래스로 옮기면 동사가 명사로 납작해지므로, 행위는 애그리거트에 두고
 * 그 결과만 값 객체로 표현한다. 근거는 ADR-0018.
 *
 * <p>{@code postId} 는 비정규화다. 댓글을 거치면 알 수 있지만 게시글별 픽 집계를
 * 조인 없이 하려고 둔다. 어긋난 값이 들어가는 것은 복합 FK
 * {@code (comment_id, post_id)} 가 막는다.
 */
public record OnePick(Long commentId, Long postId, Long pickerId) {

    public OnePick {
        if (commentId == null) {
            throw new IllegalArgumentException("픽 대상 댓글이 필요합니다.");
        }
        if (postId == null) {
            throw new IllegalArgumentException("게시글이 필요합니다.");
        }
        if (pickerId == null) {
            throw new IllegalArgumentException("픽하는 사람이 필요합니다.");
        }
    }
}
