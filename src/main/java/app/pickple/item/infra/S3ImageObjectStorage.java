package app.pickple.item.infra;

import app.pickple.item.config.ImageStorageProperties;
import app.pickple.item.domain.ImageObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@RequiredArgsConstructor
public class S3ImageObjectStorage implements ImageObjectStorage {

    private static final String NOT_CONFIGURED = "not-configured";

    private final S3Client imageS3Client;
    private final ImageStorageProperties properties;

    @Override
    public String put(String itemKey, byte[] content, String contentType) {
        String bucket = configuredBucket();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(itemKey)
                .contentType(contentType)
                .contentLength((long) content.length)
                .cacheControl("public, max-age=31536000, immutable")
                .build();
        try {
            imageS3Client.putObject(request, RequestBody.fromBytes(content));
            return accessUrl(bucket, itemKey);
        } catch (S3Exception | SdkClientException e) {
            throw new ImageStorageException("S3 이미지 업로드에 실패했습니다.", e);
        }
    }

    @Override
    public void delete(String itemKey) {
        String bucket = configuredBucket();
        try {
            imageS3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(itemKey)
                    .build());
        } catch (S3Exception | SdkClientException e) {
            throw new ImageStorageException("S3 이미지 삭제에 실패했습니다.", e);
        }
    }

    private String configuredBucket() {
        String bucket = properties.s3().bucket();
        if (!StringUtils.hasText(bucket) || NOT_CONFIGURED.equals(bucket)) {
            throw new ImageStorageException("이미지 S3 버킷이 설정되지 않았습니다.");
        }
        return bucket;
    }

    private String accessUrl(String bucket, String itemKey) {
        String publicBaseUrl = properties.s3().publicBaseUrl();
        if (StringUtils.hasText(publicBaseUrl)) {
            return publicBaseUrl.replaceAll("/+$", "") + "/" + itemKey;
        }
        return imageS3Client.utilities().getUrl(GetUrlRequest.builder()
                .bucket(bucket)
                .key(itemKey)
                .build()).toExternalForm();
    }
}
