package app.pickple.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.Instant;

@Validated
@ConfigurationProperties(prefix = "app.file.cleanup")
public record ItemCleanupProperties(boolean enabled, @NotBlank String cron,
                                    @NotNull Duration gracePeriod, Instant managedSince,
                                    @Min(1) @Max(1000) int batchSize) {

    @AssertTrue(message = "cleanup 활성화에는 기존 객체를 보호할 managed-since가 필요합니다")
    public boolean isManagedSinceConfigured() {
        return !enabled || managedSince != null;
    }

    @AssertTrue(message = "cleanup grace-period는 1초 이상이어야 합니다")
    public boolean isGracePeriodValid() {
        return gracePeriod != null && gracePeriod.compareTo(Duration.ofSeconds(1)) >= 0;
    }
}
