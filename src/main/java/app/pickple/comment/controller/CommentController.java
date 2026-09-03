package app.pickple.comment.controller;

import app.pickple.auth.security.CurrentUser;
import app.pickple.comment.domain.Comment;
import app.pickple.comment.service.CommentQueryService;
import app.pickple.comment.service.CommentService;
import app.pickple.common.ApiResponse;
import app.pickple.common.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final CommentQueryService commentQueryService;

    @Operation(summary = "댓글 목록 조회")
    @GetMapping("/posts/{postId}/comments")
    public ApiResponse<CommentListResponse> findAll(
            @PathVariable Long postId,
            @Parameter(hidden = true) @CurrentUser Long viewerId) {
        return ApiResponse.success(CommentListResponse.from(commentQueryService.findAll(postId, viewerId)));
    }

    @Operation(summary = "댓글 작성")
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
    @PatchMapping("/comments/{id}")
    public ApiResponse<CommentMutationResponse> edit(
            @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser Long userId,
            @Valid @RequestBody CommentRequest request) {
        return ApiResponse.success(CommentMutationResponse.from(
                commentService.edit(id, userId, request.content())));
    }

    @Operation(summary = "댓글 삭제")
    @DeleteMapping("/comments/{id}")
    public ApiResponse<Void> delete(
            @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser Long userId) {
        commentService.delete(id, userId);
        return ApiResponse.success(null);
    }

    public record CommentRequest(
            @NotBlank @Size(max = 300) String content) {
    }

    public record CommentMutationResponse(Long id, String content) {

        static CommentMutationResponse from(Comment comment) {
            return new CommentMutationResponse(comment.id(), comment.content());
        }
    }

    public record CommentListResponse(long commentCount, List<CommentResponse> comments) {

        static CommentListResponse from(CommentQueryService.CommentListResult result) {
            return new CommentListResponse(
                    result.commentCount(),
                    result.comments().stream().map(CommentResponse::from).toList());
        }
    }

    public record CommentResponse(
            Long id,
            Long authorId,
            String profileImageUrl,
            String nickname,
            LocalDateTime createdAt,
            String createdAgo,
            String content,
            long onePickCount,
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
