package app.pickple.post.domain;

import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.time.LocalDateTime;

/**
 * 게시글 목록 화면에 필요한 읽기 모델 저장소.
 *
 * <p>쓰기용 {@link PostStore} 와 나눈 이유는 <b>모양이 다르기 때문</b>이다.
 * 쓰기는 애그리거트 전체({@link Post} + 상품 + 선택지)를 오가야 불변식을 지킬 수 있지만,
 * 목록 화면은 게시글당 필드 몇 개와 작성자·대표 사진만 필요하다.
 * 목록을 애그리거트로 읽으면 10건마다 상품·선택지 컬렉션이 따라 올라와
 * 화면이 쓰지도 않는 행을 읽는다.
 *
 * <p>Spring Data 의 {@link ScrollPosition}·{@link Window} 를 그대로 쓴다(ADR-0004).
 * 커서 인코딩과 조각 경계 판정을 다시 만들지 않기 위해서다.
 */
public interface PostQueryStore {

    /**
     * 삭제되지 않은 게시글 한 조각을 읽는다.
     *
     * @param category 필터. {@code null} 이면 전체다 (§4.1 기본값)
     * @param sort     정렬 기준
     * @param position 커서. 첫 조각이면 {@link ScrollPosition#keyset()}
     * @param size     한 조각의 크기
     */
    Window<PostListView> findSlice(PostCategory category, PostSort sort, ScrollPosition position, int size);

    /**
     * 목록 한 줄. 유형에 따라 의미가 갈리는 필드가 있다 (§4.2).
     *
     * <p><b>세 유형을 한 레코드로 받는 이유</b> — 유형별로 타입을 나누면 목록이
     * 이종 컬렉션이 되어 정렬·커서 처리가 유형마다 갈라진다. 세 유형은 같은 테이블의
     * 같은 순서 위에 있으므로 한 줄로 읽고, 유형별 차이는 값의 유무로 표현한다.
     *
     * @param title         찬반=상품명, A/B=주제, 일반=제목 (한 컬럼을 공유한다)
     * @param voteCount     투표 인원. 일반 게시글은 항상 0이다
     * @param commentCount  댓글 <b>건수</b>. 화면 표시용이라 인기순 점수와 다르다 (R-24·R-25)
     * @param thumbnailUrl  대표 상품 사진 1장. 찬반=가장 처음 등록한 사진, A/B=A 상품 사진,
     *                      일반=사진이 없으므로 {@code null}
     *
     * <p><b>작성자 랭킹은 아직 없다.</b> 순위는 전역 값이라 조회 시점에 구하면
     * 회원 전체를 정렬해야 한다 — 실측에서 한 조각에 154ms 가 들었고, 랭킹만 빼면
     * 0.23ms 였다. 사전 계산해 둘 자리이지 요청마다 셀 값이 아니므로 후속 과제로 넘긴다.
     */
    record PostListView(
            Long id,
            PostType type,
            PostCategory category,
            String title,
            String description,
            long voteCount,
            long commentCount,
            LocalDateTime createdAt,
            String thumbnailUrl,
            Long authorId,
            String authorNickname) {
    }
}
