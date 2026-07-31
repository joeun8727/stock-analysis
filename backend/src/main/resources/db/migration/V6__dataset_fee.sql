-- Commission belongs to the symbol, not the strategy: a leverage ETF and its inverse can sit at
-- different rates, and an ETF pre-market run trades both. Existing rows inherit the global rate,
-- which from here on only seeds newly uploaded datasets.
ALTER TABLE dataset ADD COLUMN fee_rate_pct DOUBLE NOT NULL DEFAULT 0.015 AFTER kind;

UPDATE dataset
SET fee_rate_pct = COALESCE((SELECT fee_rate_pct FROM fee_setting WHERE id = 1), 0.015);
