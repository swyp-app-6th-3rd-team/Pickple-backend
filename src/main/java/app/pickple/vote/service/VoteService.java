package app.pickple.vote.service;

import app.pickple.post.domain.PostCounters;
import app.pickple.vote.domain.Vote;
import app.pickple.vote.domain.VoteStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 투표와 그에 딸린 집계 갱신.
 *
 * <p><b>여기가 서비스인 이유</b> — R-22(재투표는 사람 수를 늘리지 않는다)는
 * 투표와 게시글 집계 두 도메인에 걸친다. "처음 투표인가 재투표인가" 에 따라
 * 카운터를 올릴지가 갈리는데, 투표 객체 혼자서는 그 판단을 게시글에 전달할 수 없다.
 */
@Service
@RequiredArgsConstructor
public class VoteService {

    private final VoteStore voteStore;
    private final PostCounters counters;

    /**
     * 투표하거나 선택을 바꾼다.
     *
     * <p>이미 투표했으면 새 행을 만들지 않고 선택지만 바꾼다 (R-22).
     * 새로 INSERT 하면 {@code UNIQUE(post_id, user_id)} 가 막기도 하지만,
     * 의미상으로도 "다시 투표" 가 아니라 "선택 변경" 이다.
     *
     * <p>재투표에서 카운터를 올리지 않는 것이 핵심이다 — 올리면 투표할 때마다
     * 인원이 부풀어 등급과 뱃지가 잘못 나간다.
     */
    @Transactional
    public Vote castOrChange(Long postId, Long optionId, Long voterId) {
        return voteStore.findByPostAndVoter(postId, voterId)
                .map(existing -> changeChoice(existing, optionId))
                .orElseGet(() -> castFirst(postId, optionId, voterId));
    }

    private Vote castFirst(Long postId, Long optionId, Long voterId) {
        Vote saved = voteStore.save(new Vote(postId, optionId, voterId));
        counters.increaseVoteCount(postId);
        return saved;
    }

    private Vote changeChoice(Vote existing, Long optionId) {
        if (existing.isSameChoice(optionId)) {
            return existing;
        }
        existing.changeTo(optionId);
        return voteStore.save(existing);
    }
}
