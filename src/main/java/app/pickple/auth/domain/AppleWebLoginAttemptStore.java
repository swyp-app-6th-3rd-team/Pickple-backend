package app.pickple.auth.domain;

import java.time.LocalDateTime;
import java.util.Optional;

/** Apple 웹 로그인 state와 앱 handoff code의 일회성을 보장하는 저장소. */
public interface AppleWebLoginAttemptStore {

    void create(PendingAttempt attempt);

    Optional<PendingAttempt> claimPending(String stateHash, LocalDateTime now);

    void completeCallback(String stateHash, VerifiedAttempt verified, LocalDateTime now);

    void failCallback(String stateHash, Status status, LocalDateTime now);

    Optional<VerifiedAttempt> findVerifiedForUpdate(String handoffCodeHash, LocalDateTime now);

    void consume(String stateHash, LocalDateTime now);

    Optional<VerifiedAttempt> findExpiredVerifiedForUpdate(
            LocalDateTime handoffExpiredAt, LocalDateTime retryBefore);

    void completeCleanup(String stateHash, LocalDateTime now);

    void deferCleanup(String stateHash, LocalDateTime now);

    void deleteStaleNonVerifiedBefore(LocalDateTime threshold);

    enum Status {
        PENDING,
        CALLBACK_PROCESSING,
        VERIFIED,
        CONSUMED,
        FAILED,
        CANCELLED
    }

    record PendingAttempt(
            String stateHash,
            String appState,
            String codeChallenge,
            String nonceHash,
            LocalDateTime createdAt,
            LocalDateTime expiresAt) {
    }

    record VerifiedAttempt(
            String stateHash,
            String appState,
            String codeChallenge,
            Long boundUserId,
            String providerId,
            String email,
            String name,
            int encryptionFormatVersion,
            String encryptedProviderRefreshToken,
            String encryptionIv,
            String encryptionKeyId,
            String handoffCodeHash,
            LocalDateTime handoffExpiresAt) {
    }
}
