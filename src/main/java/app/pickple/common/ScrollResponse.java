package app.pickple.common;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.util.List;
import java.util.function.Function;

/**
 * 무한 스크롤 응답 — keyset(=cursor) 기반.
 *
 * <p><b>Page 와의 차이</b>
 * <ul>
 *   <li>{@code Page} 는 {@code OFFSET n LIMIT m} 이라 뒤로 갈수록 느려진다.
 *       DB 가 건너뛸 행을 실제로 읽기 때문이다. 총 건수를 위해 count 쿼리도 한 번 더 나간다.</li>
 *   <li>{@code Scroll} 은 {@code WHERE (key) > (마지막 값)} 이라 페이지 위치와 무관하게 일정하다.
 *       대신 총 페이지 수를 알 수 없고 임의 페이지로 건너뛸 수 없다.</li>
 * </ul>
 * 무한 스크롤은 "다음"만 필요하므로 후자가 맞다.
 *
 * <p>클라이언트는 {@code nextCursor} 를 그대로 다음 요청에 실어 보낸다.
 * 커서 내부 구조는 서버 구현 세부사항이므로 Base64 로 감싸 불투명하게 전달한다.
 */
public record ScrollResponse<T>(
        @Schema(description = "현재 조각의 내용") List<T> content,
        @Schema(description = "다음 요청에 그대로 전달할 커서. null 이면 마지막") String nextCursor,
        @Schema(description = "다음 조각 존재 여부") boolean hasNext) {

    public static <E, T> ScrollResponse<T> of(Window<E> window, Function<E, T> mapper) {
        String cursor = null;
        if (window.hasNext() && !window.isEmpty()) {
            ScrollPosition position = window.positionAt(window.size() - 1);
            if (position instanceof KeysetScrollPosition keyset) {
                cursor = CursorCodec.encode(keyset);
            }
        }
        return new ScrollResponse<>(
                window.getContent().stream().map(mapper).toList(),
                cursor,
                window.hasNext());
    }

    public static <T> ScrollResponse<T> of(Window<T> window) {
        return of(window, Function.identity());
    }
}
