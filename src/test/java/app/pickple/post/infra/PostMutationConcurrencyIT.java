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
 * 뒤에 오는 수정이 <b>커밋된</b> 삭제를 보게 한다.
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

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Long authorId;
    private Long postId;

    @BeforeEach
    void setUp() {
        authorId = userStore.save(
                new User(SocialProvider.GOOGLE, "race-" + System.nanoTime(), null, "작성자")).id();
        postId = postStore.save(new Post(authorId, PostType.GENERAL, PostCategory.ETC, "원래 제목", null)).id();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbcTemplate.update("DELETE FROM post WHERE user_id = ?", authorId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", authorId);
    }

    @Test
    @DisplayName("삭제와 경합한 수정은 삭제를 되돌리지 않는다 — 잠금 뒤에 온 수정이 404 를 받는다")
    void editRacingDeleteDoesNotResurrect() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // T1: 잠금을 쥔 채 멈춰 있다가 신호를 받으면 지우고 커밋한다.
        Future<?> deleter = executor.submit(() -> transactionTemplate.execute(status -> {
            Post post = postStore.findByIdForUpdate(postId).orElseThrow();
            locked.countDown();
            await(release);
            post.delete();
            postStore.save(post);
            return null;
        }));

        // T2: T1 이 잠근 뒤에 수정을 시작한다. 잠금 없이는 낡은 스냅샷으로 deleted_at 을 되쓴다.
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Post> editor = executor.submit(() ->
                postService.update(postId, authorId, new UpdateCommand(null, "되살리려는 수정", null)));

        // T2 가 잠금에 걸릴 시간을 준 뒤 T1 을 풀어 준다. 이 순서가 "삭제가 먼저 커밋" 을 만든다.
        Thread.sleep(500);
        assertThat(editor.isDone()).as("잠금 없이 수정이 먼저 끝났다면 경합이 성립하지 않는다").isFalse();
        release.countDown();
        deleter.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> editor.get(10, TimeUnit.SECONDS))
                .cause()
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ResponseCode.NOT_FOUND);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM post WHERE id = ?", Object.class, postId))
                .as("수정이 삭제를 되돌리면 안 된다").isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM post WHERE id = ?", String.class, postId)).isEqualTo("원래 제목");
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
