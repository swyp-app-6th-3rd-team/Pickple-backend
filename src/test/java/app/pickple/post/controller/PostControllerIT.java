package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.AccountWithdrawalPersistenceService;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.service.CommentService;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.point.service.RankingBatchService;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostSort;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.support.SqlCapture;
import app.pickple.vote.service.VoteService;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import net.minidev.json.JSONArray;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시글 목록 조회 API (이슈 #19) 의 완료 판정을 실제 MySQL 로 확인한다.
 *
 * <p>단위 테스트로는 잡히지 않는 것만 여기서 본다 — 실행 계획(WHERE 로 걸리는가),
 * 쿼리 횟수(N+1 이 없는가), 커서 왕복(중복·누락이 없는가).
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
@Import(SqlCapture.Config.class)
class PostControllerIT {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private PostStore postStore;
    @Autowired
    private ItemContainerStore containerStore;
    @Autowired
    private CommentService commentService;
    @Autowired
    private VoteService voteService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Clock clock;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private RankingBatchService rankingBatch;
    @Autowired
    private AccountWithdrawalPersistenceService withdrawalPersistenceService;
    @Autowired
    private SqlCapture sqlCapture;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 이 클래스만 쓰는 카테고리.
     *
     * <p>테스트 컨테이너를 재사용하므로({@code ContainerConfig.withReuse}) 다른 테스트
     * 클래스가 남긴 게시글이 같은 테이블에 남아 있다. 목록 API 는 <b>전체</b>를 읽는
     * 첫 엔드포인트라 그 잔여 데이터에 그대로 노출된다. 조각 경계와 "0건" 을 검증하려면
     * 이 실행이 만든 것만 보이는 창이 필요하다.
     */
    private static final PostCategory EMPTY_CATEGORY = PostCategory.ELECTRONICS;

    /**
     * 목록 한 조각이 내는 SQL 문장 수. 행 수에 비례하는 문장이 없다는 것이 지키려는 성질이고,
     * 이 상수는 그 상수 비용이 무엇으로 이루어졌는지를 고정한다 — 늘어나면 이유를 대야 한다.
     * <ol>
     *   <li>키 문장 — 조각에 들어갈 게시글 id 를 정렬 인덱스로 확정한다</li>
     *   <li>행 문장 — 그 id 들에만 작성자와 대표 사진을 붙인다 (ADR-0045)</li>
     * </ol>
     * 옛 네이티브 SQL 은 파생 테이블로 둘을 한 문장에 담았다. QueryDSL 전환으로 갈라졌지만
     * 행 수에 비례하는 쪽은 여전히 없다. 활동 목록의 3 과 달리 2 인 것은 {@code GET /posts} 가
     * 게스트 허용이라 탈퇴 차단 관문 문장이 없기 때문이다.
     */
    private static final long STATEMENTS_PER_SLICE = 2L;

    /**
     * 닉네임 일련번호. 인스턴스가 아니라 클래스 전역이다 — {@code QueryPlan} 의 {@code ANALYZE TABLE} 이
     * 트랜잭션을 암묵적으로 커밋해 그 테스트의 회원이 롤백되지 않고 남으므로, 다음 테스트가 같은
     * 번호를 다시 쓰면 {@code uk_users_active_nickname} 에 걸린다. 재사용 컨테이너의 이전 실행분과도
     * 겹치지 않도록 시작점을 시각에서 얻는다.
     */
    private static final AtomicLong NICKNAME_SEQUENCE = new AtomicLong(System.nanoTime() % 60_000_000L);

    private MockMvc mockMvc;
    private User author;
    private long seed;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        seed = System.nanoTime();
        author = saveUser("post-author-" + seed, "글쓴이");
    }

    @Test
    @DisplayName("게시글이 없으면 게스트에게 200 과 빈 배열을 준다")
    void emptyListForGuest() throws Exception {
        // 완료 판정: "게시글 0건일 때 빈 목록을 정상 응답 → 200 + 빈 배열".
        // 서버는 존재하지 않는 더미 게시글을 지어내지 않는다 — 지어내면 탭했을 때 갈 곳이 없다.
        //
        // 컨테이너를 재사용하므로(ContainerConfig.withReuse) 다른 테스트 클래스가 남긴
        // 게시글이 보인다. "0건" 을 만들려면 아무도 쓰지 않는 카테고리로 좁혀야 한다.
        mockMvc.perform(get("/posts?category=" + EMPTY_CATEGORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject.content").isEmpty())
                .andExpect(jsonPath("$.returnObject.hasNext").value(false))
                .andExpect(jsonPath("$.returnObject.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("유형별로 명세가 요구하는 필드가 내려온다")
    void exposesFieldsPerPostType() throws Exception {
        // §4.2 — 찬반은 상품명·상품사진, A/B 는 주제·A 상품 사진, 일반은 제목만.
        Long agreeId = saveAgreePost("가방 살까", EMPTY_CATEGORY, 3).id();
        Long abId = saveAbPost("A 냐 B 냐", EMPTY_CATEGORY).id();
        Long generalId = saveGeneralPost("그냥 잡담", EMPTY_CATEGORY).id();

        // 순서를 <b>시각으로 명시</b>한다. 세 건이 같은 초에 저장되면 순서는 id 가 가르는데,
        // 그 id 순서는 픽스처의 저장 방식(JPA vs JDBC)에 따라 달라질 수 있다.
        // 이 테스트가 보려는 것은 유형별 필드이지 id 채번 순서가 아니므로 시각을 못박는다.
        stampCreatedAt(agreeId, 3);
        stampCreatedAt(abId, 2);
        stampCreatedAt(generalId, 1);
        flush();

        // 다른 테스트가 남긴 게시글과 섞이지 않도록 이 실행이 만든 카테고리로 좁힌다.
        mockMvc.perform(get("/posts?category=" + EMPTY_CATEGORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content.length()").value(3))
                // 최신순이므로 마지막에 만든 일반 게시글이 앞에 온다.
                .andExpect(jsonPath("$.returnObject.content[0].id").value(generalId))
                .andExpect(jsonPath("$.returnObject.content[0].type").value("GENERAL"))
                .andExpect(jsonPath("$.returnObject.content[0].title").value("그냥 잡담"))
                // 일반 게시글에는 투표도 상품 사진도 없다.
                .andExpect(jsonPath("$.returnObject.content[0].voteCount").doesNotExist())
                .andExpect(jsonPath("$.returnObject.content[0].thumbnailUrl").doesNotExist())
                .andExpect(jsonPath("$.returnObject.content[0].commentCount").value(0))
                .andExpect(jsonPath("$.returnObject.content[0].authorNickname").value(authorNickname()))
                .andExpect(jsonPath("$.returnObject.content[0].createdAt").isString())

                .andExpect(jsonPath("$.returnObject.content[1].id").value(abId))
                .andExpect(jsonPath("$.returnObject.content[1].type").value("A_B"))
                .andExpect(jsonPath("$.returnObject.content[1].voteCount").value(0))
                // A/B 는 A 상품(display_order = 1)의 사진이다.
                .andExpect(jsonPath("$.returnObject.content[1].thumbnailUrl")
                        .value("https://cdn.test/ab-a-1-" + seed))

                .andExpect(jsonPath("$.returnObject.content[2].id").value(agreeId))
                .andExpect(jsonPath("$.returnObject.content[2].type").value("AGREE"))
                .andExpect(jsonPath("$.returnObject.content[2].voteCount").value(0))
                // 찬반은 사진 3장 중 가장 처음 등록한 1장이다 (R-03).
                .andExpect(jsonPath("$.returnObject.content[2].thumbnailUrl")
                        .value("https://cdn.test/agree-1-" + seed));
    }

    @Test
    @DisplayName("작성 시각이 같아도 목록 순서가 매번 같다")
    void orderIsDeterministicWhenCreatedAtTies() throws Exception {
        // 정렬 키가 created_at 하나뿐이면 MySQL 이 동률 구간의 순서를 보장하지 않아
        // 같은 요청이 매번 다른 순서를 낼 수 있다. (정렬키, id) 튜플이 그것을 막는다.
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(saveGeneralPost("동시각 " + i, EMPTY_CATEGORY).id().intValue());
        }
        flush();

        // 실행이 초 경계를 넘더라도 테스트 목적이 바뀌지 않도록 동률을 직접 만든다.
        // 실제 시계를 기다리는 방식은 머신 부하에 따라 간헐적으로 두 초에 걸칠 수 있다.
        LocalDateTime tiedAt = LocalDateTime.now(clock).minusHours(1);
        ids.forEach(id -> jdbcTemplate.update(
                "UPDATE post SET created_at = ? WHERE id = ?", tiedAt, id));

        // 모두 같은 초에 들어갔는지 먼저 확인한다. 아니면 이 테스트는 아무것도 검증하지 않는다.
        Long distinctInstants = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT created_at) FROM post WHERE category = ?",
                Long.class, EMPTY_CATEGORY.name());
        assertThat(distinctInstants).as("같은 초에 저장돼야 동률을 검증할 수 있다").isEqualTo(1L);

        // 조각을 나눠 끝까지 받는다. 여기가 동률의 진짜 시험대다 —
        // 커서 조건이 (created_at, id) 튜플이 아니면, 같은 시각을 가진 12건에서
        // 다음 조각 조건이 이미 준 행을 배제하지 못해 중복되거나 통째로 건너뛴다.
        List<Integer> scrolled = scrollAll("/posts?category=" + EMPTY_CATEGORY, 5);

        assertThat(scrolled).containsExactlyElementsOf(
                ids.stream().sorted(java.util.Comparator.reverseOrder()).toList());

        // 같은 요청을 반복해도 결과가 같다.
        assertThat(scrollAll("/posts?category=" + EMPTY_CATEGORY, 5))
                .containsExactlyElementsOf(scrolled);
    }

    @Test
    @DisplayName("카테고리 필터가 SQL WHERE 로 걸린다")
    void filtersByCategoryInSql() throws Exception {
        saveGeneralPost("패션 글", PostCategory.FASHION);
        saveGeneralPost("뷰티 글", PostCategory.BEAUTY);
        saveGeneralPost("뷰티 글 둘", PostCategory.BEAUTY);
        flush();

        // 애플리케이션 필터라면 조각 크기(2)만큼 읽은 뒤 걸러내므로 결과가 2건 미만이 된다.
        // WHERE 로 걸리면 조건에 맞는 2건이 그대로 채워진다.
        mockMvc.perform(get("/posts?category=BEAUTY&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content.length()").value(2))
                .andExpect(jsonPath("$.returnObject.content[0].category").value("BEAUTY"))
                .andExpect(jsonPath("$.returnObject.content[1].category").value("BEAUTY"))
                .andExpect(jsonPath("$.returnObject.hasNext").value(false));

        // 실행 계획으로 한 번 더 확인한다 — 결과만 보면 애플리케이션 필터와 구별되지 않는다.
        // 손으로 베낀 SQL 이 아니라 방금 실제로 나간 키 문장을 EXPLAIN 한다.
        List<String> statements = sqlCapture.record(() -> {
            try {
                mockMvc.perform(get("/posts?category=BEAUTY&size=2")).andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(explain(keysStatement(statements), "BEAUTY", 3)).contains("idx_post_latest");
    }

    @Test
    @DisplayName("한 사람이 댓글 3개를 달아도 인기순 점수는 1만 오른다")
    void popularityCountsPeopleNotComments() throws Exception {
        // R-25. 완료 판정: "댓글 3개 작성 후 정렬 점수 비교".
        //
        // 카테고리를 EMPTY_CATEGORY 로 둔다. 아래 단언이 목록의 첫 두 항목을 지목하는데,
        // 전역 인기순은 다른 테스트가 남긴 게시글까지 후보로 삼기 때문이다(#137).
        // 실제로 점수 22 짜리 잔여 게시글이 1등을 차지해 깨졌다.
        Post three = saveGeneralPost("댓글 3개 한 사람", EMPTY_CATEGORY);
        Post two = saveGeneralPost("댓글 2명", EMPTY_CATEGORY);
        User first = saveUser("commenter-a-" + seed, "댓글러A");
        User second = saveUser("commenter-b-" + seed, "댓글러B");

        // 한 사람이 세 번 — 인원은 1이다.
        commentService.write(new Comment(three.id(), first.id(), "하나", null));
        commentService.write(new Comment(three.id(), first.id(), "둘", null));
        commentService.write(new Comment(three.id(), first.id(), "셋", null));
        // 두 사람이 한 번씩 — 인원은 2다.
        commentService.write(new Comment(two.id(), first.id(), "하나", null));
        commentService.write(new Comment(two.id(), second.id(), "둘", null));
        flush();

        assertThat(postStore.findById(three.id()).orElseThrow().popularityScore()).isEqualTo(1L);
        assertThat(postStore.findById(two.id()).orElseThrow().popularityScore()).isEqualTo(2L);

        // 댓글 건수가 더 많은 쪽(3건)이 아니라 인원이 많은 쪽(2명)이 앞에 온다.
        mockMvc.perform(get("/posts?category=" + EMPTY_CATEGORY + "&sort=POPULAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content[0].id").value(two.id()))
                .andExpect(jsonPath("$.returnObject.content[0].commentCount").value(2))
                .andExpect(jsonPath("$.returnObject.content[1].id").value(three.id()))
                .andExpect(jsonPath("$.returnObject.content[1].commentCount").value(3));
    }

    @Test
    @DisplayName("인기순은 투표 인원과 댓글 인원의 합으로 정렬한다")
    void popularitySumsVotersAndCommenters() throws Exception {
        // R-24. 투표만 2명인 글이, 댓글만 1명인 글보다 앞선다.
        Post voted = saveAgreePost("투표 2명", PostCategory.LIVING, 1);
        Post commented = saveGeneralPost("댓글 1명", PostCategory.LIVING);
        Long optionId = firstOptionId(voted.id());
        voteService.castOrChange(voted.id(), optionId, saveUser("voter-a-" + seed, "투표A").id());
        voteService.castOrChange(voted.id(), optionId, saveUser("voter-b-" + seed, "투표B").id());
        commentService.write(new Comment(
                commented.id(), saveUser("commenter-c-" + seed, "댓글C").id(), "한마디", null));
        flush();

        mockMvc.perform(get("/posts?sort=POPULAR&category=LIVING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content[0].id").value(voted.id()))
                .andExpect(jsonPath("$.returnObject.content[0].voteCount").value(2))
                .andExpect(jsonPath("$.returnObject.content[1].id").value(commented.id()));
    }

    @Test
    @DisplayName("최신순 커서로 끝까지 받으면 중복도 누락도 없다")
    void latestCursorCoversEveryPostExactlyOnce() throws Exception {
        // 완료 판정: "전체를 10개씩 끝까지 조회해 합집합이 전체와 일치".
        // Clock 이 초 단위로 끊으므로 25건이 같은 created_at 을 공유한다 — 동률 구간
        // 그 자체가 검증 대상이다. (정렬키, id) 튜플 비교가 아니면 여기서 행이 샌다.
        Set<Integer> expected = new LinkedHashSet<>();
        for (int i = 0; i < 25; i++) {
            expected.add(saveGeneralPost("글 " + i, EMPTY_CATEGORY).id().intValue());
        }
        flush();

        assertThat(scrollAll("/posts?category=" + EMPTY_CATEGORY, 10))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("인기순 커서로 끝까지 받으면 중복도 누락도 없다")
    void popularCursorCoversEveryPostExactlyOnce() throws Exception {
        // 인기순은 동률이 훨씬 흔하다 — 아무 반응 없는 글은 전부 점수 0이다.
        Set<Integer> expected = new LinkedHashSet<>();
        for (int i = 0; i < 25; i++) {
            expected.add(saveGeneralPost("인기 " + i, EMPTY_CATEGORY).id().intValue());
        }
        User voter = saveUser("popular-voter-" + seed, "투표자");
        Post hot = saveAgreePost("표 있는 글", EMPTY_CATEGORY, 1);
        expected.add(hot.id().intValue());
        voteService.castOrChange(hot.id(), firstOptionId(hot.id()), voter.id());
        flush();

        List<Integer> ids = scrollAll("/posts?sort=POPULAR&category=" + EMPTY_CATEGORY, 10);
        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected);
        // 점수 1인 글이 맨 앞이다. 나머지는 전부 0점이라 id 내림차순으로 이어진다.
        assertThat(ids.get(0)).isEqualTo(hot.id().intValue());
    }

    @Test
    @DisplayName("인기순은 스크롤 도중 점수가 바뀌면 최선 노력이다")
    void popularSortIsBestEffortWhileScoresChange() throws Exception {
        // ERD 초안 §8.4 가 세 가지 일관성 모델을 놓고 <b>A(최선 노력)</b> 를 택했다.
        // 스냅샷(B)·랭킹 에포크(C) 는 각각 낡은 순위와 배치 비용을 대가로 한다.
        //
        // 이 테스트는 "누락되지 않는다" 를 주장하지 않는다. 그 반대다 —
        // 아직 못 본 글이 스크롤 도중 커서 위로 올라가면 이번 traversal 에서
        // 빠질 수 있다는 것이 <b>합의된 계약</b>임을 고정한다. 계약을 바꾸려면
        // 이 테스트가 먼저 실패해야 한다.
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            posts.add(saveAgreePost("최선 노력 " + i, EMPTY_CATEGORY, 1));
        }
        flush();

        // 첫 조각 3건을 받는다.
        String first = read("/posts?sort=POPULAR&category=" + EMPTY_CATEGORY + "&size=3");
        String cursor = JsonPath.read(first, "$.returnObject.nextCursor");
        List<Integer> seen = new ArrayList<>();
        ((JSONArray) JsonPath.read(first, "$.returnObject.content[*].id"))
                .forEach(id -> seen.add((Integer) id));

        // 아직 보지 못한 글 하나가 표를 얻어 커서 위로 올라간다.
        Post unseen = posts.get(0);
        assertThat(seen).doesNotContain(unseen.id().intValue());
        voteService.castOrChange(unseen.id(), firstOptionId(unseen.id()),
                saveUser("late-voter-" + seed, "늦은표").id());
        flush();

        String second = read(
                "/posts?sort=POPULAR&category=" + EMPTY_CATEGORY + "&size=3&cursor=" + cursor);
        ((JSONArray) JsonPath.read(second, "$.returnObject.content[*].id"))
                .forEach(id -> seen.add((Integer) id));

        // 점수가 오른 그 글은 이번 traversal 에서 빠진다. 중복은 없다.
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen)
                .as("최선 노력 계약 — 스크롤 중 순위가 오른 글은 이번 회차에서 누락될 수 있다")
                .doesNotContain(unseen.id().intValue());
    }

    @Test
    @DisplayName("점수가 조각 경계에서 바뀌어도 뒤쪽 글이 누락되지 않는다")
    void cursorSurvivesSortValueChangeAcrossSliceBoundary() throws Exception {
        // 이 케이스가 keyset 조건의 진짜 시험대다.
        //
        // 순진한 조건 `score <= :score AND id < :id` 는 <b>정렬 값이 모두 같을 때만</b>
        // 튜플 비교와 같은 답을 낸다. 점수가 경계에서 갈리는 순간, 점수는 더 낮지만
        // id 는 더 큰 글이 `id < :id` 에 걸려 통째로 사라진다.
        //
        // 그래서 "먼저 만든 글(=작은 id)에 높은 점수" 를 준다. 인기순 앞자리는
        // 작은 id 가 차지하므로, 조각 경계를 넘을 때 커서의 id 는 작아지는데
        // 뒤따르는 0점 글들의 id 는 그보다 크다.
        Set<Integer> expected = new LinkedHashSet<>();
        List<Post> scored = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Post post = saveAgreePost("점수 " + i, EMPTY_CATEGORY, 1);
            scored.add(post);
            expected.add(post.id().intValue());
        }
        // 앞의 6건에만 표를 준다 — 나중에 만든 0점 글들이 id 는 더 크다.
        for (Post post : scored) {
            voteService.castOrChange(post.id(), firstOptionId(post.id()),
                    saveUser("boundary-voter-" + seed + "-" + post.id(), "투표자").id());
        }
        for (int i = 0; i < 8; i++) {
            expected.add(saveGeneralPost("무득점 " + i, EMPTY_CATEGORY).id().intValue());
        }
        flush();

        // 조각 크기 4 — 경계가 점수 1 구간 안에서도, 점수 1과 0 사이에서도 생긴다.
        List<Integer> ids = scrollAll("/posts?sort=POPULAR&category=" + EMPTY_CATEGORY, 4);

        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("조각 크기가 12배가 되어도 SQL 횟수는 그대로다 — N+1 이 없다")
    void statementCountDoesNotGrowWithSliceSize() throws Exception {
        // N+1 이면 작성자·랭킹·대표 사진 조회가 행마다 붙어 조각 크기에 비례해 늘어난다.
        // 유형을 섞는다 — 상품이 있는 글에서만 추가 조회가 붙는 경우를 잡기 위해서다.
        for (int i = 0; i < 4; i++) {
            saveAgreePost("찬반 " + i, PostCategory.FASHION, 3);
            saveAbPost("AB " + i, PostCategory.BEAUTY);
            saveGeneralPost("일반 " + i, PostCategory.ETC);
        }
        flush();

        long oneRow = countStatements("/posts?size=1", 1);
        long twelveRows = countStatements("/posts?size=12", 12);

        // 지키려는 성질은 "1회" 라는 숫자가 아니라 행 수에 비례하지 않는다는 것이다.
        // 절대값만 박아 두면 요청당 상수 비용이 하나 늘 때마다 깨지면서 정작 N+1 은 알려주지 못한다.
        assertThat(twelveRows).isEqualTo(oneRow);
        assertThat(oneRow).isEqualTo(STATEMENTS_PER_SLICE);
    }

    // --- 픽스처 ------------------------------------------------------------

    /**
     * 회원을 만든다.
     *
     * <p>닉네임은 5자 이내이고 <b>활성 회원 사이에서 유일</b>하다
     * ({@code uk_users_active_nickname}). 픽스처가 같은 이름을 두 번 쓰면
     * 그 제약에 걸리므로, 표시용 이름 뒤에 일련번호를 붙여 유일하게 만든다.
     */
    private User saveUser(String providerId, String name) {
        User saved = userStore.save(new User(SocialProvider.GOOGLE, providerId, null, name));
        jdbcTemplate.update("UPDATE users SET nickname = ? WHERE id = ?", uniqueNickname(name), saved.id());
        return saved;
    }

    /** 게시글의 작성 시각을 기준 시각에서 {@code minutesAgo} 분 앞으로 못박는다. */
    private void stampCreatedAt(Long postId, int minutesAgo) {
        jdbcTemplate.update("UPDATE post SET created_at = ? WHERE id = ?",
                LocalDateTime.now(clock).minusMinutes(minutesAgo), postId);
    }

    @Test
    @DisplayName("목록 응답에 작성자 랭킹이 실린다 (#73)")
    void exposesAuthorRanking() throws Exception {
        // 완료 판정: "목록 응답에 작성자 랭킹이 포함된다".
        // 순위는 전역 값이라 이 테스트가 만든 회원의 절대 등수를 단정할 수 없다
        // (컨테이너 재사용으로 다른 클래스의 회원이 남아 있다). 확인할 것은
        // "배치가 매긴 값이 응답까지 흐르는가" 이므로, 배치 후 DB 의 값과 응답을 대조한다.
        Long postId = saveGeneralPost("랭킹 노출", EMPTY_CATEGORY).id();
        stampCreatedAt(postId, 1);
        flush();

        rankingBatch.refresh();
        flush();

        Integer expected = jdbcTemplate.queryForObject(
                "SELECT ranking FROM users WHERE id = ?", Integer.class, author.id());
        assertThat(expected).isNotNull();

        mockMvc.perform(get("/posts?category=" + EMPTY_CATEGORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content[0].id").value(postId))
                .andExpect(jsonPath("$.returnObject.content[0].authorRanking").value(expected));
    }

    @Test
    @DisplayName("아직 순위가 없으면 0 이 아니라 null 로 내려간다 (#73)")
    void unrankedAuthorIsNull() throws Exception {
        // 배치가 돌기 전 가입한 회원이다. 0 을 채우면 "아직 모른다" 가 "0위" 라는
        // 거짓이 되고, 실제 꼴찌와 구분되지 않는다.
        Long postId = saveGeneralPost("미산정 작성자", EMPTY_CATEGORY).id();
        stampCreatedAt(postId, 1);
        jdbcTemplate.update("UPDATE users SET ranking = NULL WHERE id = ?", author.id());
        flush();

        mockMvc.perform(get("/posts?category=" + EMPTY_CATEGORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content[0].id").value(postId))
                .andExpect(jsonPath("$.returnObject.content[0].authorRanking").doesNotExist());
    }

    @Test
    @DisplayName("작성자가 넷으로 늘어도 랭킹 조회가 따로 붙지 않는다 (#73)")
    void rankingDoesNotAddPerAuthorStatements() throws Exception {
        // 랭킹은 이미 하고 있는 작성자 조인에 컬럼 하나로 얹힌다. 작성자마다 별도 조회가 붙으면
        // 문장 수가 작성자 수에 비례해 늘어나므로(N+1), 글마다 작성자를 달리 해 크기 1 과 4 를 비교한다.
        // 한 작성자의 글 세 건으로는 그 비례를 볼 수 없다.
        for (int i = 0; i < 4; i++) {
            User writer = saveUser("ranked-author-" + seed + "-" + i, "랭커");
            Post post = postStore.save(new Post(writer.id(), PostType.GENERAL, EMPTY_CATEGORY, "랭킹 " + i, "설명"));
            stampCreatedAt(post.id(), i + 1);
        }
        rankingBatch.refresh();
        flush();

        long oneAuthor = countStatements("/posts?category=" + EMPTY_CATEGORY + "&size=1", 1);
        long fourAuthors = countStatements("/posts?category=" + EMPTY_CATEGORY + "&size=4", 4);

        assertThat(fourAuthors).isEqualTo(oneAuthor);
        assertThat(oneAuthor).isEqualTo(STATEMENTS_PER_SLICE);
    }

    /**
     * C-8 (PRD-023) — 탈퇴 회원의 게시글은 남고 작성자만 비식별 표기가 된다.
     *
     * <p>개인정보처리방침 제3조가 게시물을 보존 예외로 두면서 "작성자 정보는 비식별 처리" 를
     * 조건으로 달았다. 두 요구가 동시에 성립하는지를 한 번에 본다 — 게시글이 목록에서
     * 사라지면 보존 위반이고, 닉네임이 그대로 나오면 비식별 위반이다.
     *
     * <p>비식별 표기 자체는 신규 구현이 아니라 조회의 {@code COALESCE} 폴백이 낸다.
     * 그 폴백이 {@code JOIN users}(INNER) 위에 얹혀 있어 <b>행이 남아 있을 때만</b>
     * 동작한다는 사실이 이 테스트의 핵심이다 (ADR-0040).
     */
    @Test
    @DisplayName("탈퇴한 작성자의 게시글은 목록에 남고 작성자만 '알 수 없음' 으로 나온다")
    void withdrawnAuthorPostStaysListedWithMaskedNickname() throws Exception {
        Long postId = saveGeneralPost("탈퇴자가 쓴 글", EMPTY_CATEGORY).id();
        flush();

        // 제품 코드가 실제로 쓰는 탈퇴 경로로 파기시킨다.
        withdrawalPersistenceService.complete(author.id());
        flush();

        mockMvc.perform(get("/posts?category=" + EMPTY_CATEGORY))
                .andExpect(status().isOk())
                // 보존 — 게시글이 사라지면 INNER JOIN 회귀다.
                .andExpect(jsonPath("$.returnObject.content.length()").value(1))
                .andExpect(jsonPath("$.returnObject.content[0].id").value(postId))
                .andExpect(jsonPath("$.returnObject.content[0].title").value("탈퇴자가 쓴 글"))
                // 비식별 — 원본 닉네임이 남아 있으면 제3조 위반이다.
                .andExpect(jsonPath("$.returnObject.content[0].authorNickname").value("알 수 없음"));
    }

    /** 저장된 작성자 닉네임. 픽스처가 유일성을 위해 붙인 일련번호까지 포함한다. */
    private String authorNickname() {
        return jdbcTemplate.queryForObject(
                "SELECT nickname FROM users WHERE id = ?", String.class, author.id());
    }

    /** 5자 상한 안에서 유일한 닉네임. 36진수 5자리는 6천만 개라 실행 안에서도 실행 사이에서도 겹치지 않는다. */
    private String uniqueNickname(String name) {
        return Long.toString(NICKNAME_SEQUENCE.getAndIncrement(), 36);
    }

    private Post saveGeneralPost(String title, PostCategory category) {
        return postStore.save(new Post(author.id(), PostType.GENERAL, category, title, "설명"));
    }

    private Post saveAgreePost(String title, PostCategory category, int photoCount) {
        Post post = new Post(author.id(), PostType.AGREE, category, title, "설명")
                .addProduct(new PostProduct(newContainer("agree", photoCount), "상품", 10_000L, null, 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));
        return postStore.save(post);
    }

    private Post saveAbPost(String title, PostCategory category) {
        Post post = new Post(author.id(), PostType.A_B, category, title, "설명")
                .addProduct(new PostProduct(newContainer("ab-a", 1), "A 상품", 10_000L, null, 1))
                .addProduct(new PostProduct(newContainer("ab-b", 1), "B 상품", 20_000L, null, 2))
                .addOption(PostOption.ofProductDisplayOrder(1, 1))
                .addOption(PostOption.ofProductDisplayOrder(2, 2));
        return postStore.save(post);
    }

    /** 사진 여러 장을 등록 순서대로 담은 상품용 컨테이너. */
    private Long newContainer(String tag, int photoCount) {
        ItemContainer container = new ItemContainer(author.id(), AttachType.PRODUCT);
        long unique = System.nanoTime();
        for (int i = 1; i <= photoCount; i++) {
            container = container.add(new ItemResource(
                    1024L, tag + "-" + i + ".jpg",
                    "product-images/%d/%d-%d.jpg".formatted(author.id(), unique, i),
                    "https://cdn.test/" + tag + "-" + i + "-" + seed));
        }
        return containerStore.save(container).id();
    }

    private Long firstOptionId(Long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM post_option WHERE post_id = ? ORDER BY display_order LIMIT 1",
                Long.class, postId);
    }

    /**
     * 정렬과 커서가 <b>쿼리에서</b> 끝나는지 실행계획으로 본다.
     *
     * <p><b>EXPLAIN 하는 문장은 Hibernate 가 실제로 내보낸 것이다</b>({@link SqlCapture}).
     * QueryDSL 전환(#138) 뒤로 SQL 은 사람이 쓰지 않으므로, 손으로 베껴 둔 문장을 EXPLAIN 하면
     * 저장소가 바뀌어도 테스트가 초록색으로 남는다. 실제 조회 경로를 한 번 태우고 그때 나간
     * 문장에 같은 값을 바인딩해 계획을 읽는다. 정렬 튜플의 {@code id} 방향을 {@code ASC} 로
     * 바꿔 위반을 주입했을 때 {@code Sort:} 가 나타나 두 정렬 모두 실제로 실패하는 것을 확인했다.
     *
     * <p><b>둘째 조각을 본다.</b> 첫 조각은 keyset 조건이 없어 행 값 비교가 계획에
     * 어떻게 내려가는지 보여주지 못한다. 커서는 심은 게시글의 한가운데를 가리킨다.
     *
     * <p><b>행과 작성자를 먼저 심는다.</b> 빈 테이블에서는 옵티마이저가 통계 없이 아무 인덱스나
     * 고르므로 계획이 의미를 갖지 않는다. 작성자가 한 명이면 회원 테이블이 한 행이라
     * 행 문장의 조인이 기본 키 조회가 아니라 해시 조인으로 나온다 — 여럿을 심어야 운영과 같은 모양이 된다.
     */
    @Nested
    @DisplayName("실행 계획 — 정렬과 커서가 SQL 에서 끝난다")
    class QueryPlan {

        private static final int SLICE = 10;
        private static final int AUTHORS = 20;
        private static final int POSTS = 300;

        /** 최신순 커서가 가리키는 게시글. 심은 게시글의 한가운데라 앞뒤로 행이 남는다. */
        private long cursorPostId;
        private LocalDateTime cursorAt;
        /** 인기순 커서. 점수 순서의 한가운데라 최신순 커서와 다른 게시글이다. */
        private long popularCursorPostId;
        private long popularCursorScore;
        private final List<Long> authors = new ArrayList<>();

        @BeforeEach
        void seedForOptimizer() {
            LocalDateTime now = LocalDateTime.now(clock);
            for (int i = 0; i < AUTHORS; i++) {
                authors.add(saveUser("plan-author-" + seed + "-" + i, "계획").id());
            }
            for (int i = 0; i < POSTS; i++) {
                // 카테고리를 섞어 카테고리 인덱스와 전체 인덱스가 각각 의미를 갖게 한다.
                // 인기 점수는 vote_count 로 흩뿌린다 — 전부 0 이면 인기순 커서가 id 만으로 잘린다.
                jdbcTemplate.update("""
                        INSERT INTO post (user_id, type, category, title, description, vote_count, created_at, updated_at)
                        VALUES (?, 'GENERAL', ?, ?, '설명', ?, ?, ?)
                        """, authors.get(i % AUTHORS), i % 3 == 0 ? "FASHION" : "ETC", "계획" + i,
                        (i * 7) % 50, now.minusMinutes(i), now);
                if (i == POSTS / 2) {
                    cursorPostId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    cursorAt = now.minusMinutes(i);
                }
            }
            Map<String, Object> popularMiddle = jdbcTemplate.queryForMap("""
                    SELECT popularity_score, id FROM post WHERE user_id IN (%s)
                     ORDER BY popularity_score DESC, id DESC LIMIT 1 OFFSET ?
                    """.formatted(authorIds()), POSTS / 2);
            popularCursorPostId = ((Number) popularMiddle.get("id")).longValue();
            popularCursorScore = ((Number) popularMiddle.get("popularity_score")).longValue();
            jdbcTemplate.execute("ANALYZE TABLE post, users");
        }

        private String authorIds() {
            return String.join(",", authors.stream().map(String::valueOf).toList());
        }

        /**
         * {@code ANALYZE TABLE} 은 트랜잭션을 암묵적으로 커밋하므로 심은 행이 롤백되지 않는다.
         * 인기 점수를 흩뿌린 300건이 남으면 전역 인기순을 보는 다른 테스트의 첫 행을 차지한다(#137 의 모양).
         * 별도 트랜잭션에서 지운다 — 시험 트랜잭션 안의 삭제는 함께 롤백된다.
         */
        @AfterEach
        void deleteCommittedSeed() {
            TransactionTemplate committed = new TransactionTemplate(transactionTemplate.getTransactionManager());
            committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            committed.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM post WHERE user_id IN (" + authorIds() + ")");
                jdbcTemplate.update("DELETE FROM users WHERE id IN (" + authorIds() + ")");
                // 바깥 setUp 의 작성자도 같은 커밋에 실려 남는다. 이 안에서는 글을 쓰지 않았다.
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", author.id());
            });
        }

        @Test
        @DisplayName("최신순 둘째 조각이 전체 인덱스를 타고 filesort 가 없다")
        void latestSliceUsesLatestAllIndex() {
            Statements statements = slice(null, PostSort.LATEST);

            // 계획을 먼저 본다. 문장 형태 검사가 앞서면 위반 주입 때 이 단언이 실제로 물리는지 알 수 없다.
            assertThat(explain(statements.keys(), cursorAt, cursorPostId, SLICE + 1))
                    .as("정렬 튜플의 id 방향을 ASC 로 바꾸면 Sort 가 나타난다")
                    .containsPattern(SORT_INDEX_LOOKUP.formatted("idx_post_latest_all"))
                    .doesNotContain("Sort:");
            assertThat(statements.keys())
                    .as("행 값 비교가 풀어쓴 OR 로 바뀌거나 좌변에 cast 가 붙으면 인덱스 범위가 접히지 않는다")
                    .containsPattern(ROW_VALUE_LESS_THAN.formatted("created_at"))
                    .doesNotContainIgnoringCase(" or ")
                    .doesNotContain("cast(");
        }

        @Test
        @DisplayName("인기순 둘째 조각이 생성 컬럼 인덱스를 타고 filesort 가 없다")
        void popularSliceUsesPopularAllIndex() {
            Statements statements = slice(null, PostSort.POPULAR);

            assertThat(explain(statements.keys(), popularCursorScore, popularCursorPostId, SLICE + 1))
                    .containsPattern(SORT_INDEX_LOOKUP.formatted("idx_post_popular_all"))
                    .doesNotContain("Sort:");
            // 커서는 Long 이고 컬럼은 Integer 다. Hibernate 가 파라미터를 컬럼 타입으로 강제하므로
            // SQL 에 cast 가 없다 — 좌변에 cast 가 붙는 순간 인덱스를 잃는다 (ADR-0045).
            assertThat(statements.keys())
                    .containsPattern(ROW_VALUE_LESS_THAN.formatted("popularity_score"))
                    .doesNotContain("cast(");
        }

        @Test
        @DisplayName("카테고리를 걸면 카테고리 선행 인덱스를 탄다")
        void categorySliceUsesCategoryIndex() {
            Statements statements = slice(PostCategory.FASHION, PostSort.LATEST);

            assertThat(explain(statements.keys(), "FASHION", cursorAt, cursorPostId, SLICE + 1))
                    .containsPattern(SORT_INDEX_LOOKUP.formatted("idx_post_latest") + ", category=")
                    .doesNotContain("Sort:");
        }

        @Test
        @DisplayName("행 문장은 확정된 id 만 게시글 기본 키로 읽고 작성자는 기본 키로 한 줄씩 붙인다")
        void rowsStatementStartsFromPostPrimaryKey() {
            Statements statements = slice(null, PostSort.LATEST);

            // 인덱스 이름이 아니라 접근 방식을 본다 — 회원에서 시작하는 계획이면 두 단언이 동시에 뒤집힌다.
            String plan = explain(statements.rows(), statements.rowsArgs());
            assertThat(plan)
                    .as("post.id IN (…) 은 기본 키 범위, 작성자는 users 기본 키 단건 조회")
                    .containsPattern(PRIMARY_KEY_RANGE)
                    .containsPattern(AUTHOR_KEY_LOOKUP)
                    .doesNotContain("Table scan")
                    .doesNotContain("idx_post_");
            assertThat(plan.indexOf("using PRIMARY over (id = "))
                    .as("게시글 기본 키 범위가 조인의 진입점이다 — 회원이 먼저 나오면 방향이 뒤집힌 것이다")
                    .isLessThan(plan.indexOf("Single-row index lookup"));
        }

        @Test
        @DisplayName("정렬 튜플의 id 방향을 뒤집으면 filesort 로 떨어진다")
        void reversedIdDirectionFallsBackToFilesort() {
            // 규칙이 무언가를 지킨다는 증거 — 일부러 어긴 형태가 실제로 나빠지는지 본다.
            // 저장소의 order() 에서 POST.id.desc() 를 asc() 로 바꾸면 위 두 정렬 테스트가
            // 정확히 이 계획을 보고 실패한다.
            assertThat(explain("""
                    SELECT p.id FROM post p WHERE p.deleted_at IS NULL
                     ORDER BY p.created_at DESC, p.id ASC LIMIT 11
                    """))
                    .as("인덱스 (…, created_at DESC, id DESC) 는 한쪽만 뒤집힌 정렬을 맡지 못한다")
                    .contains("Sort:");
        }

        /** {@code (p.created_at, p.id) < (?, ?)} — 별칭과 공백은 Hibernate 가 정한다. */
        private static final String ROW_VALUE_LESS_THAN =
                "\\(\\s*\\w+\\.%s\\s*,\\s*\\w+\\.id\\s*\\)\\s*<\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)";
        /** 정렬 인덱스를 {@code deleted_at} 으로 좁힌 조회. 커버링이라 행을 읽지 않는다. */
        private static final String SORT_INDEX_LOOKUP = "index lookup on \\w+ using %s \\(deleted_at=NULL";
        /** 행 문장의 게시글 접근 — 확정된 id 들의 기본 키 범위. */
        private static final String PRIMARY_KEY_RANGE = "Index range scan on \\w+ using PRIMARY over \\(id = ";
        /** 행 문장의 작성자 접근 — {@code users} 기본 키 단건 조회 ({@code eq_ref}). */
        private static final String AUTHOR_KEY_LOOKUP =
                "Single-row index lookup on \\w+ using PRIMARY \\(id=\\w+\\.user_id\\)";

        /** 한 조각이 내보낸 두 문장과 행 문장의 바인딩 값. */
        private record Statements(String keys, String rows, Object[] rowsArgs) {
        }

        /**
         * 실제 조회 경로를 둘째 조각으로 한 번 태우고, 그때 나간 키 문장과 행 문장을 붙잡는다.
         * 인기순 커서의 점수는 심은 게시글의 것을 그대로 쓴다 — 동률이 흔한 값이라
         * 행 값 비교의 두 번째 자리가 실제로 일한다.
         */
        private Statements slice(PostCategory category, PostSort sort) {
            boolean latest = sort == PostSort.LATEST;
            Object sortValue = latest ? cursorAt : popularCursorScore;
            long id = latest ? cursorPostId : popularCursorPostId;
            ScrollPosition cursor = ScrollPosition.forward(Map.of(sort.cursorKey(), sortValue, "id", id));

            List<Long> ids = new ArrayList<>();
            List<String> statements = sqlCapture.record(() ->
                    postStore.findSlice(category, sort, cursor, SLICE)
                            .forEach(view -> ids.add(view.id())));
            assertThat(ids).as("커서 뒤에 행이 남아 있어야 계획이 의미를 갖는다").hasSize(SLICE);

            // 바인딩 순서는 문장 안의 위치다 — SELECT 절의 대표 사진 서브쿼리(display_order = 1),
            // 작성자 표시명 폴백의 빈 문자열 둘과 대체 문자열, 마지막이 IN 의 id 목록이다.
            // 개수만으로는 같은 개수의 자리바꿈을 못 잡으므로 문장 안의 순서까지 본다.
            String rows = rowsStatement(statements);
            assertThat(rows)
                    .as("행 문장의 파라미터 자리가 가정한 순서와 같아야 같은 값을 묶는다")
                    .matches("(?s).*display_order=\\?.*nullif\\(\\w+\\.nickname,\\?\\),"
                            + "nullif\\(\\w+\\.name,\\?\\),\\?\\).*in \\(\\?[?,]*\\).*");
            List<Object> rowsArgs = new ArrayList<>(List.of(1, "", "", "알 수 없음"));
            rowsArgs.addAll(ids);
            return new Statements(keysStatement(statements), rows, rowsArgs.toArray());
        }
    }

    // --- 검증 도구 ---------------------------------------------------------

    /** 커서를 끝까지 따라가며 받은 id 를 순서대로 모은다. */
    private List<Integer> scrollAll(String path, int size) throws Exception {
        List<Integer> collected = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        while (true) {
            String url = path + (path.contains("?") ? "&" : "?") + "size=" + size
                    + (cursor == null ? "" : "&cursor=" + cursor);
            String body = read(url);
            JSONArray ids = JsonPath.read(body, "$.returnObject.content[*].id");
            ids.forEach(id -> collected.add((Integer) id));
            if (!(boolean) JsonPath.read(body, "$.returnObject.hasNext")) {
                break;
            }
            cursor = JsonPath.read(body, "$.returnObject.nextCursor");
            assertThat(cursor).as("hasNext 가 참이면 커서가 있어야 한다").isNotNull();
            assertThat(++guard).as("커서가 전진하지 않아 무한 반복이다").isLessThan(50);
        }
        // 합집합이 전체와 같은지 보기 전에, 조각 안에서 중복이 없는지부터 본다.
        assertThat(collected).doesNotHaveDuplicates();
        return collected;
    }

    private long countStatements(String url, int expectedRows) throws Exception {
        flush();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        String body = read(url);
        JSONArray ids = JsonPath.read(body, "$.returnObject.content[*].id");
        assertThat(ids).hasSize(expectedRows);

        return statistics.getPrepareStatementCount();
    }

    /** 붙잡은 문장 가운데 키 문장 — {@code limit ?} 이 있는 쪽이다. */
    private static String keysStatement(List<String> statements) {
        return statements.stream().filter(sql -> sql.contains("limit ?")).findFirst().orElseThrow();
    }

    /** 붙잡은 문장 가운데 행 문장 — 대표 사진 서브쿼리가 있는 쪽이다. */
    private static String rowsStatement(List<String> statements) {
        return statements.stream().filter(sql -> sql.contains("item_resource")).findFirst().orElseThrow();
    }

    /**
     * Hibernate 가 실제로 내보낸 문장에 같은 값을 바인딩해 {@code EXPLAIN FORMAT=TREE} 한다.
     *
     * <p>개수만 검사한다. Hibernate 가 개수를 유지한 채 술어 순서를 바꾸면 값이 다른 자리에 묶이는데,
     * 그때도 인덱스 선택은 값이 아니라 술어 모양이 정하므로 계획은 같다.
     */
    private String explain(String sql, Object... args) {
        assertThat(sql.chars().filter(c -> c == '?').count())
                .as("바인딩 값의 수가 문장의 ? 와 같아야 같은 계획을 본다")
                .isEqualTo(args.length);
        return String.join(" ", jdbcTemplate.queryForList("EXPLAIN FORMAT=TREE " + sql, String.class, args));
    }

    private String read(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString();
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
