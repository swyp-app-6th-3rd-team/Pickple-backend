package app.pickple.item.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** 이미지 업로드 정책과 S3 접속 설정. 비밀값은 포함하지 않고 AWS 기본 자격증명 체인을 쓴다. */
@ConfigurationProperties(prefix = "app.file")
public record FileStorageProperties(DataSize maxFileSize, S3 s3) {

    public record S3(String bucket, String region, String endpoint, String publicBaseUrl) {
    }
}
