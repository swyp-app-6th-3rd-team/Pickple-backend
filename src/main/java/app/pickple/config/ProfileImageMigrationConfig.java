package app.pickple.config;

import app.pickple.auth.infra.DefaultProfileImageMigration;
import app.pickple.auth.service.DefaultProfileImages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 배포 환경의 기본 이미지 설정을 Flyway V16 데이터 복구에 연결한다. */
@Configuration(proxyBeanMethods = false)
public class ProfileImageMigrationConfig {

    @Bean
    public DefaultProfileImageMigration defaultProfileImageMigration(DefaultProfileImages images) {
        return new DefaultProfileImageMigration(images.legacyReplacements());
    }
}
