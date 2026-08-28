package app.pickple;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 애플리케이션 진입점.
 *
 * <p>{@code @SpringBootApplication} 에 {@code scanBasePackages} 를 주지 않았다.
 * 컴포넌트 스캔 루트가 이 클래스의 패키지({@code app.pickple})에서 파생되므로,
 * 클래스를 옮기면 스캔 범위도 함께 따라온다.
 */
@SpringBootApplication
public class PickpleApplication {

    public static void main(String[] args) {
        SpringApplication.run(PickpleApplication.class, args);
    }
}
