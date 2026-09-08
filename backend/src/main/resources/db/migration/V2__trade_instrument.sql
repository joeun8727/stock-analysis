-- ETF 장전 모드는 각 거래가 어느 쪽(LEVERAGE/INVERSE)을 잡았는지 기록합니다.
ALTER TABLE trade ADD COLUMN instrument VARCHAR(20) NULL AFTER exit_reason;
