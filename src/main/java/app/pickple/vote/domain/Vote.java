package app.pickple.vote.domain;

/**
 * 선택지 하나를 고른 사건. 게시글당 한 사람은 한 번이다 (R-09).
 *
 * <p>게시글 밖의 독립 애그리거트다. 게시글 안에 두면 투표가 쌓일수록
 * 게시글 하나를 읽는 비용이 계속 커진다. 게시글은 집계 값으로만 안다.
 *
 * <p>{@code postId} 를 선택지와 함께 들고 있다. 선택지를 거치면 알 수 있어
 * 이행적 종속으로 보이지만, {@code UNIQUE(post_id, user_id)} 로
 * <b>중복 투표를 스키마가 막기 위해</b> 둔다. 응용 계층 검증만으로는
 * 동시 요청에서 뚫린다.
 */
public class Vote {

    private final Long id;
    private final Long postId;
    private Long postOptionId;
    private final Long voterId;

    public Vote(Long postId, Long postOptionId, Long voterId) {
        this(null, postId, postOptionId, voterId);
    }

    private Vote(Long id, Long postId, Long postOptionId, Long voterId) {
        if (postId == null) {
            throw new IllegalArgumentException("게시글은 필수입니다.");
        }
        if (postOptionId == null) {
            throw new IllegalArgumentException("선택지는 필수입니다.");
        }
        if (voterId == null) {
            // 게스트 투표는 서버에 남지 않는다 (R-11).
            throw new IllegalArgumentException("투표자는 필수입니다. 게스트 투표는 저장하지 않습니다.");
        }
        this.id = id;
        this.postId = postId;
        this.postOptionId = postOptionId;
        this.voterId = voterId;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static Vote restore(Long id, Long postId, Long postOptionId, Long voterId) {
        return new Vote(id, postId, postOptionId, voterId);
    }

    /**
     * 선택지를 바꾼다 (R-22).
     *
     * <p>새 행을 만들지 않고 기존 행을 고친다 — 그래야 투표한 <b>사람 수</b>가 늘지 않는다.
     * 새로 INSERT 하면 {@code UNIQUE(post_id, user_id)} 가 막기도 하지만,
     * 의미상으로도 "다시 투표"가 아니라 "선택 변경"이다.
     */
    public void changeTo(Long newOptionId) {
        if (newOptionId == null) {
            throw new IllegalArgumentException("바꿀 선택지가 필요합니다.");
        }
        this.postOptionId = newOptionId;
    }

    public boolean isSameChoice(Long optionId) {
        return postOptionId.equals(optionId);
    }

    public Long id() {
        return id;
    }

    public Long postId() {
        return postId;
    }

    public Long postOptionId() {
        return postOptionId;
    }

    public Long voterId() {
        return voterId;
    }
}
