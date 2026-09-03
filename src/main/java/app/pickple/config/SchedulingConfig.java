package app.pickple.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업 활성화. 현재는 랭킹 재계산 하나가 쓴다 (ADR-0028).
 *
 * <p><b>왜 조건부인가</b> — 통합 테스트에서 스케줄러가 돌면 테스트가 만든 픽스처를
 * 배치가 중간에 건드려 결과가 실행 시점에 따라 달라진다. 테스트는 배치를
 * {@code app.ranking.enabled=false} 로 꺼두고 {@code RankingBatchService} 를 직접 불러
 * 갱신 시점을 통제한다.
 *
 * <p>{@code matchIfMissing = true} 라 운영 설정에서 키를 빼먹어도 배치는 돈다 —
 * 기본값이 "켜짐" 이어야 설정 누락이 조용한 미갱신으로 이어지지 않는다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RankingProperties.class)
@ConditionalOnProperty(prefix = "app.ranking", name = "enabled", matchIfMissing = true)
public class SchedulingConfig {
}
