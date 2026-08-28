package app.pickple.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 통합 테스트 공통 설정. 컨테이너와 프로파일을 한 곳에 묶어
 * 테스트 클래스마다 같은 애노테이션을 반복하지 않게 한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest
@Import(ContainerConfig.class)
@ActiveProfiles("test")
public @interface IntegrationTest {
}
