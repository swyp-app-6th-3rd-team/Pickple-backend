package app.pickple.auth.apple;

import app.pickple.auth.domain.AppleClientType;
import app.pickple.auth.domain.AppleWebLoginAttemptStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/** 교환되지 않고 만료된 웹 provider grant를 Apple에서 회수한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppleWebLoginCleanupService {

    static final String REVOKE_FAILURE_METRIC =
            "pickple.auth.apple.web.handoff.cleanup.revoke.failures";
    private static final Duration RETRY_DELAY = Duration.ofMinutes(5);

    private final AppleWebLoginAttemptStore attemptStore;
    private final AppleProviderTokenCipher tokenCipher;
    private final AppleTokenGateway tokenGateway;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * 한 행을 잠근 채 revoke와 상태 전이를 끝낸다. Apple revoke는 멱등이므로
     * 외부 성공 뒤 로컬 커밋이 유실돼도 다음 실행이 같은 결과로 수렴한다.
     */
    @Transactional
    public boolean cleanupOne() {
        LocalDateTime now = LocalDateTime.now(clock);
        var candidate = attemptStore.findExpiredVerifiedForUpdate(now, now.minus(RETRY_DELAY));
        if (candidate.isEmpty()) {
            return false;
        }
        AppleWebLoginAttemptStore.VerifiedAttempt attempt = candidate.get();
        try {
            String refreshToken = tokenCipher.decryptWebAttempt(attempt.stateHash(),
                    new AppleProviderTokenCipher.EncryptedToken(
                            attempt.encryptionFormatVersion(),
                            attempt.encryptedProviderRefreshToken(),
                            attempt.encryptionIv(), attempt.encryptionKeyId()));
            tokenGateway.revokeRefreshToken(AppleClientType.WEB, refreshToken);
            attemptStore.completeCleanup(attempt.stateHash(), now);
        } catch (RuntimeException failure) {
            meterRegistry.counter(REVOKE_FAILURE_METRIC).increment();
            log.warn("만료된 Apple 웹 로그인 grant 정리를 다음 실행으로 미룹니다.");
            attemptStore.deferCleanup(attempt.stateHash(), now);
        }
        return true;
    }
}
