-- #123의 동의 이력 저장 결정, #147의 DB 구현. 설계 근거: ADR-0048.
-- 최종 승인된 약관만 추후 등록한다. 초안 본문·기존 회원 동의는 시드하지 않는다.
-- 공개된 버전은 덮어쓰지 않고 새 행으로 개정한다. 임의 UPDATE 금지는 운영/서비스 책임이다.

CREATE TABLE terms (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    type           VARCHAR(40)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '약관/동의 항목 코드. 항목 목록은 최종 문안과 함께 확정',
    version        VARCHAR(30)  CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title          VARCHAR(200) NOT NULL,
    content        MEDIUMTEXT   NOT NULL COMMENT '해당 버전의 전체 Markdown 본문',
    is_required    BOOLEAN      NOT NULL COMMENT '해당 버전의 필수 동의 여부',
    effective_at   DATETIME     NOT NULL COMMENT '시행 시작 시각, Asia/Seoul',
    created_at     DATETIME     NOT NULL COMMENT '등록 시각, Asia/Seoul',

    PRIMARY KEY (id),
    UNIQUE KEY uk_terms_type_version (type, version),
    -- 같은 시각에 두 버전이 시행되는 모호함을 막고 현재 시행 버전 조회에도 사용한다.
    UNIQUE KEY uk_terms_type_effective_at (type, effective_at),
    CONSTRAINT ck_terms_type_not_blank CHECK (CHAR_LENGTH(TRIM(type)) > 0),
    CONSTRAINT ck_terms_version_not_blank CHECK (CHAR_LENGTH(TRIM(version)) > 0),
    CONSTRAINT ck_terms_title_not_blank CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT ck_terms_content_not_blank CHECK (CHAR_LENGTH(TRIM(content)) > 0),
    CONSTRAINT ck_terms_required_boolean CHECK (is_required IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE terms_agreement (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    terms_id    BIGINT   NOT NULL,
    agreed_at   DATETIME NOT NULL COMMENT '해당 버전에 최초 동의한 서버 접수 시각, Asia/Seoul',

    PRIMARY KEY (id),
    UNIQUE KEY uk_terms_agreement_user_terms (user_id, terms_id),
    KEY idx_terms_agreement_terms (terms_id),
    -- 물리 삭제만 CASCADE한다. INACTIVE 전환은 별도의 보존/파기 정책과 연동해야 한다.
    CONSTRAINT fk_terms_agreement_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_terms_agreement_terms FOREIGN KEY (terms_id) REFERENCES terms (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
