package app.pickple.auth.security;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 인가 관문이 요청당 더하는 비용을 측정한다 (PRD-021 C-12, ADR-0035 5번).
 *
 * <p><b>이 측정이 무엇인지, 그리고 무엇이 아닌지.</b>
 *
 * <p>A/B 는 <b>같은 프로세스·같은 엔드포인트</b> 안에서 가른다 —
 * 토큰이 붙은 요청(관문 조회 1회) 대 게스트 요청(조회 0회).
 * 브랜치를 껐다 켜는 A/B 보다 이쪽이 정확하다: 재기동이 끼면 버퍼 풀 워밍·JIT 상태가
 * 달라져 <b>측정하려는 변수 말고 다른 것이 함께 바뀐다.</b>
 *
 * <p><b>이 A/B 가 분리하지 못하는 것</b>: 토큰이 붙은 쪽은 JWT 파싱 비용도 함께 진다.
 * 따라서 여기서 나오는 차이는 <b>관문 조회 비용의 상한</b>이지 순수 비용이 아니다.
 * 상한이 작으면 순수 비용은 그보다 작다 — 판정에는 충분하다.
 *
 * <p><b>이 측정이 답하지 못하는 것</b>: 운영 규모의 총 DB 용량 영향.
 * Testcontainers 의 작은 테이블·워밍된 버퍼 풀·localhost 왕복이므로
 * "매 요청 도는 0.1ms 가 총량을 지배하는가" 는 여기서 답할 수 없다.
 * ADR-0035 가 같은 한계를 이미 적어 두었다 — <b>ADR-0006 을 뒤집는 근거는 성능이 아니라
 * 데이터 오염 증거다.</b> 이 측정이 말해주는 것은 "쿼리 모양이 건전하고 회귀가 없다" 까지다.
 *
 * <p>보고는 <b>(이전 절대값 → 이후 절대값)</b> 을 병기한다. "몇 % 저하" 만 쓰지 않는다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("인가 관문 부하 판정 (#106 C-12)")
class AccountStateGateLoadIT {

    /** 측정 전 버리는 요청 수. JIT·버퍼 풀·커넥션 생성이 첫 요청들에 몰린다. */
    private static final int WARMUP = 200;
    private static final int SAMPLES = 400;

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private PostStore postStore;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 이 테스트가 커밋한 게시글. 롤백이 없으니 직접 지운다. */
    private final List<Long> createdPostIds = new ArrayList<>();

    private MockMvc mockMvc;
    private String token;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        long seed = System.nanoTime();
        User user = userStore.save(new User(SocialProvider.GOOGLE, "load-" + seed, null, "부하"));
        token = jwtService.createAccessToken(user);
        for (int i = 0; i < 20; i++) {
            createdPostIds.add(postStore.save(
                    new Post(user.id(), PostType.GENERAL, PostCategory.ETC, "부하 " + i, null)).id());
        }
    }

    /**
     * 남긴 게시글을 지운다.
     *
     * <p><b>이게 없으면 다른 테스트가 깨진다.</b> {@code PopularPostsIT} 는 EXPLAIN 으로
     * 옵티마이저가 {@code idx_post_popular_all} 을 고르는지 확인하는데, 여기서 남긴 게시글이
     * 테이블 통계를 바꿔 옵티마이저가 다른 인덱스와 filesort 를 고르게 만든다
     * (실제로 겪었다 — 신선한 컨테이너에서는 통과하고 재사용 컨테이너에서만 실패했다).
     * {@code VoteControllerIT} 가 같은 이유로 같은 뒷정리를 한다.
     *
     * <p>회원은 남긴다 — 게시글 없는 회원은 어떤 목록에도 나타나지 않는다.
     */
    @AfterEach
    void tearDown() {
        for (Long postId : createdPostIds) {
            jdbcTemplate.update("DELETE FROM post WHERE id = ?", postId);
        }
        createdPostIds.clear();
    }

    @Test
    @DisplayName("A/B — 같은 엔드포인트에서 조회 있음/없음의 지연 분포")
    void latencyWithAndWithoutStateLookup() throws Exception {
        // B: 게스트 — 관문 조회 0회 (이전 상태에 해당한다)
        long[] withoutLookup = measure(() -> {
            mockMvc.perform(get("/posts")).andReturn();
            return null;
        });
        // A: 토큰 첨부 — 관문 조회 1회 (이후 상태)
        long[] withLookup = measure(() -> {
            mockMvc.perform(get("/posts").header("Authorization", "Bearer " + token)).andReturn();
            return null;
        });

        report("조회 없음(게스트)", withoutLookup);
        report("조회 있음(토큰)", withLookup);

        long p50Before = percentile(withoutLookup, 50);
        long p50After = percentile(withLookup, 50);
        long p99Before = percentile(withoutLookup, 99);
        long p99After = percentile(withLookup, 99);

        System.out.printf("%n[C-12 A/B] p50 %.2fms → %.2fms (%+.2fms)%n",
                us(p50Before), us(p50After), us(p50After - p50Before));
        System.out.printf("[C-12 A/B] p99 %.2fms → %.2fms (%+.2fms)%n",
                us(p99Before), us(p99After), us(p99After - p99Before));

        // 판정은 "빨라야 한다" 가 아니다 — 상수 하나가 늘었으니 느려지는 게 정상이다.
        // 회귀로 볼 선은 "한 자릿수 ms 응답에 관문이 지배적이 되는가" 다.
        // 조회 1회가 p99 를 10ms 넘게 밀어 올린다면 쿼리 모양이 의도와 다르다는 뜻이다.
        assertThat(us(p99After - p99Before))
                .as("관문 조회가 p99 에 더하는 시간(ms). 이 선을 넘으면 쿼리 모양을 다시 본다")
                .isLessThan(10.0);
    }

    @Test
    @DisplayName("단계적 지속 부하 — 동시성을 올려도 풀이 고갈되지 않는다")
    void poolSurvivesSteppedConcurrency() throws Exception {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();

        for (int concurrency : new int[]{1, 5, 10, 20, 40}) {
            ExecutorService pool0 = Executors.newFixedThreadPool(concurrency);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger notOk = new AtomicInteger();
            List<Future<Long>> futures = new ArrayList<>();

            int perThread = 20;
            for (int t = 0; t < concurrency; t++) {
                futures.add(pool0.submit(() -> {
                    start.await();
                    long worst = 0;
                    for (int i = 0; i < perThread; i++) {
                        long began = System.nanoTime();
                        int status = mockMvc.perform(get("/posts")
                                        .header("Authorization", "Bearer " + token))
                                .andReturn().getResponse().getStatus();
                        worst = Math.max(worst, System.nanoTime() - began);
                        if (status == 200) {
                            ok.incrementAndGet();
                        } else {
                            notOk.incrementAndGet();
                        }
                    }
                    return worst;
                }));
            }

            // 풀 지표는 별도 스레드가 부하가 도는 <b>동안</b> 표집한다.
            // future 가 끝난 뒤에 읽으면 이미 반납이 끝나 피크를 놓친다(실제로 0 이 찍혔다).
            AtomicInteger activePeak = new AtomicInteger();
            AtomicInteger pendingPeak = new AtomicInteger();
            AtomicInteger sampling = new AtomicInteger(1);
            Thread sampler = new Thread(() -> {
                while (sampling.get() == 1) {
                    activePeak.accumulateAndGet(pool.getActiveConnections(), Math::max);
                    pendingPeak.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                    Thread.onSpinWait();
                }
            });
            sampler.setDaemon(true);
            sampler.start();

            start.countDown();
            long worstAll = 0;
            for (Future<Long> f : futures) {
                worstAll = Math.max(worstAll, f.get(2, TimeUnit.MINUTES));
            }
            sampling.set(0);
            sampler.join(TimeUnit.SECONDS.toMillis(5));
            pool0.shutdown();

            System.out.printf(
                    "[C-12 부하] 동시 %2d | 성공 %3d 실패 %d | 최악 %.1fms | Hikari active peak %d, pending peak %d, idle %d%n",
                    concurrency, ok.get(), notOk.get(), us(worstAll),
                    activePeak.get(), pendingPeak.get(), pool.getIdleConnections());

            // 관문이 커넥션을 붙잡아 두면 여기서 실패가 난다.
            // 풀(10)보다 큰 동시성에서도 전부 성공해야 한다 — 대기는 해도 고갈되면 안 된다.
            assertThat(notOk.get())
                    .as("동시 %d 에서 실패한 요청 수", concurrency)
                    .isZero();
        }
    }

    // --- 측정 도구 ---------------------------------------------------------

    private long[] measure(Callable<Void> request) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            request.call();
        }
        long[] samples = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            long began = System.nanoTime();
            request.call();
            samples[i] = System.nanoTime() - began;
        }
        java.util.Arrays.sort(samples);
        return samples;
    }

    /** 정렬된 표본에서의 백분위. 평균이 아니라 상한을 본다. */
    private static long percentile(long[] sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static double us(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void report(String label, long[] sorted) {
        System.out.printf("[C-12 %s] p50 %.2fms  p95 %.2fms  p99 %.2fms  max %.2fms  (n=%d)%n",
                label, us(percentile(sorted, 50)), us(percentile(sorted, 95)),
                us(percentile(sorted, 99)), us(sorted[sorted.length - 1]), sorted.length);
    }
}
