package app.pickple.post.controller;

import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 게시글 수정 응답. 저장된 값을 되돌려 준다.
 *
 * <p>요청 밖의 필드(상품·유형)는 조용히 무시되므로, 클라이언트가 결과를 확인할 길이 응답이다 (ADR-0047).
 * {@code type} 을 함께 주는 것은 바뀌지 않았음을 보여 주기 위해서다 (R-01).
 */
public record PostUpdateResponse(
        @Schema(description = "게시글 식별자") Long postId,
        @Schema(description = "GENERAL | AGREE | A_B. 수정으로 바뀌지 않는다(R-01)") PostType type,
        @Schema(description = "저장된 카테고리") PostCategory category,
        @Schema(description = "저장된 제목. 찬반=상품명, A/B=주제, 일반=제목") String title,
        @Schema(description = "저장된 설명. 비웠으면 null") String description) {

    static PostUpdateResponse from(Post post) {
        return new PostUpdateResponse(
                post.id(), post.type(), post.category(), post.title(), post.description());
    }
}
