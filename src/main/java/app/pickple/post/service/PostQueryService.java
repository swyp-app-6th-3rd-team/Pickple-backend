package app.pickple.post.service;

import app.pickple.common.CursorCodec;
import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostQueryStore;
import app.pickple.post.domain.PostSort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시글 목록을 화면용 읽기 모델로 조립한다 (§4.1 · §4.2).
 *
 * <p>정렬·필터를 여기서 하지 않는다 — 전부 쿼리의 {@code WHERE}·{@code ORDER BY} 로 내린다.
 * 애플리케이션에서 거르면 조각 크기만큼만 읽어온 뒤 걸러내므로 한 조각이 10건보다
 * 적어지고, 커서가 가리키는 위치와 실제로 돌려준 마지막 행이 어긋난다.
 */
@Service
@RequiredArgsConstructor
public class PostQueryService {

    /** 무한 스크롤 조각 크기 (§4.2). 클라이언트가 더 크게 요청해도 이 값으로 자른다. */
    public static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 50;

    private final PostQueryStore postQueryStore;

    /**
     * @param category 없으면 전체 (§4.1 기본값)
     * @param sort     없거나 모르는 값이면 최신순
     * @param cursor   없으면 첫 조각
     */
    @Transactional(readOnly = true)
    public Window<PostQueryStore.PostListView> findSlice(
            PostCategory category, String sort, String cursor, Integer size) {

        ScrollPosition position = CursorCodec.decode(cursor);
        return postQueryStore.findSlice(category, PostSort.from(sort), position, sliceSize(size));
    }

    /**
     * 조각 크기를 제한한다. 상한이 없으면 {@code size=100000} 한 번으로 목록 전체가
     * 나가 무한 스크롤이 무의미해지고, 조인이 붙은 이 쿼리에서는 비용도 그만큼 커진다.
     */
    private static int sliceSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
