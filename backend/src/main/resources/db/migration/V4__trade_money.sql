-- Money view of each trade: how many shares the strategy's investment amount bought, and the
-- resulting won profit net of commission. NULL on runs recorded before this column existed.
ALTER TABLE trade
    ADD COLUMN quantity      BIGINT NULL AFTER instrument,
    ADD COLUMN profit_amount DOUBLE NULL AFTER quantity,
    ADD COLUMN fee_amount    DOUBLE NULL AFTER profit_amount;
