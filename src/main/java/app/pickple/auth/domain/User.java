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

    public enum State {
        ACTIVE, INACTIVE
    }

    public User(SocialProvider provider, String providerId, String email, String name) {
        this(null, provider, providerId, email, name, Role.ROLE_USER, State.ACTIVE);
    }

    private User(Long id, SocialProvider provider, String providerId,
                 String email, String name, Role role, State state) {
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
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static User restore(Long id, SocialProvider provider, String providerId,
                               String email, String name, Role role, State state) {
        return new User(id, provider, providerId, email, name, role, state);
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
}
