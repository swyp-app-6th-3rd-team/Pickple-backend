-- Pickple 도메인 코어 — 게시글·투표·댓글·원픽·포인트·이미지
--
-- 설계 근거는 docs/erd/ERD-2차.md 참조. 이 파일은 그 문서의 4장 DDL 을 옮긴 것이다.
-- 제약의 의도와 검증 결과(위반 주입 35건)는 문서에 있다.
--
-- 버전 번호: V1=인증(develop), V2=Apple 로그인(PR #12, feat/apple-sign-in).
-- V2 는 아직 develop 에 머지되지 않아, #12 머지 전까지 이 브랜치는 V1 → V3 로 적용된다.
-- 빈 DB 에는 문제없이 적용되지만, V3 를 이미 적용한 환경에 뒤늦게 V2 가 들어오면
-- out-of-order 가 되므로 그때는 spring.flyway.out-of-order 를 켜야 한다.
-- =========================================================
-- Pickple 도메인 코어 — 게시글·투표·댓글·원픽·포인트·이미지
--
-- 실제 마이그레이션 파일로 옮길 때 src/main/resources/db/migration 에서
-- 비어 있는 번호를 먼저 확인한다. (V1=인증, V2=Apple 로그인 #12)
-- =========================================================

-- ---------------------------------------------------------
-- 회원 확장 — 기존 users 에 서비스 프로필을 추가한다.
-- 등급(grade_id)은 유예 범위이므로 넣지 않는다.
-- ---------------------------------------------------------
ALTER TABLE users
    ADD COLUMN nickname          VARCHAR(5)   DEFAULT NULL COMMENT '5자 이내',
    ADD COLUMN profile_image_url VARCHAR(500) DEFAULT NULL COMMENT 'NULL이면 랜덤 기본 프로필',

    ADD COLUMN point             INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'point_history 합계',
    ADD COLUMN vote_count        INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'vote 건수',

    ADD COLUMN deleted_at        DATETIME     DEFAULT NULL COMMENT '탈퇴 시각. NULL이면 활성',

    -- 활성 회원만 유일. 탈퇴하면 NULL이 되어 닉네임이 풀린다.
    -- 유니크 키는 NULL을 서로 다르게 취급하므로 탈퇴자끼리는 충돌하지 않는다.
    ADD COLUMN active_nickname   VARCHAR(5)
        GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN nickname END) STORED,
    ADD UNIQUE KEY uk_users_active_nickname (active_nickname),

    -- TOP 피커 랭킹: 포인트 내림차순, 동점자는 가입일 빠른 순
    ADD KEY idx_users_ranking (point DESC, created_at);


-- ---------------------------------------------------------
-- 이미지 컨테이너
--
-- attach_type 은 생성 시점에 정해지고 바뀌지 않는다.
-- 부착 측이 (id, attach_type) 쌍으로 참조하므로,
-- 상품용 컨테이너가 댓글에 붙는 경로가 없다. (2.1 참조)
-- ---------------------------------------------------------
CREATE TABLE item_container (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '업로더',
    attach_type VARCHAR(20)  NOT NULL COMMENT 'PRODUCT | COMMENT',

    access_urls TEXT         DEFAULT NULL COMMENT 'CloudFront Access URL 목록',

    created_at  DATETIME     NOT NULL,
    updated_at  DATETIME     NOT NULL,

    PRIMARY KEY (id),

    -- 부착 측 복합 FK의 대상
    UNIQUE KEY uk_container_id_type (id, attach_type),

    CONSTRAINT fk_container_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_container_type CHECK (attach_type IN ('PRODUCT', 'COMMENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE item_resource (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    item_container_id  BIGINT       NOT NULL,

    size               BIGINT       NOT NULL COMMENT '파일 크기 (Byte)',
    original_file_name VARCHAR(255) NOT NULL,
    item_key           VARCHAR(500) NOT NULL COMMENT 'S3 Object Key',
    access_url         VARCHAR(500) NOT NULL COMMENT 'CloudFront Access URL',

    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT fk_resource_container FOREIGN KEY (item_container_id)
        REFERENCES item_container (id) ON DELETE CASCADE,

    KEY idx_resource_container (item_container_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------
-- 게시글 — 3유형을 한 테이블로 다룬다 (초안 8.1)
-- ---------------------------------------------------------
CREATE TABLE post (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,

    type             VARCHAR(20)  NOT NULL COMMENT 'GENERAL | A_B | AGREE',
    category         VARCHAR(20)  NOT NULL COMMENT 'FASHION | ELECTRONICS | BEAUTY | LIVING | ETC',

    -- 찬반=상품명, A/B=주제, 일반=제목. 셋 다 30자라 한 컬럼으로 받는다.
    title            VARCHAR(30)  NOT NULL,
    description      VARCHAR(300) DEFAULT NULL,

    -- 정책표 §6의 인기순은 건수가 아니라 인원 수다. (2.4 참조)
    vote_count       INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '투표한 사람 수 (1인 1표라 건수와 같다)',
    commenter_count  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '댓글을 단 사람 수. 건수가 아니다',
    comment_count    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '댓글 건수 (표시용)',

    -- 갱신 코드가 없으므로 두 카운터와 어긋날 수 없다. (2.4 참조)
    popularity_score INT UNSIGNED
                     GENERATED ALWAYS AS (vote_count + commenter_count) STORED,

    deleted_at       DATETIME     DEFAULT NULL,
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,

    PRIMARY KEY (id),

    -- 자식이 "같은 게시글의 것"임을 참조하기 위한 대상 키
    UNIQUE KEY uk_post_id_user (id, user_id),

    CONSTRAINT fk_post_user FOREIGN KEY (user_id) REFERENCES users (id),

    -- keyset 커서가 (정렬키, id)이므로 복합으로 건다 (초안 5.4)
    KEY idx_post_latest      (deleted_at, category, created_at DESC, id DESC),
    KEY idx_post_popular     (deleted_at, category, popularity_score DESC, id DESC),

    -- 카테고리 "전체"는 category 가 선행 컬럼이면 정렬에 쓰이지 못해 filesort 로 떨어진다
    KEY idx_post_latest_all  (deleted_at, created_at DESC, id DESC),
    KEY idx_post_popular_all (deleted_at, popularity_score DESC, id DESC),

    KEY idx_post_user (user_id, deleted_at, created_at DESC),

    CONSTRAINT ck_post_type     CHECK (type IN ('GENERAL', 'A_B', 'AGREE')),
    CONSTRAINT ck_post_category CHECK (category IN ('FASHION', 'ELECTRONICS', 'BEAUTY', 'LIVING', 'ETC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------
-- 상품 — AGREE 1개, A_B 2개, GENERAL 0개
--
-- item_container_id 가 NOT NULL 인 이유는 2.2 참조.
-- 일반 게시글은 이 테이블에 행 자체가 없다.
-- ---------------------------------------------------------
CREATE TABLE post_product (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    post_id           BIGINT        NOT NULL,

    item_container_id BIGINT        NOT NULL COMMENT '상품 사진은 필수다 (2.2)',

    -- 생성 컬럼은 GENERATED ... STORED 뒤에 NOT NULL 이 온다 (순서를 바꾸면 문법 오류)
    container_type    VARCHAR(20)
                      GENERATED ALWAYS AS (_utf8mb4'PRODUCT') STORED NOT NULL,

    name              VARCHAR(30)   NOT NULL COMMENT '상품명 30자',
    price             INT UNSIGNED  DEFAULT NULL COMMENT '선택. 상한 999,999,999',
    link_url          VARCHAR(2048) DEFAULT NULL,

    display_order     TINYINT       NOT NULL DEFAULT 1 COMMENT 'A=1, B=2',

    created_at        DATETIME      NOT NULL,
    updated_at        DATETIME      NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_product_post_order (post_id, display_order),

    -- 자식(post_option)이 "같은 게시글의 상품"만 참조하도록 (2.3)
    UNIQUE KEY uk_product_id_post (id, post_id),

    -- 한 컨테이너는 한 상품에만
    UNIQUE KEY uk_product_container (item_container_id),

    CONSTRAINT fk_product_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,

    -- 용도가 PRODUCT 인 컨테이너만 붙는다 (2.1)
    CONSTRAINT fk_product_container FOREIGN KEY (item_container_id, container_type)
        REFERENCES item_container (id, attach_type),

    CONSTRAINT ck_product_price CHECK (price IS NULL OR price <= 999999999),
    CONSTRAINT ck_product_order CHECK (display_order IN (1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------
-- 투표 선택지 — AGREE/A_B 각 2행, GENERAL 0행
-- AGREE: post_product_id NULL, label='사자'/'말자'
-- A_B  : post_product_id NOT NULL, label NULL
-- ---------------------------------------------------------
CREATE TABLE post_option (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    post_id         BIGINT       NOT NULL,
    post_product_id BIGINT       DEFAULT NULL COMMENT 'A_B만 사용. 찬반은 NULL',

    label           VARCHAR(20)  DEFAULT NULL COMMENT '찬반 전용: 사자 | 말자',
    display_order   TINYINT      NOT NULL COMMENT '1 | 2',

    vote_count      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '투표율 표시용',

    created_at      DATETIME     NOT NULL,

    PRIMARY KEY (id),

    -- 게시글당 1번·2번이 하나씩 (2.3)
    UNIQUE KEY uk_option_post_order (post_id, display_order),

    -- vote 가 "같은 게시글의 선택지"만 참조하도록 (2.3)
    UNIQUE KEY uk_option_id_post (id, post_id),

    CONSTRAINT fk_option_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,

    -- 단순 FK는 "그 상품이 존재한다"만 보장한다. post_id 를 함께 넘겨 교차 참조를 막는다. (2.3)
    CONSTRAINT fk_option_product FOREIGN KEY (post_product_id, post_id)
        REFERENCES post_product (id, post_id),

    CONSTRAINT ck_option_target CHECK (
        (post_product_id IS NOT NULL AND label IS NULL) OR
        (post_product_id IS NULL     AND label IS NOT NULL)
    ),
    CONSTRAINT ck_option_order CHECK (display_order IN (1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------
-- 투표 — 게시글당 1인 1표
-- post_id 중복 보유는 UNIQUE(post_id, user_id)를 성립시키기 위한 것이다 (초안 6.2)
-- ---------------------------------------------------------
CREATE TABLE vote (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    post_id        BIGINT   NOT NULL,
    post_option_id BIGINT   NOT NULL,
    user_id        BIGINT   NOT NULL COMMENT '게스트 투표는 서버에 남지 않는다',

    created_at     DATETIME NOT NULL,

    PRIMARY KEY (id),

    -- 재투표는 UPDATE 로 처리한다
    UNIQUE KEY uk_vote_post_user (post_id, user_id),

    CONSTRAINT fk_vote_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,

    -- 단순 FK였다면 post_id=1 인 투표가 post_id=2 의 선택지를 가리켜도 통과했다 (2.3)
    CONSTRAINT fk_vote_option FOREIGN KEY (post_option_id, post_id)
        REFERENCES post_option (id, post_id),

    CONSTRAINT fk_vote_user FOREIGN KEY (user_id) REFERENCES users (id),

    KEY idx_vote_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------
-- 댓글 — 사진은 선택이므로 컨테이너가 nullable 이다
-- ---------------------------------------------------------
CREATE TABLE comment (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    post_id           BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,

    item_container_id BIGINT       DEFAULT NULL COMMENT '댓글 사진은 선택',
    -- 문자 리터럴은 접속 문자셋을 따르므로, 복합 FK 대상 컬럼과 맞추기 위해 명시한다
    container_type    VARCHAR(20)
                      GENERATED ALWAYS AS
                      (CASE WHEN item_container_id IS NULL THEN NULL ELSE _utf8mb4'COMMENT' END) STORED,

    content           VARCHAR(300) NOT NULL,

    deleted_at        DATETIME     DEFAULT NULL,
    created_at        DATETIME     NOT NULL,
    updated_at        DATETIME     NOT NULL,

    PRIMARY KEY (id),

    -- comment_pick 이 "같은 게시글의 댓글"만 참조하도록 (2.3)
    UNIQUE KEY uk_comment_id_post (id, post_id),

    -- 한 컨테이너는 한 댓글에만
    UNIQUE KEY uk_comment_container (item_container_id),

    CONSTRAINT fk_comment_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,
    CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES users (id),

    -- 용도가 COMMENT 인 컨테이너만 붙는다 (2.1)
    CONSTRAINT fk_comment_container FOREIGN KEY (item_container_id, container_type)
        REFERENCES item_container (id, attach_type),

    KEY idx_comment_post (post_id, deleted_at, created_at, id),
    KEY idx_comment_user (user_id, deleted_at, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------
-- 원픽 — 여러 유저가 각자 픽한다 (좋아요형)
--
-- 화이트보드가 CommentPick 을 별도 박스로 그리고 UNIQUE(user_id, comment_id)를
-- 명시했다. 초안의 post.picked_comment_id(작성자가 1개 채택)와 양립하지 않으므로
-- 이 모델로 대체한다. post ↔ comment 순환 FK 도 함께 사라진다.
-- ---------------------------------------------------------
CREATE TABLE comment_pick (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    post_id    BIGINT   NOT NULL COMMENT '비정규화. 게시글별 픽 집계를 조인 없이',
    comment_id BIGINT   NOT NULL,
    user_id    BIGINT   NOT NULL,

    created_at DATETIME NOT NULL,

    PRIMARY KEY (id),

    -- 같은 댓글을 두 번 픽하지 않는다
    UNIQUE KEY uk_pick_user_comment (user_id, comment_id),

    -- comment_pick.post_id ≠ comment.post_id 인 행을 막는다 (2.3)
    CONSTRAINT fk_comment_pick_comment FOREIGN KEY (comment_id, post_id)
        REFERENCES comment (id, post_id),

    CONSTRAINT fk_comment_pick_user FOREIGN KEY (user_id) REFERENCES users (id),

    KEY idx_pick_post (post_id),
    KEY idx_pick_comment (comment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------
-- 게시글별 댓글 작성자 (순 인원)
--
-- 첫 댓글에서만 행이 생기고, UNIQUE 가 중복을 거부한다.
-- 판정을 애플리케이션이 아니라 유니크 키가 한다. (2.4 참조)
-- ---------------------------------------------------------
CREATE TABLE post_commenter (
    id         BIGINT   NOT NULL AUTO_INCREMENT,
    post_id    BIGINT   NOT NULL,
    user_id    BIGINT   NOT NULL,

    created_at DATETIME NOT NULL COMMENT '이 사람의 첫 댓글 시각',

    PRIMARY KEY (id),

    UNIQUE KEY uk_commenter_post_user (post_id, user_id),

    CONSTRAINT fk_commenter_post FOREIGN KEY (post_id) REFERENCES post (id) ON DELETE CASCADE,
    CONSTRAINT fk_commenter_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------
-- 포인트 원장 (정책표 §1)
--
-- 원픽 1건이 두 사람에게 지급하므로(작성자 +10, 픽한 사람 +5),
-- 멱등키는 (comment_pick_id, reason)이다.
-- comment_pick_id 가 NOT NULL 이어야 멱등키가 성립한다 —
-- nullable 이면 유니크 키가 NULL 을 서로 다르게 취급해 중복 적립이 뚫린다.
-- ---------------------------------------------------------
CREATE TABLE point_history (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL COMMENT '수령자',

    amount          INT         NOT NULL COMMENT '+10 | +5. 회수 대비 부호 있는 정수',
    reason          VARCHAR(30) NOT NULL COMMENT 'PICKED(내 댓글이 픽됨) | PICKING(내가 픽함)',

    comment_pick_id BIGINT      NOT NULL COMMENT '멱등키 구성 요소',

    created_at      DATETIME    NOT NULL,

    PRIMARY KEY (id),

    -- 픽 1건당 사유별 1회
    UNIQUE KEY uk_point_idem (comment_pick_id, reason),

    CONSTRAINT fk_point_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_point_pick FOREIGN KEY (comment_pick_id) REFERENCES comment_pick (id),

    CONSTRAINT ck_point_reason CHECK (reason IN ('PICKED', 'PICKING')),

    KEY idx_point_user_created (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
