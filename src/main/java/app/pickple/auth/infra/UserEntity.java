package app.pickple.auth.infra;

import app.pickple.auth.domain.Role;
import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "users", uniqueConstraints =
        @UniqueConstraint(name = "uk_users_provider", columnNames = {"provider", "provider_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private SocialProvider provider;

    @Column(name = "provider_id", nullable = false, length = 255)
    private String providerId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "name", length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private User.State state;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private UserEntity(User user, LocalDateTime now) {
        this.id = user.id();
        this.provider = user.provider();
        this.providerId = user.providerId();
        this.email = user.email();
        this.name = user.name();
        this.role = user.role();
        this.state = user.state();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static UserEntity from(User user, LocalDateTime now) {
        return new UserEntity(user, now);
    }

    public void applyState(User user, LocalDateTime now) {
        this.email = user.email();
        this.name = user.name();
        this.state = user.state();
        this.updatedAt = now;
    }

    public User toDomain() {
        return User.restore(id, provider, providerId, email, name, role, state);
    }
}
