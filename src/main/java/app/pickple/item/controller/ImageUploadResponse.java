package app.pickple.item.controller;

import app.pickple.item.domain.ItemContainer;
import app.pickple.item.domain.ItemResource;

import java.util.List;

/** 게시글 작성에서 참조할 컨테이너 식별자와 업로드 결과. */
public record ImageUploadResponse(Long itemContainerId, List<ImageResourceResponse> images) {

    static ImageUploadResponse from(ItemContainer container) {
        return new ImageUploadResponse(
                container.id(),
                container.resources().stream().map(ImageResourceResponse::from).toList());
    }

    public record ImageResourceResponse(
            Long resourceId,
            String originalFileName,
            long size,
            String accessUrl
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
