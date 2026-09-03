package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
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
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.vote.service.VoteService;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import net.minidev.json.JSONArray;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 이 클래스만 쓰는 카테고리.
     *
     * <p>테스트 컨테이너를 재사용하므로({@code ContainerConfig.withReuse}) 다른 테스트
     * 클래스가 남긴 게시글이 같은 테이블에 남아 있다. 목록 API 는 <b>전체</b>를 읽는
     * 첫 엔드포인트라 그 잔여 데이터에 그대로 노출된다. 조각 경계와 "0건" 을 검증하려면
     * 이 실행이 만든 것만 보이는 창이 필요하다.
     */
    private static final PostCategory EMPTY_CATEGORY = PostCategory.ELECTRONICS;

    private MockMvc mockMvc;
    private User author;
    private long seed;
    private int nicknameSequence;

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
        mockMvc.perform(get("/api/posts?category=" + EMPTY_CATEGORY))
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
        mockMvc.perform(get("/api/posts?category=" + EMPTY_CATEGORY))
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
        // Clock 이 초 단위로 끊으므로(ClockConfig) 같은 초에 저장된 글은 created_at 이 같다.
        // 정렬 키가 created_at 하나뿐이면 MySQL 이 동률 구간의 순서를 보장하지 않아
        // 같은 요청이 매번 다른 순서를 낼 수 있다. (정렬키, id) 튜플이 그것을 막는다.
        //
        // CI 에서 이 클래스가 한 번 뒤집힌 적이 있다 — 원인은 정렬이 아니라 픽스처가
        // 앱 Clock 과 DB NOW() 를 섞어 쓴 것이었다. 그 회귀를 여기서 잡는다.
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(saveGeneralPost("동시각 " + i, EMPTY_CATEGORY).id().intValue());
        }
        flush();

        // 모두 같은 초에 들어갔는지 먼저 확인한다. 아니면 이 테스트는 아무것도 검증하지 않는다.
        Long distinctInstants = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT created_at) FROM post WHERE category = ?",
                Long.class, EMPTY_CATEGORY.name());
        assertThat(distinctInstants).as("같은 초에 저장돼야 동률을 검증할 수 있다").isEqualTo(1L);

        // 조각을 나눠 끝까지 받는다. 여기가 동률의 진짜 시험대다 —
        // 커서 조건이 (created_at, id) 튜플이 아니면, 같은 시각을 가진 12건에서
        // 다음 조각 조건이 이미 준 행을 배제하지 못해 중복되거나 통째로 건너뛴다.
        List<Integer> scrolled = scrollAll("/api/posts?category=" + EMPTY_CATEGORY, 5);

        assertThat(scrolled).containsExactlyElementsOf(
                ids.stream().sorted(java.util.Comparator.reverseOrder()).toList());

        // 같은 요청을 반복해도 결과가 같다.
        assertThat(scrollAll("/api/posts?category=" + EMPTY_CATEGORY, 5))
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
        mockMvc.perform(get("/api/posts?category=BEAUTY&size=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content.length()").value(2))
                .andExpect(jsonPath("$.returnObject.content[0].category").value("BEAUTY"))
                .andExpect(jsonPath("$.returnObject.content[1].category").value("BEAUTY"))
                .andExpect(jsonPath("$.returnObject.hasNext").value(false));

        // 실행 계획으로 한 번 더 확인한다 — 결과만 보면 애플리케이션 필터와 구별되지 않는다.
        String plan = explainPostList();
        assertThat(plan).contains("idx_post_latest");
    }

    @Test
    @DisplayName("한 사람이 댓글 3개를 달아도 인기순 점수는 1만 오른다")
    void popularityCountsPeopleNotComments() throws Exception {
        // R-25. 완료 판정: "댓글 3개 작성 후 정렬 점수 비교".
        Post three = saveGeneralPost("댓글 3개 한 사람", PostCategory.ETC);
        Post two = saveGeneralPost("댓글 2명", PostCategory.ETC);
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
        mockMvc.perform(get("/api/posts?sort=POPULAR"))
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

        mockMvc.perform(get("/api/posts?sort=POPULAR&category=LIVING"))
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

        assertThat(scrollAll("/api/posts?category=" + EMPTY_CATEGORY, 10))
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

        List<Integer> ids = scrollAll("/api/posts?sort=POPULAR&category=" + EMPTY_CATEGORY, 10);
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
        String first = read("/api/posts?sort=POPULAR&category=" + EMPTY_CATEGORY + "&size=3");
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
                "/api/posts?sort=POPULAR&category=" + EMPTY_CATEGORY + "&size=3&cursor=" + cursor);
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
        List<Integer> ids = scrollAll("/api/posts?sort=POPULAR&category=" + EMPTY_CATEGORY, 4);

        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("조각 크기가 늘어도 쿼리 수는 1회로 일정하다")
    void queryCountStaysConstantRegardlessOfSliceSize() throws Exception {
        // N+1 이면 작성자·랭킹·대표 사진 조회가 행마다 붙어 조각 크기에 비례해 늘어난다.
        // 유형을 섞는다 — 상품이 있는 글에서만 추가 조회가 붙는 경우를 잡기 위해서다.
        for (int i = 0; i < 4; i++) {
            saveAgreePost("찬반 " + i, PostCategory.FASHION, 3);
            saveAbPost("AB " + i, PostCategory.BEAUTY);
            saveGeneralPost("일반 " + i, PostCategory.ETC);
        }
        flush();

        long oneRow = countStatements("/api/posts?size=1", 1);
        long twelveRows = countStatements("/api/posts?size=12", 12);

        assertThat(oneRow).isEqualTo(1L);
        assertThat(twelveRows).isEqualTo(1L);
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

        mockMvc.perform(get("/api/posts?category=" + EMPTY_CATEGORY))
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

        mockMvc.perform(get("/api/posts?category=" + EMPTY_CATEGORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content[0].id").value(postId))
                .andExpect(jsonPath("$.returnObject.content[0].authorRanking").doesNotExist());
    }

    @Test
    @DisplayName("랭킹을 얹어도 쿼리는 여전히 한 번이다 (#73)")
    void rankingAddsNoQuery() throws Exception {
        // 랭킹은 이미 하고 있는 작성자 조인에 컬럼 하나로 얹힌다. 별도 조회가 붙으면
        // 조각 크기만큼 쿼리가 늘어나므로(N+1) 횟수로 못박는다.
        for (int i = 0; i < 3; i++) {
            stampCreatedAt(saveGeneralPost("랭킹 " + i, EMPTY_CATEGORY).id(), i + 1);
        }
        rankingBatch.refresh();
        flush();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        mockMvc.perform(get("/api/posts?category=" + EMPTY_CATEGORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.content.length()").value(3));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    /** 저장된 작성자 닉네임. 픽스처가 유일성을 위해 붙인 일련번호까지 포함한다. */
    private String authorNickname() {
        return jdbcTemplate.queryForObject(
                "SELECT nickname FROM users WHERE id = ?", String.class, author.id());
    }

    private String uniqueNickname(String name) {
        String suffix = Long.toString(nicknameSequence++, 36);
        int room = Math.max(0, 5 - suffix.length());
        return name.substring(0, Math.min(name.length(), room)) + suffix;
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
        Post draft = new Post(author.id(), PostType.A_B, category, title, "설명")
                .addProduct(new PostProduct(newContainer("ab-a", 1), "A 상품", 10_000L, null, 1))
                .addProduct(new PostProduct(newContainer("ab-b", 1), "B 상품", 20_000L, null, 2));
        // A/B 선택지는 상품 id 를 가리키므로 상품이 저장돼 id 를 얻은 뒤에 붙인다.
        Long postId = saveWithoutOptions(draft);
        List<Long> productIds = jdbcTemplate.queryForList(
                "SELECT id FROM post_product WHERE post_id = ? ORDER BY display_order", Long.class, postId);
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("""
                INSERT INTO post_option (post_id, post_product_id, label, display_order, vote_count, created_at)
                VALUES (?, ?, NULL, 1, 0, ?), (?, ?, NULL, 2, 0, ?)
                """, postId, productIds.get(0), now, postId, productIds.get(1), now);
        entityManager.flush();
        entityManager.clear();
        return postStore.findById(postId).orElseThrow();
    }

    /** R-04 를 우회해 상품만 먼저 넣는다. 선택지는 상품 id 를 알아야 만들 수 있다. */
    private Long saveWithoutOptions(Post draft) {
        // 시각은 반드시 앱 Clock 에서 가져온다. DB 의 NOW() 를 쓰면 시간 원천이 둘로 갈린다 —
        // Clock 은 초 단위로 내림(ClockConfig)하므로 12:00:00.9 를 12:00:00 으로 쓰는데,
        // 같은 순간의 NOW() 는 12:00:01 이 되어 <b>나중에 만든 글이 더 이른 시각</b>을 갖는다.
        // 그러면 최신순 정렬이 저장 순서와 어긋나 CI 에서만 순서가 뒤집힌다(실제로 겪었다).
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("""
                INSERT INTO post (user_id, type, category, title, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, author.id(), draft.type().name(), draft.category().name(),
                draft.title(), draft.description(), now, now);
        Long postId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (PostProduct product : draft.products()) {
            jdbcTemplate.update("""
                    INSERT INTO post_product
                        (post_id, item_container_id, name, price, link_url, display_order, created_at, updated_at)
                    VALUES (?, ?, ?, ?, NULL, ?, ?, ?)
                    """, postId, product.itemContainerId(), product.name(),
                    product.price(), product.displayOrder(), now, now);
        }
        return postId;
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

    /** 카테고리 필터가 인덱스로 걸리는지 실행 계획으로 확인한다. */
    private String explainPostList() {
        return String.join(" ", jdbcTemplate.queryForList("""
                EXPLAIN FORMAT=TREE
                SELECT p.id FROM post p
                 WHERE p.deleted_at IS NULL AND p.category = 'BEAUTY'
                 ORDER BY p.created_at DESC, p.id DESC LIMIT 11
                """, String.class));
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
