package app.pickple.comment.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.service.CommentQueryService;
import app.pickple.comment.service.CommentService;
import app.pickple.comment.service.OnePickService;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "Comment", description = "댓글 · 원픽")
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final CommentQueryService commentQueryService;
    private final OnePickService onePickService;

    @Operation(summary = "댓글 목록 조회",
            description = "게스트도 부를 수 있다. 토큰을 함께 보내면 각 항목의 `mine` 이 채워지고, "
                    + "게스트면 항상 false 다.")
    @GetMapping("/posts/{postId}/comments")
    public ApiResponse<CommentListResponse> findAll(
            @PathVariable Long postId,
            @Parameter(hidden = true) @CurrentUser Long viewerId) {
        return ApiResponse.success(CommentListResponse.from(commentQueryService.findAll(postId, viewerId)));
    }

    @Operation(summary = "댓글 작성")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentMutationResponse>> write(
            @PathVariable Long postId,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Valid @RequestBody CommentRequest request) {
        Comment saved = commentService.write(new Comment(postId, userId, request.content(), null));
        return ResponseEntity.status(ResponseCode.CREATED.status())
                .body(ApiResponse.of(ResponseCode.CREATED, CommentMutationResponse.from(saved)));
    }

    @Operation(summary = "댓글 수정")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/comments/{id}")
    public ApiResponse<CommentMutationResponse> edit(
            @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Valid @RequestBody CommentRequest request) {
        return ApiResponse.success(CommentMutationResponse.from(
                commentService.edit(id, userId, request.content())));
    }

    @Operation(summary = "댓글 삭제")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/comments/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        commentService.delete(id, userId);
        return ApiResponse.success(null);
    }

    /**
     * 댓글을 원픽한다.
     *
     * <p>본문이 없다 — 픽 대상은 경로가, 픽하는 사람은 토큰이 정한다.
     *
     * <p><b>작성자 한정이 아니다.</b> 게시글 작성자만 고르는 모델로 한 번 읽혔다가
     * 교정된 이력이 있다(ADR-0020). 댓글 작성자 본인만 아니면 누구나 픽한다.
     */
    @Operation(summary = "댓글 원픽",
            description = "한 사람은 한 게시글에서 댓글 하나만 원픽한다(R-05). 취소·변경은 없다(R-06). "
                    + "이미 원픽했으면 409, 자기 댓글이면 400.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/comments/{commentId}/pick")
    public ResponseEntity<ApiResponse<OnePickResponse>> pick(
            @PathVariable Long commentId,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        Long pickId = onePickService.pick(commentId, userId);
        return ResponseEntity.status(ResponseCode.CREATED.status())
                .body(ApiResponse.of(ResponseCode.CREATED, new OnePickResponse(pickId, commentId)));
    }

    public record CommentRequest(
            @Schema(description = "댓글 내용. 300자 이내")
            @NotBlank @Size(max = 300) String content) {
    }

    /** 원픽 결과. {@code id} 는 포인트 지급의 멱등키이기도 하다 (R-13). */
    public record OnePickResponse(
            @Schema(description = "원픽 식별자. 포인트 지급의 멱등키다 (R-13)") Long id,
            @Schema(description = "원픽한 댓글 식별자") Long commentId) {
    }

    public record CommentMutationResponse(
            @Schema(description = "댓글 식별자") Long id,
            @Schema(description = "반영된 댓글 내용") String content) {

        static CommentMutationResponse from(Comment comment) {
            return new CommentMutationResponse(comment.id(), comment.content());
        }
    }

    public record CommentListResponse(
            @Schema(description = "활성 댓글 건수. 삭제된 댓글은 세지 않는다") long commentCount,
            @Schema(description = "(created_at, id) 오름차순. 페이징 없이 전체를 준다")
            List<CommentResponse> comments) {

        static CommentListResponse from(CommentQueryService.CommentListResult result) {
            return new CommentListResponse(
                    result.commentCount(),
                    result.comments().stream().map(CommentResponse::from).toList());
        }
    }

    public record CommentResponse(
            @Schema(description = "댓글 식별자") Long id,
            @Schema(description = "작성자 식별자") Long authorId,
            @Schema(description = "작성자 프로필 이미지") String profileImageUrl,
            @Schema(description = "작성자 닉네임. 아직 설정하지 않은 사용자는 소셜 이름을 대신 쓴다")
            String nickname,
            @Schema(description = "작성 시각") LocalDateTime createdAt,
            @Schema(description = "화면용 상대 시각", example = "3시간 전") String createdAgo,
            @Schema(description = "댓글 내용") String content,
            @Schema(description = "이 댓글이 받은 원픽 수") long onePickCount,
            @Schema(description = "현재 요청자가 쓴 댓글인지. 게스트 요청은 항상 false")
            boolean mine) {

        static CommentResponse from(CommentQueryService.CommentResult comment) {
            return new CommentResponse(
                    comment.id(),
                    comment.authorId(),
                    comment.profileImageUrl(),
                    comment.nickname(),
                    comment.createdAt(),
                    comment.createdAgo(),
                    comment.content(),
                    comment.onePickCount(),
                    comment.mine());
        }
    }
}
