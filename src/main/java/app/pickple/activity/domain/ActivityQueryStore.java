package app.pickple.activity.domain;

import app.pickple.post.domain.PostCategory;
import app.pickple.post.domain.PostType;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이페이지 "내 활동" 화면의 읽기 모델 저장소 (기능명세 §7.2 · §7.4 · §9.1 · §9.2).
 *
 * <p>{@code PostStore} 와 나눈 이유는 <b>좁히는 주체가 다르기 때문</b>이다.
 * 공개 목록은 카테고리로 좁히지만 여기는 <b>보는 사람의 활동</b>으로 좁힌다.
 * 같은 저장소에 합치면 모든 조회 메서드가 쓰지도 않는 {@code viewerId} 를 받게 되고,
 * 공개 목록에 사용자 식별자가 흘러드는 경로가 생긴다.
 *
 * <p>Spring Data 의 {@link ScrollPosition}·{@link Window} 를 그대로 쓴다(ADR-0004).
 */
public interface ActivityQueryStore {

    /**
     * 활동 갯수 요약 (§7.2). 세 값을 한 번에 읽는다.
     *
     * @param userId 조회 대상. 인증된 본인이다
     */
    ActivitySummary summarize(Long userId);

    /**
     * 활동 목록 한 조각 (§9.2).
     *
     * @param userId   조회 대상
     * @param type     활동 유형. 게시글을 좁히는 조건을 고른다
     * @param sort     정렬 기준
     * @param position 커서. 첫 조각이면 {@link ScrollPosition#keyset()}
     * @param size     한 조각의 크기
     */
    Window<ActivityPostView> findSlice(
            Long userId, ActivityType type, ActivitySort sort, ScrollPosition position, int size);

    /**
     * 최근에 올린 투표 게시글 (§7.4).
     *
     * <p>가로 스크롤 캐러셀이라 무한 스크롤이 아니다 — 고정 개수만 준다.
     *
     * @param userId 조회 대상
     * @param since  이 시각 <b>이후</b>에 올린 것만. 경계 판정은 호출자가 정한다
     * @param limit  최대 건수
     */
    List<ActivityPostView> findRecentVotePosts(Long userId, LocalDateTime since, int limit);

    /**
     * 활동 갯수 요약 한 줄 (§7.2).
     *
     * <p><b>세 값의 셈법이 다르다.</b> 명세가 "투표에 참여한 횟수, 댓글에 참여한 횟수,
     * 올린 게시글의 갯수" 라 적었고, 앞의 둘은 "참여" 이므로 <b>사람 기준 참여 건수</b>다.
     *
     * @param voteCount    투표한 게시글 수. {@code UNIQUE(post_id, user_id)} 가 재투표를
     *                     UPDATE 로 만들어 <b>스키마가 이미 R-22 를 지킨다</b> —
     *                     행 하나를 그대로 세면 되고 {@code DISTINCT} 가 필요 없다
     * @param commentCount 댓글을 단 게시글 수. 한 글에 여러 개를 달아도 1 이다.
     *                     {@code post_commenter} 가 이미 게시글당 한 행이라
     *                     여기서도 집계하지 않는다 (R-25)
     * @param postCount    올린 게시글 수. 삭제한 글은 세지 않는다
     */
    record ActivitySummary(long voteCount, long commentCount, long postCount) {
    }

    /**
     * 활동 목록 한 줄 (§9.2 조회 데이터).
     *
     * <p>{@code PostStore.PostListView} 와 필드가 겹치지만 <b>같은 타입을 쓰지 않는다.</b>
     * 이쪽은 작성자 랭킹이 없고({@code authorRanking} — 내 활동 화면은 작성자를 보여주지 않는다)
     * 대신 {@link #activityAt} 이 있다. 한 타입으로 합치면 양쪽 모두 쓰지 않는 필드를
     * 들고 다니게 되고, 어느 화면이 무엇을 쓰는지 타입이 말해주지 못한다.
     *
     * @param title        찬반=상품명, A/B=주제, 일반=제목 (한 컬럼을 공유한다)
     * @param voteCount    투표 인원. 일반 게시글은 항상 0 이다
     * @param commentCount 댓글 <b>건수</b>. 화면 표시용이라 인기순 점수와 다르다 (R-24·R-25)
     * @param thumbnailUrl 대표 상품 사진 1장. 찬반=가장 처음 등록한 사진, A/B=A 상품 사진,
     *                     일반=사진이 없으므로 {@code null}
     * @param activityAt   <b>내가 이 게시글에 활동한 시각.</b> 투표한 시각·처음 댓글을 단 시각·
     *                     글을 올린 시각이며, 게시글 작성 시각과 다를 수 있다.
     *                     최신순·오래된순의 정렬 키이자 커서 값이다 (ADR-0036)
     */
    record ActivityPostView(
            Long id,
            PostType type,
            PostCategory category,
            String title,
            String description,
            long voteCount,
            long commentCount,
            LocalDateTime createdAt,
            String thumbnailUrl,
            LocalDateTime activityAt) {
    }
}
