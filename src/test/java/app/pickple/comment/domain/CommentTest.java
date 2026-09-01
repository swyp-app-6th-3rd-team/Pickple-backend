package app.pickple.comment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentTest {

    private static final Long POST = 1L;
    private static final Long AUTHOR = 10L;
    private static final Long OTHER = 20L;

    private static Comment saved() {
        return Comment.restore(100L, POST, AUTHOR, "좋아 보여요", null, false);
    }

    @Nested
    @DisplayName("원픽 — 행위 (ADR-0018)")
    class Pick {

        @Test
        @DisplayName("자기 댓글은 원픽할 수 없다 (R-07)")
        void cannotPickOwnComment() {
            // 작성자 권한 규칙이 아니라 자기 참조 금지다. 댓글 하나만 알면 판정된다.
            Comment comment = saved();

            assertThatThrownBy(() -> comment.pick(AUTHOR))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("자기 댓글");
        }

        @Test
        @DisplayName("다른 사람은 원픽할 수 있다")
        void othersCanPick() {
            OnePick pick = saved().pick(OTHER);

            assertThat(pick.commentId()).isEqualTo(100L);
            assertThat(pick.postId()).isEqualTo(POST);
            assertThat(pick.pickerId()).isEqualTo(OTHER);
        }

        @Test
        @DisplayName("삭제된 댓글은 원픽할 수 없다")
        void deletedCommentCannotBePicked() {
            Comment comment = saved();
            comment.delete();

            assertThatThrownBy(() -> comment.pick(OTHER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("삭제된 댓글");
        }

        @Test
        @DisplayName("픽하는 사람이 없으면 거부한다")
        void pickerRequired() {
            assertThatThrownBy(() -> saved().pick(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("중복 픽은 도메인이 막지 않는다 — 유니크 키의 몫이다 (R-26)")
        void duplicateIsNotDomainConcern() {
            // 확인 후 삽입은 동시 요청에서 뚫린다. 같은 결과가 두 번 나오는 것은 정상이고,
            // 저장 시점에 UNIQUE(user_id, comment_id) 가 하나만 남긴다.
            Comment comment = saved();

            OnePick first = comment.pick(OTHER);
            OnePick second = comment.pick(OTHER);

            assertThat(first).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("생성과 수정")
    class Lifecycle {

        @Test
        @DisplayName("내용이 비면 만들 수 없다")
        void contentRequired() {
            assertThatThrownBy(() -> new Comment(POST, AUTHOR, "   ", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("내용");
        }

        @Test
        @DisplayName("300자를 넘으면 거부한다")
        void contentTooLong() {
            assertThatThrownBy(() -> new Comment(POST, AUTHOR, "가".repeat(301), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("300자");
        }

        @Test
        @DisplayName("삭제된 댓글은 수정할 수 없다")
        void deletedCannotBeEdited() {
            Comment comment = saved();
            comment.delete();

            assertThatThrownBy(() -> comment.edit("고칠래요"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("두 번 삭제할 수 없다")
        void deleteTwiceRejected() {
            Comment comment = saved();
            comment.delete();

            assertThatThrownBy(comment::delete).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("사진은 선택이다")
        void photoIsOptional() {
            assertThat(new Comment(POST, AUTHOR, "글만", null).hasPhoto()).isFalse();
            assertThat(new Comment(POST, AUTHOR, "사진도", 5L).hasPhoto()).isTrue();
        }
    }

    @Nested
    @DisplayName("원픽 값 객체")
    class OnePickValue {

        @Test
        @DisplayName("구성 요소가 비면 만들 수 없다")
        void componentsRequired() {
            assertThatThrownBy(() -> new OnePick(null, POST, OTHER))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OnePick(100L, null, OTHER))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OnePick(100L, POST, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("값이 같으면 같은 것으로 본다")
        void valueEquality() {
            assertThat(new OnePick(100L, POST, OTHER)).isEqualTo(new OnePick(100L, POST, OTHER));
        }
    }
}
