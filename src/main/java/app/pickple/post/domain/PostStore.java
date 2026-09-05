package app.pickple.post.domain;

import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 게시글 저장과 조회를 제공한다.
 *
 * <p>저장과 단건 조회는 {@link Post} 애그리거트를 사용하고,
 * 목록과 홈 투표 카드는 화면에 필요한 필드만 읽기 모델로 조회한다.
 * 목록 조회 시 상품·선택지 애그리거트를 모두 불러오지 않는다.
 *
 * <p>커서 조회는 ADR-0004에 따라 Spring Data의 {@link ScrollPosition}·{@link Window}를 쓴다.
 */
public interface PostStore {

    /** 상품·선택지를 함께 저장한다. 애그리거트는 루트 단위로만 오간다. */
    Post save(Post post);

    /** 이미지 컨테이너가 다른 게시글 상품에 연결되지 않았을 때만 저장한다. */
    Post saveIfContainerFree(Post post);

    Optional<Post> findById(Long id);

    /** 삭제되지 않은 게시글의 존재 여부. 상호작용 전 가벼운 유효성 검사에 쓴다. */
    boolean existsActiveById(Long id);

    /** 주어진 업로드 컨테이너 중 이미 게시글 상품에 붙은 id를 한 번에 조회한다. */
    Set<Long> findAttachedItemContainerIds(Collection<Long> itemContainerIds);

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
     * 한 유형의 투표 게시글을 시드 기반 임의 순서로 읽는다.
     *
     * @param type        {@link PostType#AGREE} 또는 {@link PostType#A_B}
     * @param viewerId    로그인 사용자 식별자. 게스트면 {@code null}
     * @param position    이전 응답의 커서. 첫 조각이면 빈 keyset
     * @param size        한 조각의 고정 크기
     * @param initialSeed 첫 조각의 임의 순서를 만드는 시드. 후속 조각은 커서의 시드를 쓴다
     */
    Window<RandomPostView> findRandomSlice(
            PostType type, Long viewerId, ScrollPosition position, int size, long initialSeed);

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
     * @param authorRanking 작성자의 TOP 피커 순위. 배치가 미리 매겨둔 값이며
     *                      아직 산정되지 않았으면 {@code null} 이다 (ADR-0028)
     *
     * <p><b>랭킹은 조회 시점에 세지 않는다.</b> 순위는 전역 값이라 요청마다 구하면
     * 회원 전체를 정렬해야 한다(200k 실측 97.6ms/조각). 배치가 사전 계산한
     * {@code users.ranking} 을 읽기만 하므로 조각 비용이 랭킹 없던 때와 같다.
     * 대가는 <b>순위가 최대 한 배치 주기(5분)만큼 낡는다</b>는 것이다.
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
            String authorNickname,
            Integer authorRanking) {
    }

    /**
     * 카드 한 장.
     *
     * @param selectedOptionId 현재 사용자가 고른 선택지. 게스트·미투표자는 {@code null}
     */
    record RandomPostView(
            Long id,
            PostType type,
            String title,
            String description,
            long voterCount,
            Long selectedOptionId,
            List<RandomProductView> products,
            List<RandomOptionView> options) {
    }

    /** 찬반은 한 상품, A/B는 표시 순서대로 두 상품이다. */
    record RandomProductView(Long id, String name, int displayOrder, String imageUrl) {
    }

    /**
     * 투표 선택지. 찬반은 {@code label}, A/B는 {@code productId}로 표시 내용을 찾는다.
     *
     * @param voteCount 응답에 직접 노출하지 않고, 참여자에게만 득표율을 계산하는 원본 값
     */
    record RandomOptionView(
            Long id,
            String label,
            Long productId,
            int displayOrder,
            long voteCount) {
    }
}
