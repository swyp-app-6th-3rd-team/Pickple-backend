package app.pickple.item.service;

import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.config.FileStorageProperties;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.FileObjectStorage;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageUploadService {

    private static final int MAX_ORIGINAL_FILE_NAME_LENGTH = 255;

    private final FileObjectStorage objectStorage;
    private final ItemContainerStore containerStore;
    private final FileStorageProperties properties;

    /**
     * 파일 묶음을 S3에 올린 뒤 하나의 컨테이너로 저장한다.
     *
     * <p>용도({@link AttachType})는 호출자가 정한다. 기본값을 두지 않는 이유는,
     * 기본값이 있으면 용도를 넘기지 않은 호출이 조용히 상품으로 분류되기 때문이다.
     *
     * <p>DB 메타데이터를 먼저 기록한 뒤 S3에 저장한다. 정리 작업의 잠금 조회가
     * 진행 중인 업로드의 최종 커밋/롤백을 기다릴 수 있게 하는 순서다.
     * 롤백으로 남은 객체는 유예시간 뒤 주기 정리가 회수한다(ADR-0038).
     */
    @Transactional
    public ItemContainer upload(Long ownerId, AttachType attachType, List<UploadImage> images) {
        if (ownerId == null) {
            throw new ApiException(ResponseCode.UNAUTHORIZED);
        }
        if (attachType == null) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "이미지 용도가 없습니다.");
        }
        if (images == null || images.isEmpty()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "업로드할 이미지가 없습니다.");
        }

        List<ValidatedImage> validatedImages = images.stream().map(this::validate).toList();
        List<String> itemKeys = new ArrayList<>();
        ItemContainer container = new ItemContainer(ownerId, attachType);
        for (ValidatedImage validated : validatedImages) {
            String itemKey = createItemKey(ownerId, attachType, validated.type().extension());
            itemKeys.add(itemKey);
            container.add(new ItemResource(validated.content().length,
                    validated.originalFileName(), itemKey, objectStorage.accessUrl(itemKey)));
        }
        ItemContainer saved = containerStore.save(container);
        for (int i = 0; i < validatedImages.size(); i++) {
            ValidatedImage validated = validatedImages.get(i);
            objectStorage.put(itemKeys.get(i), validated.content(), validated.type().contentType());
        }
        return saved;
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

    private String createItemKey(Long ownerId, AttachType attachType, String extension) {
        return "%s/%d/%s.%s".formatted(attachType.keyPrefix(), ownerId, UUID.randomUUID(), extension);
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
