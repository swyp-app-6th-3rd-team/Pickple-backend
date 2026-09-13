package app.pickple.post.controller;

import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostStore;
import app.pickple.post.domain.PostType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 홈 인기 카드 한 장 (§2.4). 커뮤니티 목록의 응답 계약과 별도로 확장한다.
 *
 * @param commenterCount 댓글을 남긴 서로 다른 사용자 수. 댓글 삭제 시에도 유지되는 누적 인원 (R-25)
 * @param thumbnailUrl 기존 클라이언트 호환용 대표 사진. 찬반=첫 사진, A/B=A 사진, 일반=null
 * @param products 상품별 대표 사진. 찬반 1개, A/B는 A·B 순서로 2개, 일반은 빈 배열 (R-02·R-03)
 */
public record PopularPostItem(
        @Schema(description = "게시글 식별자") Long id,
        @Schema(description = "GENERAL | AGREE | A_B") PostType type,
        @Schema(description = "카테고리") PostCategory category,
        @Schema(description = "찬반=상품명, A/B=주제, 일반=제목") String title,
        @Schema(description = "설명") String description,
        @Schema(description = "댓글 건수") long commentCount,
        @Schema(description = "댓글을 남긴 서로 다른 사용자 수. 댓글 삭제 시에도 유지되는 누적 인원(R-25)")
        long commenterCount,
        @Schema(description = "투표 인원. 일반 게시글은 null") Long voteCount,
        @Schema(description = "기존 호환용 대표 상품 사진 1장. 찬반=첫 사진, A/B=A 사진, 일반은 null")
        String thumbnailUrl,
        @Schema(description = "작성 시각") LocalDateTime createdAt,
        @Schema(description = "작성자 식별자") Long authorId,
        @Schema(description = "작성자 닉네임") String authorNickname,
        @Schema(description = "작성자 TOP 피커 순위. 아직 산정되지 않았으면 null (최대 5분 지연)")
        Integer authorRanking,
        @Schema(description = "상품별 대표 사진. 찬반 1개, A/B는 A·B 순서로 2개, 일반은 빈 배열(R-02·R-03)")
        List<ProductItem> products) {

    static PopularPostItem from(PostStore.PopularPostView view) {
        PostStore.PostListView post = view.post();
        return new PopularPostItem(
                post.id(), post.type(), post.category(), post.title(), post.description(),
                post.commentCount(), view.commenterCount(),
                post.type().hasVoting() ? post.voteCount() : null,
                post.thumbnailUrl(), post.createdAt(),
                post.authorId(), post.authorNickname(), post.authorRanking(),
                view.products().stream().map(ProductItem::from).toList());
    }

    /** 상세 상품 응답과 OpenAPI 스키마 이름을 구분한다. */
    @Schema(name = "PopularProductItem")
    public record ProductItem(
            @Schema(description = "상품 표시 순서. 1=A, 2=B, 찬반은 1") int displayOrder,
            @Schema(description = "해당 상품에 가장 먼저 등록한 사진 URL") String imageUrl) {

        static ProductItem from(PostStore.PopularProductView view) {
            return new ProductItem(view.displayOrder(), view.imageUrl());
        }
    }
}
