package app.pickple.activity.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.comment.domain.PostCommenterStore;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.vote.domain.Vote;
import app.pickple.vote.domain.VoteStore;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import net.minidev.json.JSONArray;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마이페이지 내 활동 API (이슈 #30) 의 완료 판정을 실제 MySQL 로 확인한다.
 *
 * <p><b>테이블을 비우지 않는다.</b> 이 API 는 정의상 {@code user_id} 로 좁혀지므로,
 * 테스트마다 새 회원을 만들면 다른 테스트가 남긴 행이 섞이지 않는다 —
 * 재사용 컨테이너(ContainerConfig 의 {@code withReuse(true)}) 아래에서
 * {@code RankingControllerIT} 가 테이블을 비워야 했던 이유(전역 순위)가 여기엔 없다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다 — 붙이면 MockMvc 요청이
 * 별도 커넥션에서 도는 동안 픽스처가 아직 커밋되지 않아 목록이 비어 보인다
 * ({@code RankingControllerIT} 가 기록한 실패 모드).
 *
 * <p><b>시계를 고정한다.</b> 운영 {@code Clock} 은 초 단위로 끊은 <b>살아 있는</b> 시계라
 * ({@code ClockConfig}) 픽스처를 스탬프하는 시각과 서버가 기준을 계산하는 시각이 갈릴 수 있다.
 * §7.4 의 경계 판정은 여유가 1초인데 눈금도 1초라 <b>허용오차가 0</b>이다 —
 * 두 호출 사이에 벽시계가 눈금 하나를 넘으면 "1초 안쪽" 이 "정확히 경계" 가 되어
 * 반열린 비교에서 빠진다(CI 에서 실측된 간헐 실패).
 *
 * <p>확률을 낮추는 대신 <b>경합을 없앴다</b>. 이 클래스의 모든 픽스처가 상대 오프셋
 * ({@code minusMinutes}·{@code minusDays})만 쓰므로 기준이 무엇이든 무관하고,
 * 고정하면 스탬프와 서버가 같은 "지금" 을 본다.
 * {@code ClockConfig} javadoc 이 예고한 용법이며, 같은 계열의 알려진 flaky(#83)가
 * 지목한 해법과 같다.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ActivityControllerIT {

    private static final String SUMMARY = "/users/me/activities/summary";
    private static final String ACTIVITIES = "/users/me/activities";
    private static final String RECENT = "/users/me/posts/recent";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilterChainProxy springSecurityFilterChain;
    @Autowired
    private UserStore userStore;
    @Autowired
    private PostStore postStore;
    @Autowired
    private VoteStore voteStore;
    @Autowired
    private PostCommenterStore commenterStore;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    /**
     * 초 단위로 끊은 <b>고정</b> 시계. 눈금은 운영과 같게 두어({@code datetime(0)} 정밀도)
     * 초 미만 값이 DB 에서 잘리는 상황을 그대로 재현한다 — 시각을 고정하되
     * 정밀도까지 바꾸면 테스트가 운영과 다른 조건을 보게 된다.
     *
     * <p>기준 시각은 <b>이 클래스가 도는 지금</b>이다. 미래나 과거로 못박으면
     * 다른 테스트가 심는 행({@code users.created_at} 등 애플리케이션이 아니라
     * 스토어가 시각을 넣는 자리)과 순서가 어긋난다.
     */
    @TestBean(name = "clock")
    private Clock clock;

    private static Clock clock() {
        return Clock.fixed(
                ZonedDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0).toInstant(),
                ZoneId.of("Asia/Seoul"));
    }

    /**
     * 닉네임 발급기. 재사용 컨테이너에서 이전 실행의 회원과도 겹치면 안 되므로
     * 남은 행을 세어 그 뒤부터 시작한다.
     */
    private static final java.util.concurrent.atomic.AtomicLong NICKNAME_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 60_000_000L);

    private MockMvc mockMvc;
    private long seed;

    /** 이 테스트가 만든 활동만 보는 회원. 매 테스트가 새로 만든다. */
    private User me;
    /** 게시글 작성자. 남이 쓴 글에 내가 활동하는 상황을 만든다. */
    private User author;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        seed = System.nanoTime();
        me = saveUser("act-me-" + seed, "나");
        author = saveUser("act-author-" + seed, "남");
    }

    @Nested
    @DisplayName("활동 갯수 요약 (§7.2)")
    class Summary {

        @Test
        @DisplayName("활동이 없으면 세 값이 모두 0 이다")
        void zeroWhenNoActivity() throws Exception {
            mockMvc.perform(get(SUMMARY).header("Authorization", bearer(me)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.returnObject.voteCount").value(0))
                    .andExpect(jsonPath("$.returnObject.commentCount").value(0))
                    .andExpect(jsonPath("$.returnObject.postCount").value(0));
        }

        @Test
        @DisplayName("재투표해도 투표 참여 횟수가 늘지 않는다 (R-22)")
        void revotingDoesNotIncreaseCount() throws Exception {
            Post post = saveAgreePost("재투표대상");
            long before = voteOn(post);

            mockMvc.perform(get(SUMMARY).header("Authorization", bearer(me)))
                    .andExpect(jsonPath("$.returnObject.voteCount").value(1));

            // 선택을 바꾼다. 새 행이 아니라 있던 행의 수정이다 — UNIQUE(post_id, user_id).
            changeVote(post, before);

            mockMvc.perform(get(SUMMARY).header("Authorization", bearer(me)))
                    .andExpect(jsonPath("$.returnObject.voteCount").value(1));

            assertThat(countRows("vote", me.id()))
                    .as("재투표가 새 행을 만들면 요약도 부풀어 등급·뱃지가 잘못 나간다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("같은 글에 댓글을 여러 개 달아도 1 이다 (R-25)")
        void manyCommentsOnOnePostCountOnce() throws Exception {
            Post post = saveGeneralPost("댓글대상");

            assertThat(commenterStore.recordIfFirst(post.id(), me.id())).isTrue();
            assertThat(commenterStore.recordIfFirst(post.id(), me.id()))
                    .as("두 번째 댓글은 인원을 늘리지 않는다").isFalse();

            mockMvc.perform(get(SUMMARY).header("Authorization", bearer(me)))
                    .andExpect(jsonPath("$.returnObject.commentCount").value(1));
        }

        @Test
        @DisplayName("삭제한 게시글은 올린 갯수에서 빠진다")
        void deletedPostIsNotCounted() throws Exception {
            saveGeneralPost("남길 글", me);
            Post removed = saveGeneralPost("지울 글", me);
            softDelete(removed);

            mockMvc.perform(get(SUMMARY).header("Authorization", bearer(me)))
                    .andExpect(jsonPath("$.returnObject.postCount").value(1));
        }

        @Test
        @DisplayName("남의 활동은 내 요약에 섞이지 않는다")
        void othersActivityIsNotMine() throws Exception {
            Post post = saveAgreePost("남의 투표");
            voteStore.save(new Vote(post.id(), optionIdOf(post, 1), author.id()));

            mockMvc.perform(get(SUMMARY).header("Authorization", bearer(me)))
                    .andExpect(jsonPath("$.returnObject.voteCount").value(0));
        }
    }

    @Nested
    @DisplayName("활동 목록 (§9.1 · §9.2)")
    class ActivityList {

        @Test
        @DisplayName("활동이 0건이면 200 과 빈 배열이다")
        void emptyListWhenNoActivity() throws Exception {
            mockMvc.perform(get(ACTIVITIES).header("Authorization", bearer(me)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.returnObject.content").isArray())
                    .andExpect(jsonPath("$.returnObject.content").isEmpty())
                    .andExpect(jsonPath("$.returnObject.hasNext").value(false))
                    .andExpect(jsonPath("$.returnObject.nextCursor").doesNotExist());
        }

        @Test
        @DisplayName("유형 필터가 활동 종류를 가른다")
        void typeFilterSelectsActivity() throws Exception {
            Post voted = saveAgreePost("투표한 글");
            voteOn(voted);
            Post commented = saveGeneralPost("댓글 단 글");
            commenterStore.recordIfFirst(commented.id(), me.id());
            Post mine = saveGeneralPost("내가 쓴 글", me);

            assertThat(idsOf(ACTIVITIES + "?type=VOTE")).containsExactly(voted.id().intValue());
            assertThat(idsOf(ACTIVITIES + "?type=COMMENT")).containsExactly(commented.id().intValue());
            assertThat(idsOf(ACTIVITIES + "?type=POST")).containsExactly(mine.id().intValue());
        }

        @Test
        @DisplayName("모르는 유형·정렬은 400 이 아니라 기본값으로 되돌린다")
        void unknownParametersFallBackToDefaults() throws Exception {
            Post voted = saveAgreePost("기본값 확인");
            voteOn(voted);

            assertThat(idsOf(ACTIVITIES + "?type=오타&sort=오타"))
                    .as("진입 화면이 오타 하나로 비지 않아야 한다")
                    .containsExactly(voted.id().intValue());
        }

        @Test
        @DisplayName("최신순은 내 활동 시각 기준이다 — 게시글 작성 시각이 아니다")
        void latestSortsByActivityTimeNotPostTime() throws Exception {
            // 오래된 글에 방금 투표하고, 새 글에 한참 전에 투표한 상황.
            Post oldPost = saveAgreePost("오래된 글");
            Post newPost = saveAgreePost("새 글");
            stampPostCreatedAt(oldPost, 1000);
            stampPostCreatedAt(newPost, 10);

            voteOn(oldPost);
            voteOn(newPost);
            stampVotedAt(oldPost, 1);      // 1분 전에 투표
            stampVotedAt(newPost, 500);    // 500분 전에 투표

            assertThat(idsOf(ACTIVITIES + "?type=VOTE&sort=LATEST"))
                    .as("방금 투표한 글이 위에 온다 — 그래야 다시 찾을 수 있다")
                    .containsExactly(oldPost.id().intValue(), newPost.id().intValue());
        }

        @Test
        @DisplayName("오래된순은 최신순을 뒤집은 순서다")
        void oldestReversesLatest() throws Exception {
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Post post = saveAgreePost("순서" + i);
                voteOn(post);
                stampVotedAt(post, 100 - i * 10);
                expected.add(post.id().intValue());
            }

            List<Integer> latest = idsOf(ACTIVITIES + "?type=VOTE&sort=LATEST");
            List<Integer> oldest = idsOf(ACTIVITIES + "?type=VOTE&sort=OLDEST");

            assertThat(oldest).containsExactlyElementsOf(latest.reversed());
        }

        @Test
        @DisplayName("인기순은 popularity_score 순이다 (R-24 · R-25)")
        void popularSortsByGeneratedScore() throws Exception {
            Post low = saveAgreePost("인기 낮음");
            Post high = saveAgreePost("인기 높음");
            voteOn(low);
            voteOn(high);
            // 생성 컬럼이라 직접 쓸 수 없다. 원천인 두 카운터를 채운다.
            jdbcTemplate.update("UPDATE post SET vote_count = 1, commenter_count = 1 WHERE id = ?", low.id());
            jdbcTemplate.update("UPDATE post SET vote_count = 9, commenter_count = 9 WHERE id = ?", high.id());

            assertThat(idsOf(ACTIVITIES + "?type=VOTE&sort=POPULAR"))
                    .containsExactly(high.id().intValue(), low.id().intValue());
        }

        @Test
        @DisplayName("같은 글에 댓글을 여러 개 달아도 목록에 한 번만 나온다 (R-25)")
        void commentedPostAppearsOnce() throws Exception {
            Post post = saveGeneralPost("여러 번 댓글");
            commenterStore.recordIfFirst(post.id(), me.id());
            commenterStore.recordIfFirst(post.id(), me.id());
            commenterStore.recordIfFirst(post.id(), me.id());

            assertThat(idsOf(ACTIVITIES + "?type=COMMENT"))
                    .as("comment 로 읽었다면 세 번 나왔을 것이다")
                    .containsExactly(post.id().intValue());
        }

        @Test
        @DisplayName("삭제된 게시글은 목록에서 사라진다 — 탭해도 갈 곳이 없다")
        void deletedPostDisappears() throws Exception {
            Post alive = saveAgreePost("살아있는 글");
            Post removed = saveAgreePost("지워진 글");
            voteOn(alive);
            voteOn(removed);
            softDelete(removed);

            assertThat(idsOf(ACTIVITIES + "?type=VOTE")).containsExactly(alive.id().intValue());
        }

        @Test
        @DisplayName("오래된순 커서도 끝까지 전진한다 — 부등호와 ORDER BY 가 함께 뒤집힌다")
        void oldestCursorWalksForward() throws Exception {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < 23; i++) {
                Post post = saveAgreePost("역방향" + i);
                voteOn(post);
                stampVotedAt(post, 1000 - i);
                all.add(post.id().intValue());
            }

            List<Integer> walked = scrollAll(ACTIVITIES + "?type=VOTE&sort=OLDEST", 10);

            assertThat(walked)
                    .as("부등호만 뒤집고 ORDER BY 를 그대로 두면 같은 조각을 무한히 돈다")
                    .containsExactlyInAnyOrderElementsOf(all);
            // all 은 뒤로 갈수록 최근이다(stampVotedAt 이 1000 - i 분 전).
            // 오래된순이면 그 순서 그대로 나와야 한다.
            assertThat(walked).as("오래된 것이 먼저다").containsExactlyElementsOf(all);
        }

        @Test
        @DisplayName("인기순 커서도 끝까지 전진한다")
        void popularCursorWalksForward() throws Exception {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < 23; i++) {
                Post post = saveAgreePost("인기커서" + i);
                voteOn(post);
                jdbcTemplate.update(
                        "UPDATE post SET vote_count = ?, commenter_count = 0 WHERE id = ?", i, post.id());
                all.add(post.id().intValue());
            }

            assertThat(scrollAll(ACTIVITIES + "?type=VOTE&sort=POPULAR", 10))
                    .containsExactlyInAnyOrderElementsOf(all);
        }

        @Test
        @DisplayName("댓글 활동도 커서가 끝까지 전진한다 — 유형마다 조인과 정렬 컬럼이 갈린다")
        void commentCursorWalksForward() throws Exception {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < 23; i++) {
                Post post = saveGeneralPost("댓글커서" + i);
                commenterStore.recordIfFirst(post.id(), me.id());
                jdbcTemplate.update(
                        "UPDATE post_commenter SET created_at = ? WHERE post_id = ? AND user_id = ?",
                        LocalDateTime.now(clock).minusMinutes(1000 - i), post.id(), me.id());
                all.add(post.id().intValue());
            }

            assertThat(scrollAll(ACTIVITIES + "?type=COMMENT&sort=LATEST", 10))
                    .containsExactlyInAnyOrderElementsOf(all);
            assertThat(scrollAll(ACTIVITIES + "?type=COMMENT&sort=OLDEST", 10))
                    .containsExactlyElementsOf(all);
        }

        @Test
        @DisplayName("내가 올린 글도 커서가 끝까지 전진한다")
        void myPostsCursorWalksForward() throws Exception {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < 23; i++) {
                Post post = saveAgreePost("내글커서" + i, me);
                stampPostCreatedAt(post, 1000 - i);
                all.add(post.id().intValue());
            }

            assertThat(scrollAll(ACTIVITIES + "?type=POST&sort=LATEST", 10))
                    .containsExactlyInAnyOrderElementsOf(all);
            assertThat(scrollAll(ACTIVITIES + "?type=POST&sort=POPULAR", 10))
                    .containsExactlyInAnyOrderElementsOf(all);
        }

        @Test
        @DisplayName("커서를 끝까지 따라가도 중복·누락이 없다")
        void cursorWalkCoversEverythingExactlyOnce() throws Exception {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                Post post = saveAgreePost("커서" + i);
                voteOn(post);
                stampVotedAt(post, 1000 - i);
                all.add(post.id().intValue());
            }

            List<Integer> walked = scrollAll(ACTIVITIES + "?type=VOTE&sort=LATEST", 10);

            assertThat(walked).as("합집합이 전체와 같아야 한다")
                    .containsExactlyInAnyOrderElementsOf(all);
        }

        @Test
        @DisplayName("정렬 키가 동률이어도 조각 경계에서 행이 새지 않는다")
        void tiedSortKeysDoNotLeak() throws Exception {
            // Clock 이 초 단위로 끊으므로 같은 시각은 이론이 아니라 실제로 생긴다.
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Post post = saveAgreePost("동률" + i);
                voteOn(post);
                stampVotedAt(post, 60);   // 전부 같은 시각
                all.add(post.id().intValue());
            }

            assertThat(scrollAll(ACTIVITIES + "?type=VOTE", 5))
                    .containsExactlyInAnyOrderElementsOf(all);
        }

        @Test
        @DisplayName("한 조각은 SQL 한 번으로 나간다")
        void oneStatementPerSlice() throws Exception {
            for (int i = 0; i < 12; i++) {
                Post post = saveAgreePost("N+1 확인" + i);
                voteOn(post);
            }

            assertThat(countStatements(ACTIVITIES + "?type=VOTE&size=1", 1)).isEqualTo(1L);
            assertThat(countStatements(ACTIVITIES + "?type=VOTE&size=12", 12)).isEqualTo(1L);
        }

        @Test
        @DisplayName("조각 크기에 상한이 있다")
        void sliceSizeIsCapped() throws Exception {
            for (int i = 0; i < 3; i++) {
                Post post = saveAgreePost("상한" + i);
                voteOn(post);
            }

            assertThat(idsOf(ACTIVITIES + "?type=VOTE&size=100000")).hasSize(3);
        }

        @Test
        @DisplayName("조작된 커서는 400 이다")
        void tamperedCursorIsRejected() throws Exception {
            mockMvc.perform(get(ACTIVITIES + "?cursor=not-a-cursor").header("Authorization", bearer(me)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("내가 올린 최신 투표 (§7.4)")
    class RecentPosts {

        @Test
        @DisplayName("7일 안쪽은 나오고 8일 전은 나오지 않는다")
        void sevenDayBoundary() throws Exception {
            Post inside = saveAgreePost("6일 전", me);
            Post outside = saveAgreePost("8일 전", me);
            stampPostCreatedAtDays(inside, 6);
            stampPostCreatedAtDays(outside, 8);

            assertThat(recentIds())
                    .as("8일 전 글은 최신 목록에 없다")
                    .containsExactly(inside.id().intValue());
        }

        @Test
        @DisplayName("경계는 반열린 구간이다 — 정확히 7일 전은 빠지고 1초 안쪽은 들어온다")
        void boundaryIsHalfOpen() throws Exception {
            // "지금" 을 한 번만 읽는다. 시계가 고정이라 값이 같지만, 두 번 부르는 형태는
            // 살아 있는 시계에서 두 글이 서로 다른 기준으로 스탬프될 수 있음을 숨긴다.
            LocalDateTime boundary = LocalDateTime.now(clock).minusDays(7);

            Post exactly = saveAgreePost("정확히 7일", me);
            Post justInside = saveAgreePost("7일에서 1초 안쪽", me);
            stampPostCreatedAt(exactly, boundary);
            stampPostCreatedAt(justInside, boundary.plusSeconds(1));

            assertThat(recentIds())
                    .as("기준 시각은 요청 시각이고 7일이 지난 순간이 곧 만료다")
                    .containsExactly(justInside.id().intValue());
        }

        @Test
        @DisplayName("고정 시계라 스탬프와 서버가 같은 지금을 본다 — 경계 판정의 전제")
        void clockIsFixed() {
            // 이 전제가 깨지면 위 경계 테스트가 확률적으로 실패한다(#83 과 같은 계열).
            // 전제를 단언해 두면 실패했을 때 "경계 로직이 틀렸다" 로 오진하지 않는다.
            assertThat(LocalDateTime.now(clock))
                    .as("살아 있는 시계면 두 호출이 초 경계를 넘어 달라진다")
                    .isEqualTo(LocalDateTime.now(clock));
        }

        @Test
        @DisplayName("투표가 없는 일반 게시글은 대상이 아니다")
        void generalPostIsNotAVote() throws Exception {
            Post general = saveGeneralPost("일반 글", me);
            Post agree = saveAgreePost("찬반 글", me);
            stampPostCreatedAtDays(general, 1);
            stampPostCreatedAtDays(agree, 1);

            assertThat(recentIds()).containsExactly(agree.id().intValue());
        }

        @Test
        @DisplayName("남이 올린 글은 내 목록에 없다")
        void othersPostIsNotMine() throws Exception {
            Post theirs = saveAgreePost("남의 글", author);
            stampPostCreatedAtDays(theirs, 1);

            assertThat(recentIds()).isEmpty();
        }

        @Test
        @DisplayName("올린 투표가 없으면 빈 배열이다")
        void emptyWhenNothingPosted() throws Exception {
            mockMvc.perform(get(RECENT).header("Authorization", bearer(me)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject").isArray())
                    .andExpect(jsonPath("$.returnObject").isEmpty());
        }
    }

    @Nested
    @DisplayName("인증 (ADR-0034)")
    class Authentication {

        @Test
        @DisplayName("세 엔드포인트 모두 미인증은 401 이다 — 게스트용 0 응답을 만들지 않는다")
        void allRequireAuthentication() throws Exception {
            for (String path : List.of(SUMMARY, ACTIVITIES, RECENT)) {
                mockMvc.perform(get(path))
                        .andExpect(status().isUnauthorized());
            }
        }
    }

    /**
     * 필터와 정렬이 <b>쿼리에서</b> 끝나는지 실행계획으로 본다.
     *
     * <p>애플리케이션 레이어 filter 는 조각 크기를 어긋나게 하고 커서를 깨뜨린다.
     * "결과가 맞다" 로는 그것을 구분할 수 없어 계획을 직접 읽는다.
     *
     * <p><b>행을 먼저 심는다.</b> 빈 테이블에서는 옵티마이저가 통계 없이 아무 인덱스나
     * 고르므로 계획이 의미를 갖지 않는다 — 실제로 {@code idx_post_latest_all} 을 골랐다.
     */
    @Nested
    @DisplayName("실행 계획 — 필터와 정렬이 SQL 에서 끝난다")
    class QueryPlan {

        @BeforeEach
        void seedForOptimizer() {
            LocalDateTime now = LocalDateTime.now(clock);
            // 남의 글을 함께 심는다. 전부 내 글이면 user_id 가 선택적이지 않아
            // 옵티마이저가 idx_post_user 를 고를 이유가 없다 — 운영에서는 내 글이
            // 전체의 극히 일부라, 그 비율을 흉내내지 않으면 계획이 현실과 갈린다.
            for (int i = 0; i < 300; i++) {
                jdbcTemplate.update("""
                        INSERT INTO post (user_id, type, category, title, description, created_at, updated_at)
                        VALUES (?, 'AGREE', 'ETC', ?, '설명', ?, ?)
                        """, author.id(), "남의 글" + i, now.minusMinutes(i), now);
            }
            for (int i = 0; i < 60; i++) {
                jdbcTemplate.update("""
                        INSERT INTO post (user_id, type, category, title, description, created_at, updated_at)
                        VALUES (?, 'AGREE', 'ETC', ?, '설명', ?, ?)
                        """, me.id(), "계획" + i, now.minusMinutes(i), now);
                Long postId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbcTemplate.update("""
                        INSERT INTO post_option (post_id, post_product_id, label, display_order, vote_count, created_at)
                        VALUES (?, NULL, '사자', 1, 0, ?)
                        """, postId, now);
                Long optionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbcTemplate.update("""
                        INSERT INTO vote (post_id, post_option_id, user_id, created_at) VALUES (?, ?, ?, ?)
                        """, postId, optionId, me.id(), now.minusMinutes(i));
                jdbcTemplate.update("""
                        INSERT INTO post_commenter (post_id, user_id, created_at) VALUES (?, ?, ?)
                        """, postId, me.id(), now.minusMinutes(i));
            }
            jdbcTemplate.execute("ANALYZE TABLE post, vote, post_commenter");
        }

        @Test
        @DisplayName("활동 유형 필터가 인덱스로 적용된다 — 애플리케이션이 거르지 않는다")
        void typeFilterUsesIndex() {
            assertThat(explainVoteSlice())
                    .as("애플리케이션이 아니라 쿼리가 좁힌다")
                    .contains("idx_vote_user_activity");
        }

        @Test
        @DisplayName("최신순 정렬에 filesort 가 없다")
        void latestSortNeedsNoFilesort() {
            // 정렬 튜플의 두 번째 자리를 p.id 로 쓰면 여기서 Sort 가 나타나고
            // 내 활동 전체를 읽는다 — 활동 500건 실측 4.29ms(ADR-0036).
            assertThat(explainVoteSlice()).doesNotContain("Sort:");
        }

        @Test
        @DisplayName("정렬 튜플의 두 번째 자리를 p.id 로 쓰면 filesort 로 떨어진다")
        void wrongIdColumnFallsBackToFilesort() {
            // 규칙이 무언가를 지킨다는 증거 — 일부러 어긴 형태가 실제로 나빠지는지 본다.
            assertThat(explain("""
                    SELECT p.id FROM vote v JOIN post p ON p.id = v.post_id AND p.deleted_at IS NULL
                     WHERE v.user_id = %d ORDER BY v.created_at DESC, p.id DESC LIMIT 11
                    """.formatted(me.id())))
                    .as("값이 같아도 어느 테이블에서 읽느냐가 실행계획을 가른다")
                    .contains("Sort:");
        }

        @Test
        @DisplayName("댓글 활동도 전용 인덱스를 탄다")
        void commentActivityUsesIndex() {
            assertThat(explain("""
                    SELECT p.id FROM post_commenter pc JOIN post p ON p.id = pc.post_id
                       AND p.deleted_at IS NULL
                     WHERE pc.user_id = %d ORDER BY pc.created_at DESC, pc.post_id DESC LIMIT 11
                    """.formatted(me.id())))
                    .contains("idx_commenter_user_activity")
                    .doesNotContain("Sort:");
        }

        @Test
        @DisplayName("내가 올린 글 목록에는 정렬이 남는다 — 알고 받아들인 예외다")
        void myPostsKeepASort() {
            // idx_post_user 뒤에 InnoDB 가 붙이는 PK 는 오름차순이라
            // created_at DESC, id DESC 와 어긋난다. id ASC 로 바꾸면 사라지지만
            // 커서 튜플의 두 키 방향이 갈려 행 값 비교가 성립하지 않는다(ADR-0036).
            // 이 테스트는 결함이 아니라 "여기까지 안다" 를 고정한다 —
            // 나중에 사라지면 그때 문서를 고치라는 신호다.
            assertThat(explain("""
                    SELECT p.id FROM post p WHERE p.user_id = %d AND p.deleted_at IS NULL
                     ORDER BY p.created_at DESC, p.id DESC LIMIT 11
                    """.formatted(me.id())))
                    .contains("idx_post_user")
                    .contains("Sort:");
        }

        @Test
        @DisplayName("7일 이내 조회가 내 게시글 인덱스를 탄다")
        void recentPostsUseExistingIndex() {
            assertThat(explain("""
                    SELECT p.id FROM post p
                     WHERE p.user_id = %d AND p.deleted_at IS NULL AND p.type <> 'GENERAL'
                       AND p.created_at > '2020-01-01 00:00:00'
                     ORDER BY p.created_at DESC, p.id DESC LIMIT 10
                    """.formatted(me.id())))
                    .as("V11 없이도 기존 idx_post_user 로 족하다")
                    .contains("idx_post_user");
        }

        private String explainVoteSlice() {
            return explain("""
                    SELECT p.id FROM vote v JOIN post p ON p.id = v.post_id AND p.deleted_at IS NULL
                     WHERE v.user_id = %d ORDER BY v.created_at DESC, v.post_id DESC LIMIT 11
                    """.formatted(me.id()));
        }
    }

    // ---- 픽스처 ----

    private User saveUser(String providerId, String name) {
        User saved = userStore.save(new User(SocialProvider.GOOGLE, providerId, null, name));
        jdbcTemplate.update("UPDATE users SET nickname = ? WHERE id = ?", uniqueNickname(name), saved.id());
        return saved;
    }

    /**
     * 닉네임은 5자 상한에 활성 회원 사이에서 유일하다 ({@code uk_users_active_nickname}).
     *
     * <p>재사용 컨테이너라 이전 실행이 남긴 회원도 이 유일성을 다툰다.
     * 클래스 전역 카운터를 36진수로 5자에 담아 실행 안에서도 겹치지 않게 한다 —
     * 테스트 이름을 섞으면 5자를 넘겨 잘리고, 잘린 뒤 다시 겹친다.
     */
    private String uniqueNickname(String name) {
        return Long.toString(NICKNAME_SEQUENCE.getAndIncrement(), 36);
    }

    private Post saveGeneralPost(String title) {
        return saveGeneralPost(title, author);
    }

    private Post saveGeneralPost(String title, User writer) {
        return postStore.save(new Post(writer.id(), PostType.GENERAL, PostCategory.ETC, title, "설명"));
    }

    private Post saveAgreePost(String title) {
        return saveAgreePost(title, author);
    }

    /**
     * 찬반 게시글을 <b>상품 없이</b> 심는다.
     *
     * <p>도메인을 우회해 JDBC 로 직접 넣는 이유는 R-02 다 — {@code Post} 생성자가
     * AGREE 에 상품 1개를 요구하고, 상품은 이미지 컨테이너를 요구해
     * 목록 순서 하나를 보려고 S3(LocalStack)까지 끌고 오게 된다.
     * {@code PostControllerIT} 가 R-04(선택지 2개) 때문에 같은 우회를 쓴다.
     *
     * <p><b>선택지는 심는다.</b> {@code vote} 의 복합 FK {@code (post_option_id, post_id)}
     * 가 R-10 을 강제하므로 선택지 없이는 투표 자체가 성립하지 않는다.
     * 찬반 선택지는 {@code post_product_id} 가 NULL 이라 상품 없이 만들 수 있다.
     *
     * <p>빠진 상품이 이 테스트가 보는 것을 가리지 않는다 — 대표 사진이 {@code null} 로
     * 나올 뿐이고, 여기서 보는 것은 필터·정렬·커서다.
     */
    private Post saveAgreePost(String title, User writer) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("""
                INSERT INTO post (user_id, type, category, title, description, created_at, updated_at)
                VALUES (?, 'AGREE', 'ETC', ?, '설명', ?, ?)
                """, writer.id(), title, now, now);
        Long postId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO post_option (post_id, post_product_id, label, display_order, vote_count, created_at)
                VALUES (?, NULL, '사자', 1, 0, ?), (?, NULL, '말자', 2, 0, ?)
                """, postId, now, postId, now);
        return postStore.findById(postId).orElseThrow();
    }

    private long voteOn(Post post) {
        return voteStore.save(new Vote(post.id(), optionIdOf(post, 1), me.id())).id();
    }

    private void changeVote(Post post, long voteId) {
        jdbcTemplate.update("UPDATE vote SET post_option_id = ? WHERE id = ?",
                optionIdOf(post, 2), voteId);
    }

    private Long optionIdOf(Post post, int displayOrder) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM post_option WHERE post_id = ? AND display_order = ?",
                Long.class, post.id(), displayOrder);
    }

    private void softDelete(Post post) {
        jdbcTemplate.update("UPDATE post SET deleted_at = ? WHERE id = ?",
                LocalDateTime.now(clock), post.id());
    }

    /**
     * 시각은 반드시 주입된 {@code Clock} 에서 온다 — {@code NOW()} 를 섞으면
     * 초 단위로 끊는 애플리케이션 시각과 갈려 경계 판정이 CI 에서만 뒤집힌다.
     */
    private void stampPostCreatedAt(Post post, int minutesAgo) {
        stampPostCreatedAt(post, LocalDateTime.now(clock).minusMinutes(minutesAgo));
    }

    private void stampPostCreatedAtDays(Post post, int daysAgo) {
        stampPostCreatedAt(post, LocalDateTime.now(clock).minusDays(daysAgo));
    }

    private void stampPostCreatedAt(Post post, LocalDateTime at) {
        jdbcTemplate.update("UPDATE post SET created_at = ? WHERE id = ?", at, post.id());
    }

    private void stampVotedAt(Post post, int minutesAgo) {
        jdbcTemplate.update("UPDATE vote SET created_at = ? WHERE post_id = ? AND user_id = ?",
                LocalDateTime.now(clock).minusMinutes(minutesAgo), post.id(), me.id());
    }

    private long countRows(String table, Long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }

    // ---- 호출 ----

    private String bearer(User user) {
        return "Bearer " + jwtService.createAccessToken(user);
    }

    private String read(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private List<Integer> idsOf(String url) throws Exception {
        JSONArray ids = JsonPath.read(read(url), "$.returnObject.content[*].id");
        return ids.stream().map(id -> (Integer) id).toList();
    }

    private List<Integer> recentIds() throws Exception {
        JSONArray ids = JsonPath.read(read(RECENT), "$.returnObject[*].id");
        return ids.stream().map(id -> (Integer) id).toList();
    }

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
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        JSONArray ids = JsonPath.read(read(url), "$.returnObject.content[*].id");
        assertThat(ids).hasSize(expectedRows);

        return statistics.getPrepareStatementCount();
    }

    private String explain(String sql) {
        return String.join(" ", jdbcTemplate.queryForList(
                "EXPLAIN FORMAT=TREE " + sql, String.class));
    }
}
