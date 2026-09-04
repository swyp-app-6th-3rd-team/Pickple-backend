package app.pickple.post.domain;

import java.util.Optional;

public interface PostStore {

    /** 상품·선택지를 함께 저장한다. 애그리거트는 루트 단위로만 오간다. */
    Post save(Post post);

    /** 이미지 컨테이너가 다른 게시글 상품에 연결되지 않았을 때만 저장한다. */
    Post saveIfContainerFree(Post post);

    Optional<Post> findById(Long id);

    /** 삭제되지 않은 게시글의 존재 여부. 상호작용 전 가벼운 유효성 검사에 쓴다. */
    boolean existsActiveById(Long id);

    /** 업로드 컨테이너는 게시글 상품 하나에만 붙일 수 있다. */
    boolean isItemContainerAttached(Long itemContainerId);
}
