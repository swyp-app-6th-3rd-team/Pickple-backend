package app.pickple.auth.domain;

/**
 * 인증 사용자 — 순수 도메인 모델.
 *
 * <p>소셜 신원은 {@code (provider, providerId)} 쌍으로 식별한다.
 * 식별자 하나만으로 조회하면 프로바이더가 다른데 subject 가 같을 때
 * 서로 다른 사람이 같은 계정으로 합쳐진다. DB 에도 복합 유니크 제약을 걸었다.
 */
public class User {

    private final Long id;
    private final SocialProvider provider;
    private String providerId;
    private String email;
    private String name;
    private final Role role;
    private State state;

    /** 서비스 프로필. 가입 직후에는 비어 있고 프로필 등록에서 채워진다. */
    private Nickname nickname;
    private String profileImageUrl;

    public enum State {
        ACTIVE, INACTIVE
    }

    public User(SocialProvider provider, String providerId, String email, String name) {
        this(null, provider, providerId, email, name, Role.ROLE_USER, State.ACTIVE, null, null);
    }

    private User(Long id, SocialProvider provider, String providerId,
                 String email, String name, Role role, State state,
                 Nickname nickname, String profileImageUrl) {
        if (provider == null) {
            throw new IllegalArgumentException("provider 는 필수입니다.");
        }
        State resolvedState = state == null ? State.ACTIVE : state;
        if (providerId == null) {
            // 소셜 신원은 활성 회원만 갖는다 (R-28). 조건은 provider 가 아니라 state 다 —
            // 판정의 본질이 "로그인 조회 대상인가" 이기 때문이다. 탈퇴 회원은 provider 와
            // 무관하게 식별자를 파기하므로(R-27), APPLE 한정으로 두면 마스킹한 카카오 행을
            // 복원하는 순간 모든 조회가 여기서 터진다.
            // 스키마의 ck_users_active_provider_id 도 provider 를 보지 않고 state 만 본다.
            if (resolvedState != State.INACTIVE) {
                throw new IllegalArgumentException("providerId 는 필수입니다.");
            }
        } else if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId 는 필수입니다.");
        }
        this.id = id;
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.name = name;
        this.role = role == null ? Role.ROLE_USER : role;
        this.state = resolvedState;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static User restore(Long id, SocialProvider provider, String providerId,
                               String email, String name, Role role, State state,
                               String nickname, String profileImageUrl) {
        return new User(id, provider, providerId, email, name, role, state,
                nickname == null ? null : new Nickname(nickname), profileImageUrl);
    }

    /**
     * 소셜 프로바이더가 준 최신 프로필을 반영한다.
     * 이름·이메일은 사용자가 프로바이더 쪽에서 바꿀 수 있으므로 로그인마다 갱신한다.
     */
    public void syncProfile(String email, String name) {
        if (email != null && !email.isBlank()) {
            this.email = email;
        }
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    /**
     * 프로필을 등록·수정한다. 닉네임은 필수, 프로필 이미지는 선택이다.
     *
     * <p>이미지를 주지 않았을 때 어떤 기본 프로필을 고르는지는 도메인의 판단이 아니다 —
     * 사용 가능한 기본 이미지 목록은 도메인 밖(설정)에 있으므로 서비스가 채워 넘긴다.
     */
    public void registerProfile(Nickname nickname, String profileImageUrl) {
        if (nickname == null) {
            throw new IllegalArgumentException("닉네임은 필수입니다.");
        }
        if (!isActive()) {
            throw new IllegalStateException("탈퇴한 사용자는 프로필을 등록할 수 없습니다: userId=" + id);
        }
        this.nickname = nickname;
        if (profileImageUrl != null && !profileImageUrl.isBlank()) {
            this.profileImageUrl = profileImageUrl;
        }
    }

    /**
     * 탈퇴 처리 — 상태 전이와 개인정보 파기를 함께 한다.
     *
     * <p><b>수집한 개인정보를 즉시 파기한다</b> (R-27). 개인정보처리방침 제3조가
     * "회원 탈퇴 시 지체 없이 파기" 를 규정한다. provider 와 무관하게 같은 동작을 한다 —
     * 파기 여부를 가르는 것은 소셜 제공자가 아니라 탈퇴 사실이다 (ADR-0040).
     *
     * <p><b>파기 값이 sentinel 이 아니라 {@code null} 인 이유.</b>
     * {@code uk_users_provider (provider, provider_id)} 가 걸려 있어 {@code "withdrawn"}
     * 같은 고정 문자열은 두 번째 탈퇴자부터 유니크 위반이다. InnoDB 는 유니크 제약에서
     * {@code NULL} 을 중복으로 세지 않는다.
     *
     * <p><b>상태 전이와 파기를 한 메서드에 묶는 이유.</b> {@code CHECK} 는 전이를 표현하지
     * 못해(다른 행도 이전 값도 볼 수 없다) 도메인이 유일한 방어선이다. 상태만 바꾸는 경로를
     * 따로 만들면 그 경로가 파기를 건너뛴다.
     *
     * <p>닉네임 반납 장치는 그대로 둔다 (R-21). 스키마의 {@code active_nickname} 생성 컬럼이
     * {@code state = 'ACTIVE'} 일 때만 값을 가져 유니크 인덱스에서 빠진다. 마스킹은 이 경로를
     * 지날 때만 걸리지만 생성 컬럼은 모든 경로에 걸리므로, 동시 가입의 유일성은 여전히
     * 스키마가 지킨다.
     *
     * <p>남긴 글·투표·댓글은 지우지 않는다 (R-20). 파기 대상은 행이 아니라 컬럼 값이다 —
     * 목록 조회가 {@code users} 를 INNER JOIN 하므로 행이 사라지면 그 사람의 글이 통째로
     * 결과에서 빠진다. 작성자 표시는 조회의 {@code COALESCE} 폴백이 "알 수 없음" 으로 낸다.
     *
     * <p><b>호출 순서 주의.</b> 외부 provider 연결 해제는 이 메서드보다 <b>먼저</b> 끝나야 한다.
     * Kakao unlink 가 {@code providerId} 를 인자로 받기 때문이다
     * ({@code AccountWithdrawalService.withdraw}).
     */
    public void withdraw() {
        if (state == State.INACTIVE) {
            throw new IllegalStateException("이미 탈퇴한 사용자입니다: userId=" + id);
        }
        this.state = State.INACTIVE;
        this.providerId = null;
        this.email = null;
        this.name = null;
        this.nickname = null;
        this.profileImageUrl = null;
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    public Long id() {
        return id;
    }

    public SocialProvider provider() {
        return provider;
    }

    public String providerId() {
        return providerId;
    }

    public String email() {
        return email;
    }

    public String name() {
        return name;
    }

    public Role role() {
        return role;
    }

    public State state() {
        return state;
    }

    public Nickname nickname() {
        return nickname;
    }

    public String profileImageUrl() {
        return profileImageUrl;
    }

    /** 프로필 등록을 마쳤는지. 닉네임은 필수라 이것 하나로 판정된다. */
    public boolean hasProfile() {
        return nickname != null;
    }
}
