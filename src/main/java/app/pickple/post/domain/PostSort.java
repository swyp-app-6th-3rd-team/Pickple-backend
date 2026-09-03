package app.pickple.post.domain;

/**
 * 게시글 목록 정렬 기준 (기능명세서 §4.1).
 *
 * <p>정렬 키를 <b>허용 목록</b>으로 고정한다(SPEC §5.2). 클라이언트가 컬럼명을 직접
 * 넘기게 하면 인덱스가 없는 컬럼으로 정렬해 풀스캔이 나고, 노출되면 곤란한 컬럼이
 * 커서에 실린다.
 *
 * <p>각 값은 스키마의 인덱스와 짝을 이룬다 — 짝이 없는 정렬을 추가하면
 * 그 순간 filesort 로 떨어지므로, 값을 늘릴 때는 인덱스도 함께 본다.
 * <ul>
 *   <li>{@link #LATEST} — {@code idx_post_latest} / {@code idx_post_latest_all}</li>
 *   <li>{@link #POPULAR} — {@code idx_post_popular} / {@code idx_post_popular_all}</li>
 * </ul>
 */
public enum PostSort {

    /** 최신순. 기본값이다. */
    LATEST("createdAt"),

    /**
     * 인기순 — 투표한 사람 수 + 댓글을 단 사람 수 (R-24).
     *
     * <p>건수가 아니라 인원이다(R-25). 그 합은 {@code post.popularity_score} 생성 컬럼이
     * 이미 들고 있으므로 조회 시점에 집계하지 않는다.
     */
    POPULAR("popularityScore");

    private final String cursorKey;

    PostSort(String cursorKey) {
        this.cursorKey = cursorKey;
    }

    /** 커서에 실리는 정렬 키 이름. {@code (정렬키, id)} 쌍의 앞자리다. */
    public String cursorKey() {
        return cursorKey;
    }

    /**
     * 알 수 없는 값은 기본값으로 되돌린다.
     *
     * <p>400 으로 거부하지 않는 이유는 SPEC §5.2 의 "모르는 필드는 무시한다" 다 —
     * 목록 조회는 게스트도 부르는 진입 화면이라, 오타 하나로 빈 화면을 보이는 것보다
     * 기본 정렬로 보여주는 쪽이 낫다.
     */
    public static PostSort from(String value) {
        if (value == null || value.isBlank()) {
            return LATEST;
        }
        for (PostSort sort : values()) {
            if (sort.name().equalsIgnoreCase(value.trim())) {
                return sort;
            }
        }
        return LATEST;
    }
}
