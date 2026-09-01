package app.pickple.post.domain;

import java.util.Optional;

public interface PostStore {

    /** 상품·선택지를 함께 저장한다. 애그리거트는 루트 단위로만 오간다. */
    Post save(Post post);

    Optional<Post> findById(Long id);
}
