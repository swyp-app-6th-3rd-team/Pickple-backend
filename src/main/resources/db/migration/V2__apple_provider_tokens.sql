-- Sign in with Apple provider refresh token.
-- 서비스 JWT refresh token과 달리 Apple /auth/revoke에 원문이 필요하므로
-- 애플리케이션에서 AES-256-GCM으로 암호화한 값만 저장한다.

ALTER TABLE users
    MODIFY provider VARCHAR(20) NOT NULL COMMENT 'GOOGLE | KAKAO | NAVER | APPLE';

CREATE TABLE apple_provider_token (
    user_id                    BIGINT        NOT NULL,
    encryption_format_version INT           NOT NULL,
    encrypted_refresh_token    VARCHAR(4096) NOT NULL,
    encryption_iv              VARCHAR(24)   NOT NULL COMMENT '12-byte GCM IV, Base64',
    encryption_key_id          VARCHAR(50)   NOT NULL,
    created_at                 DATETIME      NOT NULL,
    updated_at                 DATETIME      NOT NULL,

    PRIMARY KEY (user_id),
    KEY idx_apple_provider_token_key_id (encryption_key_id),

    CONSTRAINT fk_apple_provider_token_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
