-- 기존 PRODUCT/COMMENT 행과 부착 측 복합 FK는 유지한다.
ALTER TABLE item_container
    DROP CHECK ck_container_type,
    MODIFY COLUMN attach_type VARCHAR(20) NOT NULL COMMENT 'PRODUCT | COMMENT | PROFILE',
    ADD CONSTRAINT ck_container_type CHECK (attach_type IN ('PRODUCT', 'COMMENT', 'PROFILE'));

-- 프로필 API의 기존 URL 계약으로 업로드 소유자·용도를 찾기 위한 동등 조회 인덱스.
CREATE INDEX idx_resource_access_url ON item_resource (access_url);
