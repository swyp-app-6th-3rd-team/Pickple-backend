package app.pickple.post.domain;

/**
 * 게시글 집계 카운터 갱신 (R-22·R-24·R-25).
 *
 * <p>카운터는 <b>Java 에서 읽고 더해 쓰지 않는다.</b> 그렇게 하면 두 요청이
 * 같은 값을 읽고 각자 +1 해서 하나가 사라진다(lost update).
 * {@code UPDATE post SET vote_count = vote_count + 1} 처럼 DB 가 원자적으로 올린다.
 *
 * <p>게시글 본문 수정과 경로를 나눈 이유도 같다 — 일반 엔티티 UPDATE 는 SET 절에
 * 카운터 컬럼을 포함할 수 있어, 그 사이 증가한 값을 오래된 스냅샷으로 덮어쓴다.
 */
public interface PostCounters {

    /** 투표한 사람이 늘었다. 재투표는 부르지 않는다 (R-22). */
    void increaseVoteCount(Long postId);

    /** 이 선택지가 표를 하나 얻었다. 득표율 표시용 (R-22). */
    void increaseOptionVoteCount(Long postOptionId);

    /**
     * 이 선택지가 표를 하나 잃었다. 선택 변경에서만 부른다 (R-22).
     *
     * <p>표를 되돌리는 경로가 여기뿐이라 0 아래로 갈 일이 없지만,
     * {@code INT UNSIGNED} 는 언더플로에서 조용히 0 이 되는 게 아니라
     * {@code ERROR 1690} 으로 요청을 통째로 죽인다. 바닥을 0 으로 막는다.
     */
    void decreaseOptionVoteCount(Long postOptionId);

    /** 이 게시글에 처음 댓글을 단 사람이 생겼다 (R-25). */
    void increaseCommenterCount(Long postId);

    /** 댓글 건수. 화면 표시용이라 인기순에는 쓰지 않는다. */
    void increaseCommentCount(Long postId);

    void decreaseCommentCount(Long postId);
}
