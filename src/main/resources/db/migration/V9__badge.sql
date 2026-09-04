-- 뱃지 도메인 — 정의(badge) · 보유(user_badge) · 일별 활동 집계(user_daily_activity)
--
-- ERD 초안 §5.3 이 이미 설계해 둔 세 테이블이다. ERD 2차가 "코어 흐름 밖" 이라며
-- 유예했던 것을 여기서 되살린다. DDL 의 형태는 초안을 따르고, 그 뒤에 구현으로
-- 드러난 것들(ERD 3차)을 반영해 컬럼 타입과 인덱스만 조정했다.
--
-- =========================================================
-- 1. 뱃지 정의를 왜 코드가 아니라 테이블에 두는가
-- =========================================================
-- 정책 정본(정책 요약표 §3)의 표 제목이 "3. 뱃지 정책 (뱃지명은 추후 수정됩니다)" 다.
-- 기획이 이름이 바뀔 것을 명시한 값이므로, 이름은 **불안정한 값**이고
-- 조건(condition_type + threshold)이 안정 계약이다.
--
-- 그래서 식별은 code 가 하고 name 은 표시용 데이터로 둔다.
-- 이름이 바뀌면 `UPDATE badge SET name = ...` 한 줄이고,
-- 마이그레이션도 배포도 필요하지 않다. 반대로 enum 상수로 두면
-- 기획의 예고된 변경이 매번 코드 변경과 배포를 부른다.
--
-- code 는 **조건을 그대로 읽은 이름**이다(TOTAL_VOTE_10 …). 표시명에서 딴 이름
-- (VOTE_SPROUT 같은)으로 두면 이름이 바뀌는 순간 code 와 name 이 어긋나
-- "코드는 새싹인데 화면은 다른 이름" 인 상태가 남는다. 조건은 바뀌지 않으므로
-- 조건에서 딴 code 는 이름과 함께 낡지 않는다.
--
-- =========================================================
-- 2. 왜 일별 집계 테이블이 필요한가 (R-19)
-- =========================================================
-- 뱃지 8종의 조건 유형이 셋인데(R-18), 판정에 필요한 것이 서로 다르다.
--
--   누적 투표 10/100/500/1,000회 → 누적 투표 수
--   일일 투표량 하루 20/30개      → **날짜별 투표 수**
--   연속 참여 7/30일             → **날짜별 투표 유무의 연속성**
--
-- 뒤의 둘을 vote 테이블에서 직접 구하면 회원의 투표 이력 전체를 날짜로 묶어야 한다.
-- DATE(created_at) 은 컬럼을 감싸 idx_vote_user_created 를 무력화하고,
-- 범위 조건으로 풀어써도 그 사용자의 그날 투표 행을 전부 읽는다.
-- 연속 판정은 더 나쁘다 — DISTINCT DATE(...) 는 그 사용자의 투표 **전체**를 훑는다.
-- 1,000회 투표한 사람의 "7일 연속" 을 판정하려고 1,000행을 읽는 꼴이다.
--
-- 그리고 이 판정은 **투표할 때마다** 일어난다(명세 §2.3 "투표 시 미션 2의 상태바가
-- 즉시 변경됨"). 쓰기 핫패스에 선형 증가 비용을 얹는 구조다.
--
-- user_daily_activity 는 사용자당 "활동한 날" 만큼만 행을 갖는다.
-- "하루 20개" 는 오늘 행 하나를 읽으면 끝나고, "30일 연속" 은 최근 30행이면 끝난다.
-- 투표가 아무리 쌓여도 판정 비용이 늘지 않는다.
--
-- =========================================================
-- 3. 누적 투표 수의 출처 — users.vote_count 를 쓰지 않는다
-- =========================================================
-- V3 는 users.vote_count 를 "vote 건수" 로 선언했지만 **애플리케이션 어디도 이 컬럼에
-- 쓰지 않는다.** UserEntity 는 매핑조차 하지 않고, 투표 경로(VoteService.castFirst)는
-- post.vote_count 와 post_option.vote_count 만 올린다. 오늘 모든 회원의 값이 0 이다.
-- (ADR-0028 이 users.point 에서 똑같은 것을 발견해 원장 유도로 해결한 전례가 있다.)
--
-- 누적 투표 수는 user_daily_activity 의 vote_count 합계에서 유도한다.
-- 별도 카운터 컬럼을 두지 않는 이유는 **같은 사실을 두 곳이 표현하면 어긋나기 때문**이다.
-- V5 가 정확히 그 문제(닉네임 반납 기준이 deleted_at 과 state 두 곳)를 고치느라
-- 컬럼 하나를 걷어냈다. 일별 행이 이미 그날의 투표 수를 갖고 있으므로
-- 누적은 그것의 합계이지 별도의 사실이 아니다.
--
-- 합계 비용은 활동 일수에 비례한다 — 투표 수가 아니라. 매일 투표하는 사용자도
-- 1년에 365행이고, idx_daily_user_date 가 covering 으로 걸린다.
-- 이 규모에서 별도 카운터는 정합성 위험을 사서 얻는 것이 없다.
--
-- 병렬 작업(#25 등급)도 누적 투표 수를 입력으로 쓰지만, users.vote_count 는
-- 그쪽이 자기 방식으로 채우도록 **건드리지 않고 남겨 둔다.**


-- ---------------------------------------------------------
-- 뱃지 정의 (정책 요약표 §3)
--
-- 8행뿐이고 운영 중 늘어나지 않지만, 그럼에도 테이블인 이유는 위 §1 이다.
-- 판정 로직은 이 행들을 읽어 돈다 — 조건이 데이터라 8종을 코드가 나열하지 않는다.
-- ---------------------------------------------------------
CREATE TABLE badge (
    id             BIGINT       NOT NULL AUTO_INCREMENT,

    code           VARCHAR(40)  NOT NULL COMMENT '안정 식별자. 조건에서 딴 이름이라 표시명과 함께 낡지 않는다',
    name           VARCHAR(30)  NOT NULL COMMENT '표시명. 정책표가 "추후 수정" 을 명시한 불안정 값',
    description    VARCHAR(100) NOT NULL COMMENT '획득 조건 문구. 미션 목록이 그대로 내려준다',

    condition_type VARCHAR(20)  NOT NULL COMMENT 'TOTAL_VOTE | DAILY_VOTE | STREAK_VOTE',
    threshold      INT UNSIGNED NOT NULL COMMENT '10/100/500/1000 | 20/30 | 7/30',

    display_order  TINYINT      NOT NULL COMMENT '3X3 목록 노출 순서 (기능명세 §12.2)',

    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,

    PRIMARY KEY (id),

    -- code 가 실질 식별자다. 애플리케이션은 이 값으로 뱃지를 지목한다.
    UNIQUE KEY uk_badge_code (code),

    -- 같은 유형에 같은 임계값이 둘 있으면 "10회 뱃지" 가 어느 것인지 판정이 갈린다.
    UNIQUE KEY uk_badge_condition (condition_type, threshold),

    CONSTRAINT ck_badge_condition CHECK (condition_type IN ('TOTAL_VOTE', 'DAILY_VOTE', 'STREAK_VOTE')),
    CONSTRAINT ck_badge_threshold CHECK (threshold > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='뱃지 정의. 정책 요약표 §3';


-- ---------------------------------------------------------
-- 뱃지 보유
--
-- R-17(같은 뱃지를 두 번 획득하지 않는다)의 최종 방어선이 여기 UNIQUE 다.
-- 판정이 "가졌는지 확인 → 없으면 지급" 형태라 확인과 삽입 사이에 틈이 있고,
-- 동시 투표 두 건이 같은 임계값을 함께 넘기면 응용 검증만으로는 뚫린다.
-- 계층 책임 규율: 동시성이 걸린 유일성은 스키마가 지킨다.
--
-- 취소·회수 경로를 두지 않는다. 뱃지는 한 번 얻으면 남는다 —
-- 그래서 이 테이블에 UPDATE·DELETE 하는 코드가 없어야 정상이다.
-- ---------------------------------------------------------
CREATE TABLE user_badge (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    badge_id    BIGINT   NOT NULL,

    acquired_at DATETIME NOT NULL COMMENT '획득 시각. 획득 모달(§12.3)이 새 뱃지를 가리는 기준',

    PRIMARY KEY (id),

    -- R-17. 응용 검증이 아니라 여기가 막는다.
    UNIQUE KEY uk_user_badge (user_id, badge_id),

    CONSTRAINT fk_user_badge_user  FOREIGN KEY (user_id)  REFERENCES users (id),
    CONSTRAINT fk_user_badge_badge FOREIGN KEY (badge_id) REFERENCES badge (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='회원별 뱃지 보유. R-17';


-- ---------------------------------------------------------
-- 일별 활동 집계 (R-19)
--
-- 근거는 위 §2. 투표할 때마다 UPSERT 로 누적한다.
--
--   INSERT INTO user_daily_activity (user_id, activity_date, vote_count, ...)
--   VALUES (:userId, :today, 1, ...)
--   ON DUPLICATE KEY UPDATE vote_count = vote_count + 1, updated_at = ...;
--
-- **R-22 는 호출 지점이 지킨다.** 재투표는 선택지만 옮기는 것이라 이 UPSERT 를
-- 부르지 않는다. 스키마로는 표현할 수 없다 — "이 투표가 첫 투표인가" 는
-- vote 테이블을 봐야 알고, CHECK 는 다른 행을 볼 수 없기 때문이다.
-- 그래서 VoteService 의 첫 투표 경로에만 붙인다(호출을 잊을 수 없는 자리).
--
-- 날짜는 애플리케이션이 Asia/Seoul Clock 으로 계산해 넘긴다. CURRENT_DATE 를 쓰면
-- DB 세션 타임존에 따라 자정 근처의 하루가 갈린다 — 그러면 "하루 20개" 의 하루가
-- 사용자가 보는 하루와 달라진다.
-- ---------------------------------------------------------
CREATE TABLE user_daily_activity (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    activity_date DATE         NOT NULL COMMENT 'Asia/Seoul 기준 날짜. 애플리케이션이 계산해 넘긴다',

    vote_count    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '그날 처음 투표한 게시글 수. 재투표는 늘리지 않는다 (R-22)',

    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,

    PRIMARY KEY (id),

    -- UPSERT 의 충돌 지점이자, 연속 판정이 최근 N 행만 역순으로 읽게 해주는 인덱스다.
    -- (user_id, activity_date) 순서라 user_id 등가 + activity_date DESC 정렬이
    -- 인덱스만으로 끝난다. 순서를 뒤집으면 그 사용자의 행을 모으지 못한다.
    UNIQUE KEY uk_daily_user_date (user_id, activity_date),

    CONSTRAINT fk_daily_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='날짜별 투표 활동. 일일·연속 판정의 근거 (R-19)';


-- ---------------------------------------------------------
-- 뱃지 8종 (정책 요약표 §3)
--
-- 정본은 PDF 다. *.md 는 표 셀이 하드랩된 파생물이라 인용하지 않는다
-- (docs/requirement/README.md).
--
-- 이름이 바뀌면 이 INSERT 를 고치는 것이 아니라 운영 DB 를 UPDATE 한다 —
-- 적용된 마이그레이션은 다시 돌지 않으므로 여기를 고쳐도 반영되지 않는다.
-- 그 사실이 곧 §1 의 설계 의도다.
-- ---------------------------------------------------------
INSERT INTO badge (code, name, description, condition_type, threshold, display_order, created_at, updated_at)
VALUES
    ('TOTAL_VOTE_10',   '투표 꿈나무',      '누적 투표 10회 달성',    'TOTAL_VOTE',  10,   1, NOW(), NOW()),
    ('TOTAL_VOTE_100',  '결정 해결사',      '누적 투표 100회 달성',   'TOTAL_VOTE',  100,  2, NOW(), NOW()),
    ('TOTAL_VOTE_500',  '프로 참견러',      '누적 투표 500회 달성',   'TOTAL_VOTE',  500,  3, NOW(), NOW()),
    ('TOTAL_VOTE_1000', '천표 보유자',      '누적 투표 1,000회 달성', 'TOTAL_VOTE',  1000, 4, NOW(), NOW()),
    ('DAILY_VOTE_20',   '투표 헌터',        '하루에 투표 20개 이상',  'DAILY_VOTE',  20,   5, NOW(), NOW()),
    ('DAILY_VOTE_30',   '투표 폭주기관차',  '하루에 투표 30개 이상',  'DAILY_VOTE',  30,   6, NOW(), NOW()),
    ('STREAK_VOTE_7',   '성실한 유권자',    '7일 연속 매일 투표 참여',  'STREAK_VOTE', 7,    7, NOW(), NOW()),
    ('STREAK_VOTE_30',  '투표 중독자',      '30일 연속 매일 투표 참여', 'STREAK_VOTE', 30,   8, NOW(), NOW());


-- ---------------------------------------------------------
-- 기존 투표의 일별 집계 백필
--
-- **이걸 빠뜨리면 기능 전체가 조용히 틀린다.** 집계 테이블을 빈 채로 두면
-- 배포 직후 모든 회원의 누적 투표가 0 이 된다. 500회 투표한 사람이 "0/10" 을 본다.
-- 그런데 에러는 나지 않는다 — API 는 200 을 주고 테스트도 빌드도 통과한다.
-- 사용자가 신고해야 발견되는 종류의 결함이라 여기서 막는다.
--
-- 집계는 vote 행에서 유도한다. 재투표가 UPDATE 라 (R-22) 행이 늘지 않으므로
-- COUNT(*) 가 곧 "그날 처음 투표한 게시글 수" 이고, 앞으로 UPSERT 가 쌓을 값과 같다.
--
-- **여기서만 DATE(created_at) 을 쓴다.** 판정 경로에서 이 함수를 쓰면 인덱스가 죽지만,
-- 이건 일회성 변환이고 created_at 은 애플리케이션이 이미 Asia/Seoul Clock 으로 쓴 값이라
-- 세션 타임존이 끼어들지 않는다. idx_vote_user_created (user_id, created_at) 가
-- 이 GROUP BY 를 돕는다.
--
-- 탈퇴 회원의 투표도 함께 옮긴다. 탈퇴는 데이터를 지우지 않고 비활성 표시만 하며 (R-20),
-- FK 대상인 users 행도 남아 있다. 걸러내면 재가입 시 이력이 어긋난다.
-- ---------------------------------------------------------
INSERT INTO user_daily_activity (user_id, activity_date, vote_count, created_at, updated_at)
SELECT v.user_id, DATE(v.created_at), COUNT(*), NOW(), NOW()
  FROM vote v
 GROUP BY v.user_id, DATE(v.created_at);
