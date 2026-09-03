-- 닉네임 반납 기준을 deleted_at 에서 state 로 옮긴다 (R-21).
--
-- ERD 초안 §9.1 #7 이 미결로 남겨둔 항목이다. V3 의 생성 컬럼은 deleted_at 을 보는데
-- 애플리케이션의 탈퇴 경로(User.withdraw)는 state 만 INACTIVE 로 바꾼다.
-- 두 표현을 그대로 두면 탈퇴해도 deleted_at 이 NULL 로 남아
-- active_nickname 이 값을 유지하고, 닉네임이 영구히 잠긴다.
--
-- 한 사실을 두 컬럼이 표현하는 구조 자체가 원인이므로 정본을 state 하나로 통일한다.
-- deleted_at 은 users 에서 걷어낸다 — 남겨두면 어느 쪽이 정본인지 다시 흐려진다.
-- 탈퇴 시각이 필요해지면 updated_at 으로 근사하거나 별도 이력 테이블을 만든다.
--
-- 적용 순서: 유니크 인덱스 → 생성 컬럼 → deleted_at 순으로 지운 뒤 다시 만든다.
-- 생성 컬럼을 참조하는 인덱스가 살아 있으면 컬럼을 지울 수 없다.

ALTER TABLE users
    DROP INDEX uk_users_active_nickname,
    DROP COLUMN active_nickname,
    DROP COLUMN deleted_at;

ALTER TABLE users
    ADD COLUMN active_nickname VARCHAR(5)
        GENERATED ALWAYS AS (CASE WHEN state = 'ACTIVE' THEN nickname END) STORED,
    ADD UNIQUE KEY uk_users_active_nickname (active_nickname);
