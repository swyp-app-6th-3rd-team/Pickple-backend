-- Apple 탈퇴 회원은 과거 활동의 FK 대상인 users 행은 보존하되,
-- 다음 로그인에서 동일한 Apple subject를 신규 가입에 다시 쓸 수 있도록 식별자를 분리한다.
-- 다른 provider의 탈퇴 정책과 (provider, provider_id) 유니크 제약은 바꾸지 않는다.

ALTER TABLE users
    MODIFY COLUMN provider_id VARCHAR(255) NULL DEFAULT NULL
        COMMENT '프로바이더가 발급한 subject. 탈퇴한 Apple 사용자는 NULL';

-- 이 변경 이전에 이미 탈퇴한 Apple 사용자도 재가입할 수 있게 소급 적용한다.
UPDATE users
   SET provider_id = NULL,
       updated_at = NOW()
 WHERE provider = 'APPLE'
   AND state = 'INACTIVE';

-- 활성 사용자는 언제나 로그인 식별자를 가져야 한다.
ALTER TABLE users
    ADD CONSTRAINT ck_users_active_provider_id
        CHECK (state <> 'ACTIVE' OR provider_id IS NOT NULL);
