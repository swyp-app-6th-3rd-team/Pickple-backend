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
    private final String providerId;
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
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId 는 필수입니다.");
        }
        this.id = id;
        this.provider = provider;
        this.providerId = providerId;
        this.email = email;
        this.name = name;
        this.role = role == null ? Role.ROLE_USER : role;
        this.state = state == null ? State.ACTIVE : state;
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
     * 탈퇴 처리.
     *
     * <p>닉네임 값은 지우지 않는다. 스키마의 {@code active_nickname} 생성 컬럼이
     * {@code state = 'ACTIVE'} 일 때만 값을 갖도록 정의돼 있어, 여기서 상태만 바꾸면
     * 유니크 인덱스에서 빠지며 닉네임이 반납된다 (R-21).
     */
    public void withdraw() {
        if (state == State.INACTIVE) {
            throw new IllegalStateException("이미 탈퇴한 사용자입니다: userId=" + id);
        }
        this.state = State.INACTIVE;
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
