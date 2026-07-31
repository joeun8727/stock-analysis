-- Promote ETF pairing from a free-text group_id to a first-class group entity, so a symbol's
-- leverage / inverse / futures are assigned to a managed group (one slot each) instead of a
-- hand-typed string that must match across three uploads.

CREATE TABLE etf_group (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME     NOT NULL,
    UNIQUE KEY uq_etf_group_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE dataset ADD COLUMN etf_group_id BIGINT NULL AFTER kind;

-- Backfill: create a group per distinct legacy group_id, then link datasets to it.
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
