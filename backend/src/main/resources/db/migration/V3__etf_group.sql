-- ETF 쌍 묶기를 자유 입력 group_id에서 1급 그룹 엔티티로 승격합니다. 그래야 레버리지 /
-- 인버스 / 선물이 관리되는 그룹의 슬롯(각각 하나)에 배정되고, 업로드 세 번에 걸쳐 손으로
-- 똑같이 입력해야 하는 문자열에 의존하지 않게 됩니다.

CREATE TABLE etf_group (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME     NOT NULL,
    UNIQUE KEY uq_etf_group_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE dataset ADD COLUMN etf_group_id BIGINT NULL AFTER kind;

-- 백필: 기존 group_id 값마다 그룹을 만들고, 데이터셋을 거기에 연결합니다.
INSERT INTO etf_group (name, created_at)
SELECT DISTINCT group_id, NOW()
FROM dataset
WHERE group_id IS NOT NULL AND group_id <> '';

UPDATE dataset d
JOIN etf_group g ON d.group_id = g.name
SET d.etf_group_id = g.id
WHERE d.group_id IS NOT NULL AND d.group_id <> '';

ALTER TABLE dataset
    ADD CONSTRAINT fk_dataset_etf_group FOREIGN KEY (etf_group_id) REFERENCES etf_group (id);

ALTER TABLE dataset DROP COLUMN group_id;
