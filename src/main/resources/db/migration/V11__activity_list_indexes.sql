-- ---------------------------------------------------------
-- 마이페이지 "내 활동" 목록의 정렬 인덱스 (#30 · ADR-0036)
--
-- 활동 목록은 활동 테이블에서 시작해 게시글을 붙인다. 그래서 정렬이
-- (활동 시각, 게시글 id) 튜플인데, 기존 인덱스에는 두 번째 키가 없어
-- MySQL 이 내 활동 전체를 읽고 filesort 로 정렬했다.
--
-- 실측 — 게시글 100,000 · 투표 200,000 · 회원 20,000 · 활동 500건 보유자
--
--   내가 투표한 글 최신순
--     idx_vote_user_created (user_id, created_at)      4.29 ms   Sort + 500행
--     ORDER BY 를 v.post_id 로 바꿈                     0.335 ms  Sort + 500행
--     idx_vote_user_activity (아래)                    0.070 ms  Covering + 11행
--
--   내가 댓글 단 글 최신순
--     fk_commenter_user (user_id)                      0.606 ms  Sort + 500행
--     idx_commenter_user_activity (아래)               0.035 ms  Covering + 11행
--
-- 속도보다 중요한 것은 복잡도다. 인덱스 이전은 Θ(내 활동 수) 라
-- 활동이 쌓일수록 느려진다 — 활동 5,000건에서도 새 인덱스는 11행만 읽는다(1.16 ms).
--
-- 기존 인덱스를 지우지 않는다. idx_vote_user_created 는 등급 판정과 요약이
-- COUNT(*) 를 커버링으로 끝내는 데 쓰고(JpaGradeStore.READ_INPUTS),
-- fk_commenter_user 는 외래 키가 요구한다.
-- ---------------------------------------------------------

ALTER TABLE vote
    ADD KEY idx_vote_user_activity (user_id, created_at DESC, post_id DESC);

ALTER TABLE post_commenter
    ADD KEY idx_commenter_user_activity (user_id, created_at DESC, post_id DESC);
