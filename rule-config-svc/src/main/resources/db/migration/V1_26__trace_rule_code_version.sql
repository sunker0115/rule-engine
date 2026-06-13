-- 规则身份 (code, version) 阶段甲:trace/审计表补冗余逻辑键(supplement,保留 rule_version_id)
ALTER TABLE node_trace
  ADD COLUMN rule_code    VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余,人类可读)',
  ADD COLUMN rule_version BIGINT       NULL COMMENT '规则版本号(冗余)';

ALTER TABLE dry_run_session
  ADD COLUMN rule_code    VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余)',
  ADD COLUMN rule_version BIGINT       NULL COMMENT '规则版本号(冗余)';

ALTER TABLE dry_run_node_trace
  ADD COLUMN rule_code    VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余)',
  ADD COLUMN rule_version BIGINT       NULL COMMENT '规则版本号(冗余)';
