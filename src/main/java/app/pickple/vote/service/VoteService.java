package app.pickple.vote.service;

import app.pickple.post.domain.Post;
import app.pickple.post.domain.PostCounters;
import app.pickple.post.domain.PostOption;
import app.pickple.post.domain.PostStore;
import app.pickple.post.service.ActivePostGuard;
import app.pickple.vote.domain.Vote;
import app.pickple.vote.domain.VoteActivityRecorder;
import app.pickple.vote.domain.VotePercentage;
import app.pickple.vote.domain.VoteStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    private final PostStore postStore;
    private final ActivePostGuard activePost;
    private final PostCounters counters;
    private final VoteActivityRecorder badges;

    /**
     * 투표하거나 선택을 바꾸고, 갱신된 집계를 돌려준다.
     *
     * <p>이미 투표했으면 새 행을 만들지 않고 선택지만 바꾼다 (R-22).
     * 새로 INSERT 하면 {@code UNIQUE(post_id, user_id)} 가 막기도 하지만,
     * 의미상으로도 "다시 투표" 가 아니라 "선택 변경" 이다.
     *
     * <p>재투표에서 <b>사람 수</b> 카운터를 올리지 않는 것이 핵심이다 — 올리면 투표할 때마다
     * 인원이 부풀어 등급과 뱃지가 잘못 나간다. 움직이는 것은 선택지 카운터뿐이다.
     *
     * <p>집계를 여기서 함께 돌려주는 이유는 <b>같은 트랜잭션 안에서 읽어야</b>
     * 방금 넣은 표가 반영된 값이 나오기 때문이다. 컨트롤러가 따로 조회하면
     * 그 사이 끼어든 다른 투표까지 섞인 값을 자기 표의 결과인 양 보여준다.
     */
    @Transactional
    public VoteResult castOrChange(Long postId, Long optionId, Long voterId) {
        activePost.requireActive(postId);
        Post post = loadVotablePost(postId);
        requireOptionOf(post, optionId);

        voteStore.findByPostAndVoter(postId, voterId)
                .ifPresentOrElse(
                        existing -> changeChoice(existing, optionId),
                        () -> castFirst(postId, optionId, voterId));

        return tally(postId, optionId);
    }

    /**
     * 첫 투표. <b>집계를 먼저 올리고 투표 행을 나중에 넣는다.</b>
     *
     * <p>순서가 성능이 아니라 <b>교착 상태</b> 문제다. {@code vote} INSERT 는 FK 검사를 위해
     * 부모인 {@code post} 행에 공유 락(S)을 잡는다. 그 뒤에 {@code UPDATE post} 로
     * 배타 락(X)을 요구하면 <b>락 승격</b>이 되는데, 동시 요청 둘이 각각 S 를 쥔 채
     * 서로의 X 를 기다려 교착에 빠진다(16명 동시 투표에서 실제로 재현했다 — 14건 실패).
     *
     * <p>X 를 먼저 잡으면 뒤늦게 온 요청은 순서를 기다릴 뿐 고리가 생기지 않는다.
     * 같은 트랜잭션 안이라 순서를 바꿔도 원자성은 그대로다.
     *
     * <p><b>일별 활동 기록과 뱃지 판정이 여기에만 붙는다</b> (R-22). 선택 변경은 새 활동이
     * 아니므로 {@link #changeChoice} 에는 없다 — 재투표로 "하루 20개" 가 채워지면
     * 뱃지가 잘못 나간다. 두 경로가 이미 갈려 있어 호출을 잊을 자리가 없다.
     *
     * <p>순서는 <b>맨 뒤</b>다. 이 트랜잭션이 잠그는 테이블이 하나(회원) 늘어나는데,
     * <b>모든 경로가 같은 순서로 잡아야</b> 서로 반대 방향으로 도는 트랜잭션이 생기지 않는다.
     * ERD 초안 §8.8 이 정한 순서(투표 → 선택지 → 게시글 → 회원 → 일별 집계)의 끝자락이라
     * 여기가 그 자리다.
     *
     * <p>락을 <b>쥐는 시간</b>이 줄어드는 것은 아니다 — InnoDB 의 FK 공유 락은 문장이
     * 끝나도 커밋까지 유지되므로, 순서를 바꿔도 총 보유 시간은 트랜잭션 길이와 같다.
     * 얻는 것은 순서의 일관성뿐이고, 그것이 교착을 막는 실제 장치다.
     */
    private void castFirst(Long postId, Long optionId, Long voterId) {
        counters.increaseVoteCount(postId);
        counters.increaseOptionVoteCount(optionId);
        voteStore.save(new Vote(postId, optionId, voterId));
        badges.recordVoteAndEvaluate(voterId);
    }

    /**
     * 선택 변경. 사람 수는 그대로 두고 표만 옮긴다 (R-22).
     *
     * <p>두 선택지를 <b>id 순으로</b> 잠근다. 사자→말자 로 바꾸는 사람과 말자→사자 로
     * 바꾸는 사람이 동시에 오면, 각자 자기 출발지를 먼저 잠그는 순간 서로의 도착지를
     * 기다리게 된다. 모두가 같은 순서로 잡으면 그 고리가 생기지 않는다.
     */
    private void changeChoice(Vote existing, Long optionId) {
        if (existing.isSameChoice(optionId)) {
            return;
        }
        Long previousOptionId = existing.postOptionId();
        moveOptionVote(previousOptionId, optionId);
        existing.changeTo(optionId);
        voteStore.save(existing);
    }

    private void moveOptionVote(Long from, Long to) {
        if (from.compareTo(to) < 0) {
            counters.decreaseOptionVoteCount(from);
            counters.increaseOptionVoteCount(to);
            return;
        }
        counters.increaseOptionVoteCount(to);
        counters.decreaseOptionVoteCount(from);
    }

    private Post loadVotablePost(Long postId) {
        Post post = postStore.findById(postId)
                .orElseThrow(() -> new VoteConsistencyException(
                        "활성 게시글 검증 뒤 게시글을 찾을 수 없습니다: id=" + postId));
        if (!post.type().hasVoting()) {
            throw new IllegalArgumentException("투표할 수 없는 게시글입니다: id=" + postId);
        }
        return post;
    }

    /**
     * 선택지가 이 게시글의 것인지 확인한다 (R-10).
     *
     * <p>스키마의 복합 FK {@code (post_option_id, post_id)} 가 최종 방어선이지만,
     * 거기까지 가면 flush 시점의 무결성 위반이라 500 이 된다.
     * 남의 게시글 선택지를 보낸 것은 <b>잘못된 요청</b>이므로 먼저 걸러 400 으로 답한다.
     */
    private void requireOptionOf(Post post, Long optionId) {
        boolean belongs = post.options().stream()
                .anyMatch(option -> option.id().equals(optionId));
        if (!belongs) {
            throw new IllegalArgumentException(
                    "이 게시글의 선택지가 아닙니다: postId=%d, optionId=%d".formatted(post.id(), optionId));
        }
    }

    /**
     * 방금 반영된 값으로 집계를 다시 읽는다.
     *
     * <p>카운터를 Java 에서 더해 만들지 않고 다시 조회한다 — 원자 UPDATE 로 올린 값이
     * 정본이라, 메모리의 스냅샷으로 계산하면 같은 순간의 다른 투표를 빠뜨린다.
     */
    private VoteResult tally(Long postId, Long selectedOptionId) {
        Post reloaded = postStore.findById(postId)
                .orElseThrow(() -> new VoteConsistencyException(
                        "투표 반영 뒤 게시글을 찾을 수 없습니다: id=" + postId));
        long voterCount = voteStore.countByPost(postId);
        List<OptionTally> options = reloaded.options().stream()
                .map(option -> OptionTally.of(option, voterCount))
                .toList();
        return new VoteResult(postId, selectedOptionId, voterCount, options);
    }

    /**
     * 투표 직후의 집계.
     *
     * @param voterCount 투표한 <b>사람</b> 수. 1인 1표라 건수와 같다 (R-09)
     */
    public record VoteResult(Long postId, Long selectedOptionId, long voterCount, List<OptionTally> options) {
    }

    /**
     * 선택지 하나의 득표 현황.
     *
     * @param percentage 득표율. 정수 퍼센트라 반올림 때문에 합이 100 이 아닐 수 있다 —
     *                   화면이 게이지 폭으로만 쓰므로 여기서 억지로 맞추지 않는다.
     *                   아무도 투표하지 않았으면 0 이다(0 으로 나누지 않는다).
     */
    public record OptionTally(Long optionId, String label, int displayOrder, long voteCount, int percentage) {

        static OptionTally of(PostOption option, long voterCount) {
            return new OptionTally(
                    option.id(),
                    option.label(),
                    option.displayOrder(),
                    option.voteCount(),
                    VotePercentage.calculate(option.voteCount(), voterCount));
        }
    }
}
