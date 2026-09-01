package app.pickple.vote.domain;

import java.util.Optional;

public interface VoteStore {

    Vote save(Vote vote);

    /** 이미 투표했는지 확인한다. 재투표는 새 행이 아니라 이 행의 수정이다 (R-22). */
    Optional<Vote> findByPostAndVoter(Long postId, Long voterId);

    /** 게시글의 투표 인원 수 (R-24). 1인 1표라 건수와 같다. */
    long countByPost(Long postId);
}
