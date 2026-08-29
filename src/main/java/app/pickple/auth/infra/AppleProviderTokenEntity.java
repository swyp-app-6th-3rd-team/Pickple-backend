package app.pickple.auth.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Apple provider refresh token의 AES-GCM 암호문. 평문을 필드로 두지 않는다. */
@Getter
@Entity
@Table(name = "apple_provider_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppleProviderTokenEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "encrypted_refresh_token", nullable = false, length = 4096)
    private String encryptedRefreshToken;

    @Column(name = "encryption_format_version", nullable = false)
    private int encryptionFormatVersion;

    @Column(name = "encryption_iv", nullable = false, length = 24)
    private String encryptionIv;

    @Column(name = "encryption_key_id", nullable = false, length = 50)
    private String encryptionKeyId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AppleProviderTokenEntity(Long userId,
                                    int encryptionFormatVersion,
                                    String encryptedRefreshToken,
                                    String encryptionIv,
                                    String encryptionKeyId,
                                    LocalDateTime now) {
        this.userId = userId;
        this.encryptionFormatVersion = encryptionFormatVersion;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.encryptionIv = encryptionIv;
        this.encryptionKeyId = encryptionKeyId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rotate(int encryptionFormatVersion,
                       String encryptedRefreshToken,
                       String encryptionIv,
                       String encryptionKeyId,
                       LocalDateTime now) {
        this.encryptionFormatVersion = encryptionFormatVersion;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.encryptionIv = encryptionIv;
        this.encryptionKeyId = encryptionKeyId;
        this.updatedAt = now;
    }
}
