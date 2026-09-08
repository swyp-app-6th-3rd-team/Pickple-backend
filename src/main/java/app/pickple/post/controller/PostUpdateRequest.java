package app.pickple.post.controller;

import app.pickple.post.domain.PostCategory;
import app.pickple.post.service.PostService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 게시글 수정 요청 (§6.1 `[더보기]` · R-33).
 *
 * <p><b>세 필드뿐이다.</b> 상품(상품명·가격·URL·사진)과 유형은 스키마에 없다 — 보내도 바인딩되지 않는다.
 * 요청 DTO 를 화이트리스트로 좁혀 R-01(유형 불변)과 상품 불변이 타입 수준에서 보장된다 (ADR-0047).
 * 편집 화면이 작성 폼을 재사용하므로 폼 전체가 와도 깨지지 않는다.
 *
 * <p>모든 필드가 선택이다. 없거나 {@code null} 이면 그대로 둔다.
 *
 * @param title       A/B 는 주제, 일반은 제목. <b>찬반은 상품명이라 바꿀 수 없다</b> — 다른 값을 보내면 400
 * @param description 빈 문자열이면 비운다. 설명은 선택 입력이라 지우는 길이 있어야 한다
 */
public record PostUpdateRequest(
        @Schema(description = "카테고리. 없으면 유지. FASHION | ELECTRONICS | BEAUTY | LIVING | ETC")
        PostCategory category,

        @Schema(description = "A/B는 주제, 일반은 제목. 30자 이내. 없거나 빈 문자열이면 유지. "
                + "찬반 게시글의 제목은 상품명이라 바꿀 수 없다(R-33) — 현재 값과 다른 값을 보내면 400",
                maxLength = 30)
        @Size(max = 30)
        String title,

        @Schema(description = "설명. 300자 이내. 없으면 유지, 빈 문자열이면 비운다", maxLength = 300)
        @Size(max = 300)
        String description) {

    PostService.UpdateCommand toCommand() {
        return new PostService.UpdateCommand(category, title, description);
    }
}
