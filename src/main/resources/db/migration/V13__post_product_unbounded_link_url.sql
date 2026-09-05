-- 기능명세서 v0.3 §5.2는 상품 URL에 업무 길이 제한을 두지 않는다.
-- 기존 VARCHAR(2048)는 긴 URL을 DB 오류로 만들므로 LONGTEXT로 넓힌다.
ALTER TABLE post_product
    MODIFY COLUMN link_url LONGTEXT NULL COMMENT '선택. 업무 길이 제한 없음';
