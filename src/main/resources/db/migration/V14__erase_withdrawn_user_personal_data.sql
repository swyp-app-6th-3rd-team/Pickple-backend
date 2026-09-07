-- 개인정보처리방침 제3조(2026-09-20 시행)는 회원 탈퇴 시 지체 없이 개인정보를 파기하도록 한다.
-- 이 의무는 탈퇴 시점과 무관하게 모든 탈퇴 회원에게 적용되므로, 이미 탈퇴한 회원의 잔존 개인정보도 소급 파기한다.
-- 애플리케이션의 탈퇴 처리는 변경 이후의 탈퇴에만 적용되므로, 기존 탈퇴 행은 이 마이그레이션에서 처리한다.
-- 개인정보를 파기하는 UPDATE는 원래 값을 복원할 수 없으므로 롤백할 수 없다.

-- 활성 회원은 변경하지 않으며, 탈퇴 회원은 provider와 무관하게 개인정보를 파기한다.
-- ck_users_active_provider_id는 ACTIVE 행에만 provider_id를 요구하므로 INACTIVE 행의 파기와 충돌하지 않는다.
-- InnoDB는 유니크 제약에서 NULL을 중복으로 세지 않으므로 여러 행의 provider_id가 NULL이어도 uk_users_provider를 위반하지 않는다.
-- active_nickname 생성 컬럼은 INACTIVE 행에서 이미 NULL이므로 nickname을 파기해도 그 값은 바뀌지 않는다.
UPDATE users
   SET email = NULL,
       name = NULL,
       nickname = NULL,
       profile_image_url = NULL,
       provider_id = NULL,
       updated_at = NOW()
 WHERE state = 'INACTIVE';

-- 모든 provider의 탈퇴 회원에게 적용되는 정책에 맞춰 설명만 정정하고, 컬럼 타입과 NULL 허용 및 기본값은 유지한다.
ALTER TABLE users
    MODIFY COLUMN provider_id VARCHAR(255) NULL DEFAULT NULL
        COMMENT '프로바이더가 발급한 subject. 탈퇴 회원은 provider 무관하게 NULL';
