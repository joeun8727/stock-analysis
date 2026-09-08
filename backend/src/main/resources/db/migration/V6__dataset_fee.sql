-- 수수료는 전략이 아니라 종목에 붙습니다: 레버리지 ETF와 인버스는 요율이 다를 수 있는데
-- ETF 장전 실행은 둘 다 매매합니다. 기존 행은 전역 요율을 물려받고, 이제부터 전역 값은
-- 새로 업로드하는 데이터셋의 초기값 역할만 합니다.
ALTER TABLE dataset ADD COLUMN fee_rate_pct DOUBLE NOT NULL DEFAULT 0.015 AFTER kind;

UPDATE dataset
SET fee_rate_pct = COALESCE((SELECT fee_rate_pct FROM fee_setting WHERE id = 1), 0.015);
