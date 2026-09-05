-- S3 목록의 개별 키 확인 및 진행 중인 업로드 잠금 조회를 전체 테이블 스캔 없이 수행한다.
-- 기존 키의 중복 여부에 의존하지 않도록 비유니크 인덱스로 추가한다.
CREATE INDEX idx_resource_key ON item_resource (item_key);
