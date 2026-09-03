package app.pickple.post.controller;

import app.pickple.common.ApiResponse;
import app.pickple.common.ScrollResponse;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostQueryStore;
import app.pickple.post.domain.PostType;
import app.pickple.post.service.PostQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequiredArgsConstructor
public class PostController {

    private final PostQueryService postQueryService;

    /**
     * 게시글 목록. 게스트도 부를 수 있는 진입 화면이라 인증을 요구하지 않는다.
     *
     * <p>게시글이 없으면 빈 배열을 준다. "아직 없는 게시글" 안내와 예시 카드는
     * 화면의 빈 상태(empty state) 이므로 서버가 존재하지 않는 게시글을 지어내지 않는다 —
     * 지어내면 그 카드를 탭했을 때 갈 곳이 없다.
     */
    @Operation(summary = "게시글 목록 조회",
            description = "카테고리 필터와 정렬(최신순·인기순), 커서 기반 무한 스크롤. 게시글이 없으면 빈 배열이다.")
    @GetMapping("/api/posts")
    public ApiResponse<ScrollResponse<PostListItem>> findAll(
            @Parameter(description = "없으면 전체") @RequestParam(required = false) PostCategory category,
            @Parameter(description = "LATEST(기본) | POPULAR") @RequestParam(required = false) String sort,
            @Parameter(description = "이전 응답의 nextCursor. 없으면 첫 조각")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "조각 크기. 기본 10") @RequestParam(required = false) Integer size) {

        return ApiResponse.success(ScrollResponse.of(
                postQueryService.findSlice(category, sort, cursor, size), PostListItem::from));
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
}
