package app.pickple.post.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import app.pickple.common.ScrollResponse;
import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostQueryStore;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "Post", description = "게시글 작성 · 목록 · 상세")
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

        static PostListItem from(PostQueryStore.PostListView view) {
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

        static RandomVoteCard from(PostQueryStore.RandomPostView view) {
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

        static RandomVoteProduct from(PostQueryStore.RandomProductView product) {
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
                PostQueryStore.RandomOptionView option, long voterCount, boolean participated) {
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
