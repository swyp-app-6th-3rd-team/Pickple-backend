package app.pickple.comment.domain;

/**
 * 게시글별 댓글 작성자(순 인원) 집계 (R-24·R-25).
 *
 * <p>인기순의 입력은 댓글 <b>건수</b>가 아니라 <b>인원 수</b>다.
 * 한 사람이 열 번 달아도 1이다.
 */
public interface PostCommenterStore {

    /**
     * 첫 댓글이면 기록하고 참을 돌려준다.
     *
     * <p>판정을 애플리케이션이 아니라 유니크 키가 한다 —
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} 의 영향 행 수로 가른다.
     * {@code SELECT} 후 {@code INSERT} 하면 동시 댓글에서 둘 다 통과한다.
     *
     * @return 이 사람의 첫 댓글이면 {@code true}. 호출자는 이때만 카운터를 올린다
     */
    boolean recordIfFirst(Long postId, Long userId);

    /** 게시글의 댓글 인원 수. */
    long countByPost(Long postId);
}
