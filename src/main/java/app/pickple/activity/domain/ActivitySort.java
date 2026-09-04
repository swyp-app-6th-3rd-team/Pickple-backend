package app.pickple.activity.domain;

/**
 * 내 활동 목록의 정렬 기준 (기능명세 §9.1 · §9.2).
 *
 * <p><b>왜 {@code PostSort} 를 재사용하지 않는가</b> — 세 축이 다르다.
 * <ol>
 *   <li><b>값의 집합</b> — 명세가 "최신순, 오래된 순" 을 요구해 {@link #OLDEST} 가 있다.
 *       {@code PostSort} 에 얹으면 공개 목록 {@code GET /posts} 의 계약이
 *       조용히 넓어진다.</li>
 *   <li><b>비교 방향</b> — 오래된순은 keyset 조건이 {@code >} 이고 {@code ORDER BY} 가
 *       오름차순이다. {@code PostListRepository} 는 {@code <}·{@code DESC} 가
 *       박혀 있어 이 축 자체가 없다.</li>
 *   <li><b>정렬 키의 주인</b> — {@code PostSort.LATEST} 는 게시글 작성 시각이지만
 *       여기 {@link #LATEST} 는 <b>내 활동 시각</b>이다. 화면 이름이 "나의 활동" 이고,
 *       어제 올라온 글에 방금 단 댓글이 맨 위에 와야 다시 찾을 수 있다(ADR-0036).</li>
 * </ol>
 *
 * <p>각 값은 인덱스와 짝을 이룬다 — 짝이 없는 정렬을 더하면 그 순간 filesort 로 떨어진다.
 * {@link #LATEST}·{@link #OLDEST} 는 활동 테이블의 {@code (user_id, …, created_at)}
 * 인덱스를 각각 역방향·정방향으로 읽는다. InnoDB 는 {@code DESC} 인덱스를 오름차순으로도
 * 스캔하므로(backward index scan) 둘 다 정렬 없이 끝난다.
 */
public enum ActivitySort {

    /** 최신순. 기본값이다 (§9.2 "기본은 최신순으로 고정"). */
    LATEST("activityAt", false),

    /** 오래된순. 같은 인덱스를 반대 방향으로 읽는다. */
    OLDEST("activityAt", true),

    /**
     * 인기순 — 투표한 사람 수 + 댓글을 단 사람 수 (R-24).
     *
     * <p>건수가 아니라 인원이다(R-25). 그 합은 {@code post.popularity_score}
     * 생성 컬럼이 이미 들고 있으므로 조회 시점에 집계하지 않는다.
     * {@code GET /posts} 의 인기순과 <b>같은 컬럼 하나</b>를 읽는다 —
     * 양쪽이 각자 집계하면 같은 화면에서 다른 값이 나온다.
     */
    POPULAR("popularityScore", false);

    private final String cursorKey;
    private final boolean ascending;

    ActivitySort(String cursorKey, boolean ascending) {
        this.cursorKey = cursorKey;
        this.ascending = ascending;
    }

    /** 커서에 실리는 정렬 키 이름. {@code (정렬키, id)} 쌍의 앞자리다. */
    public String cursorKey() {
        return cursorKey;
    }

    /** 오름차순인가. keyset 비교 부등호와 {@code ORDER BY} 방향이 함께 뒤집힌다. */
    public boolean ascending() {
        return ascending;
    }

    /** 정렬 키가 활동 시각인가. 아니면 게시글의 인기 점수다. */
    public boolean byActivityTime() {
        return this != POPULAR;
    }

    /**
     * 알 수 없는 값은 기본값으로 되돌린다.
     *
     * <p>400 으로 거부하지 않는다 — SPEC §5.2 "모르는 필드는 무시한다" 와
     * {@code PostSort.from} 이 세운 규칙을 그대로 따른다.
     */
    public static ActivitySort from(String value) {
        if (value == null || value.isBlank()) {
            return LATEST;
        }
        for (ActivitySort sort : values()) {
            if (sort.name().equalsIgnoreCase(value.trim())) {
                return sort;
            }
        }
        return LATEST;
    }
}
