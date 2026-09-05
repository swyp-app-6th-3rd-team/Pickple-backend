package app.pickple.item.service;

import app.pickple.config.FileStorageProperties;
import app.pickple.error.ApiException;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.FileObjectStorage;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.infra.FileObjectStorageException;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageUploadServiceTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
    @Mock private FileObjectStorage objectStorage;
    @Mock private ItemContainerStore containerStore;
    private ImageUploadService service;

    @BeforeEach
    void setUp() {
        service = new ImageUploadService(objectStorage, containerStore, new FileStorageProperties(
                DataSize.ofMegabytes(5), new FileStorageProperties.S3("test", "us-east-1", null, null)));
    }

    private void stubUrls() {
        when(objectStorage.accessUrl(anyString()))
                .thenAnswer(call -> "https://images.example/" + call.getArgument(0));
    }

    @Test
    void persistsAllKeysBeforeWritingAnyObject() {
        stubUrls();
        when(containerStore.save(any())).thenAnswer(call -> call.getArgument(0));
        var saved = service.upload(1L, AttachType.PRODUCT, List.of(image("first.jpg"), image("second.jpg")));
        var order = inOrder(containerStore, objectStorage);
        order.verify(containerStore).save(any());
        order.verify(objectStorage, times(2)).put(anyString(), any(byte[].class), anyString());
        assertThat(saved.resources()).hasSize(2).allSatisfy(resource -> {
            assertThat(resource.itemKey()).startsWith("product-images/1/").endsWith(".jpg");
            assertThat(resource.accessUrl()).isEqualTo("https://images.example/" + resource.itemKey());
        });
    }

    @Test
    void databaseFailurePreventsAnyObjectWrite() {
        stubUrls();
        when(containerStore.save(any())).thenThrow(new IllegalStateException("DB failure"));
        assertThatThrownBy(() -> service.upload(1L, AttachType.PRODUCT, List.of(image("first.jpg"))))
                .isInstanceOf(IllegalStateException.class);
        verify(objectStorage, never()).put(anyString(), any(), anyString());
        verify(objectStorage, never()).delete(anyString());
    }

    @Test
    void partialUploadFailureIsLeftForPeriodicCleanup() {
        stubUrls();
        when(containerStore.save(any())).thenAnswer(call -> call.getArgument(0));
        when(objectStorage.put(anyString(), any(), anyString()))
                .thenReturn("https://images.example/first")
                .thenThrow(new FileObjectStorageException("S3 failure"));
        assertThatThrownBy(() -> service.upload(2L, AttachType.PRODUCT,
                List.of(image("first.jpg"), image("second.jpg"))))
                .isInstanceOf(FileObjectStorageException.class);
        verify(objectStorage, times(2)).put(anyString(), any(), anyString());
        verify(objectStorage, never()).delete(anyString());
    }

    @Test
    void preservesBothKeyPrefixes() {
        stubUrls();
        when(containerStore.save(any())).thenAnswer(call -> call.getArgument(0));
        service.upload(7L, AttachType.COMMENT, List.of(image("comment.jpg")));
        service.upload(7L, AttachType.PRODUCT, List.of(image("product.jpg")));
        var keys = ArgumentCaptor.forClass(String.class);
        verify(objectStorage, times(2)).put(keys.capture(), any(), anyString());
        assertThat(keys.getAllValues().get(0)).startsWith("comment-images/7/");
        assertThat(keys.getAllValues().get(1)).startsWith("product-images/7/");
    }

    @Test
    void validatesEntireRequestBeforeAnyPersistence() {
        var fake = new ImageUploadService.UploadImage("fake.jpg", "image/jpeg", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.upload(1L, AttachType.PRODUCT, List.of(image("valid.jpg"), fake)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(objectStorage, containerStore);
    }

    @Test
    void rejectsMissingAttachType() {
        assertThatThrownBy(() -> service.upload(1L, null, List.of(image("product.jpg"))))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(objectStorage, containerStore);
    }

    private ImageUploadService.UploadImage image(String name) {
        return new ImageUploadService.UploadImage(name, "image/jpeg", JPEG.clone());
    }
}
