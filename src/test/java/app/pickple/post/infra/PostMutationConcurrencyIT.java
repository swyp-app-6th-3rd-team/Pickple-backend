package app.pickple.post.infra;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.post.service.PostService;
import app.pickple.post.service.PostService.UpdateCommand;
import app.pickple.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수정과 삭제의 경합 (ADR-0047 결정 4).
 *
 * <p>{@code deleted_at} 은 보통의 갱신 가능 컬럼이라, 잠그지 않으면 삭제와 경합한 수정이 스냅샷의
 * 낡은 {@code NULL} 을 되써 지운 글이 되살아난다. {@code findByIdForUpdate} 가 두 트랜잭션을 직렬화해
 * 뒤에 오는 쪽이 <b>커밋된</b> 상태를 보게 한다.
 *
 * <p><b>경합이 실제로 성립했는지를 DB 에서 확인한다.</b> 뒤에 오는 스레드가 아직 시작하지 않은 채 앞 트랜잭션이
 * 커밋되면, 잠금이 없어도 최신 상태를 읽어 테스트가 헛되이 통과한다(Codex 리뷰 지적). 그래서 앞 트랜잭션을
 * 풀어 주기 전에 {@code information_schema.innodb_trx} 에서 <b>잠금 대기 중인 트랜잭션</b>을 본다 — 그 조회에는
 * PROCESS 권한이 필요해 컨테이너의 root 로 붙는다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다 — 두 스레드가 실제로 커밋해야 경합이 성립한다.
 * 그래서 픽스처를 손으로 지운다.
 */
@IntegrationTest
class PostMutationConcurrencyIT {

    @Autowired private PostStore postStore;
    @Autowired private PostService postService;
    @Autowired private UserStore userStore;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MySQLContainer<?> mysql;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final CountDownLatch locked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private Long authorId;
    private Long postId;

    @BeforeEach
    void setUp() {
        authorId = userStore.save(
                new User(SocialProvider.GOOGLE, "race-" + System.nanoTime(), null, "작성자")).id();
        postId = postStore.save(new Post(authorId, PostType.GENERAL, PostCategory.ETC, "원래 제목", null)).id();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        // 실패로 빠져나와도 잠금을 쥔 트랜잭션을 먼저 풀고, 스레드가 끝난 뒤에 픽스처를 지운다 —
        // 아니면 정리 DELETE 가 그 잠금에 걸려 원래 실패를 가린다.
        release.countDown();
        executor.shutdownNow();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).as("경합 스레드가 끝나지 않았다").isTrue();
        jdbcTemplate.update("DELETE FROM post WHERE user_id = ?", authorId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", authorId);
    }

    @Test
    @DisplayName("삭제와 경합한 수정은 삭제를 되돌리지 않는다 — 잠금 뒤에 온 수정이 404 를 받는다")
    void editRacingDeleteDoesNotResurrect() throws Exception {
        // T1: 잠금을 쥔 채 멈춰 있다가 신호를 받으면 지우고 커밋한다.
        Future<?> deleter = holdLockThen(post -> {
            post.delete();
            postStore.save(post);
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // T2: 실제 서비스 경로. 잠금 없이는 낡은 스냅샷으로 deleted_at 을 되쓴다.
        Future<Post> editor = startAndAwaitLockWait(() ->
                postService.update(postId, authorId, new UpdateCommand(null, "되살리려는 수정", null)));

        release.countDown();
        deleter.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> editor.get(10, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.NOT_FOUND);
        assertThat(column("deleted_at")).as("수정이 삭제를 되돌리면 안 된다").isNotNull();
        assertThat(column("title")).isEqualTo("원래 제목");
    }

    @Test
    @DisplayName("진행 중인 수정과 경합한 삭제는 그 수정을 잃지 않는다 — 실제 삭제 경로가 잠금을 기다린다")
    void deleteRacingEditKeepsTheEdit() throws Exception {
        // T1: 잠금을 쥔 채 제목을 고치고 커밋한다.
        Future<?> editor = holdLockThen(post -> {
            post.edit("먼저 고친 제목", null, null);
            postStore.save(post);
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        // T2: 실제 삭제 경로. 잠금 없이 읽으면 낡은 제목을 UPDATE 에 실어 T1 의 수정을 덮어쓴다.
        Future<?> deleter = startAndAwaitLockWait(() -> {
            postService.delete(postId, authorId);
            return null;
        });

        release.countDown();
        editor.get(10, TimeUnit.SECONDS);
        deleter.get(10, TimeUnit.SECONDS);

        assertThat(column("deleted_at")).isNotNull();
        assertThat(column("title")).as("삭제가 먼저 커밋된 수정을 덮어쓰면 안 된다").isEqualTo("먼저 고친 제목");
    }

    // --- 경합 조율 -------------------------------------------------------------

    /** 한 트랜잭션 안에서 행을 잠근 뒤 {@code release} 신호까지 멈춰 있다가 {@code work} 를 하고 커밋한다. */
    private Future<?> holdLockThen(java.util.function.Consumer<Post> work) {
        return executor.submit(() -> transactionTemplate.execute(status -> {
            Post post = postStore.findByIdForUpdate(postId).orElseThrow();
            locked.countDown();
            await(release);
            work.accept(post);
            return null;
        }));
    }

    /**
     * 뒤따르는 트랜잭션을 시작하고, 그것이 <b>DB 에서 잠금을 기다리는 상태</b>가 될 때까지 기다린다.
     * 이 확인이 없으면 "아직 시작도 안 한 스레드" 가 앞 트랜잭션 커밋 뒤에 읽어 잠금 없이도 통과한다.
     */
    private <T> Future<T> startAndAwaitLockWait(Callable<T> call) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Future<T> future = executor.submit(() -> {
            started.countDown();
            return call.call();
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!anyTransactionWaitingForLock()) {
            assertThat(future.isDone()).as("경합 상대가 기다리지 않고 끝났다 — 잠금이 없다").isFalse();
            assertThat(System.nanoTime() < deadline).as("잠금 대기 상태가 관측되지 않았다").isTrue();
            Thread.sleep(50);
        }
        return future;
    }

    private boolean anyTransactionWaitingForLock() throws Exception {
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = root.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'")) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private Object column(String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM post WHERE id = ?", Object.class, postId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("경합 상대가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
