-- Live trading: run a saved StrategySpec against a real Korea Investment & Securities account.
--
-- The rules themselves are NOT duplicated here. strategy.spec_json stays the one source of truth
-- for take-profit / stop-loss / time bands / pre-market threshold, and these tables only record
-- the execution environment (which account, how much money, what limits) and what actually
-- happened (quotes seen, orders sent, fills received). A second copy of the rules would drift
-- from the backtested one, which would make the backtest meaningless.
--
-- Trading mode (DRY_RUN / PAPER / REAL) is deliberately absent from live_config: it comes from an
-- environment variable so switching to a real account needs a restart, not an API call.

-- Display names ("KODEX레버리지") can't be ordered; live orders need the 6-digit code.
ALTER TABLE dataset ADD COLUMN ticker VARCHAR(20) NULL AFTER symbol;

-- Single-row (id=1) live trading setup, mirroring how fee_setting works.
CREATE TABLE live_config (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    strategy_id         BIGINT       NULL,           -- which saved logic to trade
    etf_group_id        BIGINT       NULL,           -- supplies the leverage/inverse tickers
    -- Verification gate: only a strategy that was actually backtested may reach a real account,
    -- and the spec must still be byte-identical to what that run scored.
    verified_run_id     BIGINT       NULL,
    verified_spec_hash  VARCHAR(64)  NULL,
    min_verified_trades INT          NOT NULL DEFAULT 20,
    min_verified_days   INT          NOT NULL DEFAULT 60,
    futures_ticker      VARCHAR(20)  NULL,           -- front-month code; changes every quarter
    armed_date          DATE         NULL,           -- scheduler only acts when this is today
    max_order_amount    DOUBLE       NOT NULL DEFAULT 1000000,
    max_daily_loss      DOUBLE       NOT NULL DEFAULT 200000,
    poll_interval_sec   INT          NOT NULL DEFAULT 10,
    day_end_exit_time   VARCHAR(5)   NOT NULL DEFAULT '15:15',  -- safety margin before the close
    updated_at          DATETIME     NOT NULL,
    CONSTRAINT fk_live_config_strategy FOREIGN KEY (strategy_id) REFERENCES strategy (id),
    CONSTRAINT fk_live_config_group FOREIGN KEY (etf_group_id) REFERENCES etf_group (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO live_config (id, updated_at) VALUES (1, NOW());

-- One session per trading day. The UNIQUE key is the duplicate-run guard: a restart mid-morning
-- rejoins the existing session instead of opening a second position.
CREATE TABLE live_session (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date        DATE        NOT NULL,
    mode              VARCHAR(10) NOT NULL,          -- DRY_RUN | PAPER | REAL
    state             VARCHAR(20) NOT NULL,          -- ARMED | WATCHING | SKIPPED | ENTRY_PENDING |
                                                     -- HOLDING | EXIT_PENDING | CLOSED | HALTED
    strategy_id       BIGINT      NOT NULL,
    etf_group_id      BIGINT      NOT NULL,
    verified_run_id   BIGINT      NULL,              -- the backtest this session was justified by
    futures_ticker    VARCHAR(20) NULL,
    trend_pct         DOUBLE      NULL,              -- measured pre-market futures move
    chosen_instrument VARCHAR(20) NULL,              -- LEVERAGE | INVERSE
    chosen_ticker     VARCHAR(20) NULL,
    budget_amount     DOUBLE      NULL,
    entry_ts          DATETIME    NULL,
    entry_price       DOUBLE      NULL,
    quantity          BIGINT      NULL,
    exit_ts           DATETIME    NULL,
    exit_price        DOUBLE      NULL,
    exit_reason       VARCHAR(20) NULL,              -- same vocabulary as trade.exit_reason
    profit_amount     DOUBLE      NULL,
    fee_amount        DOUBLE      NULL,
    halted_reason     VARCHAR(500) NULL,
    created_at        DATETIME    NOT NULL,
    updated_at        DATETIME    NOT NULL,
    UNIQUE KEY uq_live_session_date (trade_date),
    CONSTRAINT fk_live_session_strategy FOREIGN KEY (strategy_id) REFERENCES strategy (id),
    CONSTRAINT fk_live_session_group FOREIGN KEY (etf_group_id) REFERENCES etf_group (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Every order the system sent. client_order_id is our own idempotency key: it is generated from
-- (session, side) so a retry after a timeout cannot open a second position.
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
    -- TEXT, not JSON: Hibernate's JSON mapper has no Java-time module (see StoredResult), and this
    -- is an audit blob nobody queries into. We serialize it ourselves and store the text.
    raw_response    TEXT        NULL,
    UNIQUE KEY uq_live_order_client (client_order_id),
    KEY idx_live_order_session (session_id),
    CONSTRAINT fk_live_order_session FOREIGN KEY (session_id) REFERENCES live_session (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Futures quotes polled during the pre-market window. Kept so the 09:00 decision can be re-checked
-- afterwards against exactly the numbers it saw.
CREATE TABLE live_premarket_tick (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT   NOT NULL,
    ts         DATETIME NOT NULL,
    price      DOUBLE   NOT NULL,
    KEY idx_live_tick_session_ts (session_id, ts),
    CONSTRAINT fk_live_tick_session FOREIGN KEY (session_id) REFERENCES live_session (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Audit trail: every state transition, order request/response and refusal. session_id is nullable
-- so checks that happen before a session exists (connection test, arming refusal) are still logged.
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
