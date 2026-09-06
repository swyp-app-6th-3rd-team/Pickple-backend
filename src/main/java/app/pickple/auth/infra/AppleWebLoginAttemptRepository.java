package app.pickple.auth.infra;

import app.pickple.auth.domain.AppleWebLoginAttemptStore.Status;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

interface AppleWebLoginAttemptRepository extends JpaRepository<AppleWebLoginAttemptEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AppleWebLoginAttemptEntity attempt
               SET attempt.status = :processing,
                   attempt.updatedAt = :now
             WHERE attempt.stateHash = :stateHash
               AND attempt.status = :pending
               AND attempt.expiresAt >= :now
            """)
    int claimPending(@Param("stateHash") String stateHash,
                     @Param("pending") Status pending,
                     @Param("processing") Status processing,
                     @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppleWebLoginAttemptEntity> findByHandoffCodeHashAndStatusAndHandoffExpiresAtGreaterThanEqual(
            String handoffCodeHash, Status status, LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppleWebLoginAttemptEntity>
    findFirstByStatusAndHandoffExpiresAtLessThanAndUpdatedAtLessThanEqualOrderByHandoffExpiresAtAsc(
            Status status, LocalDateTime handoffExpiredAt, LocalDateTime retryBefore);

    @Modifying
    @Query("""
            DELETE FROM AppleWebLoginAttemptEntity attempt
             WHERE attempt.status IN :terminalStatuses
               AND attempt.updatedAt < :threshold
            """)
    void deleteStaleNonVerifiedBefore(@Param("terminalStatuses") Collection<Status> terminalStatuses,
                                      @Param("threshold") LocalDateTime threshold);
}
