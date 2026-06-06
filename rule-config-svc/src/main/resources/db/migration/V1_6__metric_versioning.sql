-- B6 Metric 版本化：metric_definition 加 version 列 + SUPERSEDED 状态 + UK 改为含 version
-- 升语义=旧 ACTIVE 行 status->SUPERSEDED + INSERT 新行 version+1 status=ACTIVE（应用层同事务保证至多一行 ACTIVE）
ALTER TABLE metric_definition
  ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT '指标定义版本号，per (tenant_id, metric_code) 单调递增' AFTER metric_code;

ALTER TABLE metric_definition
  MODIFY COLUMN status ENUM('ACTIVE','SUPERSEDED','DISABLED') NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE metric_definition
  DROP INDEX uk_tenant_code,
  ADD UNIQUE KEY uk_tenant_code_version (tenant_id, metric_code, version);
