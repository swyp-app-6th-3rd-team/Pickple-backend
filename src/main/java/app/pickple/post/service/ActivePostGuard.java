package app.pickple.post.service;

import app.pickple.post.domain.PostStore;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 삭제된 게시글에는 새 상호작용을 만들지 않는다.
 *
 * <p>투표·댓글·원픽이 게시글 밖의 애그리거트라, 각 서비스가 저마다 확인하면
 * 한 곳만 빠뜨려도 뚫린다. <b>한 곳에 모아 세 서비스가 같은 관문을 지나게 한다.</b>
 *
 * <p>스키마는 이걸 막지 못한다 — FK 는 {@code post} 행의 <b>존재</b>만 보장하고
 * {@code deleted_at} 은 보지 않는다. 소프트 삭제라 행이 남아 있기 때문이다.
 *
 * <p><b>도메인이 아니라 서비스인 이유</b>: 저장소에 의존해 다른 애그리거트를 조회한다.
 * 도메인 계층은 프레임워크와 인프라를 몰라야 한다(ADR-0008).
 *
 * <p><b>한계</b>: 확인과 상호작용 사이에 삭제가 끼어들면 막지 못한다.
 * 완전히 막으려면 게시글을 잠그거나 조건부 쓰기가 필요한데,
 * 삭제는 드물고 그 창이 좁아 지금은 감수한다.
 */
@Component
@RequiredArgsConstructor
public class ActivePostGuard {

    private final PostStore postStore;

    /** 활성 게시글이면 통과, 없거나 삭제됐으면 거부한다. */
    public void requireActive(Long postId) {
        if (!postStore.existsActiveById(postId)) {
            throw new IllegalStateException("활성 게시글을 찾을 수 없습니다: id=" + postId);
        }
    }
}
