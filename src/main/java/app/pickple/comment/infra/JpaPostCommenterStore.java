package app.pickple.comment.infra;

import app.pickple.comment.domain.PostCommenterStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class JpaPostCommenterStore implements PostCommenterStore {

    private final PostCommenterRepository repository;

    /** 영향 행이 1이면 이 사람의 첫 댓글이다. 0이면 이미 있던 작성자다. */
    @Override
    @Transactional
    public boolean recordIfFirst(Long postId, Long userId) {
        return repository.insertIfAbsent(postId, userId) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public long countByPost(Long postId) {
        return repository.countByPostId(postId);
    }
}
