-- =====================================================================
-- V1.0 初始建表 DDL（来源：docs/05-storage.md §三）
-- 命名规范：V{major}_{minor}__{描述}.sql
-- 后续变更新增 V1_1__xxx.sql，不修改本文件
-- =====================================================================

CREATE TABLE IF NOT EXISTS tenant (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(64)  NOT NULL COMMENT '租户标识，全局唯一',
  name        VARCHAR(128) NOT NULL COMMENT '租户名称',
  is_default  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认租户',
  status      ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_by  VARCHAR(64)  COMMENT '创建人',
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  COMMENT '最近修改人',
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户注册表';

CREATE TABLE IF NOT EXISTS scene (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         BIGINT       NOT NULL COMMENT '所属租户 id',
  code              VARCHAR(64)  NOT NULL COMMENT '业务域标识，租户内唯一',
  name              VARCHAR(128) NOT NULL,
  description       TEXT         COMMENT '给运营看的业务说明',
  dominant_mode     ENUM('PUSH','PULL','HYBRID') NOT NULL COMMENT 'PUSH=异步派发/PULL=同步返回/HYBRID=两者',
  decision_strategy ENUM('HIGHEST_PRIORITY') NOT NULL DEFAULT 'HIGHEST_PRIORITY',
  subject_type      ENUM('USER','ACCOUNT','DEVICE','ORDER','CUSTOM') NOT NULL DEFAULT 'USER',
  event_types       JSON         NOT NULL COMMENT '允许的 eventType 白名单数组',
  payload_schema    JSON         COMMENT 'payloadSchema，字段类型 + required 声明',
  default_params    JSON         COMMENT '如 timezone: Asia/Shanghai',
  status            ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_by        VARCHAR(64)  COMMENT '创建人',
  created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by        VARCHAR(64)  COMMENT '最近修改人',
  updated_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务域（Scene）元数据';

CREATE TABLE IF NOT EXISTS metric_definition (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id           BIGINT       NOT NULL,
  metric_code         VARCHAR(128) NOT NULL COMMENT 'metricCode，租户内唯一',
  name                VARCHAR(128) NOT NULL,
  source_type         ENUM('ATTRIBUTE','SQL_AGGREGATE','EXTERNAL_HTTP','STREAM') NOT NULL,
  data_type           ENUM('LONG','DOUBLE','STRING','BOOLEAN','LIST') NOT NULL,
  params              JSON         NOT NULL COMMENT 'sourceType 专属参数',
  cache_ttl_seconds   INT          NOT NULL DEFAULT 60 COMMENT '取数结果缓存 TTL，0=不缓存',
  allow_provided      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许调用方通过 providedMetrics 覆盖',
  status              ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_by          VARCHAR(64)  COMMENT '创建人',
  created_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by          VARCHAR(64)  COMMENT '最近修改人',
  updated_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标元数据';

CREATE TABLE IF NOT EXISTS rule_definition (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  scene_id        BIGINT       NOT NULL COMMENT '关联 scene.id',
  code            VARCHAR(128) NOT NULL COMMENT '规则标识，租户内唯一',
  name            VARCHAR(255) NOT NULL,
  description     TEXT,
  status          ENUM('DRAFT','PUBLISHING','PUBLISHED','PUBLISH_FAILED','DISABLED') NOT NULL DEFAULT 'DRAFT',
  kind            ENUM('AST_BOOLEAN','SCORECARD','DECISION_TREE','DECISION_TABLE','EXPRESSION_SCRIPT') NOT NULL DEFAULT 'AST_BOOLEAN',
  current_version BIGINT       COMMENT '当前有效 rule_version.id；DRAFT/PUBLISHING/PUBLISH_FAILED 时为 null',
  published_by    VARCHAR(64)  COMMENT '最后发布人',
  published_at    TIMESTAMP(3) COMMENT '最后发布时间',
  created_by      VARCHAR(64)  COMMENT '创建人',
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64)  COMMENT '最近修改人',
  updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code),
  KEY idx_scene_id (scene_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则主记录';

CREATE TABLE IF NOT EXISTS rule_version (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_definition_id    BIGINT       NOT NULL COMMENT '关联 rule_definition.id',
  version               BIGINT       NOT NULL COMMENT '单调递增，per rule_definition',
  condition_ast         JSON         NOT NULL COMMENT '完整 AST 节点树，不可变',
  decision_bindings     JSON         NOT NULL COMMENT '含 actions 快照的 Decision 绑定',
  pre_gates             JSON         NOT NULL COMMENT 'Pre-Gate 列表',
  rollout               JSON         NOT NULL COMMENT '灰度配置快照',
  kind                  ENUM('AST_BOOLEAN','SCORECARD','DECISION_TREE','DECISION_TABLE','EXPRESSION_SCRIPT') NOT NULL DEFAULT 'AST_BOOLEAN',
  trigger_event_types   JSON         NOT NULL COMMENT '触发事件类型列表',
  metric_dependencies   JSON         NOT NULL COMMENT 'AST 引用的 metricCode 列表',
  compiled_predicate_ref VARCHAR(256) NULL     COMMENT 'v1 留空，v1.5 预编译优化时启用',
  published_at          TIMESTAMP(3) COMMENT 'NULL=草稿；非 NULL=已发布',
  published_by          VARCHAR(64),
  status                ENUM('ACTIVE','SUPERSEDED','DISABLED','DRAFT') NOT NULL DEFAULT 'ACTIVE',
  created_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_def_version (rule_definition_id, version),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则版本快照（不可变）';

CREATE TABLE IF NOT EXISTS decision_definition (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  code        VARCHAR(64)  NOT NULL COMMENT '决策码，Tenant 内唯一，如 REJECT/REVIEW/PASS',
  name        VARCHAR(128) NOT NULL COMMENT '决策名称',
  priority    INT          NOT NULL COMMENT '优先级，值越小越高',
  description TEXT         COMMENT '给运营/风控看的业务说明',
  actions     JSON         NOT NULL DEFAULT (JSON_ARRAY()) COMMENT 'Action 列表（命中此 Decision 时派发）',
  status      ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_by  VARCHAR(64)  COMMENT '创建人',
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  COMMENT '最近修改人',
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Decision 实体';

CREATE TABLE IF NOT EXISTS rule_decision_binding (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_definition_id  BIGINT       NOT NULL COMMENT '关联 rule_definition.id',
  decision_id         BIGINT       NOT NULL COMMENT '关联 decision_definition.id',
  score_range_min     DECIMAL(10,4) COMMENT '仅 SCORECARD 时有意义，v1 留 null',
  score_range_max     DECIMAL(10,4) COMMENT '仅 SCORECARD 时有意义，v1 留 null',
  created_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rule_decision (rule_definition_id, decision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则与 Decision 绑定关系';

CREATE TABLE IF NOT EXISTS scene_metric_binding (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene_id              BIGINT       NOT NULL COMMENT '关联 scene.id',
  metric_definition_id  BIGINT       NOT NULL COMMENT '关联 metric_definition.id',
  cache_policy_override JSON         COMMENT 'Scene 级缓存策略覆盖，null=使用 metric_definition 默认值',
  created_by            VARCHAR(64)  COMMENT '创建人',
  created_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by            VARCHAR(64)  COMMENT '最近修改人',
  updated_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scene_metric (scene_id, metric_definition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scene 可用 Metric 白名单';

CREATE TABLE IF NOT EXISTS scene_action_binding (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene_id            BIGINT       NOT NULL COMMENT '关联 scene.id',
  action_type         VARCHAR(64)  NOT NULL COMMENT 'ActionHandler 注册的 actionType',
  default_params      JSON         COMMENT 'Scene 级默认参数',
  rate_limit_override JSON         COMMENT 'Scene 级频控覆盖',
  created_by          VARCHAR(64)  COMMENT '创建人',
  created_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by          VARCHAR(64)  COMMENT '最近修改人',
  updated_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scene_action (scene_id, action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scene 可用 ActionType 白名单';

CREATE TABLE IF NOT EXISTS audit_log (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  actor           VARCHAR(64)  NOT NULL COMMENT '操作人（来自请求头 X-Actor-Id）',
  actor_type      ENUM('USER','SYSTEM','JOB') NOT NULL DEFAULT 'USER' COMMENT '操作方类型',
  action          VARCHAR(64)  NOT NULL COMMENT 'CREATE/UPDATE/PUBLISH/PUBLISH_FAILED/ENABLE/DISABLE/DELETE',
  target_type     VARCHAR(64)  NOT NULL COMMENT 'rule_definition/scene/metric_definition 等',
  target_id       VARCHAR(128) NOT NULL,
  before_snapshot JSON         COMMENT '变更前快照',
  after_snapshot  JSON         COMMENT '变更后快照',
  trace_id        VARCHAR(128) COMMENT '请求链路 trace id',
  operated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_tenant_target (tenant_id, target_type, target_id),
  KEY idx_operated_at (operated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置变更审计（D14，同步事务写）';

CREATE TABLE IF NOT EXISTS evaluation_session (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL,
  event_id         VARCHAR(128) NOT NULL COMMENT '业务事件 id（幂等键）',
  scene_code       VARCHAR(64)  NOT NULL,
  event_type       VARCHAR(64)  NOT NULL,
  subject_id       VARCHAR(128) NOT NULL,
  source           ENUM('PUSH','PULL','REPLAY') NOT NULL DEFAULT 'PUSH',
  status           ENUM('PENDING','HIT','MISS','BLOCKED','ERROR','FAILED') NOT NULL DEFAULT 'PENDING',
  final_decision   VARCHAR(64)  COMMENT '最终决策码',
  hit_decisions    JSON         COMMENT '命中的所有决策码列表',
  blocked_by       VARCHAR(64)  COMMENT '仅 status=BLOCKED 时有值',
  error_code       VARCHAR(64)  COMMENT '仅 status=ERROR 时有值',
  candidate_rule_count INT      NOT NULL DEFAULT 0,
  hit_rule_count   INT          NOT NULL DEFAULT 0,
  occurred_at      TIMESTAMP(3) NOT NULL COMMENT '业务事件时间',
  started_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at      TIMESTAMP(3),
  eval_duration_ms INT          COMMENT '整 session 耗时（ms）',
  UNIQUE KEY uk_tenant_event (tenant_id, event_id),
  KEY idx_scene_subject (scene_code, subject_id),
  KEY idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估会话主记录';

CREATE TABLE IF NOT EXISTS node_trace (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_session_id BIGINT       NOT NULL,
  tenant_id             BIGINT       NOT NULL,
  rule_version_id       BIGINT       NOT NULL,
  node_path             VARCHAR(256) NOT NULL COMMENT 'AST 路径，如 "0.1.2"',
  node_type             VARCHAR(64)  NOT NULL,
  condition_type        VARCHAR(64)  COMMENT '仅 ConditionNode',
  metric_code           VARCHAR(128) COMMENT '仅 metric 类 conditionType',
  params                JSON         COMMENT '节点参数快照',
  actual_value          JSON         COMMENT '节点实际取到的值',
  result                TINYINT(1)   COMMENT '1=满足/0=不满足/NULL=短路跳过',
  error_code            VARCHAR(64)  COMMENT 'METRIC_FETCH_FAIL/CONDITION_EVAL_ERROR 等',
  value_source          ENUM('PROVIDED','FETCHED') COMMENT '指标来源',
  evaluated_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_id (evaluation_session_id),
  KEY idx_tenant_evaluated (tenant_id, evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AST 节点求值 trace（异步批写）';

CREATE TABLE IF NOT EXISTS action_execution (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_session_id BIGINT       NOT NULL,
  tenant_id             BIGINT       NOT NULL,
  event_id              VARCHAR(128) NOT NULL COMMENT '冗余字段，用于幂等 UK',
  action_id             VARCHAR(128) NOT NULL,
  action_type           VARCHAR(64)  NOT NULL,
  decision_code         VARCHAR(64)  NOT NULL,
  status                ENUM('PENDING','SUCCESS','FAILED','SKIPPED','RETRYING') NOT NULL DEFAULT 'PENDING',
  error_code            VARCHAR(64),
  retryable             TINYINT(1),
  retry_count           INT          NOT NULL DEFAULT 0,
  executed_at           TIMESTAMP(3),
  compensated           TINYINT(1)   NOT NULL DEFAULT 0,
  compensated_at        TIMESTAMP(3),
  compensated_by        VARCHAR(64),
  created_at            TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_idempotency (tenant_id, event_id, decision_code, action_id),
  KEY idx_session_id (evaluation_session_id),
  KEY idx_status_retryable (status, retryable)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Action 派发执行记录';

CREATE TABLE IF NOT EXISTS dry_run_session (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL,
  event_id         VARCHAR(128) NOT NULL,
  scene_code       VARCHAR(64)  NOT NULL,
  event_type       VARCHAR(64)  NOT NULL,
  subject_id       VARCHAR(128) NOT NULL,
  rule_version_id  BIGINT       NOT NULL,
  status           ENUM('PENDING','HIT','MISS','BLOCKED','ERROR','FAILED') NOT NULL DEFAULT 'PENDING',
  final_decision   VARCHAR(64),
  hit_decisions    JSON,
  blocked_by       VARCHAR(64),
  error_code       VARCHAR(64),
  occurred_at      TIMESTAMP(3) NOT NULL,
  started_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at      TIMESTAMP(3),
  `trigger`        ENUM('MANUAL','API') NOT NULL DEFAULT 'API',
  requested_by     VARCHAR(64)  COMMENT 'dry-run 发起人',
  target_rule_version_id BIGINT COMMENT '指定预览的 RuleVersion id',
  KEY idx_tenant_started (tenant_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='dry-run 评估主记录';

CREATE TABLE IF NOT EXISTS dry_run_node_trace (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  dry_run_session_id    BIGINT       NOT NULL,
  tenant_id             BIGINT       NOT NULL,
  rule_version_id       BIGINT       NOT NULL,
  node_path             VARCHAR(256) NOT NULL,
  node_type             VARCHAR(64)  NOT NULL,
  condition_type        VARCHAR(64),
  metric_code           VARCHAR(128),
  params                JSON,
  actual_value          JSON,
  result                TINYINT(1),
  error_code            VARCHAR(64),
  value_source          ENUM('PROVIDED','FETCHED'),
  evaluated_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_id (dry_run_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='dry-run 节点 trace';
