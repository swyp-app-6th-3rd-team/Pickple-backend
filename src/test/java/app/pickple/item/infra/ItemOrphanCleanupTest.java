package app.pickple.item.infra;

import app.pickple.config.ItemCleanupProperties;
import app.pickple.item.domain.FileObjectStorage;
import app.pickple.item.domain.ItemOrphanStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ItemOrphanCleanupTest {
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    @Mock private ItemOrphanStore store;
    @Mock private FileObjectStorage storage;
    private ItemOrphanCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new ItemOrphanCleanup(store, storage,
                new ItemCleanupProperties(true, "-", Duration.ofHours(24), NOW.minus(Duration.ofDays(7)), 2),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
        lenient().when(storage.list(anyString(), isNull(), eq(2)))
                .thenReturn(new FileObjectStorage.ObjectPage(List.of(), null));
    }

    @Test
    void doesNothingWhenDisabled() {
        new ItemOrphanCleanup(store, storage, new ItemCleanupProperties(false, "-", Duration.ofHours(24), null, 2),
                Clock.fixed(NOW, ZoneOffset.UTC)).clean();
        verifyNoInteractions(store, storage);
    }

    @Test
    void databaseFailureDoesNotDeleteItsKeysOrStopNextCandidate() {
        when(store.findCandidates(any(), any(), eq(0L), eq(2))).thenReturn(List.of(1L, 2L));
        when(store.removeIfUnattached(eq(1L), any(), any())).thenThrow(new IllegalStateException("commit failed"));
        when(store.removeIfUnattached(eq(2L), any(), any())).thenReturn(List.of("product-images/2/valid.jpg"));
        cleanup.clean();
        verify(storage).delete("product-images/2/valid.jpg");
        verify(storage, times(1)).delete(anyString());
        verify(store).findCandidates(any(), any(), eq(2L), eq(2));
    }

    @Test
    void metadataLookupFailureProtectsObjectAndContinues() {
        when(storage.list(eq("product-images/"), isNull(), eq(2))).thenReturn(page("first", "next"));
        when(store.containsObjectKey("first")).thenThrow(new IllegalStateException("lock timeout"));
        cleanup.clean();
        verify(storage, never()).delete("first");
        verify(storage).delete("next");
    }

    @Test
    void listingFailureInOnePrefixDoesNotStopTheOther() {
        when(storage.list(eq("product-images/"), isNull(), eq(2))).thenThrow(new FileObjectStorageException("403"));
        when(storage.list(eq("comment-images/"), isNull(), eq(2))).thenReturn(page("comment"));
        cleanup.clean();
        verify(storage).delete("comment");
    }

    @Test
    void keepsAKeyStillReferencedByAnotherContainer() {
        when(store.findCandidates(any(), any(), eq(0L), eq(2))).thenReturn(List.of(1L));
        when(store.removeIfUnattached(eq(1L), any(), any())).thenReturn(List.of("shared"));
        when(store.containsObjectKey("shared")).thenReturn(true);
        cleanup.clean();
        verify(storage, never()).delete(anyString());
    }

    private FileObjectStorage.ObjectPage page(String... keys) {
        return new FileObjectStorage.ObjectPage(java.util.Arrays.stream(keys)
                .map(key -> new FileObjectStorage.StoredObject(key, NOW.minus(Duration.ofDays(2)))).toList(), null);
    }
}
