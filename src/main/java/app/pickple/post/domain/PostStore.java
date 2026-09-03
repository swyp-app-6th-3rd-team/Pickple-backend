package app.pickple.post.domain;

import java.util.Optional;

public interface PostStore {

    /** 상품·선택지를 함께 저장한다. 애그리거트는 루트 단위로만 오간다. */
    Post save(Post post);

    Optional<Post> findById(Long id);

    /** 삭제되지 않은 게시글의 존재 여부. 상호작용 전 가벼운 유효성 검사에 쓴다. */
    boolean existsActiveById(Long id);
}
