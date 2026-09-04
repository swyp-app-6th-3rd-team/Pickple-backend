package app.pickple.badge.domain;

/**
 * 뱃지 하나의 정의 — 무엇을 얼마나 하면 얻는가 (정책 요약표 §3).
 *
 * <p><b>이름은 데이터이고 조건이 계약이다.</b> 정책 정본의 표 제목이
 * "3. 뱃지 정책 (뱃지명은 추후 수정됩니다)" 라 이름은 기획이 바뀔 것을 예고한 값이다.
 * 그래서 식별은 {@code code} 가 하고 {@code name} 은 화면에 내보내는 표시값일 뿐이다.
 * 이름이 바뀌면 {@code badge} 행을 고치면 되고 코드도 마이그레이션도 손대지 않는다.
 *
 * <p><b>판정이 여기 있는 이유</b> — "임계값을 넘었는가" 는 뱃지 하나와 활동 요약만 있으면
 * 답이 나온다. 애그리거트를 넘지 않으므로 서비스로 올리지 않는다
 * (계층 책임: 애그리거트 하나의 상태로 판정되면 도메인 객체).
 *
 * <p>이걸 서비스에 두면 <b>획득 판정과 미션 진행률이 각자 임계값을 비교</b>하게 된다.
 * 한쪽만 고치면 "미션은 20/20 인데 뱃지가 안 나온다" 는 어긋남이 생기고,
 * 그 버그는 경계값에서만 드러나 발견이 늦다.
 */
public class Badge {

    private final Long id;
    private final String code;
    private final String name;
    private final String description;
    private final BadgeConditionType conditionType;
    private final long threshold;
    private final int displayOrder;

    public Badge(Long id, String code, String name, String description,
                 BadgeConditionType conditionType, long threshold, int displayOrder) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("뱃지 코드는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("뱃지 이름은 필수입니다.");
        }
        if (conditionType == null) {
            throw new IllegalArgumentException("뱃지 조건 유형은 필수입니다.");
        }
        if (threshold <= 0) {
            // 0 이면 가입만으로 획득되고, 음수면 영원히 획득되지 않는다.
            // 둘 다 "조건" 이라 부를 수 없는 값이라 만들 수 없게 막는다.
            throw new IllegalArgumentException("뱃지 임계값은 1 이상이어야 합니다: " + threshold);
        }
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.conditionType = conditionType;
        this.threshold = threshold;
        this.displayOrder = displayOrder;
    }

    /**
     * 이 활동이 조건을 충족하는가.
     *
     * <p>경계는 <b>이상</b>이다 — "누적 투표 10회 <b>달성</b>", "하루에 투표 20개 <b>이상</b>",
     * "7일 연속" 셋 다 그 수에 <b>도달하면</b> 얻는다. 초과로 읽으면 10회 투표한 사람이
     * 11회째에야 받게 되어 화면의 "(10/10)" 과 어긋난다.
     */
    public boolean isAchievedBy(VoteActivity activity) {
        return activity.countFor(conditionType) >= threshold;
    }

    /**
     * 목표까지의 진행 상황. 미획득 뱃지의 미션 표시에 쓴다 (기능명세 §2.3).
     *
     * <p>퍼센트가 아니라 <b>두 수를 그대로</b> 내린다. 명세의 조회 데이터가
     * {@code 누적 투표 10회 달성 (0/10)} 형태라, 퍼센트로 환산해 내려주면
     * 클라이언트가 "0/1000" 을 복원할 수 없다.
     *
     * <p>현재값은 목표를 넘어도 목표에서 자른다. 이미 달성한 뱃지의 진행률은
     * 미션 목록에 나가지 않지만, 나가더라도 "1500/1000" 같은 값이
     * 게이지 폭 계산을 넘치게 하지 않도록 여기서 막는다.
     */
    public BadgeProgress progressOf(VoteActivity activity) {
        long current = Math.min(activity.countFor(conditionType), threshold);
        return new BadgeProgress(this, current, threshold);
    }

    public Long id() {
        return id;
    }

    public String code() {
        return code;
    }

    /** 표시명. 정책이 "추후 수정" 을 예고한 값이라 이것으로 뱃지를 식별하지 않는다. */
    public String name() {
        return name;
    }

    /** 획득 조건 문구. 미션 목록이 그대로 내려준다. */
    public String description() {
        return description;
    }

    public BadgeConditionType conditionType() {
        return conditionType;
    }

    public long threshold() {
        return threshold;
    }

    /** 3X3 목록 노출 순서 (기능명세 §12.2). */
    public int displayOrder() {
        return displayOrder;
    }
}
