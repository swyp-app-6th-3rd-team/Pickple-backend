package app.pickple.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;

/** 이미지 업로드 통합 테스트에서만 사용하는 LocalStack S3 설정. */
@TestConfiguration(proxyBeanMethods = false)
public class LocalStackS3Config {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("localstack/localstack:4.14.0");

    @Bean
    @SuppressWarnings("resource")
    public LocalStackContainer localStackContainer() {
        return new LocalStackContainer(IMAGE)
                .withServices(S3)
                .withReuse(true);
    }

    @Bean(destroyMethod = "close")
    @Primary
    public S3Client localStackS3Client(LocalStackContainer localStackContainer) {
        return S3Client.builder()
                .endpointOverride(localStackContainer.getEndpointOverride(S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                localStackContainer.getAccessKey(),
                                localStackContainer.getSecretKey())))
                .region(Region.of(localStackContainer.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
