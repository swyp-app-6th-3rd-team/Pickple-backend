package app.pickple.post.controller;

import app.pickple.auth.domain.SocialProvider;
import app.pickple.auth.domain.User;
import app.pickple.auth.domain.UserStore;
import app.pickple.auth.service.JwtService;
import app.pickple.item.domain.AttachType;
import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemContainerStore;
import app.pickple.item.domain.ItemResource;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.support.IntegrationTest;
import app.pickple.vote.service.VoteService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게시글 상세 조회 API (이슈 #20) 의 완료 판정을 실제 MySQL 로 확인한다.
 *
 * <p>단위 테스트로는 잡히지 않는 것만 여기서 본다 — 응답 스키마가 유형별로 갈리는지,
 * 감춘 필드가 <b>정말 직렬화에서 빠지는지</b>(전역 설정이 없어 애노테이션이 유일한 장치다),
 * 쿼리 횟수가 상품·사진 수에 따라 늘지 않는지.
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class PostDetailIT {

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
    private VoteService voteService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MockMvc mockMvc;
    private User author;
    private User voter;
    private String voterToken;
    private String authorToken;
    private long seed;
    private int nicknameSequence;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        seed = System.nanoTime();
        author = saveUser("detail-author-" + seed, "글쓴이");
        voter = saveUser("detail-voter-" + seed, "투표자");
        authorToken = jwtService.createAccessToken(author);
        voterToken = jwtService.createAccessToken(voter);
    }

    // --- 완료 판정 1: 일반 게시글에 투표 영역이 없다 (R-04) ------------------

    @Test
    @DisplayName("일반 게시글 응답에는 투표 영역이 없다 (R-04)")
    void generalPostHasNoVoteSection() throws Exception {
        // 완료 판정: "일반 게시글 응답에 투표 영역이 없음".
        // 일반 게시글은 선택지를 갖지 않고(R-04) 상품도 없다(R-02) — "빈 배열" 이 아니라
        // 투표라는 기능 자체가 없으므로 섹션을 통째로 null 로 준다 (ADR-0046).
        Long postId = saveGeneralPost("그냥 잡담").id();
        flush();

        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.returnObject.id").value(postId))
                .andExpect(jsonPath("$.returnObject.type").value("GENERAL"))
                // 섹션이 통째로 없다. 상품·선택지가 "빈 배열" 로도 나오지 않는다.
                .andExpect(jsonPath("$.returnObject.vote").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.products").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options").doesNotExist());
    }

    @Test
    @DisplayName("투표 게시글은 선택지를 정확히 둘 준다 (R-04)")
    void votingPostHasExactlyTwoOptions() throws Exception {
        Long agreeId = saveAgreePost("가방 살까", 3).id();
        Long abId = saveAbPost("A 냐 B 냐").id();
        flush();

        // 찬반 — 상품 1개(R-02), 선택지는 라벨형.
        mockMvc.perform(get("/posts/{id}", agreeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.type").value("AGREE"))
                .andExpect(jsonPath("$.returnObject.vote.products.length()").value(1))
                .andExpect(jsonPath("$.returnObject.vote.options.length()").value(2))
                .andExpect(jsonPath("$.returnObject.vote.options[0].label").value("사자"))
                .andExpect(jsonPath("$.returnObject.vote.options[1].label").value("말자"))
                // 찬반 선택지는 상품을 가리키지 않는다.
                .andExpect(jsonPath("$.returnObject.vote.options[0].productId").doesNotExist());

        // A/B — 상품 2개(R-02), 선택지는 각 상품을 가리킨다.
        mockMvc.perform(get("/posts/{id}", abId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.type").value("A_B"))
                .andExpect(jsonPath("$.returnObject.vote.products.length()").value(2))
                .andExpect(jsonPath("$.returnObject.vote.options.length()").value(2))
                // A/B 선택지는 라벨이 없고 상품을 가리킨다.
                .andExpect(jsonPath("$.returnObject.vote.options[0].label").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[0].productId").exists());
    }

    // --- 완료 판정 2: 투표한 사용자는 득표율을 본다 -------------------------

    @Test
    @DisplayName("투표한 사용자는 재조회 시 득표율을 받는다")
    void votedUserSeesPercentage() throws Exception {
        // 완료 판정: "이미 투표한 사용자는 응답에 득표율이 포함됨 → 투표 후 재조회 시 비율 필드 존재".
        Post post = saveAgreePost("살까 말까", 1);
        Long postId = post.id();
        Long chosen = optionIdAt(postId, 1);
        voteService.castOrChange(postId, chosen, voter.id());
        flush();

        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(voterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.vote.voted").value(true))
                .andExpect(jsonPath("$.returnObject.vote.selectedOptionId").value(chosen))
                .andExpect(jsonPath("$.returnObject.vote.voterCount").value(1))
                // 두 선택지 모두 집계가 보인다 — 내가 고른 쪽만이 아니다.
                .andExpect(jsonPath("$.returnObject.vote.options[0].voteCount").value(1))
                .andExpect(jsonPath("$.returnObject.vote.options[0].percentage").value(100))
                .andExpect(jsonPath("$.returnObject.vote.options[1].voteCount").value(0))
                .andExpect(jsonPath("$.returnObject.vote.options[1].percentage").value(0));
    }

    @Test
    @DisplayName("득표율은 투표 API 응답과 같은 값이다")
    void percentageMatchesVoteApi() throws Exception {
        // 같은 게이지를 두 경로가 그린다 (ADR-0046). 반올림이 다르면 투표하자마자
        // 게이지 폭이 미세하게 달라지므로 두 응답의 값을 직접 대조한다.
        Post post = saveAgreePost("반올림 대조", 1);
        Long postId = post.id();
        User second = saveUser("detail-voter2-" + seed, "투표자2");
        User third = saveUser("detail-voter3-" + seed, "투표자3");

        // 3명 중 1명 — 33.33% 라 정수 반올림 경계에 걸린다.
        voteService.castOrChange(postId, optionIdAt(postId, 1), voter.id());
        voteService.castOrChange(postId, optionIdAt(postId, 2), second.id());
        VoteService.VoteResult result =
                voteService.castOrChange(postId, optionIdAt(postId, 2), third.id());
        flush();

        int firstFromVoteApi = result.options().stream()
                .filter(option -> option.displayOrder() == 1)
                .findFirst().orElseThrow().percentage();

        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(voterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.vote.voterCount").value(3))
                .andExpect(jsonPath("$.returnObject.vote.options[0].percentage")
                        .value(firstFromVoteApi));
    }

    // --- QA 보강: A/B 투표 후 productId·percentage 동시 노출 ---------------

    @Test
    @DisplayName("A/B 게시글에 투표한 사용자는 재조회 시 productId 와 percentage 를 함께 받는다")
    void votedUserSeesProductIdAndPercentageOnAbPost() throws Exception {
        // 기존 votingPostHasExactlyTwoOptions 는 A/B 의 미투표 상태(productId 만 존재)만 봤다.
        // A/B 는 선택지가 상품을 가리켜 경로가 다르므로, 투표 후에는 두 값이 "함께" 나오는지
        // 별도로 확인해야 한다 — productId 매핑과 percentage 계산이 서로 다른 코드 경로다.
        Post post = saveAbPost("A 살까 B 살까");
        Long postId = post.id();
        Long chosen = optionIdAt(postId, 1);
        voteService.castOrChange(postId, chosen, voter.id());
        flush();

        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(voterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.type").value("A_B"))
                .andExpect(jsonPath("$.returnObject.vote.voted").value(true))
                .andExpect(jsonPath("$.returnObject.vote.selectedOptionId").value(chosen))
                // 선택한 선택지 — productId 와 득표율이 함께 있어야 한다.
                .andExpect(jsonPath("$.returnObject.vote.options[0].productId").exists())
                .andExpect(jsonPath("$.returnObject.vote.options[0].voteCount").value(1))
                .andExpect(jsonPath("$.returnObject.vote.options[0].percentage").value(100))
                // 선택하지 않은 선택지도 productId 는 그대로, 득표율은 0.
                .andExpect(jsonPath("$.returnObject.vote.options[1].productId").exists())
                .andExpect(jsonPath("$.returnObject.vote.options[1].voteCount").value(0))
                .andExpect(jsonPath("$.returnObject.vote.options[1].percentage").value(0));
    }

    // --- QA 보강: 재투표(R-22) 후 상세 조회 ---------------------------------

    @Test
    @DisplayName("재투표하면 상세 조회에서 selectedOptionId 는 바뀌고 voterCount 는 늘지 않는다 (R-22)")
    void revoteChangesSelectionWithoutIncreasingVoterCount() throws Exception {
        Post post = saveAgreePost("재투표 검증", 1);
        Long postId = post.id();
        Long first = optionIdAt(postId, 1);
        Long second = optionIdAt(postId, 2);

        voteService.castOrChange(postId, first, voter.id());
        flush();
        voteService.castOrChange(postId, second, voter.id());
        flush();

        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(voterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.vote.voted").value(true))
                // 선택이 바뀐 뒤 값을 따라가야 한다 — 첫 투표 값이 남아 있으면 안 된다.
                .andExpect(jsonPath("$.returnObject.vote.selectedOptionId").value(second))
                // 사람 수는 여전히 1 — 재투표가 인원을 늘리지 않는다 (R-22).
                .andExpect(jsonPath("$.returnObject.vote.voterCount").value(1))
                .andExpect(jsonPath("$.returnObject.vote.options[0].voteCount").value(0))
                .andExpect(jsonPath("$.returnObject.vote.options[1].voteCount").value(1))
                .andExpect(jsonPath("$.returnObject.vote.options[1].percentage").value(100));
    }

    // --- QA 보강: 로그인했지만 남의 글인 경우의 mine ------------------------

    @Test
    @DisplayName("로그인 사용자가 남의 글을 조회하면 mine 이 false 다")
    void mineIsFalseForOtherUsersPost() throws Exception {
        // 기존 테스트는 작성자 본인(true, exposesAuthorInfo)과 게스트(false, guestSeesNoTally)
        // 만 봤다. "로그인했지만 남의 글" 조합이 비어 있었다 — mine 판정이 uid 비교가 아니라
        // 예를 들어 "토큰 존재 여부" 로 잘못 구현돼도 그 두 케이스만으로는 못 잡는다.
        Long postId = saveGeneralPost("남의 글").id();
        flush();

        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(voterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.authorId").value(author.id()))
                .andExpect(jsonPath("$.returnObject.mine").value(false));
    }

    // --- QA 보강: authorRanking 이 아직 산정되지 않았으면 null 그대로 -------

    @Test
    @DisplayName("배치가 아직 순위를 매기지 않은 작성자는 authorRanking 이 null 로 그대로 실린다 (ADR-0028)")
    void authorRankingIsNullWhenNotYetComputed() throws Exception {
        // ADR-0028·ADR-0046 열린 질문: 목록은 null 을 그대로 싣는다. 상세도 같아야 한다.
        // 신규 유저는 배치가 돌기 전이라 users.ranking 이 null 이다 — 지어낸 0 이나
        // 필드 부재가 아니라 "null 이 그대로 응답에 실리는지" 를 직접 확인한다.
        Long rankingIsNull = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND ranking IS NULL", Long.class, author.id());
        assertThat(rankingIsNull).as("픽스처 전제: 신규 유저는 랭킹 배치 전이라 ranking 이 null 이어야 한다").isEqualTo(1L);

        Long postId = saveGeneralPost("랭킹 미산정 작성자").id();
        flush();

        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                // 필드가 빠지는 것이 아니라 키는 있고 값이 null 이어야 한다 (목록과 동일 표현).
                .andExpect(jsonPath("$.returnObject").value(
                        org.hamcrest.Matchers.hasKey("authorRanking")))
                .andExpect(jsonPath("$.returnObject.authorRanking").value(org.hamcrest.Matchers.nullValue()));
    }

    // --- QA 보강: 삭제된 게시글에 투표 이력이 남아 있는 경우 ----------------

    @Test
    @DisplayName("투표 이력이 있는 게시글을 삭제해도 조회하면 여전히 404 다 (LEFT JOIN이 행을 되살리지 않는다)")
    void deletedPostWithVoteHistoryStillReturns404() throws Exception {
        // DETAIL 쿼리는 내 투표를 LEFT JOIN 한다. 삭제 필터(p.deleted_at IS NULL)가
        // post 테이블 자체에 걸려 있어 이론상 안전하지만, 조인 조건이 잘못 바뀌어
        // "투표 행이 있으면 부모 필터를 우회" 하는 식으로 회귀할 위험을 봉인해 둔다.
        Post post = saveAgreePost("삭제 예정 + 투표 이력", 1);
        Long postId = post.id();
        voteService.castOrChange(postId, optionIdAt(postId, 1), voter.id());
        flush();

        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(voterToken)))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE post SET deleted_at = NOW() WHERE id = ?", postId);
        flush();

        // 투표 이력을 남긴 본인 토큰으로 조회해도 투표 행이 게시글을 되살리지 않는다.
        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(voterToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- 완료 판정 3: 미투표자에게 득표율이 노출되지 않는다 -----------------

    @Test
    @DisplayName("투표하지 않은 회원에게는 득표 수와 득표율이 둘 다 없다")
    void unvotedUserSeesNoTally() throws Exception {
        // 완료 판정: "아직 투표하지 않은 사용자는 득표율이 노출되지 않음 → 해당 필드 부재".
        //
        // 득표 수와 득표율을 <b>둘 다</b> 본다. 하나만 빼면 선택지가 정확히 둘이고(R-04)
        // 1인 1표라(R-09) `나머지 = voterCount − 준 값` 으로 완전히 복원된다 (ADR-0046).
        Post post = saveAgreePost("미투표 조회", 1);
        Long postId = post.id();
        voteService.castOrChange(postId, optionIdAt(postId, 1), author.id());
        flush();

        // 글쓴이가 투표했고, voter 는 아직 투표하지 않았다.
        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(voterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.vote.voted").value(false))
                .andExpect(jsonPath("$.returnObject.vote.selectedOptionId").doesNotExist())
                // 총 인원은 준다 — 총계만으로는 선택지별 비율이 나오지 않는다 (§6.3).
                .andExpect(jsonPath("$.returnObject.vote.voterCount").value(1))
                .andExpect(jsonPath("$.returnObject.vote.options[0].voteCount").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[0].percentage").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[1].voteCount").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[1].percentage").doesNotExist())
                // 감춰도 선택지 자체는 보인다 — 버튼을 그려야 하기 때문이다.
                .andExpect(jsonPath("$.returnObject.vote.options.length()").value(2))
                .andExpect(jsonPath("$.returnObject.vote.options[0].optionId").exists());
    }

    @Test
    @DisplayName("게스트에게도 득표 수와 득표율이 둘 다 없다 (R-11)")
    void guestSeesNoTally() throws Exception {
        // 게스트는 투표 이력을 가질 수 없으므로(R-11) 미투표로 답하는 것이 정확한 답이다.
        Post post = saveAgreePost("게스트 조회", 1);
        Long postId = post.id();
        voteService.castOrChange(postId, optionIdAt(postId, 1), author.id());
        flush();

        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.vote.voted").value(false))
                .andExpect(jsonPath("$.returnObject.vote.options[0].voteCount").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[0].percentage").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[1].voteCount").doesNotExist())
                .andExpect(jsonPath("$.returnObject.vote.options[1].percentage").doesNotExist())
                // 게스트는 남의 글이므로 mine 이 거짓이다.
                .andExpect(jsonPath("$.returnObject.mine").value(false));
    }

    // --- 완료 판정 4: 삭제된 게시글은 404 ----------------------------------

    @Test
    @DisplayName("삭제된 게시글을 조회하면 404 다")
    void deletedPostReturns404() throws Exception {
        // 완료 판정: "삭제된 게시글 조회 시 404 → 삭제 후 조회".
        // 소프트 삭제라 행은 남지만 화면에는 없는 글이다. 목록이 deleted_at IS NULL 로
        // 거르는 것과 같은 기준을 쓴다.
        Long postId = saveGeneralPost("곧 지울 글").id();
        flush();

        mockMvc.perform(get("/posts/{id}", postId)).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE post SET deleted_at = NOW() WHERE id = ?", postId);
        flush();

        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 게시글을 조회하면 404 다")
    void missingPostReturns404() throws Exception {
        mockMvc.perform(get("/posts/{id}", 99_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- 완료 판정 5: N+1 이 없다 ------------------------------------------

    @Test
    @DisplayName("쿼리 수가 상품 수와 사진 수에 따라 늘지 않는다")
    void queryCountIsFlat() throws Exception {
        // 완료 판정: "조회 시 N+1 쿼리가 발생하지 않음 → 실행 쿼리 수 측정(상품·선택지·작성자 포함)".
        //
        // 절대 횟수를 못박는 대신 <b>변하지 않음</b>을 본다. 상품이 하나(찬반)든 둘(A/B)이든,
        // 사진이 한 장이든 세 장이든 문장 수가 같아야 팬아웃이 없다는 뜻이다.
        Long agreeOnePhoto = saveAgreePost("사진 1장", 1).id();
        Long agreeThreePhotos = saveAgreePost("사진 3장", 3).id();
        Long ab = saveAbPost("상품 2개").id();
        Long general = saveGeneralPost("상품 없음").id();
        flush();

        long onePhoto = countStatements("/posts/" + agreeOnePhoto);
        long threePhotos = countStatements("/posts/" + agreeThreePhotos);
        long twoProducts = countStatements("/posts/" + ab);
        long noProducts = countStatements("/posts/" + general);

        // 사진이 3배가 돼도 문장 수가 같다 — 대표 사진이 스칼라 서브쿼리라 행이 불어나지 않는다.
        assertThat(threePhotos)
                .as("사진 수가 늘어도 쿼리가 늘면 N+1 이다 (1장 %d회 → 3장 %d회)", onePhoto, threePhotos)
                .isEqualTo(onePhoto);

        // 상품이 둘이어도 같다 — 상품을 한 문장으로 읽는다.
        assertThat(twoProducts)
                .as("상품 수가 늘어도 쿼리가 늘면 N+1 이다 (1개 %d회 → 2개 %d회)", onePhoto, twoProducts)
                .isEqualTo(onePhoto);

        // 일반 게시글은 상품·선택지를 조회하지 않으므로 오히려 적다.
        assertThat(noProducts)
                .as("일반 게시글은 상품·선택지 조회를 건너뛴다")
                .isLessThan(onePhoto);

        // 절대값도 기록한다 — 횟수가 고정이어도 값이 크면 그 자체가 문제다.
        assertThat(onePhoto)
                .as("상세 한 건의 실행 문장 수 (본문+작성자+내투표 / 상품 / 선택지)")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("투표 이력 조회가 데이터 문장을 늘리지 않는다")
    void viewerVoteAddsNoQuery() throws Exception {
        // "이미 투표했는가" 는 본문 질의의 LEFT JOIN 으로 얹힌다. 따로 조회하면
        // 인증 사용자마다 데이터 문장이 하나 더 붙는다.
        //
        // <b>인증 요청은 게스트보다 문장이 정확히 하나 많다.</b> 그 하나는 이 API 가 아니라
        // AnonymousDemotionFilter 가 낸다 — 탈퇴한 신원을 익명으로 강등하기 위해
        // 계정 상태를 확인한다(ADR-0035). 게스트는 신원이 없어 그 조회를 건너뛴다.
        // 인가 비용이지 N+1 이 아니다. 요청당 상수이고 상품·선택지·사진 수와 무관하다.
        Post post = saveAgreePost("투표 이력 조인", 1);
        Long postId = post.id();
        voteService.castOrChange(postId, optionIdAt(postId, 1), voter.id());
        flush();

        long asGuest = countStatements("/posts/" + postId);
        long asVoter = countStatements("/posts/" + postId, voterToken);

        assertThat(asVoter - asGuest)
                .as("투표 이력 조회가 별도 문장으로 붙으면 차이가 2 가 된다 "
                        + "(게스트 %d회 → 투표자 %d회. 차이 1 = 인가 관문의 계정 상태 확인)",
                        asGuest, asVoter)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("인증 요청도 상품·사진 수와 무관하게 쿼리 수가 같다")
    void queryCountIsFlatForAuthenticatedViewer() throws Exception {
        // 위 테스트가 허용한 "인가 관문의 한 문장" 이 상수인지 확인한다.
        // 상수가 아니라 데이터에 비례하면 그때는 정말 N+1 이다.
        Long onePhoto = saveAgreePost("인증 사진 1장", 1).id();
        Long threePhotos = saveAgreePost("인증 사진 3장", 3).id();
        Long ab = saveAbPost("인증 상품 2개").id();
        flush();

        long one = countStatements("/posts/" + onePhoto, voterToken);
        long three = countStatements("/posts/" + threePhotos, voterToken);
        long two = countStatements("/posts/" + ab, voterToken);

        assertThat(three).as("사진 1장 %d회 → 3장 %d회", one, three).isEqualTo(one);
        assertThat(two).as("상품 1개 %d회 → 2개 %d회", one, two).isEqualTo(one);
    }

    // --- 그 밖의 계약 -------------------------------------------------------

    @Test
    @DisplayName("작성자 정보와 상대 시각이 응답에 실린다 (§6.2)")
    void exposesAuthorInfo() throws Exception {
        Long postId = saveGeneralPost("작성자 정보").id();
        // 3시간 전으로 못박아 상대 시각 문구를 단정할 수 있게 한다.
        jdbcTemplate.update("UPDATE post SET created_at = DATE_SUB(NOW(), INTERVAL 3 HOUR) WHERE id = ?",
                postId);
        flush();

        mockMvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(authorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.authorId").value(author.id()))
                .andExpect(jsonPath("$.returnObject.authorNickname").exists())
                // 가입 시 LV.1 이 기본이라 등급은 비지 않는다 (R-16).
                .andExpect(jsonPath("$.returnObject.authorGradeLevel").value(1))
                .andExpect(jsonPath("$.returnObject.authorGradeName").value("LV.1"))
                .andExpect(jsonPath("$.returnObject.createdAt").exists())
                .andExpect(jsonPath("$.returnObject.createdAgo").value("3시간 전"))
                // 글쓴이 본인이 조회했다.
                .andExpect(jsonPath("$.returnObject.mine").value(true));
    }

    @Test
    @DisplayName("상품 사진은 가장 처음 등록한 1장이다 (R-03)")
    void exposesFirstProductPhoto() throws Exception {
        // 찬반 상품은 사진을 최대 3장 갖는다. 명세 §6.3 은 "가장 처음 등록한 사진 1장" 이다.
        Post post = saveAgreePost("사진 순서", 3);
        Long postId = post.id();
        flush();

        String firstUrl = jdbcTemplate.queryForObject("""
                SELECT ir.access_url
                  FROM post_product pp
                  JOIN item_resource ir ON ir.item_container_id = pp.item_container_id
                 WHERE pp.post_id = ?
                 ORDER BY ir.id ASC
                 LIMIT 1
                """, String.class, postId);

        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.vote.products.length()").value(1))
                .andExpect(jsonPath("$.returnObject.vote.products[0].imageUrl").value(firstUrl));
    }

    @Test
    @DisplayName("게스트도 상세를 볼 수 있다 (§6.2)")
    void guestCanReadDetail() throws Exception {
        // 목록이 공개인데 상세가 막히면 게스트가 카드를 눌러 갈 곳이 없다.
        Long postId = saveGeneralPost("게스트 열람").id();
        flush();

        mockMvc.perform(get("/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnObject.id").value(postId));
    }

    // --- 픽스처 -------------------------------------------------------------

    private User saveUser(String providerId, String name) {
        User saved = userStore.save(new User(SocialProvider.GOOGLE, providerId, null, name));
        jdbcTemplate.update("UPDATE users SET nickname = ? WHERE id = ?", uniqueNickname(name), saved.id());
        return saved;
    }

    private String uniqueNickname(String name) {
        String suffix = Long.toString(nicknameSequence++, 36) + Long.toString(seed % 1296, 36);
        int room = Math.max(0, 5 - suffix.length());
        return name.substring(0, Math.min(name.length(), room)) + suffix;
    }

    private Post saveGeneralPost(String title) {
        return postStore.save(new Post(author.id(), PostType.GENERAL, PostCategory.ETC, title, "설명"));
    }

    private Post saveAgreePost(String title, int photoCount) {
        Post post = new Post(author.id(), PostType.AGREE, PostCategory.ETC, title, "설명")
                .addProduct(new PostProduct(newContainer("agree", photoCount), "상품", 10_000L,
                        "https://shop.test/1", 1))
                .addOption(PostOption.ofLabel("사자", 1))
                .addOption(PostOption.ofLabel("말자", 2));
        return postStore.save(post);
    }

    private Post saveAbPost(String title) {
        Post post = new Post(author.id(), PostType.A_B, PostCategory.ETC, title, "설명")
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
                    "https://cdn.test/" + tag + "-" + i + "-" + unique));
        }
        return containerStore.save(container).id();
    }

    private Long optionIdAt(Long postId, int displayOrder) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM post_option WHERE post_id = ? AND display_order = ?",
                Long.class, postId, displayOrder);
    }

    private long countStatements(String url) throws Exception {
        return countStatements(url, null);
    }

    /** 이 요청이 실제로 실행한 SQL 문장 수. 캐시가 섞이지 않게 flush·clear 후 센다. */
    private long countStatements(String url, String token) throws Exception {
        flush();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        var request = get(url);
        if (token != null) {
            request = request.header("Authorization", bearer(token));
        }
        mockMvc.perform(request).andExpect(status().isOk());

        return statistics.getPrepareStatementCount();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
