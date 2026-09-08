package app.pickple.post.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.common.ScrollResponse;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import app.pickple.post.service.PostService;
import app.pickple.vote.domain.VotePercentage;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "Post", description = "게시글 작성 · 수정 · 삭제 · 목록 · 상세")
@RestController
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @Operation(
            summary = "게시글 작성",
            description = "업로드 API가 반환한 itemContainerId를 상품에 연결하고 유형별 상품·사진·선택지 규칙을 검증합니다.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PostCreateResponse> create(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Valid @RequestBody PostCreateRequest request) {
        Post post = postService.create(userId, request.toCommand());
        return ApiResponse.of(ResponseCode.CREATED, PostCreateResponse.from(post));
    }

    /**
     * 게시글 수정 (§6.1 `[더보기]` · R-33). 작성자만.
     *
     * <p>요청 스키마가 카테고리·제목·설명 셋뿐이다. 상품과 유형은 <b>계약에 없다</b> — 편집 화면이
     * 작성 폼을 재사용해 폼 전체를 보내더라도 그 값은 바인딩되지 않고 저장 값이 바뀌지 않는다 (ADR-0047).
     * 응답이 저장된 값을 되돌려 주므로 클라이언트가 결과를 확인한다.
     */
    @Operation(summary = "게시글 수정",
            description = "작성자만. 카테고리·주제/제목·설명만 바꿀 수 있다(R-33). "
                    + "상품 정보(상품명·가격·URL·사진)와 유형은 이 API 로 바꿀 수 없으며, 함께 보내도 무시된다. "
                    + "찬반 게시글의 제목은 상품명이라 다른 값을 보내면 400. "
                    + "없는 필드는 유지, 설명의 빈 문자열은 비움. "
                    + "없거나 삭제된 게시글은 404, 남의 글은 403.")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/posts/{id}")
    public ApiResponse<PostUpdateResponse> update(
            @Parameter(description = "게시글 식별자") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Valid @RequestBody PostUpdateRequest request) {
        return ApiResponse.success(
                PostUpdateResponse.from(postService.update(id, userId, request.toCommand())));
    }

    /**
     * 게시글 삭제 (§6.1 `[더보기]`). 작성자만.
     *
     * <p>소프트 삭제다. 행과 상품·선택지·투표·댓글은 남고 조회에서만 사라진다. 그 뒤의 투표·댓글은
     * {@code ActivePostGuard} 가 막는다. 응답 모양은 댓글 삭제와 같다.
     */
    @Operation(summary = "게시글 삭제",
            description = "작성자만. 소프트 삭제라 이후 조회에서 404 이고 투표·댓글이 거절된다. "
                    + "지운 글의 이미지는 다른 게시글에 재사용할 수 없다. 없거나 이미 삭제된 게시글은 404, 남의 글은 403.")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/posts/{id}")
    public ApiResponse<Void> delete(
            @Parameter(description = "게시글 식별자") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        postService.delete(id, userId);
        return ApiResponse.success(null);
    }

    /**
     * 게시글 목록. 게스트도 부를 수 있는 진입 화면이라 인증을 요구하지 않는다.
     *
     * <p>게시글이 없으면 빈 배열을 준다. "아직 없는 게시글" 안내와 예시 카드는
     * 화면의 빈 상태(empty state) 이므로 서버가 존재하지 않는 게시글을 지어내지 않는다 —
     * 지어내면 그 카드를 탭했을 때 갈 곳이 없다.
     */
    @Operation(summary = "게시글 목록 조회",
            description = "카테고리 필터와 정렬(최신순·인기순), 커서 기반 무한 스크롤. 게시글이 없으면 빈 배열이다.")
    @GetMapping("/posts")
    public ApiResponse<ScrollResponse<PostListItem>> findAll(
            @Parameter(description = "없으면 전체") @RequestParam(required = false) PostCategory category,
            @Parameter(description = "LATEST(기본) | POPULAR") @RequestParam(required = false) String sort,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 첫 조각")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "조각 크기. 기본 10") @RequestParam(required = false) Integer size) {

        return ApiResponse.success(ScrollResponse.of(
                postService.findSlice(category, sort, cursor, size), PostListItem::from));
    }

    /**
     * 홈 화면의 인기 투표 게시글 Top 10 (§2.4). 게스트도 부르는 첫 화면이라 인증이 없다.
     *
     * <p><b>목록 조회와 같은 쿼리를 탄다.</b> "커서 없는 인기순 첫 조각" 이 곧 상위 10건이라
     * {@code GET /posts?sort=POPULAR} 와 실행되는 SQL 이 같다. 별도 엔드포인트로 둔 이유는
     * 성능이 아니라 <b>계약</b>이다 — 홈은 정확히 열 건만 필요하고 더 스크롤하지 않으므로,
     * 커서 봉투({@code nextCursor}·{@code hasNext})를 주면 클라이언트가 이어받을 수 있다고
     * 읽는다. 여기서는 봉투를 벗기고 배열만 준다. 더 보기는 목록 API 로 간다.
     *
     * <p>게시글이 0건이면 <b>빈 배열</b>이다 — 목록과 같은 판단이다.
     */
    @Operation(summary = "인기 게시글 Top 10 조회",
            description = "홈 화면용. 인기순 상위 10건을 커서 없이 고정으로 준다. "
                    + "인기 점수는 투표 인원과 댓글 인원의 합이다. 게시글이 없으면 빈 배열이다.")
    @GetMapping("/posts/popular")
    public ApiResponse<List<PostListItem>> findPopular() {
        return ApiResponse.success(
                postService.findPopularTop().stream().map(PostListItem::from).toList());
    }

    /**
     * 홈 화면의 랜덤 투표 카드 (§2.1 · §2.2).
     *
     * <p>게스트도 조회할 수 있지만, 유효한 액세스 토큰을 함께 보내면 현재 사용자의
     * 투표를 같은 쿼리에서 찾아 이미 참여한 카드에만 결과를 싣는다.
     */
    @Operation(summary = "랜덤 투표 카드 조회",
            description = "AGREE 또는 A_B 한 유형을 시드 기반 임의 순서로 조회한다. "
                    + "커서를 이어 쓰면 한 순회 안에서 카드가 중복되지 않는다. "
                    + "로그인 사용자가 이미 투표한 카드에만 선택과 득표 결과가 포함된다.")
    @GetMapping("/posts/random")
    public ApiResponse<ScrollResponse<RandomVoteCard>> findRandomVoteCards(
            @Parameter(
                    description = "AGREE | A_B",
                    required = true,
                    schema = @Schema(allowableValues = {"AGREE", "A_B"}))
            @RequestParam PostType type,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 새 임의 순서의 첫 조각")
            @RequestParam(required = false) String cursor,
            @Parameter(hidden = true) @CurrentUser Long viewerId) {

        return ApiResponse.success(ScrollResponse.of(
                postService.findRandomSlice(type, cursor, viewerId), RandomVoteCard::from));
    }

    /**
     * 게시글 상세 (§6.2·§6.3). 목록에서 카드를 탭하면 여기로 온다.
     *
     * <p>게스트도 커뮤니티를 둘러보다 들어오므로 인증을 요구하지 않는다. 다만
     * "이미 투표했는가" 는 신원이 있어야 답할 수 있어 <b>인증을 선택적으로 받는다</b> —
     * {@code @CurrentUser} 가 비로그인 요청에 {@code null} 을 넣어주므로 별도 분기가 없다.
     * 게스트는 투표 이력을 가질 수 없어(R-11) 언제나 미투표로 답한다.
     *
     * <p>응답 모양은 ADR-0046 이 정한다. 유형별로 타입을 쪼개지 않고 한 모양으로 두되,
     * 투표 영역만 통째로 {@code null} 이 될 수 있는 중첩 객체다 — 일반 게시글에는
     * 투표라는 기능 자체가 없기 때문이다 (R-04).
     */
    @Operation(summary = "게시글 상세 조회",
            description = "작성자 정보와 게시물 정보를 유형별로 준다. 일반 게시글은 vote 가 null 이다(R-04). "
                    + "투표한 사용자에게만 선택지별 득표 수와 득표율을 준다 — "
                    + "미투표자와 게스트에게는 두 필드가 응답에서 빠진다. "
                    + "없거나 삭제된 게시글은 404 다.")
    @GetMapping("/posts/{id}")
    public ApiResponse<PostDetailResponse> findOne(
            @Parameter(description = "게시글 식별자") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ApiResponse.success(PostDetailResponse.from(postService.findDetail(id, userId)));
    }

    /**
     * 게시글 상세 (§6.2·§6.3). 계약은 ADR-0046.
     *
     * <p><b>작성자는 평면, 투표만 중첩이다.</b> 작성자는 항상 있고 내부 컬렉션이 없어
     * 목록·댓글·랭킹과 같은 평면 표기를 쓰고, 투표는 유형에 따라 <b>통째로 사라지며</b>
     * 안에 컬렉션 둘을 가지므로 중첩 객체로 둔다.
     *
     * @param createdAgo    화면용 상대 시각. 서버가 만드는 이유는 ADR-0046 —
     *                      §6.2 와 §6.4 가 한 화면에서 같은 문구를 요구한다
     * @param authorNickname 탈퇴로 파기된 작성자는 {@code "알 수 없음"} 이다 (ADR-0040). 목록과 같은 표기다
     * @param authorRanking 작성자의 TOP 피커 순위. 배치가 매기기 전이거나 탈퇴한 회원이면
     *                      {@code null} 이다 — 0 을 지어내지 않는다 (ADR-0028).
     *                      목록과 같이 {@code null} 을 그대로 싣는다
     * @param vote          투표 영역. <b>일반 게시글은 {@code null}</b> 이다 (R-04)
     */
    public record PostDetailResponse(
            @Schema(description = "게시글 식별자") Long id,
            @Schema(description = "GENERAL | AGREE | A_B. 만들 때 정해지고 바뀌지 않는다(R-01)")
            PostType type,
            @Schema(description = "카테고리") PostCategory category,
            @Schema(description = "찬반=상품명, A/B=주제, 일반=제목") String title,
            @Schema(description = "설명. 입력이 선택이라 없을 수 있다") String description,
            @Schema(description = "작성 시각") LocalDateTime createdAt,
            @Schema(description = "화면용 상대 시각", example = "3시간 전") String createdAgo,
            @Schema(description = "댓글 건수. 댓글 단 사람 수가 아니다(R-25)") long commentCount,
            @Schema(description = "작성자 식별자") Long authorId,
            @Schema(description = "작성자 닉네임. 아직 설정하지 않았으면 소셜 이름을, 탈퇴했으면 '알 수 없음' 을 쓴다")
            String authorNickname,
            @Schema(description = "작성자 프로필 이미지. 등록하지 않았거나 탈퇴했으면 null")
            String authorProfileImageUrl,
            @Schema(description = "작성자 등급 레벨. 1~5") int authorGradeLevel,
            @Schema(description = "작성자 등급 명칭", example = "LV.2") String authorGradeName,
            @Schema(description = "작성자 TOP 피커 순위. 아직 산정되지 않았으면 null (최대 5분 지연)")
            Integer authorRanking,
            @Schema(description = "현재 요청자가 쓴 글인지. 게스트는 false") boolean mine,
            @Schema(description = "투표 영역. 일반 게시글은 null (R-04)") VoteSection vote) {

        static PostDetailResponse from(PostService.PostDetail detail) {
            PostStore.PostDetailView view = detail.view();
            return new PostDetailResponse(
                    view.id(),
                    view.type(),
                    view.category(),
                    view.title(),
                    view.description(),
                    view.createdAt(),
                    detail.createdAgo(),
                    view.commentCount(),
                    view.authorId(),
                    view.authorNickname(),
                    view.authorProfileImageUrl(),
                    view.authorGrade().level(),
                    view.authorGrade().displayName(),
                    view.authorRanking(),
                    detail.mine(),
                    detail.hasVoting() ? VoteSection.from(detail) : null);
        }
    }

    /**
     * 투표 영역 (§6.3). 일반 게시글에는 이 기능 자체가 없다 (R-04).
     *
     * <p><b>상품이 여기 안에 있는 이유</b> — 상품을 갖는 조건과 투표를 갖는 조건이
     * 완전히 같다({@code PostType.productCount() > 0} ≡ {@code hasVoting()}).
     * 밖에 두면 "투표는 없는데 상품은 빈 배열" 이라는 중복 표현이 생긴다.
     *
     * @param voted            이 게시글에 투표한 적이 있는가. 게스트는 언제나 거짓이다(R-11)
     * @param selectedOptionId 내가 고른 선택지. 아직 투표하지 않았으면 {@code null}
     * @param voterCount       투표한 <b>사람</b> 수. 한 사람이 선택을 바꿔도 늘지 않는다 (R-09·R-22).
     *                         미투표자에게도 준다 — 총계만으로는 선택지별 비율이 나오지 않는다
     */
    public record VoteSection(
            @Schema(description = "이 게시글에 투표한 적이 있는지. 게스트는 항상 false (R-11)")
            boolean voted,
            @Schema(description = "내가 고른 선택지. 아직 투표하지 않았으면 null")
            @JsonInclude(JsonInclude.Include.NON_NULL) Long selectedOptionId,
            @Schema(description = "투표한 사람 수. 한 사람이 선택을 바꿔도 늘지 않는다 (R-09·R-22)")
            long voterCount,
            @Schema(description = "상품. 찬반은 1개, A/B는 2개다 (R-02)") List<ProductItem> products,
            @Schema(description = "선택지. 정확히 둘이다 (R-04)") List<OptionItem> options) {

        static VoteSection from(PostService.PostDetail detail) {
            PostStore.PostDetailView view = detail.view();
            return new VoteSection(
                    view.voted(),
                    view.myOptionId(),
                    view.voterCount(),
                    view.products().stream().map(ProductItem::from).toList(),
                    detail.options().stream().map(OptionItem::from).toList());
        }
    }

    /**
     * 투표 대상 상품 (§6.3).
     *
     * @param imageUrl 상품 사진 1장. 찬반은 최대 3장 중 가장 처음 등록한 것이고,
     *                 A/B 는 상품마다 1장이라 그 한 장이다 (R-03)
     */
    public record ProductItem(
            @Schema(description = "상품 식별자") Long id,
            @Schema(description = "상품명") String name,
            @Schema(description = "가격. 입력이 선택이라 없을 수 있다") Long price,
            @Schema(description = "상품 URL. 입력이 선택이라 없을 수 있다") String linkUrl,
            @Schema(description = "상품 사진 1장. 찬반은 가장 처음 등록한 것, A/B는 상품마다 1장 (R-03)")
            String imageUrl,
            @Schema(description = "표시 순서. 1(A) 또는 2(B)") int displayOrder) {

        static ProductItem from(PostStore.PostDetailProduct product) {
            return new ProductItem(
                    product.id(),
                    product.name(),
                    product.price(),
                    product.linkUrl(),
                    product.imageUrl(),
                    product.displayOrder());
        }
    }

    /**
     * 선택지 하나 (§6.3). 투표한 사람에게는 그대로 결과 게이지가 된다.
     *
     * <p>{@code VoteController.OptionResponse} 와 같은 모양을 유지하되 {@code productId}
     * 하나가 더 있다 — 투표 직후에는 상품 카드가 이미 화면에 있지만, 상세는 A/B 에서
     * "이 버튼이 어느 상품 카드인가" 를 처음 연결해야 한다.
     *
     * @param voteCount  득표 수. <b>투표하지 않았으면 이 필드가 없다</b> (ADR-0046)
     * @param percentage 득표율. <b>투표하지 않았으면 이 필드가 없다.</b> 0 으로 채우지 않는다 —
     *                   "아직 볼 수 없음" 과 "정말 0표" 가 구분되지 않기 때문이다
     */
    public record OptionItem(
            @Schema(description = "선택지 식별자") Long optionId,
            @Schema(description = "찬반 선택지의 라벨. A/B는 null") String label,
            @Schema(description = "이 선택지가 가리키는 상품. 찬반은 null") Long productId,
            @Schema(description = "표시 순서. 1 또는 2") int displayOrder,

            // 미투표자에게 감추는 두 필드. 하나만 빼면 선택지가 정확히 둘이고(R-04)
            // 1인 1표라(R-09) 나머지가 역산되므로 반드시 함께 없어야 한다 (ADR-0046).
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "이 선택지의 득표 수. 투표하지 않았으면 이 필드가 없다")
            Long voteCount,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "정수 퍼센트. 반올림 때문에 두 값의 합이 100이 아닐 수 있다. "
                    + "투표하지 않았으면 이 필드가 없다")
            Integer percentage) {

        static OptionItem from(PostService.OptionTally tally) {
            return new OptionItem(
                    tally.optionId(),
                    tally.label(),
                    tally.productId(),
                    tally.displayOrder(),
                    tally.voteCount(),
                    tally.percentage());
        }
    }

    /**
     * 목록 한 줄 (§4.2).
     *
     * <p>유형마다 명세가 요구하는 필드가 다르다. <b>응답 스키마를 유형별로 쪼개지 않고
     * 한 모양으로 두되, 해당 없는 필드는 {@code null} 로 비운다.</b>
     * 쪼개면 클라이언트가 세 가지 파싱 분기를 갖게 되고, 목록은 세 유형이 섞여 내려오므로
     * 그 분기가 항목마다 필요해진다.
     *
     * @param voteCount     찬반·A/B 만. 일반 게시글은 투표가 없어 {@code null} 이다
     * @param thumbnailUrl  찬반=처음 등록한 사진, A/B=A 상품 사진, 일반={@code null}
     * @param authorRanking 작성자의 TOP 피커 순위. 배치가 매기기 전이거나 탈퇴한 회원이면
     *                      {@code null} 이다 — 0 이나 꼴찌 순위를 지어내지 않는다 (ADR-0028)
     */
    public record PostListItem(
            @Schema(description = "게시글 식별자") Long id,
            @Schema(description = "GENERAL | AGREE | A_B") PostType type,
            @Schema(description = "카테고리") PostCategory category,
            @Schema(description = "찬반=상품명, A/B=주제, 일반=제목") String title,
            @Schema(description = "설명") String description,
            @Schema(description = "댓글 건수") long commentCount,
            @Schema(description = "투표 인원. 일반 게시글은 null") Long voteCount,
            @Schema(description = "대표 상품 사진 1장. 일반 게시글은 null") String thumbnailUrl,
            @Schema(description = "작성 시각") LocalDateTime createdAt,
            @Schema(description = "작성자 식별자") Long authorId,
            @Schema(description = "작성자 닉네임") String authorNickname,
            @Schema(description = "작성자 TOP 피커 순위. 아직 산정되지 않았으면 null (최대 5분 지연)")
            Integer authorRanking) {

        static PostListItem from(PostStore.PostListView view) {
            return new PostListItem(
                    view.id(),
                    view.type(),
                    view.category(),
                    view.title(),
                    view.description(),
                    view.commentCount(),
                    view.type().hasVoting() ? view.voteCount() : null,
                    view.thumbnailUrl(),
                    view.createdAt(),
                    view.authorId(),
                    view.authorNickname(),
                    view.authorRanking());
        }
    }

    /** 홈 랜덤 투표 카드 한 장. */
    public record RandomVoteCard(
            @Schema(description = "게시글 식별자") Long id,
            @Schema(description = "AGREE | A_B", allowableValues = {"AGREE", "A_B"}) PostType type,
            @Schema(description = "찬반=상품명, A/B=주제") String title,
            @Schema(description = "설명") String description,
            @Schema(description = "투표한 사람 수") long voterCount,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "현재 사용자가 고른 선택지. 게스트·미투표자는 이 필드가 없다")
            Long selectedOptionId,
            @Schema(description = "찬반은 상품 1개, A/B는 표시 순서대로 상품 2개")
            List<RandomVoteProduct> products,
            @Schema(description = "표시 순서대로 정렬된 투표 선택지 2개")
            List<RandomVoteOption> options) {

        static RandomVoteCard from(PostStore.RandomPostView view) {
            boolean participated = view.selectedOptionId() != null;
            return new RandomVoteCard(
                    view.id(),
                    view.type(),
                    view.title(),
                    view.description(),
                    view.voterCount(),
                    view.selectedOptionId(),
                    view.products().stream().map(RandomVoteProduct::from).toList(),
                    view.options().stream()
                            .map(option -> RandomVoteOption.from(option, view.voterCount(), participated))
                            .toList());
        }
    }

    public record RandomVoteProduct(
            @Schema(description = "게시글 상품 식별자") Long productId,
            @Schema(description = "상품명") String name,
            @Schema(description = "표시 순서. 찬반은 1, A/B는 1 또는 2") int displayOrder,
            @Schema(description = "이 상품에서 가장 먼저 등록한 사진 URL") String imageUrl) {

        static RandomVoteProduct from(PostStore.RandomProductView product) {
            return new RandomVoteProduct(
                    product.id(), product.name(), product.displayOrder(), product.imageUrl());
        }
    }

    /** 찬반은 {@code label}, A/B는 {@code productId}가 선택지의 표시 내용을 정한다. */
    public record RandomVoteOption(
            @Schema(description = "투표 선택지 식별자") Long optionId,
            @Schema(description = "찬반 선택지 라벨. A/B는 null") String label,
            @Schema(description = "A/B 상품 식별자. 찬반은 null") Long productId,
            @Schema(description = "표시 순서. 1 또는 2") int displayOrder,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "이 선택지의 득표 수. 현재 사용자가 투표한 카드에만 존재")
            Long voteCount,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "정수 득표율. 현재 사용자가 투표한 카드에만 존재")
            Integer percentage) {

        static RandomVoteOption from(
                PostStore.RandomOptionView option, long voterCount, boolean participated) {
            return new RandomVoteOption(
                    option.id(),
                    option.label(),
                    option.productId(),
                    option.displayOrder(),
                    participated ? option.voteCount() : null,
                    participated ? VotePercentage.calculate(option.voteCount(), voterCount) : null);
        }
    }
}
