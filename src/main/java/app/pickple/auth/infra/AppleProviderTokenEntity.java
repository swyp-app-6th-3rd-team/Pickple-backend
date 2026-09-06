package app.pickple.auth.infra;

import app.pickple.auth.domain.AppleClientType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.io.Serializable;
import java.util.Objects;

/** Apple provider refresh token의 AES-GCM 암호문. 평문을 필드로 두지 않는다. */
@Getter
@Entity
@Table(name = "apple_provider_token")
@IdClass(AppleProviderTokenEntity.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppleProviderTokenEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 16)
    private AppleClientType clientType;

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
                                    AppleClientType clientType,
                                    int encryptionFormatVersion,
                                    String encryptedRefreshToken,
                                    String encryptionIv,
                                    String encryptionKeyId,
                                    LocalDateTime now) {
        this.userId = userId;
        this.clientType = clientType;
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

    public static class Key implements Serializable {
        private Long userId;
        private AppleClientType clientType;

        public Key() {
        }

        public Key(Long userId, AppleClientType clientType) {
            this.userId = userId;
            this.clientType = clientType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(userId, key.userId) && clientType == key.clientType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, clientType);
        }
    }
}
