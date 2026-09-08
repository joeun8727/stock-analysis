-- 실투자: 저장된 StrategySpec을 실제 한국투자증권 계좌에 대고 돌립니다.
--
-- 규칙 자체는 여기 복제하지 않습니다. 익절 / 손절 / 시간대 밴드 / 장전 임계치의 원본은
-- strategy.spec_json 한 군데뿐이고, 이 테이블들은 실행 환경(어느 계좌, 얼마, 어떤 한도)과
-- 실제로 일어난 일(본 시세, 보낸 주문, 받은 체결)만 기록합니다. 규칙이 두 벌이 되면
-- 백테스트한 쪽과 어긋나고, 그러면 백테스트가 의미를 잃습니다.
--
-- 매매 모드(DRY_RUN / PAPER / REAL)는 일부러 live_config에 없습니다: 환경변수에서 오므로
-- 실계좌 전환에 API 호출이 아니라 재기동이 필요합니다.

-- 표시명("KODEX레버리지")으로는 주문할 수 없습니다. 실주문에는 6자리 종목코드가 필요합니다.
ALTER TABLE dataset ADD COLUMN ticker VARCHAR(20) NULL AFTER symbol;

-- 단일 행(id=1) 실투자 설정. fee_setting과 같은 방식입니다.
CREATE TABLE live_config (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    strategy_id         BIGINT       NULL,           -- 어느 저장 로직을 매매할지
    etf_group_id        BIGINT       NULL,           -- 레버리지/인버스 종목코드의 출처
    -- 검증 게이트: 실제로 백테스트를 거친 전략만 실계좌에 닿을 수 있고, 스펙이 그 실행이
    -- 점수를 매긴 것과 바이트 단위로 같아야 합니다.
    verified_run_id     BIGINT       NULL,
    verified_spec_hash  VARCHAR(64)  NULL,
    min_verified_trades INT          NOT NULL DEFAULT 20,
    min_verified_days   INT          NOT NULL DEFAULT 60,
    futures_ticker      VARCHAR(20)  NULL,           -- 최근월물 코드. 분기마다 바뀝니다
    armed_date          DATE         NULL,           -- 이 값이 오늘일 때만 스케줄러가 움직입니다
    max_order_amount    DOUBLE       NOT NULL DEFAULT 1000000,
    max_daily_loss      DOUBLE       NOT NULL DEFAULT 200000,
    poll_interval_sec   INT          NOT NULL DEFAULT 10,
    day_end_exit_time   VARCHAR(5)   NOT NULL DEFAULT '15:15',  -- 장 마감 전 안전 여유
    updated_at          DATETIME     NOT NULL,
    CONSTRAINT fk_live_config_strategy FOREIGN KEY (strategy_id) REFERENCES strategy (id),
    CONSTRAINT fk_live_config_group FOREIGN KEY (etf_group_id) REFERENCES etf_group (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO live_config (id, updated_at) VALUES (1, NOW());

-- 거래일마다 세션 하나. UNIQUE 키가 중복 실행 방지 장치입니다: 오전 중에 재기동하면 두 번째
-- 포지션을 여는 대신 기존 세션에 다시 붙습니다.
CREATE TABLE live_session (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date        DATE        NOT NULL,
    mode              VARCHAR(10) NOT NULL,          -- DRY_RUN | PAPER | REAL
    state             VARCHAR(20) NOT NULL,          -- ARMED | WATCHING | SKIPPED | ENTRY_PENDING |
                                                     -- HOLDING | EXIT_PENDING | CLOSED | HALTED
    strategy_id       BIGINT      NOT NULL,
    etf_group_id      BIGINT      NOT NULL,
    verified_run_id   BIGINT      NULL,              -- 이 세션의 근거가 된 백테스트
    futures_ticker    VARCHAR(20) NULL,
    trend_pct         DOUBLE      NULL,              -- 측정된 장전 선물 움직임
    chosen_instrument VARCHAR(20) NULL,              -- LEVERAGE | INVERSE
    chosen_ticker     VARCHAR(20) NULL,
    budget_amount     DOUBLE      NULL,
    entry_ts          DATETIME    NULL,
    entry_price       DOUBLE      NULL,
    quantity          BIGINT      NULL,
    exit_ts           DATETIME    NULL,
    exit_price        DOUBLE      NULL,
    exit_reason       VARCHAR(20) NULL,              -- trade.exit_reason과 같은 어휘
    profit_amount     DOUBLE      NULL,
    fee_amount        DOUBLE      NULL,
    halted_reason     VARCHAR(500) NULL,
    created_at        DATETIME    NOT NULL,
    updated_at        DATETIME    NOT NULL,
    UNIQUE KEY uq_live_session_date (trade_date),
    CONSTRAINT fk_live_session_strategy FOREIGN KEY (strategy_id) REFERENCES strategy (id),
    CONSTRAINT fk_live_session_group FOREIGN KEY (etf_group_id) REFERENCES etf_group (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 시스템이 보낸 모든 주문. client_order_id는 우리 자체의 멱등 키로 (세션, 방향)에서 만들며,
-- 타임아웃 뒤에 재시도해도 두 번째 포지션이 열리지 않게 합니다.
CREATE TABLE live_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT      NOT NULL,
    side            VARCHAR(4)  NOT NULL,            -- BUY | SELL
    ticker          VARCHAR(20) NOT NULL,
    quantity        BIGINT      NOT NULL,
    order_type      VARCHAR(10) NOT NULL,            -- MARKET | LIMIT
    limit_price     DOUBLE      NULL,
    client_order_id VARCHAR(64) NOT NULL,
    broker_order_no VARCHAR(40) NULL,
    status          VARCHAR(20) NOT NULL,            -- SENT | FILLED | PARTIAL | REJECTED | FAILED
    filled_quantity BIGINT      NOT NULL DEFAULT 0,
    filled_price    DOUBLE      NULL,
    fee_amount      DOUBLE      NULL,
    requested_at    DATETIME    NOT NULL,
    filled_at       DATETIME    NULL,
    -- JSON이 아니라 TEXT입니다: Hibernate의 JSON 매퍼에 Java time 모듈이 없고(StoredResult
    -- 참고), 어차피 아무도 안을 조회하지 않는 감사용 덩어리입니다. 직접 직렬화해 텍스트로 넣습니다.
    raw_response    TEXT        NULL,
    UNIQUE KEY uq_live_order_client (client_order_id),
    KEY idx_live_order_session (session_id),
    CONSTRAINT fk_live_order_session FOREIGN KEY (session_id) REFERENCES live_session (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 장전 구간에 폴링한 선물 시세. 09:00의 판단을 그때 본 숫자 그대로 사후에 다시 확인할 수
-- 있도록 남겨둡니다.
CREATE TABLE live_premarket_tick (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT   NOT NULL,
    ts         DATETIME NOT NULL,
    price      DOUBLE   NOT NULL,
    KEY idx_live_tick_session_ts (session_id, ts),
    CONSTRAINT fk_live_tick_session FOREIGN KEY (session_id) REFERENCES live_session (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 감사 기록: 모든 상태 전이, 주문 요청/응답, 거절. session_id가 nullable인 이유는 세션이
-- 생기기 전의 확인(연결 점검, 활성화 거절)도 기록되게 하려고입니다.
CREATE TABLE live_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT       NULL,
    ts         DATETIME     NOT NULL,
    type       VARCHAR(40)  NOT NULL,
    message    VARCHAR(1000) NULL,
    detail     TEXT         NULL,
    KEY idx_live_event_session_ts (session_id, ts),
    CONSTRAINT fk_live_event_session FOREIGN KEY (session_id) REFERENCES live_session (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
