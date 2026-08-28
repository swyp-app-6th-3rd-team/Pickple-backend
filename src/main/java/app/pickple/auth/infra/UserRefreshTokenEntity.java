package app.pickple.auth.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 리프레시 토큰. <b>원문이 아니라 SHA-256 해시를 저장한다.</b>
 * 사용자당 한 행만 존재한다(DB 유니크 제약).
 */
@Getter
@Entity
@Table(name = "user_refresh_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public UserRefreshTokenEntity(Long userId, String tokenHash, LocalDateTime expiresAt, LocalDateTime now) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public void rotate(String tokenHash, LocalDateTime expiresAt, LocalDateTime now) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }
}
