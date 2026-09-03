package app.pickple.item.service;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.item.config.ImageStorageProperties;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ImageObjectStorage;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageUploadService {

    private static final int MAX_ORIGINAL_FILE_NAME_LENGTH = 255;

    private final ImageObjectStorage objectStorage;
    private final ItemContainerStore containerStore;
    private final ImageStorageProperties properties;

    /**
     * 파일 묶음을 S3에 올린 뒤 하나의 PRODUCT 컨테이너로 저장한다.
     *
     * <p>S3는 DB 트랜잭션에 참여하지 않으므로, 일부 업로드나 DB 저장이 실패하면
     * 이번 요청에서 생성한 키를 best-effort로 보상 삭제한다.
     */
    @Transactional
    public ItemContainer upload(Long ownerId, List<UploadImage> images) {
        if (ownerId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        if (images == null || images.isEmpty()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "업로드할 이미지가 없습니다.");
        }

        List<String> uploadedKeys = new ArrayList<>();
        registerTransactionRollbackCompensation(uploadedKeys);
        ItemContainer container = new ItemContainer(ownerId, AttachType.PRODUCT);

        try {
            for (UploadImage image : images) {
                ValidatedImage validated = validate(image);
                String itemKey = createItemKey(ownerId, validated.type().extension());
                // put 응답을 잃었어도 S3에 객체가 생겼을 수 있어 요청 전에 추적한다.
                uploadedKeys.add(itemKey);
                String accessUrl = objectStorage.put(
                        itemKey, validated.content(), validated.type().contentType());
                container.add(new ItemResource(
                        validated.content().length,
                        validated.originalFileName(),
                        itemKey,
                        accessUrl));
            }
            return containerStore.save(container);
        } catch (RuntimeException e) {
            compensateUploadedObjects(uploadedKeys);
            // 트랜잭션 콜백이 같은 목록을 참조하므로 이미 보상한 키는 다시 삭제하지 않는다.
            uploadedKeys.clear();
            throw e;
        }
    }

    private ValidatedImage validate(UploadImage image) {
        if (image == null || image.content() == null || image.content().length == 0) {
            throw new ApiException(ResponseCode.INVALID_IMAGE, "빈 이미지 파일입니다.");
        }
        if (image.content().length > properties.maxFileSize().toBytes()) {
            throw new ApiException(ResponseCode.IMAGE_TOO_LARGE);
        }

        ImageType type = ImageType.from(image.contentType());
        if (!type.matches(image.content())) {
            throw new ApiException(ResponseCode.INVALID_IMAGE, "Content-Type과 실제 파일 형식이 다릅니다.");
        }
        return new ValidatedImage(safeOriginalFileName(image.originalFileName()), image.content(), type);
    }

    private String safeOriginalFileName(String originalFileName) {
        if (originalFileName == null) {
            throw new ApiException(ResponseCode.INVALID_IMAGE, "원본 파일명이 없습니다.");
        }
        String normalized = originalFileName.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.length() > MAX_ORIGINAL_FILE_NAME_LENGTH || name.indexOf('\0') >= 0) {
            throw new ApiException(ResponseCode.INVALID_IMAGE, "원본 파일명이 올바르지 않습니다.");
        }
        return name;
    }

    private String createItemKey(Long ownerId, String extension) {
        return "product-images/%d/%s.%s".formatted(ownerId, UUID.randomUUID(), extension);
    }

    /**
     * 대상 메서드가 반환된 뒤 DB commit이 실패하는 경우까지 보상한다.
     * S3는 DB 트랜잭션에 참여하지 않으므로 최종 결과가 rollback이면 업로드한 객체를 삭제한다.
     */
    private void registerTransactionRollbackCompensation(List<String> uploadedKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // 결과 불명일 때 삭제하면 실제 commit된 DB 행이 없는 S3 객체를 가리킬 수 있다.
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    compensateUploadedObjects(uploadedKeys);
                }
            }
        });
    }

    private void compensateUploadedObjects(List<String> itemKeys) {
        for (String itemKey : itemKeys) {
            try {
                objectStorage.delete(itemKey);
            } catch (RuntimeException cleanupFailure) {
                log.warn("실패 보상 중 S3 객체를 삭제하지 못했습니다: key={}", itemKey, cleanupFailure);
            }
        }
    }

    public record UploadImage(String originalFileName, String contentType, byte[] content) {
    }

    private record ValidatedImage(String originalFileName, byte[] content, ImageType type) {
    }

    private enum ImageType {
        JPEG("image/jpeg", "jpg") {
            @Override
            boolean matches(byte[] content) {
                return content.length >= 3
                        && unsigned(content[0]) == 0xFF
                        && unsigned(content[1]) == 0xD8
                        && unsigned(content[2]) == 0xFF;
            }
        },
        PNG("image/png", "png") {
            private static final int[] SIGNATURE = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

            @Override
            boolean matches(byte[] content) {
                if (content.length < SIGNATURE.length) {
                    return false;
                }
                for (int i = 0; i < SIGNATURE.length; i++) {
                    if (unsigned(content[i]) != SIGNATURE[i]) {
                        return false;
                    }
                }
                return true;
            }
        };

        private final String contentType;
        private final String extension;

        ImageType(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        static ImageType from(String contentType) {
            if (contentType != null) {
                String normalized = contentType.trim().toLowerCase(Locale.ROOT);
                for (ImageType type : values()) {
                    if (type.contentType.equals(normalized)) {
                        return type;
                    }
                }
            }
            throw new ApiException(ResponseCode.INVALID_IMAGE, "JPEG 또는 PNG만 업로드할 수 있습니다.");
        }

        abstract boolean matches(byte[] content);

        String contentType() {
            return contentType;
        }

        String extension() {
            return extension;
        }

        static int unsigned(byte value) {
            return value & 0xFF;
        }
    }
}
