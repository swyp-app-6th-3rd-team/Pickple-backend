package app.pickple.comment.service;

import app.pickple.comment.domain.Comment;
import app.pickple.comment.domain.CommentStore;
import app.pickple.comment.domain.DuplicatePickException;
import app.pickple.comment.domain.OnePick;
import app.pickple.comment.domain.OnePickStore;
import app.pickple.common.ResponseCode;
import app.pickple.error.ApiException;
import app.pickple.point.domain.PointHistory;
import app.pickple.point.domain.PointHistoryStore;
import app.pickple.point.domain.PointReason;
import app.pickple.post.service.ActivePostGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원픽과 그에 따른 포인트 지급.
 *
 * <p><b>여기가 서비스인 이유</b> — R-12(원픽 1건에 두 사람이 받는다)는
 * 댓글·원픽·포인트 세 도메인이 얽혀 어느 애그리거트도 혼자 판정할 수 없다.
 * 반면 R-07(자기 댓글 금지)은 댓글 하나로 판정되므로 {@link Comment#pick(Long)} 이 갖는다.
 * 기준은 규칙의 종류가 아니라 <b>판정에 필요한 정보의 범위</b>다 (ADR-0019).
 *
 * <p>저장소는 "삽입됐는가" 만 알린다. 그것을 "이미 픽했다" 는 정책 위반으로 해석하는 것이
 * 이 계층의 일이다.
 */
@Service
@RequiredArgsConstructor
public class OnePickService {

    private final CommentStore commentStore;
    private final ActivePostGuard activePost;
    private final OnePickStore pickStore;
    private final PointHistoryStore pointStore;

    /**
     * 댓글을 원픽하고 포인트를 지급한다.
     *
     * <p>한 트랜잭션으로 묶는다. 픽만 저장되고 포인트가 빠지면 원장과 실제가 어긋나는데,
     * 멱등키가 재시도를 막아 손으로 고쳐야 한다.
     *
     * @return 저장된 원픽의 식별자
     * @throws DuplicatePickException 이 게시글에서 이미 픽했을 때 (R-05)
     */
    @Transactional
    public Long pick(Long commentId, Long pickerId) {
        Comment comment = commentStore.findById(commentId)
                .orElseThrow(() -> new IllegalArgumentException("댓글을 찾을 수 없습니다: id=" + commentId));

        if (comment.isDeleted()) {
            throw new ApiException(ResponseCode.INVALID_REQUEST, "삭제된 댓글은 원픽할 수 없습니다.");
        }

        activePost.requireActive(comment.postId());

        // R-07 은 댓글 하나로 판정된다 — 도메인이 막는다.
        OnePick pick = comment.pick(pickerId);

        // 경합 시의 DB 제약 해석은 저장소가 맡는다. 서비스는 같은 도메인 예외를 사용한다.
        Long pickId = pickStore.saveIfAbsent(pick)
                .orElseThrow(() -> new DuplicatePickException(comment.postId(), pickerId));

        grant(comment.authorId(), PointReason.PICKED, pickId);
        grant(pickerId, PointReason.PICKING, pickId);
        return pickId;
    }

    /**
     * 포인트를 지급한다 (R-12).
     *
     * <p>이미 지급됐으면 조용히 넘어간다 — 멱등키가 막았다는 것은
     * 같은 픽으로 이미 지급이 끝났다는 뜻이라 오류가 아니다 (R-13).
     * 재시도가 안전해야 하므로 예외로 만들지 않는다.
     */
    private void grant(Long userId, PointReason reason, Long pickId) {
        pointStore.saveIfAbsent(PointHistory.forPick(userId, reason, pickId));
    }
}
