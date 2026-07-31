-- Datasets: one row per uploaded excel file (a symbol's bar series).
CREATE TABLE dataset (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol            VARCHAR(100) NOT NULL,
    market            VARCHAR(20)  NOT NULL,           -- FUTURES | ETF | NORMAL
    kind              VARCHAR(20)  NOT NULL,           -- LEVERAGE | INVERSE | SINGLE
    group_id          VARCHAR(100) NULL,               -- ETF leverage/inverse pair grouping
    original_filename VARCHAR(255) NULL,
    bar_count         INT          NOT NULL DEFAULT 0,
    from_ts           DATETIME     NULL,
    to_ts             DATETIME     NULL,
    uploaded_at       DATETIME     NOT NULL,
    UNIQUE KEY uq_dataset_symbol_kind (symbol, kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Bar data body: 3-minute OHLCV + moving averages. Source of truth for backtests.
CREATE TABLE price_bar (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT   NOT NULL,
    ts         DATETIME NOT NULL,
    open       DOUBLE   NOT NULL,
    high       DOUBLE   NOT NULL,
    low        DOUBLE   NOT NULL,
    close      DOUBLE   NOT NULL,
    ma5        DOUBLE   NULL,
    ma10       DOUBLE   NULL,
    ma20       DOUBLE   NULL,
    ma60       DOUBLE   NULL,
    volume     DOUBLE   NULL,
    vol_ma5    DOUBLE   NULL,
    vol_ma20   DOUBLE   NULL,
    vol_ma60   DOUBLE   NULL,
    vol_ma120  DOUBLE   NULL,
    CONSTRAINT fk_price_bar_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (id) ON DELETE CASCADE,
    KEY idx_price_bar_dataset_ts (dataset_id, ts)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Strategy rule spec (entry/exit condition tree) stored as JSON; USER or LLM authored.
CREATE TABLE strategy (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    source     VARCHAR(20)  NOT NULL,                  -- USER | LLM
    spec_json  JSON         NOT NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- One backtest execution: strategy x dataset, with summary metrics.
CREATE TABLE backtest_run (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    strategy_id  BIGINT   NOT NULL,
    dataset_id   BIGINT   NOT NULL,
    params       JSON     NULL,
    summary_json JSON     NULL,
    created_at   DATETIME NOT NULL,
    CONSTRAINT fk_run_strategy FOREIGN KEY (strategy_id) REFERENCES strategy (id),
    CONSTRAINT fk_run_dataset FOREIGN KEY (dataset_id) REFERENCES dataset (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Individual trades produced by a backtest run.
CREATE TABLE trade (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    backtest_run_id BIGINT   NOT NULL,
    entry_ts        DATETIME NOT NULL,
    exit_ts         DATETIME NOT NULL,
    entry_price     DOUBLE   NOT NULL,
    exit_price      DOUBLE   NOT NULL,
    return_pct      DOUBLE   NOT NULL,
    exit_reason     VARCHAR(20) NOT NULL,              -- STOP_LOSS | TAKE_PROFIT | SIGNAL | TIME | DAY_END | END_OF_DATA
    success         BOOLEAN  NOT NULL,
    CONSTRAINT fk_trade_run FOREIGN KEY (backtest_run_id) REFERENCES backtest_run (id) ON DELETE CASCADE,
    KEY idx_trade_run (backtest_run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
