package app.pickple.item.service;

import app.pickple.error.ApiException;
import app.pickple.config.FileStorageProperties;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.FileObjectStorage;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.infra.FileStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private FileObjectStorage objectStorage;
    @Mock
    private ItemContainerStore containerStore;

    private ImageUploadService service;

    @BeforeEach
    void setUp() {
        FileStorageProperties properties = new FileStorageProperties(
                DataSize.ofMegabytes(5),
                new FileStorageProperties.S3("test", "us-east-1", null, null));
        service = new ImageUploadService(objectStorage, containerStore, properties);
    }

    @Test
    @DisplayName("DB 저장이 실패하면 이미 업로드한 S3 객체를 모두 보상 삭제한다")
    void compensatesObjectsWhenDatabaseSaveFails() {
        when(objectStorage.put(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> "https://images.example/" + invocation.getArgument(0, String.class));
        when(containerStore.save(any(ItemContainer.class)))
                .thenThrow(new IllegalStateException("DB failure"));

        assertThatThrownBy(() -> service.upload(1L, AttachType.PRODUCT, List.of(
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
                .thenThrow(new FileStorageException("S3 failure"));

        assertThatThrownBy(() -> service.upload(2L, AttachType.PRODUCT, List.of(
                image("first.jpg"), image("second.jpg"))))
                .isInstanceOf(FileStorageException.class);

        verify(objectStorage, times(2)).delete(anyString());
        verify(containerStore, never()).save(any());
    }

    @Test
    @DisplayName("용도에 따라 객체 키 접두어가 갈린다")
    void usesKeyPrefixOfAttachType() {
        when(objectStorage.put(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> "https://images.example/" + invocation.getArgument(0, String.class));
        when(containerStore.save(any(ItemContainer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.upload(7L, AttachType.COMMENT, List.of(image("comment.jpg")));

        ArgumentCaptor<String> commentKey = ArgumentCaptor.forClass(String.class);
        verify(objectStorage).put(commentKey.capture(), any(byte[].class), anyString());
        assertThat(commentKey.getValue()).startsWith("comment-images/7/");

        // PRODUCT 접두어는 이미 배포된 객체가 쓰고 있으므로 바뀌면 안 된다.
        service.upload(7L, AttachType.PRODUCT, List.of(image("product.jpg")));

        ArgumentCaptor<String> allKeys = ArgumentCaptor.forClass(String.class);
        verify(objectStorage, times(2)).put(allKeys.capture(), any(byte[].class), anyString());
        assertThat(allKeys.getAllValues().get(1)).startsWith("product-images/7/");
    }

    @Test
    @DisplayName("용도가 없으면 400으로 거부한다")
    void rejectsMissingAttachType() {
        assertThatThrownBy(() -> service.upload(1L, null, List.of(image("product.jpg"))))
                .isInstanceOf(ApiException.class);

        verify(objectStorage, never()).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("트랜잭션 완료 콜백이 롤백 상태를 받으면 S3 객체를 보상 삭제한다")
    void compensatesObjectsWhenTransactionCompletionReportsRollback() {
        when(objectStorage.put(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> "https://images.example/" + invocation.getArgument(0, String.class));
        when(containerStore.save(any(ItemContainer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();

        try {
            service.upload(3L, AttachType.PRODUCT, List.of(image("product.jpg")));
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));

            ArgumentCaptor<String> uploadedKey = ArgumentCaptor.forClass(String.class);
            verify(objectStorage).put(uploadedKey.capture(), any(byte[].class), anyString());
            verify(objectStorage).delete(uploadedKey.getValue());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션 결과를 알 수 없으면 DB 참조 보호를 위해 S3 객체를 삭제하지 않는다")
    void keepsObjectsWhenTransactionCompletionIsUnknown() {
        when(objectStorage.put(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> "https://images.example/" + invocation.getArgument(0, String.class));
        when(containerStore.save(any(ItemContainer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();

        try {
            service.upload(4L, AttachType.PRODUCT, List.of(image("product.jpg")));
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_UNKNOWN));

            verify(objectStorage, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("파일 시그니처가 틀리면 외부 저장소와 DB를 호출하지 않는다")
    void rejectsInvalidSignatureBeforeExternalCalls() {
        ImageUploadService.UploadImage fake = new ImageUploadService.UploadImage(
                "fake.jpg", "image/jpeg", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.upload(1L, AttachType.PRODUCT, List.of(fake)))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(objectStorage, containerStore);
    }

    private ImageUploadService.UploadImage image(String name) {
        return new ImageUploadService.UploadImage(name, "image/jpeg", JPEG.clone());
    }
}
