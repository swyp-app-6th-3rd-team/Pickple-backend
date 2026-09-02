package app.pickple.auth.infra;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

interface UserRefreshTokenRepository extends JpaRepository<UserRefreshTokenEntity, Long> {

    Optional<UserRefreshTokenEntity> findByUserId(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update UserRefreshTokenEntity token
               set token.tokenHash = :newTokenHash,
                   token.expiresAt = :newExpiresAt,
                   token.createdAt = :rotatedAt
             where token.userId = :userId
               and token.tokenHash = :expectedTokenHash
            """)
    int rotateIfMatches(@Param("userId") Long userId,
                        @Param("expectedTokenHash") String expectedTokenHash,
                        @Param("newTokenHash") String newTokenHash,
                        @Param("newExpiresAt") LocalDateTime newExpiresAt,
                        @Param("rotatedAt") LocalDateTime rotatedAt);

    void deleteByUserId(Long userId);
}
