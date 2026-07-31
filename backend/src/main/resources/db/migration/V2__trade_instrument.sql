-- ETF pre-market mode records which side (LEVERAGE/INVERSE) each trade took.
ALTER TABLE trade ADD COLUMN instrument VARCHAR(20) NULL AFTER exit_reason;
