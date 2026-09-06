-- Android Apple 웹 로그인은 브라우저 callback과 앱 token 교환 사이의 일회성 상태가 필요하다.
-- 기존 Apple provider token은 native/web client별로 분리해 revoke 시 발급 client를 보존한다.

ALTER TABLE apple_provider_token
    ADD COLUMN client_type VARCHAR(16) NOT NULL DEFAULT 'NATIVE' AFTER user_id,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (user_id, client_type),
    ADD CONSTRAINT ck_apple_provider_token_client_type
        CHECK (client_type IN ('NATIVE', 'WEB'));

CREATE TABLE apple_web_login_attempt (
    state_hash                       CHAR(64)      NOT NULL,
    app_state                        VARCHAR(256)  NOT NULL,
    code_challenge                   CHAR(43)      NOT NULL,
    nonce_hash                       CHAR(64)      NOT NULL,
    status                           VARCHAR(32)   NOT NULL,
    bound_user_id                    BIGINT        NULL,
    provider_id                      VARCHAR(255)  NULL,
    email                            VARCHAR(255)  NULL,
    name                             VARCHAR(100)  NULL,
    encryption_format_version        INT           NULL,
    encrypted_provider_refresh_token VARCHAR(4096) NULL,
    encryption_iv                    VARCHAR(24)   NULL,
    encryption_key_id                VARCHAR(50)   NULL,
    handoff_code_hash                CHAR(64)      NULL,
    created_at                       DATETIME      NOT NULL,
    expires_at                       DATETIME      NOT NULL,
    handoff_expires_at               DATETIME      NULL,
    updated_at                       DATETIME      NOT NULL,

    PRIMARY KEY (state_hash),
    UNIQUE KEY uk_apple_web_handoff_code (handoff_code_hash),
    KEY idx_apple_web_attempt_expiry (status, expires_at),
    KEY idx_apple_web_handoff_expiry (status, handoff_expires_at),
    KEY idx_apple_web_attempt_retention (status, updated_at),
    CONSTRAINT ck_apple_web_attempt_status
        CHECK (status IN ('PENDING', 'CALLBACK_PROCESSING', 'VERIFIED', 'CONSUMED', 'FAILED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
