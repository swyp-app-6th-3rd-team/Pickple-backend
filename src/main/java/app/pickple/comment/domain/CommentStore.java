package app.pickple.comment.domain;

import java.util.Optional;

public interface CommentStore {

    Comment save(Comment comment);

    Optional<Comment> findById(Long id);

    /** 수정·삭제 경합을 직렬화하기 위해 비관적 쓰기 잠금으로 조회한다. */
    Optional<Comment> findByIdForUpdate(Long id);
}
