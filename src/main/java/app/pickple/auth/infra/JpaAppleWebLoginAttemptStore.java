package app.pickple.auth.infra;

import app.pickple.auth.domain.AppleWebLoginAttemptStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaAppleWebLoginAttemptStore implements AppleWebLoginAttemptStore {

    private final AppleWebLoginAttemptRepository repository;

    @Override
    @Transactional
    public void create(PendingAttempt attempt) {
        repository.save(new AppleWebLoginAttemptEntity(
                attempt.stateHash(), attempt.appState(), attempt.codeChallenge(), attempt.nonceHash(),
                attempt.createdAt(), attempt.expiresAt()));
    }

    @Override
    @Transactional
    public Optional<PendingAttempt> claimPending(String stateHash, LocalDateTime now) {
        int claimed = repository.claimPending(stateHash, Status.PENDING, Status.CALLBACK_PROCESSING, now);
        if (claimed != 1) {
            return Optional.empty();
        }
        return repository.findById(stateHash).map(this::toPending);
    }

    @Override
    @Transactional
    public void completeCallback(String stateHash, VerifiedAttempt verified, LocalDateTime now) {
        AppleWebLoginAttemptEntity entity = repository.findById(stateHash)
                .orElseThrow(() -> invalidState("Apple 웹 로그인 시도가 없습니다."));
        entity.verify(verified.boundUserId(), verified.providerId(), verified.email(), verified.name(),
                verified.encryptionFormatVersion(), verified.encryptedProviderRefreshToken(),
                verified.encryptionIv(), verified.encryptionKeyId(), verified.handoffCodeHash(),
                verified.handoffExpiresAt(), now);
    }

    @Override
    @Transactional
    public void failCallback(String stateHash, Status status, LocalDateTime now) {
        repository.findById(stateHash).ifPresent(entity -> entity.fail(status, now));
    }

    @Override
    @Transactional
    public Optional<VerifiedAttempt> findVerifiedForUpdate(String handoffCodeHash, LocalDateTime now) {
        return repository.findByHandoffCodeHashAndStatusAndHandoffExpiresAtGreaterThanEqual(
                handoffCodeHash, Status.VERIFIED, now).map(this::toVerified);
    }

    @Override
    @Transactional
    public void consume(String stateHash, LocalDateTime now) {
        AppleWebLoginAttemptEntity entity = repository.findById(stateHash)
                .orElseThrow(() -> invalidState("Apple 웹 로그인 handoff가 없습니다."));
        entity.consume(now);
    }

    @Override
    @Transactional
    public Optional<VerifiedAttempt> findExpiredVerifiedForUpdate(
            LocalDateTime handoffExpiredAt, LocalDateTime retryBefore) {
        return repository
                .findFirstByStatusAndHandoffExpiresAtLessThanAndUpdatedAtLessThanEqualOrderByHandoffExpiresAtAsc(
                        Status.VERIFIED, handoffExpiredAt, retryBefore)
                .map(this::toVerified);
    }

    @Override
    @Transactional
    public void completeCleanup(String stateHash, LocalDateTime now) {
        AppleWebLoginAttemptEntity entity = repository.findById(stateHash)
                .orElseThrow(() -> invalidState("Apple 웹 로그인 정리 대상이 없습니다."));
        entity.completeCleanup(now);
    }

    @Override
    @Transactional
    public void deferCleanup(String stateHash, LocalDateTime now) {
        AppleWebLoginAttemptEntity entity = repository.findById(stateHash)
                .orElseThrow(() -> invalidState("Apple 웹 로그인 정리 대상이 없습니다."));
        entity.deferCleanup(now);
    }

    @Override
    @Transactional
    public void deleteStaleNonVerifiedBefore(LocalDateTime threshold) {
        repository.deleteStaleNonVerifiedBefore(List.of(
                Status.PENDING, Status.CALLBACK_PROCESSING, Status.CONSUMED,
                Status.FAILED, Status.CANCELLED), threshold);
    }

    private PendingAttempt toPending(AppleWebLoginAttemptEntity entity) {
        return new PendingAttempt(entity.getStateHash(), entity.getAppState(), entity.getCodeChallenge(),
                entity.getNonceHash(), entity.getCreatedAt(), entity.getExpiresAt());
    }

    private VerifiedAttempt toVerified(AppleWebLoginAttemptEntity entity) {
        return new VerifiedAttempt(
                entity.getStateHash(), entity.getAppState(), entity.getCodeChallenge(), entity.getBoundUserId(),
                entity.getProviderId(), entity.getEmail(), entity.getName(),
                entity.getEncryptionFormatVersion(), entity.getEncryptedProviderRefreshToken(),
                entity.getEncryptionIv(), entity.getEncryptionKeyId(), entity.getHandoffCodeHash(),
                entity.getHandoffExpiresAt());
    }

    private ApiException invalidState(String message) {
        return new ApiException(ResponseCode.APPLE_WEB_LOGIN_INVALID, message);
    }
}
