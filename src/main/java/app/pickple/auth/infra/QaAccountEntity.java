package app.pickple.auth.infra;

import app.pickple.auth.domain.QaAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "qa_account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QaAccountEntity {
    @Id
    @Column(name = "login_id", nullable = false, length = 100)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    QaAccount toDomain() {
        return new QaAccount(loginId, passwordHash, userId);
    }
}
