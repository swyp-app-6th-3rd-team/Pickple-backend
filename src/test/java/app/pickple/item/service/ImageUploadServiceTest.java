package app.pickple.item.service;

import app.pickple.error.ApiException;
import app.pickple.item.config.ImageStorageProperties;
import app.pickple.item.domain.ImageObjectStorage;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.infra.ImageStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageUploadServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    @Mock
    private ImageObjectStorage objectStorage;
    @Mock
    private ItemContainerStore containerStore;

    private ImageUploadService service;

    @BeforeEach
    void setUp() {
        ImageStorageProperties properties = new ImageStorageProperties(
                DataSize.ofMegabytes(5),
                new ImageStorageProperties.S3("test", "us-east-1", null, null));
        service = new ImageUploadService(objectStorage, containerStore, properties);
    }

    @Test
    @DisplayName("DB 저장이 실패하면 이미 업로드한 S3 객체를 모두 보상 삭제한다")
    void compensatesObjectsWhenDatabaseSaveFails() {
        when(objectStorage.put(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> "https://images.example/" + invocation.getArgument(0, String.class));
        when(containerStore.save(any(ItemContainer.class)))
                .thenThrow(new IllegalStateException("DB failure"));

        assertThatThrownBy(() -> service.upload(1L, List.of(
                image("first.jpg"), image("second.jpg"))))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> deletedKeys = ArgumentCaptor.forClass(String.class);
        verify(objectStorage, times(2)).delete(deletedKeys.capture());
        assertThat(deletedKeys.getAllValues())
                .allMatch(key -> key.startsWith("product-images/1/"))
                .allMatch(key -> key.endsWith(".jpg"));
    }

    @Test
    @DisplayName("두 번째 S3 요청이 실패해도 성공 여부가 불확실한 키까지 보상 삭제한다")
    void compensatesAttemptedKeysWhenLaterUploadFails() {
        when(objectStorage.put(anyString(), any(byte[].class), anyString()))
                .thenReturn("https://images.example/first")
                .thenThrow(new ImageStorageException("S3 failure"));

        assertThatThrownBy(() -> service.upload(2L, List.of(
                image("first.jpg"), image("second.jpg"))))
                .isInstanceOf(ImageStorageException.class);

        verify(objectStorage, times(2)).delete(anyString());
        verify(containerStore, never()).save(any());
    }

    @Test
    @DisplayName("파일 시그니처가 틀리면 외부 저장소와 DB를 호출하지 않는다")
    void rejectsInvalidSignatureBeforeExternalCalls() {
        ImageUploadService.UploadImage fake = new ImageUploadService.UploadImage(
                "fake.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.upload(1L, List.of(fake)))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(objectStorage, containerStore);
    }

    private ImageUploadService.UploadImage image(String name) {
        return new ImageUploadService.UploadImage(name, "image/jpeg", JPEG.clone());
    }
}
