package app.pickple.activity.controller;

import app.pickple.activity.domain.ActivityQueryStore;
import app.pickple.activity.domain.ActivitySort;
import app.pickple.activity.domain.ActivityType;
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
import app.pickple.support.SqlCapture;
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
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
@Import(SqlCapture.Config.class)
class ActivityControllerIT {

    private static final String SUMMARY = "/users/me/activities/summary";
    /** 구 경로. 유형을 쿼리 파라미터로 받으며 deprecated 다 (#156). 동작은 그대로여야 한다. */
    private static final String ACTIVITIES = "/users/me/activities";
    private static final String VOTES = "/users/me/activities/votes";
    private static final String COMMENTS = "/users/me/activities/comments";
    private static final String POSTS = "/users/me/activities/posts";
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
    @Autowired
    private ActivityQueryStore activityQueryStore;
    @Autowired
    private SqlCapture sqlCapture;
    /**
     * 초 단위로 끊은 <b>고정</b> 시계. 눈금은 운영과 같게 두어({@code datetime(0)} 정밀도)
     * 초 미만 값이 DB 에서 잘리는 상황을 그대로 재현한다 — 시각을 고정하되
     * 정밀도까지 바꾸면 테스트가 운영과 다른 조건을 보게 된다.
     *
     * <p>기준 시각은 <b>이 클래스가 도는 지금</b>이다. 저장소도 같은 {@code Clock} 빈을
     * 주입받으므로({@code JpaPostStore.save}) 픽스처가 심는 행의 기본 시각과
     * 테스트의 스탬프가 같은 기준을 공유한다. 고정 시각을 과거나 미래로 못박으면
     * 재사용 컨테이너에 남은 이전 실행의 행과 시간 관계가 뒤엉킨다.
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
        @DisplayName("삭제된 게시글은 투표·댓글 갯수에서도 빠진다 — 목록과 같은 숫자여야 한다")
        void deletedPostIsNotCountedInVoteAndComment() throws Exception {
            // 목록의 키 문장은 삭제된 글을 빼므로, 요약이 세면 "12" 아래 카드 11장이 뜬다 (ADR-0047).
            Post alive = saveAgreePost("남는 글");
            Post removed = saveAgreePost("지워질 글");
            voteOn(alive);
            voteOn(removed);
            assertThat(commenterStore.recordIfFirst(alive.id(), me.id())).isTrue();
            assertThat(commenterStore.recordIfFirst(removed.id(), me.id())).isTrue();
            softDelete(removed);

            mockMvc.perform(get(SUMMARY).header("Authorization", bearer(me)))
                    .andExpect(jsonPath("$.returnObject.voteCount").value(1))
                    .andExpect(jsonPath("$.returnObject.commentCount").value(1));
            assertThat(idsOf(VOTES)).hasSize(1);
            assertThat(idsOf(COMMENTS)).hasSize(1);
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
        @DisplayName("활동이 0건이면 200 과 빈 배열이다 — 세 경로 모두")
        void emptyListWhenNoActivity() throws Exception {
            for (String path : List.of(VOTES, COMMENTS, POSTS)) {
                mockMvc.perform(get(path).header("Authorization", bearer(me)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value("OK"))
                        .andExpect(jsonPath("$.returnObject.content").isArray())
                        .andExpect(jsonPath("$.returnObject.content").isEmpty())
                        .andExpect(jsonPath("$.returnObject.hasNext").value(false))
                        .andExpect(jsonPath("$.returnObject.nextCursor").doesNotExist());
            }
        }

        @Test
        @DisplayName("경로가 활동 종류를 가른다 — 유형은 더 이상 쿼리 파라미터가 아니다")
        void pathSelectsActivity() throws Exception {
            Post voted = saveAgreePost("투표한 글");
            voteOn(voted);
            Post commented = saveGeneralPost("댓글 단 글");
            commenterStore.recordIfFirst(commented.id(), me.id());
            Post mine = saveGeneralPost("내가 쓴 글", me);

            assertThat(idsOf(VOTES)).containsExactly(voted.id().intValue());
            assertThat(idsOf(COMMENTS)).containsExactly(commented.id().intValue());
            assertThat(idsOf(POSTS)).containsExactly(mine.id().intValue());
        }

        @Test
        @DisplayName("유형별 경로에서 type 파라미터는 무시된다 — 경로가 이긴다")
        void typeParameterIsIgnoredOnTypedPaths() throws Exception {
            // 경로가 유형을 고정하므로 남은 type 파라미터는 아무것도 바꾸지 못한다.
            // 이것이 성립해야 "유형 fold 가 사라졌다" 는 계약이 실제로 참이다.
            Post voted = saveAgreePost("투표한 글");
            voteOn(voted);
            Post mine = saveGeneralPost("내가 쓴 글", me);

            assertThat(idsOf(VOTES + "?type=POST"))
                    .as("경로가 /votes 면 type=POST 를 실어도 투표 활동이다")
                    .containsExactly(voted.id().intValue());
            assertThat(idsOf(POSTS + "?type=VOTE"))
                    .containsExactly(mine.id().intValue());
        }

        @Test
        @DisplayName("모르는 정렬은 400 이 아니라 기본값으로 되돌린다 — 정렬 계약은 그대로다")
        void unknownSortFallsBackToDefault() throws Exception {
            // 유형 fold 는 경로가 고정하면서 사라졌지만 ActivitySort.from 은 살아남는다.
            // "모르는 값은 400 이 아니다" 가 절반만 참이 된 자리다 (SPEC §3.10).
            Post voted = saveAgreePost("기본값 확인");
            voteOn(voted);

            assertThat(idsOf(VOTES + "?sort=오타"))
                    .as("진입 화면이 오타 하나로 비지 않아야 한다")
                    .containsExactly(voted.id().intValue());
        }

        @Test
        @DisplayName("구 경로는 그대로 200 이다 — deprecated 지만 동작은 유지된다")
        void deprecatedPathStillWorks() throws Exception {
            Post voted = saveAgreePost("구 경로 투표");
            voteOn(voted);
            Post mine = saveGeneralPost("구 경로 내 글", me);

            assertThat(idsOf(VOTES)).containsExactly(voted.id().intValue());
            assertThat(idsOf(ACTIVITIES + "?type=POST")).containsExactly(mine.id().intValue());
            assertThat(idsOf(ACTIVITIES + "?type=오타"))
                    .as("구 경로에는 유형 fold 가 남는다 — 마지막 호출자다")
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

            assertThat(idsOf(VOTES + "?sort=LATEST"))
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

            List<Integer> latest = idsOf(VOTES + "?sort=LATEST");
            List<Integer> oldest = idsOf(VOTES + "?sort=OLDEST");

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

            assertThat(idsOf(VOTES + "?sort=POPULAR"))
                    .containsExactly(high.id().intValue(), low.id().intValue());
        }

        @Test
        @DisplayName("같은 글에 댓글을 여러 개 달아도 목록에 한 번만 나온다 (R-25)")
        void commentedPostAppearsOnce() throws Exception {
            Post post = saveGeneralPost("여러 번 댓글");
            commenterStore.recordIfFirst(post.id(), me.id());
            commenterStore.recordIfFirst(post.id(), me.id());
            commenterStore.recordIfFirst(post.id(), me.id());

            assertThat(idsOf(COMMENTS))
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

            assertThat(idsOf(VOTES)).containsExactly(alive.id().intValue());
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

            List<Integer> walked = scrollAll(VOTES + "?sort=OLDEST", 10);

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

            assertThat(scrollAll(VOTES + "?sort=POPULAR", 10))
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

            assertThat(scrollAll(COMMENTS + "?sort=LATEST", 10))
                    .containsExactlyInAnyOrderElementsOf(all);
            assertThat(scrollAll(COMMENTS + "?sort=OLDEST", 10))
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

            assertThat(scrollAll(POSTS + "?sort=LATEST", 10))
                    .containsExactlyInAnyOrderElementsOf(all);
            assertThat(scrollAll(POSTS + "?sort=POPULAR", 10))
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

            List<Integer> walked = scrollAll(VOTES + "?sort=LATEST", 10);

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

            assertThat(scrollAll(VOTES, 5))
                    .containsExactlyInAnyOrderElementsOf(all);
        }

        @Test
        @DisplayName("세 경로 모두 조각 크기가 12배가 되어도 SQL 횟수는 그대로다 — N+1 이 없다")
        void statementCountDoesNotGrowWithSliceSizeOnEveryPath() throws Exception {
            // 지금까지 이 검증은 VOTE 한 유형만 돌았다. 세 경로로 갈렸으니 경로별로 확인한다 —
            // POST 는 활동 테이블이 게시글 자신이라 조인이 없어 구조가 다른 유일한 분기다.
            for (int i = 0; i < 12; i++) {
                Post voted = saveAgreePost("N+1 투표" + i);
                voteOn(voted);
                Post commented = saveGeneralPost("N+1 댓글" + i);
                commenterStore.recordIfFirst(commented.id(), me.id());
                saveGeneralPost("N+1 내글" + i, me);
            }

            for (String path : List.of(VOTES, COMMENTS, POSTS)) {
                long one = countStatements(path + "?size=1", 1);
                long twelve = countStatements(path + "?size=12", 12);

                // 지키려는 성질은 숫자가 아니라 행 수에 비례하지 않는다는 것이다.
                assertThat(twelve)
                        .as("%s 의 문장 수가 행 수를 따라 늘면 N+1 이다", path)
                        .isEqualTo(one);

                // 그 상수가 무엇으로 이루어졌는지도 고정한다. 늘어나면 이유를 대야 한다.
                //   1) 키 문장 — 조각에 들어갈 게시글 id 를 활동 인덱스로 확정한다
                //   2) 행 문장 — 그 id 들에만 게시글과 대표 사진을 붙인다 (ADR-0043)
                //      옛 네이티브 SQL 은 파생 테이블로 1) 2) 를 한 문장에 담았다. QueryDSL 전환으로
                //      둘로 갈라졌지만 행 수에 비례하는 쪽은 여전히 없다.
                //   3) 탈퇴 회원 차단 관문의 상태 확인 (#106, ADR-0035)
                //      요청당 1회이고 행 수와 무관하다. type=const / key=PRIMARY 로 끝난다.
                // 투표 경로는 여기에 둘이 더 붙는다 — 선택지와 상품을 조각 전체에
                // post.id IN 한 문장씩으로 읽는다 (#157). 이 둘도 행 수와 무관하다.
                // COMMENT·POST 는 그 계약이 없어 셋 그대로다 (#158 소관).
                long expected = path.equals(VOTES) ? 5L : 3L;
                assertThat(one).as("%s 의 요청당 상수 문장 수", path).isEqualTo(expected);
            }

            // ⚠️ 문장 수 단언만으로 N+1 부재를 선언하지 않는다 — 한 문장 안의 상관 서브쿼리는
            // 이 계수에 잡히지 않는다(대표 사진·상품 사진이 그렇다). 조각 크기를 12배로 키워도
            // 위 단언이 같은 값을 요구하므로, 문장이 늘지 않으면서 안쪽이 행마다 도는 경우는
            // 실행계획 테스트(RowStatementPlan)가 따로 고정한다.
        }

        @Test
        @DisplayName("선택을 바꾸면 목록의 내 선택도 최신 선택이다 (R-22)")
        void selectedOptionIsTheLatestChoice() throws Exception {
            // 재투표는 vote 한 행의 UPDATE 라 uk_vote_post_user 가 "한 사람당 한 선택" 을
            // 이미 보장한다 — 목록이 옛 선택을 보여주면 그 한 행을 안 읽고 있다는 뜻이다.
            Post post = saveAgreePost("재투표 카드");
            castVote(post, me, 1);

            assertThat(firstItem(VOTES, "selectedOptionId"))
                    .isEqualTo(optionIdOf(post, 1).intValue());

            recastVote(post, me, 1, 2);

            assertThat(firstItem(VOTES, "selectedOptionId"))
                    .as("재투표 뒤에는 최신 선택이어야 한다")
                    .isEqualTo(optionIdOf(post, 2).intValue());
            // 사람 수는 그대로고 표만 옮겨갔다.
            assertThat(firstItem(VOTES, "voteCount")).isEqualTo(1);
            assertThat(firstItem(VOTES, "options[0].voteCount")).isEqualTo(0);
            assertThat(firstItem(VOTES, "options[1].voteCount")).isEqualTo(1);
        }

        @Test
        @DisplayName("득표율이 게시글 상세와 같은 값이고 합이 100±1 이다")
        void percentageMatchesPostDetail() throws Exception {
            // 같은 게이지를 두 화면이 그린다. 반올림 규칙이 갈리면 카드에서 본 값과
            // 탭해 들어간 상세의 값이 미세하게 달라진다 — 그래서 값을 직접 대조한다.
            // 3명 중 1명 = 33.33% 라 정수 반올림 경계에 걸린다.
            Post post = saveAgreePost("반올림 대조 카드");
            User second = saveUser("act-voter2-" + seed, "투표2");
            User third = saveUser("act-voter3-" + seed, "투표3");
            castVote(post, me, 1);
            castVote(post, second, 2);
            castVote(post, third, 2);

            int firstFromDetail = detail(post.id(), "vote.options[0].percentage");
            int secondFromDetail = detail(post.id(), "vote.options[1].percentage");

            assertThat(firstItem(VOTES, "options[0].percentage"))
                    .as("활동 목록의 득표율이 상세와 달라지면 정본이 둘이다")
                    .isEqualTo(firstFromDetail);
            assertThat(firstItem(VOTES, "options[1].percentage")).isEqualTo(secondFromDetail);

            int sum = firstItem(VOTES, "options[0].percentage")
                    + firstItem(VOTES, "options[1].percentage");
            assertThat(sum)
                    .as("선택지별 반올림이라 합이 정확히 100 은 아닐 수 있다 — 다만 1 을 넘게 벌어지면 계산이 틀린 것이다")
                    .isBetween(99, 101);
        }

        @Test
        @DisplayName("A/B 카드는 사진 2장, 찬반 카드는 1장이다 (R-02 · R-03)")
        void productPhotosFollowPostType() throws Exception {
            Post ab = saveAbPost("A/B 카드", "https://cdn/ab-a.jpg", "https://cdn/ab-b.jpg");
            castVote(ab, me, 1);

            JSONArray abImages = JsonPath.read(read(VOTES), "$.returnObject.content[0].products[*].imageUrl");
            assertThat(abImages)
                    .as("A/B 는 표시 순서대로 두 상품의 사진이 필요하다 — 선택지별 결과가 양쪽을 그린다")
                    .containsExactly("https://cdn/ab-a.jpg", "https://cdn/ab-b.jpg");

            // 찬반은 상품이 하나뿐이다 (R-02).
            Post agree = saveAgreePost("찬반 카드");
            attachPhotos(agree, "https://cdn/agree-1.jpg", "https://cdn/agree-2.jpg");
            castVote(agree, me, 1);
            stampVotedAt(ab, 60);   // A/B 를 뒤로 보내 찬반이 첫 항목이 되게 한다

            JSONArray agreeImages = JsonPath.read(read(VOTES), "$.returnObject.content[0].products[*].imageUrl");
            assertThat(agreeImages)
                    .as("찬반 상품은 사진이 최대 3장이어도 대표 1장이다")
                    .containsExactly("https://cdn/agree-1.jpg");
        }

        @Test
        @DisplayName("선택지는 정확히 둘이고 표시 순서대로다 (R-04)")
        void optionsAreExactlyTwoInDisplayOrder() throws Exception {
            Post post = saveAgreePost("선택지 순서");
            castVote(post, me, 1);

            JSONArray orders = JsonPath.read(read(VOTES), "$.returnObject.content[0].options[*].displayOrder");
            assertThat(orders).containsExactly(1, 2);
            JSONArray labels = JsonPath.read(read(VOTES), "$.returnObject.content[0].options[*].label");
            assertThat(labels).containsExactly("사자", "말자");
        }

        @Test
        @DisplayName("댓글·내 글 응답은 그대로다 — 투표 카드에만 필드가 붙는다")
        void otherPathsKeepTheirContract() throws Exception {
            // #158 이 댓글 카드를 따로 정하므로, 그전까지 두 경로의 계약은 변하지 않아야 한다.
            Post commented = saveAgreePost("댓글 단 투표글");
            commenterStore.recordIfFirst(commented.id(), me.id());
            Post mine = saveAgreePost("내가 쓴 투표글", me);

            mockMvc.perform(get(COMMENTS).header("Authorization", bearer(me)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.content[0].selectedOptionId").doesNotExist())
                    .andExpect(jsonPath("$.returnObject.content[0].options").doesNotExist())
                    .andExpect(jsonPath("$.returnObject.content[0].products").doesNotExist());

            mockMvc.perform(get(POSTS).header("Authorization", bearer(me)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.returnObject.content[0].selectedOptionId").doesNotExist())
                    .andExpect(jsonPath("$.returnObject.content[0].options").doesNotExist())
                    .andExpect(jsonPath("$.returnObject.content[0].products").doesNotExist());

            assertThat(idsOf(COMMENTS)).containsExactly(commented.id().intValue());
            assertThat(idsOf(POSTS)).containsExactly(mine.id().intValue());
        }

        @Test
        @DisplayName("조각 크기에 상한이 있다")
        void sliceSizeIsCapped() throws Exception {
            for (int i = 0; i < 3; i++) {
                Post post = saveAgreePost("상한" + i);
                voteOn(post);
            }

            assertThat(idsOf(VOTES + "?size=100000")).hasSize(3);
        }

        @Test
        @DisplayName("대표 사진은 가장 처음 등록한 사진 한 장이다 — 세 유형 모두")
        void thumbnailIsTheFirstRegisteredPhoto() throws Exception {
            // 다른 픽스처는 상품 없이 심어 대표 사진이 늘 null 이다. 이 테스트만 사진을 붙여
            // 상관 서브쿼리가 "그 게시글의" 첫 사진을 고르는지 본다 — 상관 조건이 엉뚱한
            // 별칭에 묶이면 아무 글의 사진이나 올라온다.
            Post voted = saveAgreePost("투표한 사진 글");
            Post commented = saveAgreePost("댓글 단 사진 글");
            Post mine = saveAgreePost("내 사진 글", me);
            attachPhotos(voted, "https://cdn/voted-1.jpg", "https://cdn/voted-2.jpg", "https://cdn/voted-3.jpg");
            attachPhotos(commented, "https://cdn/commented-1.jpg", "https://cdn/commented-2.jpg");
            attachPhotos(mine, "https://cdn/mine-1.jpg", "https://cdn/mine-2.jpg");
            voteOn(voted);
            commenterStore.recordIfFirst(commented.id(), me.id());

            assertThat(thumbnailsOf(VOTES)).containsExactly("https://cdn/voted-1.jpg");
            assertThat(thumbnailsOf(COMMENTS)).containsExactly("https://cdn/commented-1.jpg");
            assertThat(thumbnailsOf(POSTS)).containsExactly("https://cdn/mine-1.jpg");
            assertThat(idsOf(VOTES))
                    .as("사진이 세 장이어도 게시글은 한 줄이다")
                    .containsExactly(voted.id().intValue());
        }

        @Test
        @DisplayName("조작된 커서는 400 이다")
        void tamperedCursorIsRejected() throws Exception {
            mockMvc.perform(get(VOTES + "?cursor=not-a-cursor").header("Authorization", bearer(me)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("다른 경로에서 만든 커서를 넣으면 400 이다 — 조용히 첫 조각으로 되감기지 않는다")
        void cursorFromAnotherPathIsRejected() throws Exception {
            // 세 경로가 같은 키 이름을 쓰므로 이 커서는 구조상 유효해 보인다.
            // 유형 판별자가 없으면 그대로 통과해 /posts 가 엉뚱한 첫 조각을 준다 (ADR-0049).
            for (int i = 0; i < 12; i++) {
                Post voted = saveAgreePost("교차" + i);
                voteOn(voted);
                saveGeneralPost("내 글" + i, me);
            }

            String votesCursor = JsonPath.read(read(VOTES + "?size=10"), "$.returnObject.nextCursor");
            assertThat(votesCursor).as("다음 조각이 있어야 커서가 나온다").isNotNull();

            mockMvc.perform(get(POSTS + "?cursor=" + votesCursor).header("Authorization", bearer(me)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

            // 같은 경로에 그대로 넣으면 통과한다 — 위 400 이 "커서가 늘 깨진다" 가 아니라는 대조군이다.
            mockMvc.perform(get(VOTES + "?cursor=" + votesCursor).header("Authorization", bearer(me)))
                    .andExpect(status().isOk());
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
        @DisplayName("여섯 엔드포인트 모두 미인증은 401 이다 — 게스트용 0 응답을 만들지 않는다")
        void allRequireAuthentication() throws Exception {
            // 유형별 경로 셋을 더해도 게스트 접근 정책은 그대로다 (#156).
            // SecurityConfig 의 .anyRequest() 관문이 새 경로를 자동으로 잡는다.
            for (String path : List.of(SUMMARY, ACTIVITIES, RECENT, VOTES, COMMENTS, POSTS)) {
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
     * <p><b>EXPLAIN 하는 문장은 Hibernate 가 실제로 내보낸 것이다</b>({@link SqlCapture}).
     * QueryDSL 전환(#133) 뒤로 SQL 은 사람이 쓰지 않으므로, 손으로 베껴 둔 문장을 EXPLAIN 하면
     * 저장소가 바뀌어도 테스트가 초록색으로 남는다. 실제 조회 경로를 한 번 태우고 그때 나간
     * 문장에 같은 값을 바인딩해 계획을 읽는다. 정렬 튜플의 두 번째 자리를 {@code p.id} 로
     * 바꿔 위반을 주입했을 때 {@code Sort} 가 나타나 실제로 실패하는 것을 확인했다.
     *
     * <p><b>둘째 조각을 본다.</b> 첫 조각은 keyset 조건이 없어 행 값 비교가 계획에
     * 어떻게 내려가는지 보여주지 못한다. 커서는 심은 활동의 한가운데를 가리킨다.
     *
     * <p><b>행을 먼저 심는다.</b> 빈 테이블에서는 옵티마이저가 통계 없이 아무 인덱스나
     * 고르므로 계획이 의미를 갖지 않는다 — 실제로 {@code idx_post_latest_all} 을 골랐다.
     */
    @Nested
    @DisplayName("실행 계획 — 필터와 정렬이 SQL 에서 끝난다")
    class QueryPlan {

        private static final int SLICE = 10;

        private LocalDateTime now;
        /** 커서가 가리키는 게시글. 심은 60건의 한가운데라 앞뒤로 행이 남는다. */
        private long cursorPostId;
        private LocalDateTime cursorAt;

        @BeforeEach
        void seedForOptimizer() {
            now = LocalDateTime.now(clock);
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
                if (i == 30) {
                    cursorPostId = postId;
                    cursorAt = now.minusMinutes(i);
                }
            }
            jdbcTemplate.execute("ANALYZE TABLE post, vote, post_commenter");
        }

        @Test
        @DisplayName("투표 최신순 둘째 조각이 활동 인덱스를 타고 filesort 가 없다")
        void voteLatestSliceUsesActivityIndex() {
            Statements statements = slice(ActivityType.VOTE, ActivitySort.LATEST);

            // 계획을 먼저 본다. 문장 형태 검사가 앞서면 위반 주입 때 이 단언이 실제로 물리는지 알 수 없다.
            assertThat(explain(statements.keys(), me.id(), cursorAt, cursorPostId, SLICE + 1))
                    .as("애플리케이션이 아니라 쿼리가 좁힌다 — 정렬 튜플을 p.id 로 바꾸면 Sort 가 나타난다")
                    .containsPattern(ACTIVITY_INDEX_LOOKUP.formatted("idx_vote_user_activity"))
                    .doesNotContain("Sort:");
            assertThat(statements.keys())
                    .as("행 값 비교가 풀어쓴 OR 로 바뀌면 인덱스 범위가 접히지 않는다")
                    .containsPattern(ROW_VALUE_LESS_THAN)
                    .doesNotContainIgnoringCase(" or ");
        }

        @Test
        @DisplayName("투표 오래된순은 같은 인덱스를 반대 방향으로 읽고 filesort 가 없다")
        void voteOldestSliceReadsTheSameIndexForward() {
            Statements statements = slice(ActivityType.VOTE, ActivitySort.OLDEST);

            assertThat(explain(statements.keys(), me.id(), cursorAt, cursorPostId, SLICE + 1))
                    .containsPattern(ACTIVITY_INDEX_LOOKUP.formatted("idx_vote_user_activity"))
                    .doesNotContain("Sort:");
            assertThat(statements.keys()).containsPattern(ROW_VALUE_GREATER_THAN);
        }

        @Test
        @DisplayName("댓글 활동도 전용 인덱스를 탄다")
        void commentLatestSliceUsesActivityIndex() {
            Statements statements = slice(ActivityType.COMMENT, ActivitySort.LATEST);

            assertThat(explain(statements.keys(), me.id(), cursorAt, cursorPostId, SLICE + 1))
                    .containsPattern(ACTIVITY_INDEX_LOOKUP.formatted("idx_commenter_user_activity"))
                    .doesNotContain("Sort:");
        }

        @Test
        @DisplayName("인기순은 활동 인덱스로 좁힌 뒤 정렬한다 — Θ(내 활동 수)는 의도한 한계다")
        void popularSliceStartsFromActivityIndex() {
            // 정렬 키 popularity_score 는 활동 테이블에 없어 인덱스가 정렬을 맡지 못한다(ADR-0036).
            // 그래도 user_id 로 좁히는 것은 활동 인덱스여야 한다 — post 에서 시작하면 전체를 훑는다.
            Statements statements = slice(ActivityType.VOTE, ActivitySort.POPULAR);

            assertThat(explain(statements.keys(), me.id(), 0L, cursorPostId, SLICE + 1))
                    .as("인덱스 이름만으로는 전량 스캔과 구분되지 않는다 — user_id 로 좁힌 조회여야 한다")
                    .containsPattern(ACTIVITY_INDEX_LOOKUP.formatted("idx_vote_user_activity"));
        }

        @Test
        @DisplayName("행 문장은 확정된 id 만 기본 키와 유니크 키로 읽는다 — 내 활동 전체를 다시 훑지 않는다")
        void rowsStatementReadsOnlyTheSlice() {
            Statements vote = slice(ActivityType.VOTE, ActivitySort.LATEST);
            Statements comment = slice(ActivityType.COMMENT, ActivitySort.LATEST);

            // 인덱스 이름이 아니라 접근 방식을 본다 — 유니크 키를 전량 훑어도 이름은 계획에 남는다.
            assertThat(explain(vote.rows(), vote.rowsArgs()))
                    .as("post.id IN (…) 은 기본 키 범위, 활동은 (post_id, user_id) 유니크 키로 한 줄씩")
                    .containsPattern(PRIMARY_KEY_RANGE)
                    .containsPattern(UNIQUE_KEY_LOOKUP.formatted("uk_vote_post_user"))
                    .doesNotContain("idx_vote_user_activity")
                    .doesNotContain("Table scan");
            assertThat(explain(comment.rows(), comment.rowsArgs()))
                    .containsPattern(PRIMARY_KEY_RANGE)
                    .containsPattern(UNIQUE_KEY_LOOKUP.formatted("uk_commenter_post_user"))
                    .doesNotContain("Table scan");
        }

        @Test
        @DisplayName("정렬 튜플의 두 번째 자리를 p.id 로 쓰면 filesort 로 떨어진다")
        void wrongIdColumnFallsBackToFilesort() {
            // 규칙이 무언가를 지킨다는 증거 — 일부러 어긴 형태가 실제로 나빠지는지 본다.
            // 저장소의 postIdOf(VOTE) 를 POST.id 로 바꾸면 위 voteLatestSliceUsesActivityIndex 가
            // 정확히 이 계획을 보고 실패한다.
            assertThat(explain("""
                    SELECT p.id FROM vote v JOIN post p ON p.id = v.post_id AND p.deleted_at IS NULL
                     WHERE v.user_id = ? ORDER BY v.created_at DESC, p.id DESC LIMIT 11
                    """, me.id()))
                    .as("값이 같아도 어느 테이블에서 읽느냐가 실행계획을 가른다")
                    .contains("Sort:");
        }

        @Test
        @DisplayName("내가 올린 글 목록에는 정렬이 남는다 — 알고 받아들인 예외다")
        void myPostsKeepASort() {
            // idx_post_user 뒤에 InnoDB 가 붙이는 PK 는 오름차순이라
            // created_at DESC, id DESC 와 어긋난다. id ASC 로 바꾸면 사라지지만
            // 커서 튜플의 두 키 방향이 갈려 행 값 비교가 성립하지 않는다(ADR-0036).
            // 이 테스트는 결함이 아니라 "여기까지 안다" 를 고정한다 —
            // 나중에 사라지면 그때 문서를 고치라는 신호다.
            Statements statements = slice(ActivityType.POST, ActivitySort.LATEST);

            assertThat(explain(statements.keys(), me.id(), cursorAt, cursorPostId, SLICE + 1))
                    .contains("idx_post_user")
                    .contains("Sort:");
        }

        @Test
        @DisplayName("7일 이내 조회가 내 게시글 인덱스를 탄다")
        void recentPostsUseExistingIndex() {
            LocalDateTime since = now.minusDays(7);
            List<String> statements = sqlCapture.record(
                    () -> activityQueryStore.findRecentVotePosts(me.id(), since, SLICE));
            String keys = statements.stream().filter(sql -> sql.contains(" limit ")).findFirst().orElseThrow();

            assertThat(explain(keys, me.id(), "GENERAL", since, SLICE))
                    .as("V11 없이도 기존 idx_post_user 로 족하다")
                    .contains("idx_post_user");
        }

        /** {@code (v.created_at, v.post_id) < (?, ?)} — 별칭과 공백은 Hibernate 가 정한다. */
        private static final String ROW_VALUE_LESS_THAN =
                "\\(\\s*\\w+\\.created_at\\s*,\\s*\\w+\\.post_id\\s*\\)\\s*<\\s*\\(\\s*\\?\\s*,\\s*\\?\\s*\\)";
        private static final String ROW_VALUE_GREATER_THAN = ROW_VALUE_LESS_THAN.replace("<", ">");
        /** 활동 테이블을 {@code user_id} 로 좁힌 인덱스 조회. 이름만 보면 전량 스캔과 구분되지 않는다. */
        private static final String ACTIVITY_INDEX_LOOKUP = "index lookup on \\w+ using %s \\(user_id=";
        /** 행 문장의 게시글 접근 — 확정된 id 들의 기본 키 범위. */
        private static final String PRIMARY_KEY_RANGE = "Index range scan on \\w+ using PRIMARY over \\(id = ";
        /** 행 문장의 활동 접근 — {@code (post_id, user_id)} 유니크 키 단건 조회. */
        private static final String UNIQUE_KEY_LOOKUP = "Single-row index lookup on \\w+ using %s \\(post_id=";

        /** 한 조각이 내보낸 두 문장과 행 문장의 바인딩 값. */
        private record Statements(String keys, String rows, Object[] rowsArgs) {
        }

        /**
         * 실제 조회 경로를 둘째 조각으로 한 번 태우고, 그때 나간 키 문장과 행 문장을 붙잡는다.
         * 문장의 자리는 형태로 가른다 — {@code limit} 이 있으면 키 문장, 대표 사진 서브쿼리가
         * 있으면 행 문장이다.
         */
        private Statements slice(ActivityType type, ActivitySort sort) {
            Object sortValue = sort.byActivityTime() ? cursorAt : 0L;
            // 커서에 유형을 함께 싣는다 (#156). 이 그룹은 HTTP 를 거치지 않고 저장소를 직접
            // 부르는데, 유형 판별자가 요구되는 자리가 바로 그 저장소 진입점이다 —
            // 빠뜨리면 "커서와 활동 유형이 맞지 않습니다" 로 막힌다.
            // toPosition 은 activity.infra 의 package-private 이라 여기서 부르지 못해
            // 키 맵을 직접 만든다. 키 이름이 갈리면 ActivityListCursorTest 가 먼저 깨진다.
            ScrollPosition cursor = ScrollPosition.forward(
                    Map.of("type", type.name(), sort.cursorKey(), sortValue, "id", cursorPostId));

            List<Long> ids = new ArrayList<>();
            List<String> statements = sqlCapture.record(() ->
                    activityQueryStore.findSlice(me.id(), type, sort, cursor, SLICE)
                            .forEach(view -> ids.add(view.id())));
            assertThat(ids).as("커서 뒤에 행이 남아 있어야 계획이 의미를 갖는다").hasSize(SLICE);

            String keys = statements.stream().filter(sql -> sql.contains(" limit ")).findFirst().orElseThrow();
            String rows = statements.stream().filter(sql -> sql.contains("item_resource")).findFirst().orElseThrow();
            // 바인딩 순서는 문장 안의 위치다 — SELECT 절의 대표 사진 서브쿼리(display_order = 1)가
            // 가장 앞이고, 그 다음이 조인 조건의 user_id, 마지막이 IN 의 id 목록이다.
            // POST 는 조인이 없어 user_id 가 WHERE 의 IN 뒤에 온다.
            List<Object> rowsArgs = new ArrayList<>();
            rowsArgs.add(1);
            if (type == ActivityType.POST) {
                rowsArgs.addAll(ids);
                rowsArgs.add(me.id());
            } else {
                rowsArgs.add(me.id());
                rowsArgs.addAll(ids);
            }
            return new Statements(keys, rows, rowsArgs.toArray());
        }

        private String explain(String sql, Object... args) {
            // 개수만 검사한다. Hibernate 가 개수를 유지한 채 술어 순서를 바꾸면 값이 다른 자리에 묶이는데,
            // 그때도 인덱스 선택은 값이 아니라 술어 모양이 정하므로 계획은 같다. 값까지 붙잡으려면
            // JDBC 바인딩을 가로채야 해 이 테스트의 크기를 넘는다.
            assertThat(sql.chars().filter(c -> c == '?').count())
                    .as("바인딩 값의 수가 문장의 ? 와 같아야 같은 계획을 본다")
                    .isEqualTo(args.length);
            return String.join(" ", jdbcTemplate.queryForList("EXPLAIN FORMAT=TREE " + sql, String.class, args));
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

    /**
     * 게시글에 대표 상품과 사진을 붙인다. 사진은 주어진 순서대로 등록되어 id 가 오름차순이다.
     *
     * <p>컨테이너 → 사진 → 상품 순으로 넣는다. {@code post_product} 가 {@code (item_container_id,
     * container_type)} 복합 FK 로 {@code PRODUCT} 용 컨테이너만 받으므로 컨테이너가 먼저다.
     */
    private void attachPhotos(Post post, String... accessUrls) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("""
                INSERT INTO item_container (user_id, attach_type, created_at, updated_at)
                VALUES (?, 'PRODUCT', ?, ?)
                """, author.id(), now, now);
        Long containerId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (String url : accessUrls) {
            jdbcTemplate.update("""
                    INSERT INTO item_resource (item_container_id, size, original_file_name, item_key, access_url, created_at, updated_at)
                    VALUES (?, 1024, 'photo.jpg', ?, ?, ?, ?)
                    """, containerId, "key/" + url, url, now, now);
        }
        jdbcTemplate.update("""
                INSERT INTO post_product (post_id, item_container_id, name, display_order, created_at, updated_at)
                VALUES (?, ?, '상품', 1, ?, ?)
                """, post.id(), containerId, now, now);
    }

    private long voteOn(Post post) {
        return voteStore.save(new Vote(post.id(), optionIdOf(post, 1), me.id())).id();
    }

    private void changeVote(Post post, long voteId) {
        jdbcTemplate.update("UPDATE vote SET post_option_id = ? WHERE id = ?",
                optionIdOf(post, 2), voteId);
    }

    /**
     * 한 사람의 첫 투표를 <b>카운터까지</b> 심는다 — {@code VoteService.castFirst} 와 같은 모양이다.
     *
     * <p>{@link #voteOn} 은 {@code vote} 행만 넣어 정렬·커서를 보기에 충분했지만, 득표율을
     * 대조하려면 집계가 운영과 같아야 한다. 사람 수({@code post.vote_count})와 선택지 표
     * ({@code post_option.vote_count})를 함께 올리지 않으면 두 화면이 <b>같은 틀린 값</b>을
     * 보여도 테스트가 통과한다.
     */
    private void castVote(Post post, User voter, int displayOrder) {
        Long optionId = optionIdOf(post, displayOrder);
        jdbcTemplate.update("INSERT INTO vote (post_id, post_option_id, user_id, created_at) VALUES (?, ?, ?, ?)",
                post.id(), optionId, voter.id(), LocalDateTime.now(clock));
        jdbcTemplate.update("UPDATE post SET vote_count = vote_count + 1 WHERE id = ?", post.id());
        jdbcTemplate.update("UPDATE post_option SET vote_count = vote_count + 1 WHERE id = ?", optionId);
    }

    /**
     * 선택을 바꾼다 — {@code VoteService.changeChoice} 와 같은 모양이다.
     * <b>사람 수는 그대로 두고 선택지 표만 옮긴다</b> (R-22).
     */
    private void recastVote(Post post, User voter, int fromDisplayOrder, int toDisplayOrder) {
        Long from = optionIdOf(post, fromDisplayOrder);
        Long to = optionIdOf(post, toDisplayOrder);
        jdbcTemplate.update("UPDATE vote SET post_option_id = ? WHERE post_id = ? AND user_id = ?",
                to, post.id(), voter.id());
        jdbcTemplate.update("UPDATE post_option SET vote_count = vote_count - 1 WHERE id = ?", from);
        jdbcTemplate.update("UPDATE post_option SET vote_count = vote_count + 1 WHERE id = ?", to);
    }

    /**
     * A/B 게시글을 상품 둘과 사진 한 장씩으로 심는다 (R-02 · R-03).
     *
     * <p>{@link #saveAgreePost} 와 같은 이유로 JDBC 로 우회한다. 선택지는 상품을 가리키고
     * ({@code post_product_id}) 표시 순서가 1=A · 2=B 라, 카드가 사진 두 장을 순서대로
     * 그릴 수 있는지 보려면 이 연결까지 심어야 한다.
     */
    private Post saveAbPost(String title, String imageA, String imageB) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("""
                INSERT INTO post (user_id, type, category, title, description, created_at, updated_at)
                VALUES (?, 'A_B', 'ETC', ?, '설명', ?, ?)
                """, author.id(), title, now, now);
        Long postId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Long productA = saveProduct(postId, "A 상품", 1, imageA);
        Long productB = saveProduct(postId, "B 상품", 2, imageB);
        jdbcTemplate.update("""
                INSERT INTO post_option (post_id, post_product_id, label, display_order, vote_count, created_at)
                VALUES (?, ?, NULL, 1, 0, ?), (?, ?, NULL, 2, 0, ?)
                """, postId, productA, now, postId, productB, now);
        return postStore.findById(postId).orElseThrow();
    }

    /** 상품 한 건과 사진 한 장. 컨테이너가 사진의 부모라 먼저 넣는다. */
    private Long saveProduct(Long postId, String name, int displayOrder, String accessUrl) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate.update("""
                INSERT INTO item_container (user_id, attach_type, created_at, updated_at)
                VALUES (?, 'PRODUCT', ?, ?)
                """, author.id(), now, now);
        Long containerId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("""
                INSERT INTO item_resource (item_container_id, size, original_file_name, item_key, access_url, created_at, updated_at)
                VALUES (?, 1024, 'photo.jpg', ?, ?, ?, ?)
                """, containerId, "key/" + accessUrl, accessUrl, now, now);
        jdbcTemplate.update("""
                INSERT INTO post_product (post_id, item_container_id, name, display_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, postId, containerId, name, (byte) displayOrder, now, now);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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

    private List<String> thumbnailsOf(String url) throws Exception {
        JSONArray urls = JsonPath.read(read(url), "$.returnObject.content[*].thumbnailUrl");
        return urls.stream().map(u -> (String) u).toList();
    }

    /**
     * 첫 항목의 정수 필드를 목록에서 읽는다. 대조 테스트가 조각에 한 건만 두므로 [0] 이다.
     *
     * <p>반환형을 {@code int} 로 못박는다 — 타입 변수로 두면 호출자가 값을 쓰는 자리에서
     * AssertJ 의 {@code assertThat(IntPredicate)} 와 {@code assertThat(Predicate<T>)} 가
     * 모두 후보가 되어 컴파일이 모호해진다.
     */
    private int firstItem(String url, String path) throws Exception {
        Number value = JsonPath.read(read(url), "$.returnObject.content[0]." + path);
        return value.intValue();
    }

    /** 게시글 상세의 정수 필드. 활동 목록과 값을 대조할 때 쓴다. */
    private int detail(Long postId, String path) throws Exception {
        MvcResult result = mockMvc.perform(get("/posts/{id}", postId)
                        .header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andReturn();
        Number value = JsonPath.read(
                result.getResponse().getContentAsString(), "$.returnObject." + path);
        return value.intValue();
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
