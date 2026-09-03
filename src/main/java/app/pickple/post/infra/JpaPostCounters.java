package app.pickple.post.infra;

import app.pickple.post.domain.PostCounters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class JpaPostCounters implements PostCounters {

    private final PostCounterRepository repository;

    @Override
    @Transactional
    public void increaseVoteCount(Long postId) {
        repository.increaseVoteCount(postId);
    }

    @Override
    @Transactional
    public void increaseOptionVoteCount(Long postOptionId) {
        repository.increaseOptionVoteCount(postOptionId);
    }

    @Override
    @Transactional
    public void decreaseOptionVoteCount(Long postOptionId) {
        repository.decreaseOptionVoteCount(postOptionId);
    }

    @Override
    @Transactional
    public void increaseCommenterCount(Long postId) {
        repository.increaseCommenterCount(postId);
    }

    @Override
    @Transactional
    public void increaseCommentCount(Long postId) {
        repository.increaseCommentCount(postId);
    }

    @Override
    @Transactional
    public void decreaseCommentCount(Long postId) {
        repository.decreaseCommentCount(postId);
    }
}
