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

import java.util.List;

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

    /** 홈 화면 인기 게시글의 고정 건수 (§2.4). 조각 크기와 달리 클라이언트가 바꾸지 못한다. */
    private static final int POPULAR_TOP_SIZE = 10;

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
     * 홈 화면의 인기 게시글 Top 10 (§2.4).
     *
     * <p><b>목록 조회 경로를 그대로 탄다.</b> 커서 없는 첫 조각이 곧 상위 10건이므로
     * {@link #findSlice} 와 같은 쿼리다 — 인기순 정렬은 {@code post.popularity_score}
     * 생성 컬럼이 이미 들고 있고({@code idx_post_popular_all}), 조각을 인덱스로 확정한 뒤
     * 작성자와 대표 사진을 붙이는 순서도 그쪽에서 검증됐다(SPEC §3.3).
     * 전용 쿼리를 새로 짜면 그 실측(454ms → 0.28ms)을 처음부터 다시 세워야 한다.
     *
     * <p>다른 점은 <b>커서를 돌려주지 않는다</b>는 것 하나다. Top 10 은 그 열 건이
     * 전부라서 "다음 조각" 이 없다. {@code hasNext} 를 실어 보내면 11번째 글이 있을 때
     * {@code true} 가 되고, 클라이언트가 그 커서로 다시 부르면 이 엔드포인트가
     * 정의하지 않은 동작이 된다. 더 보려면 {@code GET /posts?sort=POPULAR} 로 간다.
     *
     * <p>게시글이 없으면 <b>빈 목록</b>이다. 서버가 더미 게시글을 지어내지 않는다.
     */
    @Transactional(readOnly = true)
    public List<PostQueryStore.PostListView> findPopularTop() {
        return postQueryStore
                .findSlice(null, PostSort.POPULAR, ScrollPosition.keyset(), POPULAR_TOP_SIZE)
                .getContent();
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
