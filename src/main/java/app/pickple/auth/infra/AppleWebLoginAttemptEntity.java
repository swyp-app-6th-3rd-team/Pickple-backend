package app.pickple.auth.infra;

import app.pickple.auth.domain.AppleWebLoginAttemptStore.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Apple 웹 로그인 시도와 앱 handoff의 영속 상태. */
@Getter
@Entity
@Table(name = "apple_web_login_attempt")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppleWebLoginAttemptEntity {

    @Id
    @Column(name = "state_hash", nullable = false, length = 64)
    private String stateHash;

    @Column(name = "app_state", nullable = false, length = 256)
    private String appState;

    @Column(name = "code_challenge", nullable = false, length = 43)
    private String codeChallenge;

    @Column(name = "nonce_hash", nullable = false, length = 64)
    private String nonceHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "bound_user_id")
    private Long boundUserId;

    @Column(name = "provider_id", length = 255)
    private String providerId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "encryption_format_version")
    private Integer encryptionFormatVersion;

    @Column(name = "encrypted_provider_refresh_token", length = 4096)
    private String encryptedProviderRefreshToken;

    @Column(name = "encryption_iv", length = 24)
    private String encryptionIv;

    @Column(name = "encryption_key_id", length = 50)
    private String encryptionKeyId;

    @Column(name = "handoff_code_hash", length = 64)
    private String handoffCodeHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "handoff_expires_at")
    private LocalDateTime handoffExpiresAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AppleWebLoginAttemptEntity(String stateHash, String appState, String codeChallenge,
                                      String nonceHash, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.stateHash = stateHash;
        this.appState = appState;
        this.codeChallenge = codeChallenge;
        this.nonceHash = nonceHash;
        this.status = Status.PENDING;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.updatedAt = createdAt;
    }

    void verify(Long boundUserId, String providerId, String email, String name,
                int encryptionFormatVersion, String encryptedProviderRefreshToken,
                String encryptionIv, String encryptionKeyId, String handoffCodeHash,
                LocalDateTime handoffExpiresAt, LocalDateTime now) {
        requireStatus(Status.CALLBACK_PROCESSING);
        this.status = Status.VERIFIED;
        this.boundUserId = boundUserId;
        this.providerId = providerId;
        this.email = email;
        this.name = name;
        this.encryptionFormatVersion = encryptionFormatVersion;
        this.encryptedProviderRefreshToken = encryptedProviderRefreshToken;
        this.encryptionIv = encryptionIv;
        this.encryptionKeyId = encryptionKeyId;
        this.handoffCodeHash = handoffCodeHash;
        this.handoffExpiresAt = handoffExpiresAt;
        this.updatedAt = now;
    }

    void fail(Status terminalStatus, LocalDateTime now) {
        requireStatus(Status.CALLBACK_PROCESSING);
        if (terminalStatus != Status.FAILED && terminalStatus != Status.CANCELLED) {
            throw new IllegalArgumentException("Apple 웹 로그인 종료 상태가 올바르지 않습니다.");
        }
        this.status = terminalStatus;
        this.updatedAt = now;
    }

    void consume(LocalDateTime now) {
        requireStatus(Status.VERIFIED);
        this.status = Status.CONSUMED;
        clearVerifiedPayload();
        this.updatedAt = now;
    }

    void completeCleanup(LocalDateTime now) {
        requireStatus(Status.VERIFIED);
        this.status = Status.FAILED;
        clearVerifiedPayload();
        this.updatedAt = now;
    }

    void deferCleanup(LocalDateTime now) {
        requireStatus(Status.VERIFIED);
        this.updatedAt = now;
    }

    private void clearVerifiedPayload() {
        this.boundUserId = null;
        this.providerId = null;
        this.email = null;
        this.name = null;
        this.encryptionFormatVersion = null;
        this.encryptedProviderRefreshToken = null;
        this.encryptionIv = null;
        this.encryptionKeyId = null;
        this.handoffCodeHash = null;
    }

    private void requireStatus(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("Apple 웹 로그인 상태 전이가 올바르지 않습니다.");
        }
    }
}
