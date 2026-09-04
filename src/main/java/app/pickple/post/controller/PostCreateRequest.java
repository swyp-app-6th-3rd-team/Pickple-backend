package app.pickple.post.controller;

import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostProduct;
import app.pickple.post.domain.PostType;
import app.pickple.post.service.PostService.CreateCommand;
import app.pickple.post.service.PostService.ProductCommand;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PostCreateRequest(
        @NotNull
        @Schema(description = "게시글 유형", allowableValues = {"AGREE", "A_B", "GENERAL"})
        PostType type,

        @NotNull
        @Schema(description = "카테고리. 전체(ALL)는 조회 필터이므로 작성 값이 아니다.")
        PostCategory category,

        @Size(max = 30)
        @Schema(description = "A/B 주제 또는 일반 제목. 찬반은 첫 상품명으로 자동 결정된다.")
        String title,

        @Size(max = 300)
        String description,

        @Valid
        List<@NotNull @Valid ProductRequest> products
) {

    CreateCommand toCommand() {
        List<ProductCommand> commands = products == null
                ? List.of()
                : products.stream().map(ProductRequest::toCommand).toList();
        return new CreateCommand(type, category, title, description, commands);
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "A/B 및 일반 게시글의 제목은 필수입니다.")
    public boolean isTitleValidForType() {
        return type == null
                || type == PostType.AGREE
                || (title != null && !title.isBlank());
    }

    public record ProductRequest(
            @NotNull @Positive Long itemContainerId,
            @NotBlank @Size(max = 30) String name,
            @PositiveOrZero @Max(PostProduct.MAX_PRICE) Long price,
            @Schema(description = "선택 텍스트. 업무 길이 제한 없이 서버가 문자열로만 저장하며 접속하지 않는다.")
            String linkUrl
    ) {
        ProductCommand toCommand() {
            return new ProductCommand(itemContainerId, name, price, linkUrl);
        }
    }
}
