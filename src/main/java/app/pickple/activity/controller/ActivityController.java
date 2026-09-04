package app.pickple.activity.controller;

import app.pickple.activity.domain.ActivityQueryStore;
import app.pickple.activity.service.ActivityQueryService;
import app.pickple.auth.security.CurrentUser;
import app.pickple.common.ApiResponse;
import app.pickple.common.ScrollResponse;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostType;
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
    @Operation(summary = "내 활동 목록 조회",
            description = "활동 유형 필터와 정렬(최신순·오래된순·인기순), 커서 기반 무한 스크롤. "
                    + "세 유형 모두 결과는 게시글 카드다 — 내가 투표한 글, 댓글 단 글, 올린 글. "
                    + "활동이 없으면 빈 배열이다.")
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
                activityQueryService.findSlice(userId, type, sort, cursor, size), ActivityItem::from));
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
            @Schema(description = "투표에 참여한 게시글 수. 재투표는 선택 변경이라 늘지 않는다(R-22)")
            long voteCount,
            @Schema(description = "댓글을 단 게시글 수. 한 글에 여러 개를 달아도 1이다(R-25)")
            long commentCount,
            @Schema(description = "올린 게시글 수. 삭제한 글은 세지 않는다")
            long postCount) {

        static ActivitySummaryResponse from(ActivityQueryStore.ActivitySummary summary) {
            return new ActivitySummaryResponse(
                    summary.voteCount(), summary.commentCount(), summary.postCount());
        }
    }

    /**
     * 활동 목록 한 줄 (§9.2).
     *
     * <p>세 활동 유형이 <b>한 스키마를 공유한다.</b> 명세의 조회 데이터가 세 유형 모두
     * 게시글 카드이고, 탭하면 게시글 상세로 간다. 유형별로 쪼개면 클라이언트가
     * 파싱 분기를 갖는데 목록은 칩 하나로 한 유형만 담으므로 그 분기가 쓰이지도 않는다.
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
}
