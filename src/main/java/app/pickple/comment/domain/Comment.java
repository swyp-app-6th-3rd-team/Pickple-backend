package app.pickple.comment.domain;

/**
 * 게시글에 남기는 의견. 게시글 밖의 독립 애그리거트다.
 *
 * <p>원픽은 이 애그리거트의 <b>행위</b>다 — {@link #pick(Long)} 이 동사를 갖고
 * 결과를 {@link OnePick} 으로 돌려준다. 근거는 ADR-0018.
 */
public class Comment {

    private static final int MAX_CONTENT_LENGTH = 300;

    private final Long id;
    private final Long postId;
    private final Long authorId;
    private final Long itemContainerId;
    private String content;
    private boolean deleted;

    public Comment(Long postId, Long authorId, String content, Long itemContainerId) {
        this(null, postId, authorId, content, itemContainerId, false);
    }

    private Comment(Long id, Long postId, Long authorId, String content,
                    Long itemContainerId, boolean deleted) {
        if (postId == null) {
            throw new IllegalArgumentException("게시글은 필수입니다.");
        }
        if (authorId == null) {
            throw new IllegalArgumentException("작성자는 필수입니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("댓글 내용은 필수입니다.");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("댓글은 %d자 이내여야 합니다.".formatted(MAX_CONTENT_LENGTH));
        }
        this.id = id;
        this.postId = postId;
        this.authorId = authorId;
        this.content = content;
        this.itemContainerId = itemContainerId;
        this.deleted = deleted;
    }

    /** 저장된 상태를 그대로 복원한다. 인프라 계층만 쓴다. */
    public static Comment restore(Long id, Long postId, Long authorId, String content,
                                  Long itemContainerId, boolean deleted) {
        return new Comment(id, postId, authorId, content, itemContainerId, deleted);
    }

    /**
     * 이 댓글을 원픽한다 (R-07).
     *
     * <p>자기 댓글은 고를 수 없다. 댓글 하나만 알면 판정되므로 애그리거트를 넘지 않는다.
     *
     * <p><b>중복 픽은 여기서 막지 않는다.</b> 이미 픽했는지 확인하려면 픽 목록 전체를
     * 들고 있어야 하고, 확인과 삽입 사이의 틈에서 동시 요청이 뚫린다.
     * {@code UNIQUE(user_id, comment_id)} 가 원자적으로 막는다 (R-26).
     */
    public OnePick pick(Long pickerId) {
        if (deleted) {
            throw new IllegalStateException("삭제된 댓글은 원픽할 수 없습니다.");
        }
        if (pickerId == null) {
            throw new IllegalArgumentException("픽하는 사람이 필요합니다.");
        }
        if (pickerId.equals(authorId)) {
            throw new IllegalArgumentException("자기 댓글은 원픽할 수 없습니다.");
        }
        return new OnePick(id, postId, pickerId);
    }

    public void edit(String content) {
        if (deleted) {
            throw new IllegalStateException("삭제된 댓글은 수정할 수 없습니다.");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("댓글 내용은 필수입니다.");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("댓글은 %d자 이내여야 합니다.".formatted(MAX_CONTENT_LENGTH));
        }
        this.content = content;
    }

    /** 소프트 삭제. 남긴 댓글이 다른 사람의 맥락이다 (R-20 과 같은 취지). */
    public void delete() {
        if (deleted) {
            throw new IllegalStateException("이미 삭제된 댓글입니다.");
        }
        this.deleted = true;
    }

    public boolean isWrittenBy(Long userId) {
        return authorId.equals(userId);
    }

    public boolean hasPhoto() {
        return itemContainerId != null;
    }

    public Long id() {
        return id;
    }

    public Long postId() {
        return postId;
    }

    public Long authorId() {
        return authorId;
    }

    public String content() {
        return content;
    }

    public Long itemContainerId() {
        return itemContainerId;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
