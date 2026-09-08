-- 수수료는 전략별이 아니라 계좌 차원의 사실입니다: 모든 백테스트가 쓰는 요율을 한 행에
-- 둡니다. 이전의 전략별 기본값(편도 0.015%)으로 심어둡니다.
CREATE TABLE fee_setting (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    fee_rate_pct DOUBLE   NOT NULL,
    updated_at   DATETIME NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO fee_setting (id, fee_rate_pct, updated_at) VALUES (1, 0.015, NOW());
