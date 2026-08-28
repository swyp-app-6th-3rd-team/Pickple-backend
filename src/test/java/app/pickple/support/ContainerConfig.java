package app.pickple.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트용 MySQL 컨테이너.
 *
 * <p>{@code @ServiceConnection} 이 {@code spring.datasource.*} 를 자동으로 채우므로
 * URL·계정을 수동 설정할 필요가 없다.
 *
 * <p><b>재사용</b> — {@code withReuse(true)} 로 테스트 클래스마다 컨테이너를 새로
 * 띄우지 않는다. 켜려면 {@code ~/.testcontainers.properties} 에
 * {@code testcontainers.reuse.enable=true} 가 필요하다. 없으면 무시되고
 * 매번 새로 뜨므로 동작은 같고 속도만 느려진다.
 *
 * <p>시드 데이터(9MB)는 로드하지 않는다. Flyway location 이 {@code db/migration} 뿐이라
 * 스키마만 올라간다. 테스트는 필요한 픽스처를 직접 만든다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainerConfig {

    private static final DockerImageName IMAGE = DockerImageName.parse("mysql:8.4");

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    public MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(IMAGE)
                .withDatabaseName("pickple")
                .withCommand(
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_0900_ai_ci",
                        "--lower_case_table_names=0",
                        "--default-time-zone=+09:00")
                .withReuse(true);
    }
}
