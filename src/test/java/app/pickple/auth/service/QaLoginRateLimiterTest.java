package app.pickple.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QaLoginRateLimiterTest {
    private final Clock clock = mock(Clock.class);
    private final QaLoginRateLimiter limiter = new QaLoginRateLimiter(clock);

    @Test
    void limitsEachIpAndAccountWithoutLockingOtherClients() {
        for (int i = 0; i < 10; i++) assertThat(limiter.retryAfterSeconds("ip-a", "review")).isZero();
        assertThat(limiter.retryAfterSeconds("ip-a", "review")).isEqualTo(60);
        assertThat(limiter.retryAfterSeconds("ip-a", "tester")).isZero();
        assertThat(limiter.retryAfterSeconds("ip-b", "review")).isZero();
    }

    @Test
    void rotatingIdsCannotBypassIpLimit() {
        for (int i = 0; i < 60; i++) assertThat(limiter.retryAfterSeconds("ip-a", "id-" + i)).isZero();
        assertThat(limiter.retryAfterSeconds("ip-a", "new-id")).isEqualTo(60);
        assertThat(limiter.retryAfterSeconds("ip-b", "new-id")).isZero();
    }

    @Test
    void retryAfterRoundsUpAndWindowExpiresWithoutExtendingOnRejection() {
        for (int i = 0; i < 10; i++) limiter.retryAfterSeconds("ip-a", "review");
        when(clock.millis()).thenReturn(59_001L);
        assertThat(limiter.retryAfterSeconds("ip-a", "review")).isEqualTo(1);
        when(clock.millis()).thenReturn(60_000L);
        assertThat(limiter.retryAfterSeconds("ip-a", "review")).isZero();
    }

    @Test
    void boundsMemoryWithoutEvictingActiveLimitsAndReclaimsExpiredClients() {
        var bounded = new QaLoginRateLimiter(clock, 2);
        for (int i = 0; i < 10; i++) bounded.retryAfterSeconds("ip-a", "review");
        when(clock.millis()).thenReturn(1000L);
        assertThat(bounded.retryAfterSeconds("ip-b", "tester")).isZero();
        assertThat(bounded.retryAfterSeconds("ip-c", "new")).isEqualTo(59);
        assertThat(bounded.retryAfterSeconds("ip-a", "review")).isEqualTo(59);
        when(clock.millis()).thenReturn(60_000L);
        assertThat(bounded.retryAfterSeconds("ip-c", "new")).isZero();
    }

    @Test
    void concurrentRequestsCannotExceedAccountAllowance() throws Exception {
        var concurrent = new QaLoginRateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try (var executor = Executors.newFixedThreadPool(16)) {
            var requests = IntStream.range(0, 100)
                    .<Callable<Long>>mapToObj(i -> () -> concurrent.retryAfterSeconds("ip-a", "review")).toList();
            int accepted = 0;
            for (var result : executor.invokeAll(requests)) if (result.get() == 0) accepted++;
            assertThat(accepted).isEqualTo(10);
        }
    }
}
