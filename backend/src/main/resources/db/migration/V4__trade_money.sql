-- 거래의 금액 관점: 전략의 투자금으로 몇 주를 샀는지, 그리고 수수료를 뺀 원화 손익이
-- 얼마인지. 이 컬럼이 생기기 전에 기록된 실행에서는 NULL입니다.
ALTER TABLE trade
    ADD COLUMN quantity      BIGINT NULL AFTER instrument,
    ADD COLUMN profit_amount DOUBLE NULL AFTER quantity,
    ADD COLUMN fee_amount    DOUBLE NULL AFTER profit_amount;
