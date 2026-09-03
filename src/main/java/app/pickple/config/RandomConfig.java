package app.pickple.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * 난수를 주입 가능하게 만든다. Clock 과 같은 이유다 —
 * 난수를 직접 부르면 같은 입력이 매번 다른 결과를 내어 테스트에서 고정할 수 없다.
 *
 * <p>기본 프로필 선택 같은 표시용 난수라 예측 가능성이 문제되지 않는다.
 * 토큰·nonce 처럼 추측되면 안 되는 값에는 {@code SecureRandom} 을 따로 쓴다.
 *
 * <p><b>{@code ThreadLocalRandom.current()} 를 그대로 빈으로 만들지 않는다.</b>
 * 그 메서드는 <i>호출한 스레드</i>의 인스턴스를 준다. 빈 생성 시점에 한 번 부르면
 * 기동 스레드의 인스턴스가 모든 요청 스레드에 공유되어, 설계상 보장되던
 * 무경합 전제가 깨진다. 호출마다 현재 스레드의 것을 찾도록 위임한다.
 */
@Configuration
public class RandomConfig {

    @Bean
    public RandomGenerator randomGenerator() {
        return new ThreadLocalRandomGenerator();
    }

    /** 모든 호출을 현재 스레드의 {@link ThreadLocalRandom} 으로 넘긴다. */
    private static final class ThreadLocalRandomGenerator implements RandomGenerator {

        @Override
        public long nextLong() {
            return ThreadLocalRandom.current().nextLong();
        }

        @Override
        public int nextInt() {
            return ThreadLocalRandom.current().nextInt();
        }

        @Override
        public int nextInt(int bound) {
            return ThreadLocalRandom.current().nextInt(bound);
        }

        @Override
        public double nextDouble() {
            return ThreadLocalRandom.current().nextDouble();
        }
    }
}
