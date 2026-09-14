package app.pickple.item.controller;

import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemResource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 이미지 업로드 결과. 상품·댓글은 컨테이너 식별자를, 프로필은 파일 접근 URL을 사용한다. */
public record ImageUploadResponse(
        @Schema(description = "상품·댓글 부착용 컨테이너 식별자. PROFILE에서는 사용하지 않음")
        Long itemContainerId,
        @Schema(description = "이번 요청으로 올라간 파일들") List<ImageResourceResponse> images) {

    static ImageUploadResponse from(ItemContainer container) {
        return new ImageUploadResponse(
                container.id(),
                container.resources().stream().map(ImageResourceResponse::from).toList());
    }

    public record ImageResourceResponse(
            @Schema(description = "업로드된 파일 식별자") Long resourceId,
            @Schema(description = "원본 파일명") String originalFileName,
            @Schema(description = "파일 크기. 파일당 5MB 를 넘으면 413 이다") long size,
            @Schema(description = "CloudFront 접근 URL. 만료되지 않는다. PROFILE은 프로필 요청의 profileImageUrl에 전달") String accessUrl
    ) {
        static ImageResourceResponse from(ItemResource resource) {
            return new ImageResourceResponse(
                    resource.id(),
                    resource.originalFileName(),
                    resource.size(),
                    resource.accessUrl());
        }
    }
}
