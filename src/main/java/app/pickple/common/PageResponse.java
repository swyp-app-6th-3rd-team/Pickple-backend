package app.pickple.common;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * HTTP 응답 전용 페이징 표현.
 *
 * <p>계층 내부에서는 Spring Data 의 {@link org.springframework.data.domain.Pageable}
 * ·{@link Page} 를 그대로 쓰지만, <b>응답 경계에서는 이 DTO 로 변환한다.</b>
 *
 * <p>{@code Page} 를 그대로 직렬화하면 {@code pageable.sort.sorted},
 * {@code pageable.offset} 같은 Spring 내부 구조가 API 계약이 되어버린다.
 * Spring 이 그 구조를 바꾸면 클라이언트가 깨진다. Spring Boot 3.3+ 는
 * {@code Page} 직렬화 시 경고를 낸다. ArchitectureTest 가 컨트롤러의
 * {@code Page} 반환을 금지한다.
 */
public record PageResponse<T>(
        @Schema(description = "현재 페이지 내용") List<T> content,
        @Schema(description = "현재 페이지 번호(0부터)") int page,
        @Schema(description = "페이지 크기") int size,
        @Schema(description = "전체 건수") long totalElements,
        @Schema(description = "전체 페이지 수") int totalPages,
        @Schema(description = "다음 페이지 존재 여부") boolean hasNext) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return of(page, Function.identity());
    }
}
