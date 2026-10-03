-- 정식 출시 직전 데이터 초기화 (#187, ADR-0053).
-- 출시 후에는 develop DB 가 그대로 운영 DB 가 된다. 개발·테스트 기간의 데이터를 비우고
-- 스토어 심사용 QA 계정만 로그인 가능한 상태로 남긴다.
--
-- !! develop 머지가 곧 운영 DB 삭제다. 출시 컷오버 직전에만 머지하고, ADR-0053 런북의
-- !! 사전 점검(qa_account 1행 이상)과 mysqldump 백업을 먼저 한다. 적용 후 롤백은 백업 복원뿐이다.
--
-- 보존 대상은 실행 시점에 qa_account.user_id 가 가리키는 users 행이다. ID 를 적지 않는다.
-- qa_account 가 비어 있으면 users 전원이 지워진다. 여기서 막지 않는 이유는 CI·로컬·Testcontainers 가
-- 빈 DB 에서 이 파일을 실행하기 때문이다 — 가드를 넣으면 새 환경이 모두 깨진다.
--
-- FOREIGN_KEY_CHECKS 를 끄지 않는다. 자식부터 지우고, 순서가 틀리면 고아 행을 남기는 대신
-- FK 위반으로 실패하게 둔다. 전부 DML 이라 Flyway 가 한 트랜잭션으로 감싼다.
--
-- 남기는 것: qa_account, QA 의 users·refresh 토큰·약관 동의·본인 PROFILE 이미지,
--           badge(V9 시드), terms(운영자가 등록하는 약관), flyway_schema_history.

-- ---------------------------------------------------------
-- 1. 활동 — QA 의 활동도 지운다. 자식에서 부모 순서.
-- ---------------------------------------------------------
DELETE FROM point_history;        -- → comment_pick
DELETE FROM comment_pick;         -- → comment
DELETE FROM user_badge;
DELETE FROM user_daily_activity;
DELETE FROM vote;                 -- → post_option
DELETE FROM post_commenter;
DELETE FROM comment;              -- → item_container
DELETE FROM post_option;          -- → post_product
DELETE FROM post_product;         -- → item_container
DELETE FROM post;

-- ---------------------------------------------------------
-- 2. 이미지 — QA 본인의 PROFILE 컨테이너만 남긴다.
-- ---------------------------------------------------------
-- 지워질 컨테이너의 이미지를 프로필로 쓰는 QA 가 있으면 먼저 기본 프로필(NULL)로 돌린다.
-- 컨테이너가 사라진 뒤에는 어느 URL 이 끊겼는지 알 수 없다. URL 은 ADR-0052 처럼 정확히 비교한다.
-- BINARY 연산자는 8.4 에서 deprecated(경고 1287)라 CAST 로 바이트 비교한다.
UPDATE users u
   SET u.profile_image_url = NULL,
       u.updated_at = NOW()
 WHERE EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = u.id)
   AND EXISTS (SELECT 1
                 FROM item_resource r
                 JOIN item_container c ON c.id = r.item_container_id
                WHERE CAST(r.access_url AS BINARY) = CAST(u.profile_image_url AS BINARY)
                  AND NOT (c.attach_type = 'PROFILE'
                           AND EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = c.user_id)));

-- item_resource 는 fk_resource_container 의 ON DELETE CASCADE 로 함께 지워진다.
-- S3 객체는 남는다 — scripts/purge-orphan-upload-objects.sh 로 정리한다.
DELETE FROM item_container
 WHERE NOT (attach_type = 'PROFILE'
            AND EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = item_container.user_id));

-- ---------------------------------------------------------
-- 3. 회원 — qa_account 가 가리키지 않는 회원을 지운다.
-- ---------------------------------------------------------
-- 아래 세 테이블은 users 삭제 시 CASCADE 로도 지워지지만, 무엇이 지워지는지 드러내려고 먼저 지운다.
DELETE FROM terms_agreement
 WHERE NOT EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = terms_agreement.user_id);
DELETE FROM user_refresh_token
 WHERE NOT EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = user_refresh_token.user_id);
DELETE FROM apple_provider_token
 WHERE NOT EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = apple_provider_token.user_id);

DELETE FROM users
 WHERE NOT EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = users.id);

-- ---------------------------------------------------------
-- 4. QA 파생 컬럼 — 원장이 비었으므로 캐시도 초기값으로 돌린다.
-- ---------------------------------------------------------
-- highest_grade 를 1 로 돌리는 것은 R-16(등급은 내려가지 않는다)의 예외다.
-- R-16 은 원장이 유지되는 동안의 규칙이고, 이 파일은 원장 자체를 비운다.
UPDATE users u
   SET u.point = 0,
       u.vote_count = 0,
       u.ranking = NULL,
       u.highest_grade = 1,
       u.updated_at = NOW()
 WHERE EXISTS (SELECT 1 FROM qa_account q WHERE q.user_id = u.id);
