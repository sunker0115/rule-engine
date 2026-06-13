-- 列序调整（前向迁移，不改 V1_26/V1_28 历史以免 Flyway checksum 不一致）：
-- 把 rule_code/rule_version 紧随 rule_version_id 聚为「规则身份」字段组；script_source 紧随 condition_ast。
-- 纯列重排，列定义与 V1_26/V1_28 保持一致。

ALTER TABLE node_trace
  MODIFY COLUMN rule_code    VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余,人类可读)' AFTER rule_version_id,
  MODIFY COLUMN rule_version BIGINT       NULL COMMENT '规则版本号(冗余)'          AFTER rule_code;

ALTER TABLE dry_run_session
  MODIFY COLUMN rule_code    VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余)' AFTER rule_version_id,
  MODIFY COLUMN rule_version BIGINT       NULL COMMENT '规则版本号(冗余)'   AFTER rule_code;

ALTER TABLE dry_run_node_trace
  MODIFY COLUMN rule_code    VARCHAR(128) NULL COMMENT '规则逻辑编码(冗余)' AFTER rule_version_id,
  MODIFY COLUMN rule_version BIGINT       NULL COMMENT '规则版本号(冗余)'   AFTER rule_code;

ALTER TABLE rule_version
  MODIFY COLUMN script_source JSON NULL COMMENT 'EXPRESSION_SCRIPT 脚本载体 {source,lang}，其它 kind 为 NULL'
      AFTER condition_ast;
