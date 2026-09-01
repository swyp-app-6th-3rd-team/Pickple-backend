package app.pickple.item.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/** 운영 S3 클라이언트. 로컬 endpoint를 지정한 경우에만 path-style 요청을 사용한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageStorageProperties.class)
public class S3ImageStorageConfig {

    @Bean
    public S3Client imageS3Client(ImageStorageProperties properties) {
        ImageStorageProperties.S3 s3 = properties.s3();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.region()));

        if (StringUtils.hasText(s3.endpoint())) {
            builder.endpointOverride(URI.create(s3.endpoint()))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
        return builder.build();
    }
}
