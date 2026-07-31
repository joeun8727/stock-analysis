-- Commission is an account-level fact, not a per-strategy one: one row holds the rate every
-- backtest uses. Seeded with the previous per-strategy default (0.015% per side).
CREATE TABLE fee_setting (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    fee_rate_pct DOUBLE   NOT NULL,
    updated_at   DATETIME NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO fee_setting (id, fee_rate_pct, updated_at) VALUES (1, 0.015, NOW());
