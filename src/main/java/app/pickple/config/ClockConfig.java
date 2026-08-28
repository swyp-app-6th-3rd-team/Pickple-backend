package app.pickple.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * 시간을 주입 가능하게 만든다.
 * 도메인과 Store 가 LocalDateTime.now() 를 직접 부르지 않고 이 Clock 을 받으면
 * 테스트에서 Clock.fixed(...) 로 시간을 고정할 수 있다.
 *
 * <p><b>초 단위로 끊는 이유</b> — 현재 시간 컬럼은 {@code datetime(0)}, 즉 초 단위다.
 * {@code LocalDateTime.now()} 는 나노초를 갖기 때문에 저장하면 값이 잘리고,
 * <b>저장 직후 메모리의 객체와 DB 에서 다시 읽은 값이 달라진다.</b>
 *
 * <p>이 불일치는 조용히 데이터를 잃게 만든다. keyset 스크롤 커서를 나노초 값으로 만들면
 * {@code createdAt > 09:00:00.123456789} 조건이
 * DB 의 {@code 09:00:00} 행을 걸러내 다음 조각에서 행이 누락됐다.
 *
 * <p>컬럼 정밀도를 높이는 대신 애플리케이션 시각을 컬럼에 맞춘다.
 * 마이그레이션에서 {@code datetime(6)} 을 쓴다면 이 설정도 함께 바꿔야 한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.system(ZoneId.of("Asia/Seoul")), Duration.ofSeconds(1));
    }
}
