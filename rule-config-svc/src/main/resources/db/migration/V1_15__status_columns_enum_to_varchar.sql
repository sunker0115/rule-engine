-- rule_definition / rule_version / scene 的 status ENUM 列改 VARCHAR：ENUM 每加值需 ALTER 且与 app 双重定义，
-- 改 VARCHAR 后取值由 app 层 Java enum 单一真相源校验（实体字段为 enum，按 name() 与列往返）。
-- DEFAULT 沿用 V1_0 原值；metric_definition.status 已在 V1_11 转过，本次不动。
ALTER TABLE rule_definition
  MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DRAFT';

ALTER TABLE rule_version
  MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE scene
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
