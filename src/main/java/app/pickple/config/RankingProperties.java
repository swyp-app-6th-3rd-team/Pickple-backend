package app.pickple.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 랭킹 사전 계산 배치 설정 (ADR-0028).
 *
 * @param enabled 배치 실행 여부. 통합 테스트는 꺼두고 직접 호출해 시점을 통제한다
 * @param cron    재계산 주기. 이 주기가 곧 <b>랭킹 지연 상한</b>이라 API 계약의 일부다
 */
@ConfigurationProperties(prefix = "app.ranking")
public record RankingProperties(boolean enabled, String cron) {
}
