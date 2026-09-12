package app.pickple.activity.controller;

import app.pickple.activity.domain.ActivityQueryStore;
import app.pickple.activity.domain.ActivityType;
import app.pickple.activity.service.ActivityQueryService;
import app.pickple.auth.security.CurrentUser;
import app.pickple.common.ApiResponse;
import app.pickple.common.ScrollResponse;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostType;
import app.pickple.vote.domain.VotePercentage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이페이지의 내 활동 (기능명세 §7.2 · §7.4 · §9.1 · §9.2).
 *
 * <p><b>세 엔드포인트 모두 인증이 필요하다.</b> 명세는 게스트에게 "모든 갯수 표시를
 * 0개로 고정" 하라 적었지만, 같은 문단이 게스트에게 <b>"클릭 제한"</b> 도 함께 건다 —
 * 게스트는 활동 영역을 눌러 목록 화면으로 갈 수 없고, 프로필 자리에는
 * "로그인해주세요" 가 대신 놓인다. 즉 0 은 <b>로그인하지 않은 화면이 그리는
 * 플레이스홀더</b>이지 서버가 내려주는 값이 아니다.
 *
 * <p>서버가 게스트에게 0 을 내려주면 "활동이 없는 회원" 과 "로그인하지 않은 사람" 이
 * 같은 응답이 되어 화면이 둘을 구분하지 못한다. 401 은 그 구분을 화면에 돌려준다.
 */
@Tag(name = "Activity", description = "마이페이지 내 활동 요약 · 목록")
@RestController
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityQueryService activityQueryService;

    @Operation(summary = "내 활동 갯수 요약",
            description = "투표에 참여한 횟수, 댓글에 참여한 횟수, 올린 게시글 갯수를 한 번에 준다. "
                    + "앞의 둘은 건수가 아니라 사람 기준 참여 건수라, 재투표하거나 "
                    + "같은 글에 댓글을 여러 개 달아도 늘지 않는다(R-22 · R-25).")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me/activities/summary")
    public ApiResponse<ActivitySummaryResponse> summary(
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ApiResponse.success(ActivitySummaryResponse.from(activityQueryService.summarize(userId)));
    }

    /**
     * 활동 목록. 활동이 없으면 빈 배열을 준다.
     *
     * <p>"아직 참여한 활동이 없어요" 안내는 화면의 빈 상태(empty state) 이므로
     * 서버가 존재하지 않는 활동을 지어내지 않는다 — 지어내면 그 카드를 탭했을 때
     * 갈 곳이 없다. {@code GET /posts} 가 세운 규칙과 같다.
     */
    @Operation(summary = "내가 투표한 글 목록",
            description = "내가 투표한 게시글을 정렬(최신순·오래된순·인기순)과 커서 기반 무한 스크롤로 준다. "
                    + "정렬 키인 활동 시각은 투표한 시각이다(R-32). 활동이 없으면 빈 배열이다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me/activities/votes")
    public ApiResponse<ScrollResponse<VoteActivityItem>> findVotes(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Parameter(description = "LATEST(기본) | OLDEST | POPULAR. 모르는 값은 기본값으로 되돌린다")
            @RequestParam(value = "sort", required = false) String sort,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 첫 조각. 다른 유형의 커서면 400")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "조각 크기. 기본 10")
            @RequestParam(value = "size", required = false) Integer size) {

        return ApiResponse.success(ScrollResponse.of(
                activityQueryService.findSlice(userId, ActivityType.VOTE, sort, cursor, size),
                VoteActivityItem::from));
    }

    @Operation(summary = "내가 댓글 단 글 목록",
            description = "내가 댓글을 단 게시글을 정렬과 커서 기반 무한 스크롤로 준다. "
                    + "한 글에 댓글을 여러 개 달아도 한 번만 나오고(R-25), "
                    + "정렬 키인 활동 시각은 처음 댓글을 단 시각이다(R-32).")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me/activities/comments")
    public ApiResponse<ScrollResponse<CommentActivityItem>> findComments(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Parameter(description = "LATEST(기본) | OLDEST | POPULAR. 모르는 값은 기본값으로 되돌린다")
            @RequestParam(value = "sort", required = false) String sort,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 첫 조각. 다른 유형의 커서면 400")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "조각 크기. 기본 10")
            @RequestParam(value = "size", required = false) Integer size) {

        return ApiResponse.success(ScrollResponse.of(
                activityQueryService.findSlice(userId, ActivityType.COMMENT, sort, cursor, size),
                CommentActivityItem::from));
    }

    @Operation(summary = "내가 올린 글 목록",
            description = "내가 올린 게시글을 정렬과 커서 기반 무한 스크롤로 준다. "
                    + "내가 올린 글은 활동이 곧 작성이라 활동 시각이 작성 시각과 같다(R-32).")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me/activities/posts")
    public ApiResponse<ScrollResponse<PostActivityItem>> findPosts(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Parameter(description = "LATEST(기본) | OLDEST | POPULAR. 모르는 값은 기본값으로 되돌린다")
            @RequestParam(value = "sort", required = false) String sort,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 첫 조각. 다른 유형의 커서면 400")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "조각 크기. 기본 10")
            @RequestParam(value = "size", required = false) Integer size) {

        return ApiResponse.success(ScrollResponse.of(
                activityQueryService.findSlice(userId, ActivityType.POST, sort, cursor, size),
                PostActivityItem::from));
    }

    /**
     * 유형을 쿼리 파라미터로 받던 옛 경로. <b>유형별 경로로 대체됐다</b> (ADR-0049).
     *
     * <p>지우지 않는 이유는 SPEC 에 공개된 계약이고 FE 가 OpenAPI 로 계약을 가져가는
     * 구조라 미사용을 증명할 수 없어서다. 동작은 그대로 두고 문서에 deprecated 로
     * 표시해 소비 클라이언트 전환을 확인한 뒤 별도 이슈로 제거한다.
     *
     * <p><b>{@link ActivityType#from} 의 마지막 호출자다.</b> "모르는 값은 400 이 아니라
     * 기본값" 계약은 이 경로에만 남는다 — 세 새 경로는 유형을 경로가 고정하므로
     * 접을 값 자체가 없다.
     */
    @Deprecated(since = "#156")
    @Operation(summary = "내 활동 목록 조회 (deprecated)",
            deprecated = true,
            description = "유형별 경로(`/users/me/activities/votes` · `/comments` · `/posts`)로 대체됐다. "
                    + "동작은 그대로이나 새 클라이언트는 유형별 경로를 쓴다. "
                    + "활동 유형 필터와 정렬(최신순·오래된순·인기순), 커서 기반 무한 스크롤.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me/activities")
    public ApiResponse<ScrollResponse<ActivityItem>> findAll(
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Parameter(description = "VOTE(기본) | COMMENT | POST. 모르는 값은 기본값으로 되돌린다")
            @RequestParam(value = "type", required = false) String type,
            @Parameter(description = "LATEST(기본) | OLDEST | POPULAR. 모르는 값은 기본값으로 되돌린다")
            @RequestParam(value = "sort", required = false) String sort,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 첫 조각")
            @RequestParam(value = "cursor", required = false) String cursor,
            @Parameter(description = "조각 크기. 기본 10")
            @RequestParam(value = "size", required = false) Integer size) {

        return ApiResponse.success(ScrollResponse.of(
                activityQueryService.findSlice(userId, ActivityType.from(type), sort, cursor, size),
                ActivityItem::from));
    }

    @Operation(summary = "내가 올린 최신 투표",
            description = "7일 이내에 올린 투표 게시글을 최신순으로 준다. 가로 스크롤 캐러셀이라 "
                    + "무한 스크롤이 아니며 최대 10건이다. 기준은 요청 시각이고, "
                    + "정확히 7일이 지난 글은 빠진다. 일반 게시글은 투표가 없어 대상이 아니다.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users/me/posts/recent")
    public ApiResponse<List<ActivityItem>> recentPosts(
            @Parameter(hidden = true) @CurrentUser Long userId) {
        return ApiResponse.success(activityQueryService.findRecentVotePosts(userId).stream()
                .map(ActivityItem::from)
                .toList());
    }

    /**
     * 활동 갯수 요약 한 줄 (§7.2).
     *
     * <p>세 값 모두 <b>사람 기준</b>이다. 스키마의 유니크 키가 그것을 이미 지키고 있어
     * 조회 시점에 다시 세지 않는다.
     */
    public record ActivitySummaryResponse(
            @Schema(description = "투표에 참여한 게시글 수. 재투표는 선택 변경이라 늘지 않는다(R-22). 삭제된 글은 세지 않는다")
            long voteCount,
            @Schema(description = "댓글을 단 게시글 수. 한 글에 여러 개를 달아도 1이다(R-25). 삭제된 글은 세지 않는다")
            long commentCount,
            @Schema(description = "올린 게시글 수. 삭제한 글은 세지 않는다")
            long postCount) {

        static ActivitySummaryResponse from(ActivityQueryStore.ActivitySummary summary) {
            return new ActivitySummaryResponse(
                    summary.voteCount(), summary.commentCount(), summary.postCount());
        }
    }

    /**
     * 활동 목록 한 줄 (§9.2). <b>deprecated 경로 {@link #findAll} 전용이다.</b>
     *
     * <p>세 활동 유형이 한 스키마를 공유한다. 그 근거였던 "목록은 칩 하나로 한 유형만
     * 담으므로 파싱 분기가 쓰이지도 않는다" 는 <b>경로가 유형을 고정하면서 그대로
     * 분리의 근거로 뒤집혔다</b> (ADR-0049 가 ADR-0046 의 이 항목을 부분 대체).
     * 새 경로는 {@link VoteActivityItem}·{@link CommentActivityItem}·{@link PostActivityItem}
     * 를 쓰고, 이 타입은 옛 계약을 깨지 않기 위해 남는다 — 구 경로가 사라질 때 함께 사라진다.
     *
     * @param voteCount  찬반·A/B 만. 일반 게시글은 투표가 없어 {@code null} 이다
     * @param activityAt 내가 이 게시글에 활동한 시각. 투표한 시각·처음 댓글을 단 시각·
     *                   글을 올린 시각이며 {@code createdAt} 과 다를 수 있다 (ADR-0036)
     */
    public record ActivityItem(
            @Schema(description = "게시글 식별자") Long id,
            @Schema(description = "GENERAL | AGREE | A_B") PostType type,
            @Schema(description = "카테고리") PostCategory category,
            @Schema(description = "찬반=상품명, A/B=주제, 일반=제목") String title,
            @Schema(description = "설명") String description,
            @Schema(description = "댓글 건수") long commentCount,
            @Schema(description = "투표 인원. 일반 게시글은 null") Long voteCount,
            @Schema(description = "대표 상품 사진 1장. 일반 게시글은 null") String thumbnailUrl,
            @Schema(description = "게시글 작성 시각") LocalDateTime createdAt,
            @Schema(description = "내가 이 게시글에 활동한 시각. 내가 올린 글이면 작성 시각과 같다")
            LocalDateTime activityAt) {

        static ActivityItem from(ActivityQueryStore.ActivityPostView view) {
            return new ActivityItem(
                    view.id(),
                    view.type(),
                    view.category(),
                    view.title(),
                    view.description(),
                    view.commentCount(),
                    view.type().hasVoting() ? view.voteCount() : null,
                    view.thumbnailUrl(),
                    view.createdAt(),
                    view.activityAt());
        }
    }

    /**
     * 내가 투표한 글 한 줄 (§9.2).
     *
     * <p><b>세 유형이 각기 다른 타입이다</b> (ADR-0049). 지금은 필드 구성이 셋 다 같지만
     * 그것이 합칠 근거가 되지는 않는다 — 명세 §9.2 가 투표 활동에 "각 항목의 투표율,
     * 내가 선택한 항목" 을, 댓글 활동에 "내가 남긴 댓글, 받은 원픽 갯수" 를 따로 요구하므로
     * 세 모양은 곧 갈린다(#157 · #158). 한 타입을 공유해 두면 그때 유형에 따라
     * {@code null} 이 되는 필드가 늘어 계약이 흐려진다.
     *
     * @param activityAt 내가 투표한 시각. 게시글 작성 시각과 다를 수 있다 (R-32 · ADR-0036)
     */
    public record VoteActivityItem(
            @Schema(description = "게시글 식별자") Long id,
            @Schema(description = "GENERAL | AGREE | A_B") PostType type,
            @Schema(description = "카테고리") PostCategory category,
            @Schema(description = "찬반=상품명, A/B=주제, 일반=제목") String title,
            @Schema(description = "설명") String description,
            @Schema(description = "댓글 건수") long commentCount,
            @Schema(description = "투표 인원. 일반 게시글은 null") Long voteCount,
            @Schema(description = "대표 상품 사진 1장. 일반 게시글은 null") String thumbnailUrl,
            @Schema(description = "게시글 작성 시각") LocalDateTime createdAt,
            @Schema(description = "내가 투표한 시각") LocalDateTime activityAt,
            @Schema(description = "내가 고른 선택지. 재투표했으면 최신 선택이다 (R-22)")
            Long selectedOptionId,
            @Schema(description = "투표 대상 상품. 찬반은 1개, A/B는 표시 순서대로 2개 (R-02)")
            List<VoteActivityProduct> products,
            @Schema(description = "선택지별 득표 현황. 정확히 둘이다 (R-04)")
            List<VoteActivityOption> options) {

        static VoteActivityItem from(ActivityQueryStore.ActivityPostView view) {
            return new VoteActivityItem(
                    view.id(),
                    view.type(),
                    view.category(),
                    view.title(),
                    view.description(),
                    view.commentCount(),
                    view.type().hasVoting() ? view.voteCount() : null,
                    view.thumbnailUrl(),
                    view.createdAt(),
                    view.activityAt(),
                    view.selectedOptionId(),
                    view.products().stream().map(VoteActivityProduct::from).toList(),
                    view.options().stream()
                            .map(option -> VoteActivityOption.from(option, view.voteCount()))
                            .toList());
        }
    }

    /**
     * 투표 활동 카드의 상품 한 건 (§9.2). 상세의 {@code ProductItem} 과 어휘를 맞추되
     * <b>카드가 그리는 두 필드만 둔다</b> — 목록은 사진으로 A/B 를 보여줄 뿐 상품명·가격·URL 을
     * 쓰지 않고, 그 셋은 카드를 탭해 들어간 상세에 있다.
     *
     * @param imageUrl 상품 사진 1장. 찬반은 가장 처음 등록한 것, A/B 는 상품마다 1장 (R-03)
     */
    public record VoteActivityProduct(
            @Schema(description = "표시 순서. 1(A) 또는 2(B)") int displayOrder,
            @Schema(description = "상품 사진 1장. 찬반은 가장 처음 등록한 것, A/B는 상품마다 1장 (R-03)")
            String imageUrl) {

        static VoteActivityProduct from(ActivityQueryStore.ActivityPostProduct product) {
            return new VoteActivityProduct(product.displayOrder(), product.imageUrl());
        }
    }

    /**
     * 투표 활동 카드의 선택지 하나 (§9.2). 상세의 {@code OptionItem} 과 어휘가 같다.
     *
     * <p><b>득표 수와 득표율을 감추지 않는다.</b> 상세는 미투표자에게 둘 다 지우지만(ADR-0046)
     * 이 경로는 <b>내가 투표한 글만</b> 돌려주므로 조회자가 곧 투표자다 — 감출 대상이 존재하지
     * 않는다. 작성자 예외 논의는 이 계약 밖이다(#159).
     *
     * <p>득표율은 {@link VotePercentage} 가 계산한다. 상세·투표 직후 응답과 <b>같은 정본</b>을
     * 써야 같은 글을 어느 화면에서 열어도 게이지 값이 같다 — 여기서 직접 나누면 반올림이 갈린다.
     *
     * @param percentage 정수 퍼센트. 선택지별 반올림 때문에 두 값의 합이 100 이 아닐 수 있다
     */
    public record VoteActivityOption(
            @Schema(description = "선택지 식별자") Long optionId,
            @Schema(description = "찬반 선택지의 라벨. A/B는 null") String label,
            @Schema(description = "표시 순서. 1 또는 2") int displayOrder,
            @Schema(description = "이 선택지의 득표 수") long voteCount,
            @Schema(description = "정수 퍼센트. 반올림 때문에 두 값의 합이 100이 아닐 수 있다")
            int percentage) {

        static VoteActivityOption from(ActivityQueryStore.ActivityPostOption option, long voterCount) {
            return new VoteActivityOption(
                    option.id(),
                    option.label(),
                    option.displayOrder(),
                    option.voteCount(),
                    VotePercentage.calculate(option.voteCount(), voterCount));
        }
    }

    /**
     * 내가 댓글 단 글 한 줄 (§9.2).
     *
     * <p>한 글에 댓글을 여러 개 달아도 한 줄이다 — 읽는 곳이 {@code comment} 가 아니라
     * {@code post_commenter} 이고 그 테이블이 게시글당 한 행이다(R-25). 그래서 여러 개 중
     * <b>어느 것을 보여줄지</b>가 정해져야 하고, 그것이 {@link #myComment} 의 대표 규칙이다.
     *
     * <p><b>내 댓글을 전부 지운 글은 이 목록에 없다</b>(#158). {@code post_commenter} 는
     * 참여 원장이라 행이 남지만, 카드에 그릴 내 댓글이 없으면 보여줄 것이 없다.
     *
     * @param activityAt 내가 <b>처음</b> 댓글을 단 시각 (R-32)
     */
    public record CommentActivityItem(
            @Schema(description = "게시글 식별자") Long id,
            @Schema(description = "GENERAL | AGREE | A_B") PostType type,
            @Schema(description = "카테고리") PostCategory category,
            @Schema(description = "찬반=상품명, A/B=주제, 일반=제목") String title,
            @Schema(description = "설명") String description,
            @Schema(description = "댓글 건수") long commentCount,
            @Schema(description = "투표 인원. 일반 게시글은 null") Long voteCount,
            @Schema(description = "대표 상품 사진 1장. 일반 게시글은 null") String thumbnailUrl,
            @Schema(description = "게시글 작성 시각") LocalDateTime createdAt,
            @Schema(description = "내가 처음 댓글을 단 시각") LocalDateTime activityAt,
            @Schema(description = "내가 남긴 대표 댓글 원문. 원픽이 가장 많은 한 건이고 "
                    + "동률이면 최신이다. 1줄 줄임은 클라이언트가 한다")
            String myComment,
            @Schema(description = "대표 댓글이 받은 원픽 수. 그 한 건의 것이지 "
                    + "이 글에서 내 댓글들이 받은 합계가 아니다")
            long myCommentOnePickCount) {

        static CommentActivityItem from(ActivityQueryStore.ActivityPostView view) {
            return new CommentActivityItem(
                    view.id(),
                    view.type(),
                    view.category(),
                    view.title(),
                    view.description(),
                    view.commentCount(),
                    view.type().hasVoting() ? view.voteCount() : null,
                    view.thumbnailUrl(),
                    view.createdAt(),
                    view.activityAt(),
                    view.myComment(),
                    view.myCommentOnePickCount());
        }
    }

    /**
     * 내가 올린 글 한 줄 (§9.2).
     *
     * @param activityAt 내가 올린 시각. 내가 올린 글은 활동이 곧 작성이라
     *                   {@code createdAt} 과 같다 (R-32)
     */
    public record PostActivityItem(
            @Schema(description = "게시글 식별자") Long id,
            @Schema(description = "GENERAL | AGREE | A_B") PostType type,
            @Schema(description = "카테고리") PostCategory category,
            @Schema(description = "찬반=상품명, A/B=주제, 일반=제목") String title,
            @Schema(description = "설명") String description,
            @Schema(description = "댓글 건수") long commentCount,
            @Schema(description = "투표 인원. 일반 게시글은 null") Long voteCount,
            @Schema(description = "대표 상품 사진 1장. 일반 게시글은 null") String thumbnailUrl,
            @Schema(description = "게시글 작성 시각") LocalDateTime createdAt,
            @Schema(description = "내가 올린 시각. 작성 시각과 같다") LocalDateTime activityAt) {

        static PostActivityItem from(ActivityQueryStore.ActivityPostView view) {
            return new PostActivityItem(
                    view.id(),
                    view.type(),
                    view.category(),
                    view.title(),
                    view.description(),
                    view.commentCount(),
                    view.type().hasVoting() ? view.voteCount() : null,
                    view.thumbnailUrl(),
                    view.createdAt(),
                    view.activityAt());
        }
    }
}
