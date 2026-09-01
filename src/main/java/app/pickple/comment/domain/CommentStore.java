package app.pickple.comment.domain;

import java.util.Optional;

public interface CommentStore {

    Comment save(Comment comment);

    Optional<Comment> findById(Long id);
}
