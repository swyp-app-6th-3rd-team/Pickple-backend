package app.pickple.item.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.config.ItemCleanupProperties;
import app.pickple.config.FileStorageProperties;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.FileObjectStorage;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemOrphanStore;
import app.pickple.item.service.ImageUploadService;
import app.pickple.post.domain.*;
import app.pickple.support.IntegrationTest;
import app.pickple.support.LocalStackConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@IntegrationTest
@Import(LocalStackConfig.class)
class ItemOrphanCleanupIT {
    private static final String BUCKET = "pickple-image-upload-it";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Duration GRACE = Duration.ofHours(24);
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    @Autowired private UserStore users;
    @Autowired private ItemContainerStore containers;
    @Autowired private ItemOrphanStore orphans;
    @Autowired private FileObjectStorage storage;
    @Autowired private ImageUploadService uploadService;
    @Autowired private PostStore posts;
    @Autowired private CommentStore comments;
    @Autowired private S3Client s3;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FileStorageProperties fileProperties;

    private Long ownerId;
    private Instant managedSince;
    private final List<String> extraKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        try {
            s3.createBucket(request -> request.bucket(BUCKET));
        } catch (S3Exception failure) {
            if (failure.statusCode() != 409) throw failure;
        }
        managedSince = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        ownerId = users.save(new User(SocialProvider.GOOGLE, "cleanup-" + UUID.randomUUID(), null, "정리테스트")).id();
    }

    @AfterEach
    void tearDown() {
        for (AttachType type : AttachType.values()) {
            s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(type.keyPrefix() + "/" + ownerId + "/"))
                    .contents().forEach(object -> storage.delete(object.key()));
        }
        extraKeys.forEach(storage::delete);
        jdbc.update("DELETE FROM comment WHERE user_id = ?", ownerId);
        jdbc.update("DELETE FROM post WHERE user_id = ?", ownerId);
        jdbc.update("DELETE FROM item_container WHERE user_id = ?", ownerId);
        jdbc.update("DELETE FROM users WHERE id = ?", ownerId);
    }

    @Test
    void graceBoundaryAndExistingObjectsAreProtected() {
        String key = objectOnly(AttachType.PRODUCT);
        Instant modified = modifiedAt(key);
        run(storage, modified.plus(GRACE));
        assertThat(exists(key)).isTrue(); // 경계와 같은 시각은 보호한다.
        run(storage, modified.plus(GRACE).minusSeconds(1));
        assertThat(exists(key)).isTrue();
        run(storage, modified.plus(GRACE).plusSeconds(1));
        assertThat(exists(key)).isFalse();

        String legacy = objectOnly(AttachType.PRODUCT);
        managedSince = modifiedAt(legacy).plusSeconds(1);
        run(storage, Instant.now().plus(Duration.ofDays(2)));
        assertThat(exists(legacy)).isTrue();
    }

    @Test
    void removesUnattachedMetadataAndObjectsAcrossPagesIdempotently() {
        var uploaded = new ArrayList<ItemContainer>();
        for (int i = 0; i < 5; i++) uploaded.add(upload(i % 2 == 0 ? AttachType.PRODUCT : AttachType.COMMENT));
        Instant now = Instant.now().plus(Duration.ofDays(2));
        run(storage, now);
        run(storage, now);
        for (ItemContainer container : uploaded) {
            assertThat(containers.findById(container.id())).isEmpty();
            assertThat(exists(key(container))).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_resource r JOIN item_container c ON c.id = r.item_container_id WHERE c.user_id = ?",
                Long.class, ownerId)).isZero();
    }

    @Test
    void metadataGraceBoundaryAndManagementStartAreProtected() {
        ItemContainer container = upload(AttachType.PRODUCT);
        Instant created = jdbc.queryForObject("SELECT updated_at FROM item_container WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1).toLocalDateTime().atZone(ZONE).toInstant(), container.id());
        run(storage, created.plus(GRACE));
        assertThat(containers.findById(container.id())).isPresent();
        managedSince = created.plusSeconds(1);
        run(storage, created.plus(Duration.ofDays(2)));
        assertThat(containers.findById(container.id())).isPresent();
        assertThat(exists(key(container))).isTrue();
    }

    @Test
    void preservesActiveAndSoftDeletedPostAndCommentReferences() {
        ItemContainer active = upload(AttachType.PRODUCT);
        productPost(active.id());
        ItemContainer deleted = upload(AttachType.PRODUCT);
        Post deletedPost = productPost(deleted.id());
        deletedPost.delete();
        posts.save(deletedPost);
        ItemContainer commentImage = upload(AttachType.COMMENT);
        Post general = generalPost();
        Comment comment = comments.save(new Comment(general.id(), ownerId, "이미지 댓글", commentImage.id()));
        comment.delete();
        comments.save(comment);

        run(storage, Instant.now().plus(Duration.ofDays(2)));
        for (ItemContainer container : List.of(active, deleted, commentImage)) {
            assertThat(containers.findById(container.id())).isPresent();
            assertThat(exists(key(container))).isTrue();
        }
    }

    @Test
    void objectFailureDoesNotStopNextObjectAndNextRunRetriesAfterMetadataDeletion() {
        ItemContainer failed = upload(AttachType.PRODUCT);
        ItemContainer next = upload(AttachType.PRODUCT);
        FileObjectStorage failingStorage = spy(storage);
        doThrow(new FileObjectStorageException("injected delete failure")).when(failingStorage).delete(key(failed));
        Instant now = Instant.now().plus(Duration.ofDays(2));
        run(failingStorage, now);
        assertThat(containers.findById(failed.id())).isEmpty();
        assertThat(exists(key(failed))).isTrue();
        assertThat(exists(key(next))).isFalse();
        run(storage, now);
        assertThat(exists(key(failed))).isFalse();
    }

    @Test
    void paginatesObjectOnlyOrphansAndIgnoresUnmanagedPrefixes() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 5; i++) keys.add(objectOnly(AttachType.PRODUCT));
        String outside = "defaults/" + UUID.randomUUID() + ".jpg";
        extraKeys.add(outside);
        storage.put(outside, JPEG, "image/jpeg");
        run(storage, Instant.now().plus(Duration.ofDays(2)));
        assertThat(keys).noneMatch(this::exists);
        assertThat(exists(outside)).isTrue();
    }

    @Test
    void outerTransactionRollbackLeavesObjectForReconciliation() {
        ItemContainer[] result = new ItemContainer[1];
        tx().executeWithoutResult(status -> {
            result[0] = upload(AttachType.PRODUCT);
            status.setRollbackOnly();
        });
        assertThat(containers.findById(result[0].id())).isEmpty();
        assertThat(exists(key(result[0]))).isTrue();
        run(storage, Instant.now().plus(Duration.ofDays(2)));
        assertThat(exists(key(result[0]))).isFalse();
    }

    @Test
    void lostResponseDuringPartialUploadRollsBackMetadataAndReconcilesEveryWrittenObject() {
        FileObjectStorage ambiguousStorage = spy(storage);
        AtomicInteger writes = new AtomicInteger();
        doAnswer(call -> {
            Object url = call.callRealMethod();
            if (writes.incrementAndGet() == 2) throw new FileObjectStorageException("response lost after put");
            return url;
        }).when(ambiguousStorage).put(anyString(), any(), anyString());
        var service = new ImageUploadService(ambiguousStorage, containers, fileProperties);
        var image = new ImageUploadService.UploadImage("test.jpg", "image/jpeg", JPEG);
        assertThatThrownBy(() -> tx().executeWithoutResult(status ->
                service.upload(ownerId, AttachType.PRODUCT, List.of(image, image))))
                .isInstanceOf(FileObjectStorageException.class);
        var objects = s3.listObjectsV2(request -> request.bucket(BUCKET).prefix("product-images/" + ownerId + "/"))
                .contents();
        assertThat(objects).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_container WHERE user_id = ?", Long.class, ownerId))
                .isZero();
        run(storage, Instant.now().plus(Duration.ofDays(2)));
        assertThat(objects).allSatisfy(object -> assertThat(exists(object.key())).isFalse());
    }

    @Test
    void rechecksAttachmentAfterCandidateDiscovery() {
        ItemContainer container = upload(AttachType.PRODUCT);
        LocalDateTime cutoff = LocalDateTime.now(ZONE).plusDays(2);
        assertThat(orphans.findCandidates(lower(), cutoff, 0, 1000)).contains(container.id());
        productPost(container.id());
        assertThat(orphans.removeIfUnattached(container.id(), lower(), cutoff)).isEmpty();
        assertThat(exists(key(container))).isTrue();
    }

    @Test
    void attachmentCommittedDuringCleanupWaitKeepsItsImage() throws Exception {
        ItemContainer container = upload(AttachType.PRODUCT);
        CountDownLatch attached = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var attachment = executor.submit(() -> tx().executeWithoutResult(status -> {
                productPost(container.id());
                attached.countDown();
                await(commit);
            }));
            try {
                await(attached);
                var cleanup = executor.submit(() -> {
                    cleaning.countDown();
                    return orphans.removeIfUnattached(container.id(), lower(), LocalDateTime.now(ZONE).plusDays(2));
                });
                await(cleaning);
                assertThatThrownBy(() -> cleanup.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commit.countDown();
                attachment.get(10, TimeUnit.SECONDS);
                assertThat(cleanup.get(10, TimeUnit.SECONDS)).isEmpty();
            } finally {
                commit.countDown();
            }
        }
        assertThat(containers.findById(container.id())).isPresent();
        assertThat(exists(key(container))).isTrue();
    }

    @Test
    void cleanupCommittedFirstRejectsLateAttachment() {
        ItemContainer container = upload(AttachType.PRODUCT);
        var keys = orphans.removeIfUnattached(container.id(), lower(), LocalDateTime.now(ZONE).plusDays(2));
        assertThat(keys).containsExactly(key(container));
        assertThatThrownBy(() -> productPost(container.id())).isInstanceOf(DataIntegrityViolationException.class);
        keys.forEach(storage::delete);
        assertThat(exists(key(container))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void objectKeyLookupWaitsForInflightUploadOutcome(boolean committed) throws Exception {
        CountDownLatch uploaded = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch looking = new CountDownLatch(1);
        ItemContainer[] result = new ItemContainer[1];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> tx().executeWithoutResult(status -> {
                result[0] = upload(AttachType.PRODUCT);
                uploaded.countDown();
                await(commit);
                if (!committed) status.setRollbackOnly();
            }));
            try {
                await(uploaded);
                assertThat(exists(key(result[0]))).isTrue();
                var lookup = executor.submit(() -> {
                    looking.countDown();
                    return orphans.containsObjectKey(key(result[0]));
                });
                await(looking);
                assertThatThrownBy(() -> lookup.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                commit.countDown();
                writer.get(10, TimeUnit.SECONDS);
                assertThat(lookup.get(10, TimeUnit.SECONDS)).isEqualTo(committed);
            } finally {
                commit.countDown();
            }
        }
    }

    private void run(FileObjectStorage objectStorage, Instant now) {
        var properties = new ItemCleanupProperties(true, "-", GRACE, managedSince, 2);
        new ItemOrphanCleanup(orphans, objectStorage, properties, Clock.fixed(now, ZONE)).clean();
    }

    private ItemContainer upload(AttachType type) {
        return uploadService.upload(ownerId, type, List.of(new ImageUploadService.UploadImage("test.jpg", "image/jpeg", JPEG)));
    }

    private String objectOnly(AttachType type) {
        String key = type.keyPrefix() + "/" + ownerId + "/" + UUID.randomUUID() + ".jpg";
        storage.put(key, JPEG, "image/jpeg");
        return key;
    }

    private String key(ItemContainer container) { return container.resources().getFirst().itemKey(); }
    private LocalDateTime lower() { return LocalDateTime.ofInstant(managedSince, ZONE); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private Post generalPost() { return posts.save(new Post(ownerId, PostType.GENERAL, PostCategory.ETC, "정리 테스트", null)); }

    private Post productPost(Long containerId) {
        return posts.save(new Post(ownerId, PostType.AGREE, PostCategory.ETC, "참조 테스트", null)
                .addProduct(new PostProduct(containerId, "상품", 100L, null, 1))
                .addOption(PostOption.ofLabel("산다", 1)).addOption(PostOption.ofLabel("안산다", 2)));
    }

    private Instant modifiedAt(String key) {
        return s3.headObject(request -> request.bucket(BUCKET).key(key)).lastModified();
    }

    private boolean exists(String key) {
        try {
            s3.headObject(request -> request.bucket(BUCKET).key(key));
            return true;
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) return false;
            throw failure;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("동시성 단계 대기 시간 초과");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
