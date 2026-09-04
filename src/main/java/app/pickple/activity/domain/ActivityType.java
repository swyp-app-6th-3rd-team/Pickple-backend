package app.pickple.activity.domain;

/**
 * 내 활동의 종류 (기능명세 §9.1).
 *
 * <p><b>세 값 모두 결과 행은 게시글이다.</b> 명세의 조회 데이터가 세 유형 모두
 * "게시글 유형 / 상품명 / 설명 / 댓글 수 / 투표 수 / 작성 시간 / 상품 사진" 이고,
 * 카드를 탭하면 게시글 상세(COM_03)로 간다. 그래서 이 값이 가르는 것은
 * <b>결과의 모양이 아니라 게시글을 좁히는 조건</b>이다 — 투표한 글이냐,
 * 댓글 단 글이냐, 내가 쓴 글이냐.
 *
 * <p>이 사실이 설계를 통째로 결정한다(ADR-0036). 활동 행을 목록 항목으로 삼았다면
 * 세 테이블을 UNION 해야 하고, 그러면 테이블마다 독립된 {@code id} 시퀀스 때문에
 * 커서에 판별자를 더해야 한다. 게시글이 항목이므로 커서는 언제나
 * {@code (정렬키, post.id)} 이고 기존 규약({@code PostListCursor})이 그대로 선다.
 */
public enum ActivityType {

    /** 내가 투표한 게시글. {@code vote} 에 내 행이 있는 글이다. */
    VOTE,

    /**
     * 내가 댓글을 단 게시글.
     *
     * <p>읽는 곳은 {@code comment} 가 아니라 <b>{@code post_commenter}</b> 다.
     * 그 테이블은 {@code UNIQUE(post_id, user_id)} 라 게시글당 정확히 한 행이어서,
     * 한 글에 댓글을 여러 개 달아도 목록에 한 번만 나온다(R-25).
     * {@code comment} 로 읽고 {@code DISTINCT} 를 걸면 커서가 가리키는 행이 사라진다.
     */
    COMMENT,

    /** 내가 올린 게시글. 활동 시각이 곧 작성 시각이다. */
    POST;

    /**
     * 알 수 없는 값은 기본값({@link #VOTE})으로 되돌린다.
     *
     * <p>400 으로 거부하지 않는 이유는 {@code PostSort.from} 과 같다(SPEC §5.2) —
     * 진입 화면이 오타 하나로 비지 않게 한다. 다만 <b>기본값의 근거는 다르다.</b>
     * 명세 §9.1 은 "마이페이지 메인화면에서 어떤 유형을 탭해서 들어왔는지에 따라
     * 기본 칩이 변경" 이라 적었다 — 즉 <b>칩은 항상 하나가 활성이고 "전체" 가 없다.</b>
     * 서버가 유형 없는 상태를 표현할 필요가 없으므로 {@code null} 을 전체로 풀지 않고
     * 첫 칩으로 접는다. 화면은 언제나 유형을 실어 보낸다.
     */
    public static ActivityType from(String value) {
        if (value == null || value.isBlank()) {
            return VOTE;
        }
        for (ActivityType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return VOTE;
    }
}
