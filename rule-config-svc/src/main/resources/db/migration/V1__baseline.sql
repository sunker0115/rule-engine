-- 首个公开版本的最终结构基线。
-- 新安装直接执行本文件；已有开发库必须先核对结构并显式标记 Flyway baseline=1，禁止在有数据的库上重放。

CREATE TABLE tenant (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(64)  NOT NULL COMMENT '租户标识，全局唯一',
  name        VARCHAR(128) NOT NULL COMMENT '租户名称',
  type        VARCHAR(16)  NOT NULL DEFAULT 'STANDARD' COMMENT 'STANDARD=普通租户, SYSTEM=平台系统租户',
  is_default  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认租户',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_by  VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  DEFAULT NULL COMMENT '最近修改人',
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_tenant_code UNIQUE (code)
);

CREATE TABLE scene (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         BIGINT       NOT NULL COMMENT '所属租户 id',
  code              VARCHAR(64)  NOT NULL COMMENT '业务域标识，租户内唯一',
  name              VARCHAR(128) NOT NULL,
  description       TEXT         DEFAULT NULL COMMENT '给运营看的业务说明',
  dominant_mode     VARCHAR(16)  NOT NULL,
  decision_strategy VARCHAR(32)  NOT NULL DEFAULT 'HIGHEST_PRIORITY',
  subject_type      VARCHAR(16)  NOT NULL DEFAULT 'USER',
  event_types       JSON         NOT NULL COMMENT '允许的 eventType 白名单数组',
  payload_schema    JSON         DEFAULT NULL COMMENT '字段类型与 required 声明',
  default_params    JSON         DEFAULT NULL COMMENT 'Scene 默认参数',
  status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_by        VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
  created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by        VARCHAR(64)  DEFAULT NULL COMMENT '最近修改人',
  updated_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_scene_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE metric_definition (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id           BIGINT       NOT NULL,
  metric_code         VARCHAR(128) NOT NULL COMMENT '租户内指标编码',
  version             INT          NOT NULL DEFAULT 1 COMMENT '同一指标内单调递增',
  name                VARCHAR(128) NOT NULL,
  source_type         VARCHAR(32)  NOT NULL,
  data_type           VARCHAR(32)  NOT NULL,
  params              JSON         NOT NULL COMMENT 'sourceType 专属参数',
  cache_ttl_seconds   INT          NOT NULL DEFAULT 60 COMMENT '取数缓存 TTL，0=不缓存',
  allow_provided      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许调用方提供指标值',
  `sensitive`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否在 trace 展示出口脱敏',
  status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_by          VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
  created_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by          VARCHAR(64)  DEFAULT NULL COMMENT '最近修改人',
  updated_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_metric_tenant_code_version UNIQUE (tenant_id, metric_code, version)
);

CREATE TABLE connector_definition (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  connector_code  VARCHAR(128) NOT NULL COMMENT '租户内连接器编码',
  name            VARCHAR(128) NOT NULL,
  descriptor      JSON         NOT NULL COMMENT '声明式连接器描述符',
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_by      VARCHAR(64)  DEFAULT NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64)  DEFAULT NULL,
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_connector_tenant_code UNIQUE (tenant_id, connector_code)
);

CREATE TABLE rule_definition (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  scene_code      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '关联 scene.code',
  code            VARCHAR(128) NOT NULL COMMENT '租户内规则编码',
  name            VARCHAR(255) NOT NULL,
  description     TEXT         DEFAULT NULL,
  status          VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
  kind            VARCHAR(32)  NOT NULL DEFAULT 'AST_BOOLEAN',
  current_version BIGINT       DEFAULT NULL COMMENT '当前有效 rule_version.id',
  published_by    VARCHAR(64)  DEFAULT NULL,
  published_at    TIMESTAMP(3) NULL DEFAULT NULL,
  created_by      VARCHAR(64)  DEFAULT NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64)  DEFAULT NULL,
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_rule_tenant_code UNIQUE (tenant_id, code),
  KEY idx_rule_tenant_scene (tenant_id, scene_code)
);

CREATE TABLE rule_version (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_definition_id         BIGINT       NOT NULL,
  version                    BIGINT       NOT NULL COMMENT '同一规则内单调递增',
  body                       JSON         NOT NULL COMMENT 'RuleBody 多态判定主体',
  decision_bindings          JSON         NOT NULL COMMENT 'Decision 绑定快照',
  pre_gates                  JSON         NOT NULL COMMENT 'Pre-Gate 列表',
  kind                       VARCHAR(32)  NOT NULL DEFAULT 'AST_BOOLEAN',
  trigger_event_types        JSON         NOT NULL COMMENT '触发事件类型列表',
  metric_dependencies        JSON         NOT NULL COMMENT '指标版本依赖',
  payload_dependencies       JSON         NOT NULL COMMENT 'payload 字段依赖',
  compiled_predicate_ref     VARCHAR(256) DEFAULT NULL,
  published_at               TIMESTAMP(3) NULL DEFAULT NULL,
  published_by               VARCHAR(64)  DEFAULT NULL,
  status                     VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  created_at                 TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_rule_version_definition_version UNIQUE (rule_definition_id, version),
  KEY idx_rule_version_status (status)
);

CREATE TABLE decision_definition (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  code        VARCHAR(64)  NOT NULL COMMENT '租户内决策编码',
  name        VARCHAR(128) NOT NULL,
  priority    INT          NOT NULL COMMENT '值越小优先级越高',
  description TEXT         DEFAULT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_by  VARCHAR(64)  DEFAULT NULL,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  DEFAULT NULL,
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_decision_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE audit_log (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  actor           VARCHAR(64)  NOT NULL COMMENT '操作人',
  actor_type      VARCHAR(16)  NOT NULL DEFAULT 'USER',
  action          VARCHAR(64)  NOT NULL,
  target_type     VARCHAR(64)  NOT NULL,
  target_id       VARCHAR(128) NOT NULL,
  before_snapshot JSON         DEFAULT NULL,
  after_snapshot  JSON         DEFAULT NULL,
  trace_id        VARCHAR(128) DEFAULT NULL,
  operated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_audit_tenant_target (tenant_id, target_type, target_id),
  KEY idx_audit_operated_at (operated_at)
);

CREATE TABLE evaluation_session (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  BIGINT       NOT NULL,
  event_id                   VARCHAR(128) NOT NULL COMMENT '业务事件幂等键',
  scene_code                 VARCHAR(64)  NOT NULL,
  event_type                 VARCHAR(64)  NOT NULL,
  subject_id                 VARCHAR(128) NOT NULL,
  source                     VARCHAR(16)  NOT NULL DEFAULT 'HTTP',
  mode                       VARCHAR(8)   NOT NULL DEFAULT 'PULL',
  status                     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  final_decision             VARCHAR(64)  DEFAULT NULL,
  hit_decisions              JSON         DEFAULT NULL,
  blocked_by                 VARCHAR(64)  DEFAULT NULL,
  error_code                 VARCHAR(64)  DEFAULT NULL,
  candidate_rule_count       INT          NOT NULL DEFAULT 0,
  hit_rule_count             INT          NOT NULL DEFAULT 0,
  score                      DOUBLE       DEFAULT NULL,
  category                   VARCHAR(64)  DEFAULT NULL,
  occurred_at                TIMESTAMP(3) NOT NULL,
  started_at                 TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at                TIMESTAMP(3) NULL DEFAULT NULL,
  eval_duration_ms           INT          DEFAULT NULL,
  context_snapshot           JSON         DEFAULT NULL,
  payload                    JSON         DEFAULT NULL,
  candidate_rule_version_ids JSON         DEFAULT NULL,
  CONSTRAINT uk_evaluation_tenant_event UNIQUE (tenant_id, event_id),
  KEY idx_evaluation_scene_subject (scene_code, subject_id),
  KEY idx_evaluation_started_at (started_at)
);

CREATE TABLE node_trace (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_session_id BIGINT       NOT NULL,
  tenant_id             BIGINT       NOT NULL,
  rule_version_id       BIGINT       NOT NULL,
  rule_code             VARCHAR(128) DEFAULT NULL,
  rule_version          BIGINT       DEFAULT NULL,
  node_path             VARCHAR(256) NOT NULL,
  node_type             VARCHAR(64)  NOT NULL,
  condition_type        VARCHAR(64)  DEFAULT NULL,
  metric_code           VARCHAR(128) DEFAULT NULL,
  display_label         VARCHAR(256) DEFAULT NULL,
  params                JSON         DEFAULT NULL,
  actual_value          JSON         DEFAULT NULL,
  result                TINYINT(1)   DEFAULT NULL,
  error_code            VARCHAR(64)  DEFAULT NULL,
  value_source          VARCHAR(16)  DEFAULT NULL,
  evaluated_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_trace_session (evaluation_session_id),
  KEY idx_trace_tenant_evaluated (tenant_id, evaluated_at)
);

CREATE TABLE decision_outcome (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT       NOT NULL,
  event_id      VARCHAR(128) NOT NULL,
  outcome_label VARCHAR(64)  NOT NULL,
  outcome_value DECIMAL(18,4) DEFAULT NULL,
  outcome_note  VARCHAR(512) DEFAULT NULL,
  labeled_at    TIMESTAMP(3) NOT NULL,
  source        VARCHAR(64)  DEFAULT NULL,
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_outcome_tenant_event UNIQUE (tenant_id, event_id),
  KEY idx_outcome_tenant_labeled (tenant_id, labeled_at)
);

CREATE TABLE scheduled_task (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  code        VARCHAR(64)  NOT NULL,
  name        VARCHAR(128) NOT NULL,
  task_type   VARCHAR(32)  NOT NULL,
  cron        VARCHAR(128) NOT NULL,
  config      JSON         NOT NULL,
  run_cursor  VARCHAR(64)  DEFAULT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_by  VARCHAR(64)  DEFAULT NULL,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  DEFAULT NULL,
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_scheduled_task_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE scheduled_task_execution (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  scheduled_task_id BIGINT       NOT NULL,
  tenant_id         BIGINT       NOT NULL,
  trigger_at        TIMESTAMP(3) NOT NULL,
  status            VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
  processed_count   INT          NOT NULL DEFAULT 0,
  success_count     INT          NOT NULL DEFAULT 0,
  error_count       INT          NOT NULL DEFAULT 0,
  error_summary     TEXT         DEFAULT NULL,
  finished_at       TIMESTAMP(3) NULL DEFAULT NULL,
  KEY idx_scheduled_execution_task_trigger (scheduled_task_id, trigger_at)
);

CREATE TABLE rule_template (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  code        VARCHAR(128) NOT NULL,
  name        VARCHAR(256) NOT NULL,
  description VARCHAR(1024) DEFAULT NULL,
  kind        VARCHAR(32)  NOT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
  created_by  VARCHAR(64)  DEFAULT NULL,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  DEFAULT NULL,
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_template_tenant_code UNIQUE (tenant_id, code),
  KEY idx_template_tenant_status (tenant_id, status)
);

CREATE TABLE rule_template_version (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_id   BIGINT       NOT NULL,
  version       INT          NOT NULL,
  body_skeleton JSON         NOT NULL,
  slots         JSON         NOT NULL,
  bindings      JSON         NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
  created_by    VARCHAR(64)  DEFAULT NULL,
  created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_template_version_template_version UNIQUE (template_id, version)
);

CREATE TABLE rule_template_instantiation (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_id         BIGINT       NOT NULL,
  template_version_id BIGINT       NOT NULL,
  template_version    INT          NOT NULL,
  rule_definition_id  BIGINT       NOT NULL,
  rule_version_id     BIGINT       NOT NULL,
  slot_values         JSON         NOT NULL,
  instantiated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  instantiated_by     VARCHAR(64)  DEFAULT NULL,
  KEY idx_instantiation_template (template_id),
  KEY idx_instantiation_rule_version (rule_version_id)
);

INSERT INTO tenant (code, name, type, status, created_at, updated_at)
VALUES ('SYSTEM', '平台系统', 'SYSTEM', 'ACTIVE', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3));
