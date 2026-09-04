package app.pickple.item.controller;

import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemResource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 게시글 작성에서 참조할 컨테이너 식별자와 업로드 결과. */
public record ImageUploadResponse(
        @Schema(description = "게시글·댓글에 부착할 때 넘기는 컨테이너 식별자") Long itemContainerId,
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
            @Schema(description = "CloudFront 접근 URL. 만료되지 않는다") String accessUrl
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
