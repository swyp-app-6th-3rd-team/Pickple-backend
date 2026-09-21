-- V17은 DefaultProfileImageMigration(Java)이다. 외부 소셜 계정과 독립된 QA 사용자.
ALTER TABLE users
    MODIFY COLUMN provider VARCHAR(20) NOT NULL COMMENT 'GOOGLE | KAKAO | NAVER | APPLE | QA',
    MODIFY COLUMN provider_id VARCHAR(255) NULL DEFAULT NULL
        COMMENT '소셜 subject 또는 QA 내부 UUID. 탈퇴 시 NULL';

CREATE TABLE qa_account (
    login_id      VARCHAR(100) COLLATE utf8mb4_0900_as_cs NOT NULL,
    password_hash VARCHAR(60) NOT NULL COMMENT 'BCrypt cost 10~14',
    user_id       BIGINT NOT NULL,
    created_at    DATETIME NOT NULL,
    updated_at    DATETIME NOT NULL,
    PRIMARY KEY (login_id),
    UNIQUE KEY uk_qa_account_user (user_id),
    CONSTRAINT fk_qa_account_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
