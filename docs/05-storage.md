# 05 — 存储模型与 DDL

> **位置定位**：本文档承载 rule-engine 的**持久化层契约**——表清单 / 各表 DDL / 索引设计 / 不可变快照与数据保留策略。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) 各章节字段表、[`00-decisions.md`](./00-decisions.md) D17 / D19 / D21
>
> **解决什么疑问**："数据库里都有哪些表？""哪些字段有索引？""rule_version 怎么做不可变快照？""node_trace / audit_log 写入路径有什么区别？"
>
> **职责边界**——
> - ✅ 表清单 / DDL / 索引 / 不可变快照实现 / 数据保留策略
> - ❌ 不写概念字段语义（→ 01-concepts，本文档只贴 SQL 类型 + 索引）、不写决策权衡（→ 00-decisions）、不写运维参数（→ 07-operability）、不写 API 字段（→ 10-api-contract）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 表清单总览 | ✅ |
| §三 各表 DDL | ✅ |
| §四 索引设计 | ✅ |
| §五 不可变快照与数据保留 | ✅ |

---

## 二、表清单总览

**配置层表（管理面，量小）：**

| 表名 | 职责 | 写入路径 | 生命周期 |
|---|---|---|---|
| `tenant` | 租户注册 | 同步事务 | 永久 |
| `scene` | 业务域元数据（dominantMode / payloadSchema / decisionStrategy） | 同步事务 | 永久 |
| `metric_definition` | 指标元数据（sourceType / params / cacheTtl / allowProvided） | 同步事务 | 永久 |
| `rule_definition` | 规则主记录（code / name / status） | 同步事务 | 永久 |
| `rule_version` | 规则版本快照（conditionAst / decisionBindings / preGates），不可变（D19） | 同步事务（发布时） | 永久（不可删） |
| `decision_definition` | Decision 实体（Tenant 级）— 决策码 / 名称 / 优先级 / actions（D26/D27） | 同步事务 | 永久 |
| `rule_decision_binding` | 规则与 Decision 的绑定关系（支持可选 score 区间，D26 SCORECARD 占位） | 同步事务 | 永久 |
| `scene_metric_binding` | Scene 可用 Metric 白名单（D30），Rule 发布时校验 | 同步事务 | 永久 |
| `scene_action_binding` | Scene 可用 ActionType 白名单（D27），仅 PUSH/HYBRID Scene | 同步事务 | 永久 |
| `job_definition` | 定时触发规则配置（§3.10），调度器到点合成 RuleEvent | 同步事务 | 永久 |
| `job_execution` | Job 每次运行记录（§3.10） | 异步 | 永久 |
| `audit_log` | 配置变更审计——人的行为（D14，同步事务红线） | 同步事务 | 永久 |

**评估层表（运行面，量大）：**

| 表名 | 职责 | 写入路径 | 生命周期 |
|---|---|---|---|
| `evaluation_session` | 每次评估主记录 / 幂等锚点（D11 / D21 同步写） | **同步**（session 行） | 30 天 TTL（D9） |
| `node_trace` | AST 各节点求值 trace（D7 / D21 异步批写） | **异步批写** | 30 天 TTL（D9） |
| `action_execution` | Action 派发执行记录（D4） | 异步 | 30 天 TTL |
| `dry_run_session` | dry-run 评估主记录（与 prod 隔离，D7） | 同步 | 7 天 TTL |
| `dry_run_node_trace` | dry-run 节点 trace | 异步批写 | 7 天 TTL |

---

## 三、各表 DDL

> DDL 版本管理遵循 Flyway 命名规范：`V{major}_{minor}__{描述}.sql`，如 `V1_0__init_schema.sql`。v1 所有建表 SQL 合并到 `V1_0__init_schema.sql`，后续变更新增 `V1_1__xxx.sql`（不改已有 migration 文件）。

### 3.1 配置层

**tenant**

```sql
CREATE TABLE tenant (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(64)  NOT NULL COMMENT '租户标识，全局唯一',
  name        VARCHAR(128) NOT NULL COMMENT '租户名称',
  status      ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户注册表';
```

**scene**

```sql
CREATE TABLE scene (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         BIGINT       NOT NULL COMMENT '所属租户 id',
  code              VARCHAR(64)  NOT NULL COMMENT '业务域标识，租户内唯一',
  name              VARCHAR(128) NOT NULL,
  dominant_mode     ENUM('PUSH','PULL','HYBRID') NOT NULL COMMENT 'PUSH=异步派发/PULL=同步返回/HYBRID=两者',
  decision_strategy ENUM('HIGHEST_PRIORITY') NOT NULL DEFAULT 'HIGHEST_PRIORITY' COMMENT 'D29 v1 仅实现 HIGHEST_PRIORITY；v2 扩展 MAJORITY / CUSTOM_SPI 时加列',
  subject_type      ENUM('USER','ACCOUNT','DEVICE','ORDER','CUSTOM') NOT NULL DEFAULT 'USER',
  event_types       JSON         NOT NULL COMMENT 'D13：允许的 eventType 白名单数组，发布校验 + 事件接入双校验',
  payload_schema    JSON         COMMENT 'payloadSchema D13，字段类型 + required 声明',
  default_params    JSON         COMMENT '如 timezone: Asia/Shanghai',
  status            ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务域（Scene）元数据';
```

**metric_definition**

```sql
CREATE TABLE metric_definition (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id           BIGINT       NOT NULL,
  metric_code         VARCHAR(128) NOT NULL COMMENT 'metricCode，租户内唯一',
  name                VARCHAR(128) NOT NULL,
  source_type         ENUM('ATTRIBUTE','SQL_AGGREGATE','EXTERNAL_HTTP','STREAM') NOT NULL,
  data_type           ENUM('LONG','DOUBLE','STRING','BOOLEAN','LIST') NOT NULL,
  params              JSON         NOT NULL COMMENT 'sourceType 专属参数（sql/url/column 等）',
  cache_ttl_seconds   INT          NOT NULL DEFAULT 60 COMMENT '取数结果缓存 TTL，0=不缓存',
  allow_provided      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'D30：是否允许调用方通过 providedMetrics 覆盖',
  status              ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标元数据（sourceType / params / cacheTtl / allowProvided）';
```

**rule_definition**

```sql
CREATE TABLE rule_definition (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     BIGINT       NOT NULL,
  scene_id      BIGINT       NOT NULL COMMENT '关联 scene.id',
  code          VARCHAR(128) NOT NULL COMMENT '规则标识，租户内唯一',
  name          VARCHAR(255) NOT NULL,
  description   TEXT,
  status          ENUM('DRAFT','PUBLISHING','PUBLISHED','PUBLISH_FAILED','DISABLED') NOT NULL DEFAULT 'DRAFT' COMMENT 'D19 状态机：DRAFT→PUBLISHING→PUBLISHED/PUBLISH_FAILED；DISABLED=关停',
  kind            ENUM('AST_BOOLEAN','SCORECARD','DECISION_TREE','DECISION_TABLE','EXPRESSION_SCRIPT') NOT NULL DEFAULT 'AST_BOOLEAN' COMMENT 'D12：Rule 类型占位，v1 仅 AST_BOOLEAN 实装，其他枚举值发布时拒绝',
  current_version BIGINT       COMMENT '当前有效 rule_version.id；DRAFT/PUBLISHING/PUBLISH_FAILED 时为 null',
  published_by    VARCHAR(64)  COMMENT '最后发布人（来自 audit_log.actor）',
  published_at    DATETIME(3)  COMMENT '最后发布时间',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code),
  KEY idx_scene_id (scene_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则主记录（D12 kind 占位，D19 状态机）';
```

**rule_version**（不可变，D19 — 写入后永不 UPDATE/DELETE）

```sql
CREATE TABLE rule_version (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_definition_id    BIGINT       NOT NULL COMMENT '关联 rule_definition.id',
  version               INT          NOT NULL COMMENT '单调递增，per rule_definition',
  condition_ast         JSON         NOT NULL COMMENT '完整 AST 节点树，不可变',
  decision_bindings     JSON         NOT NULL COMMENT 'D27/D28：含 actions 快照的 Decision 绑定',
  pre_gates             JSON         NOT NULL COMMENT 'Pre-Gate 列表（含 ROLLOUT / WHITELIST / BLACKLIST / RATE_LIMIT / MUTEX 各类型配置）',
  rollout               JSON         NOT NULL COMMENT 'D6 灰度配置快照（type / percentage / tagConditions）；空对象表示无灰度限制，全量放行',
  kind                  ENUM('AST_BOOLEAN','SCORECARD','DECISION_TREE','DECISION_TABLE','EXPRESSION_SCRIPT') NOT NULL DEFAULT 'AST_BOOLEAN' COMMENT 'D12：规则形态冻结；v1 仅 AST_BOOLEAN 实装，其他占位',
  trigger_event_types   JSON         NOT NULL COMMENT '触发事件类型列表',
  metric_dependencies   JSON         NOT NULL COMMENT 'AST 引用的 metricCode 列表（发布期静态收集）',
  compiled_predicate_ref VARCHAR(256) NULL     COMMENT 'D20 §5：编译产物引用键，v1 留空，v1.5 预编译优化时启用',
  published_at          DATETIME(3)           COMMENT 'NULL = 草稿；非 NULL = 已发布',
  published_by          VARCHAR(64),
  status                ENUM('ACTIVE','SUPERSEDED','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=当前有效/SUPERSEDED=被新版本取代/DISABLED=手动禁用',
  created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_def_version (rule_definition_id, version),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则版本快照（不可变，D19）';
```

**decision_definition**（D26/D27）

```sql
CREATE TABLE decision_definition (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  code        VARCHAR(64)  NOT NULL COMMENT '决策码，Tenant 内唯一，如 REJECT/REVIEW/PASS',
  name        VARCHAR(128) NOT NULL COMMENT '决策名称，如"拒绝"/"人工审核"/"放行"',
  priority    INT          NOT NULL COMMENT '优先级，值越小越高（D26：HIGHEST_PRIORITY 策略取 priority 最小的命中决策）',
  actions     JSON         NOT NULL DEFAULT '[]' COMMENT 'D27：Action 列表（命中此 Decision 时派发），含 actionId/actionType/sortOrder/failFast/compensateActionType/params',
  status      ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Decision 实体（D26，Tenant 级）；actions 字段在发布时快照到 rule_version.decision_bindings（D28）';
```

**rule_decision_binding**（D26：规则绑定 Decision，支持 score 区间占位）

```sql
CREATE TABLE rule_decision_binding (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_definition_id BIGINT      NOT NULL COMMENT '关联 rule_definition.id',
  decision_id       BIGINT       NOT NULL COMMENT '关联 decision_definition.id',
  score_range_min   DECIMAL(10,4) COMMENT '仅 Rule.kind=SCORECARD 时有意义，v1 留 null',
  score_range_max   DECIMAL(10,4) COMMENT '仅 Rule.kind=SCORECARD 时有意义，v1 留 null',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rule_decision (rule_definition_id, decision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则与 Decision 绑定关系（D26）；score 区间 v1 为 null 占位，SCORECARD kind 时启用';
```

**scene_metric_binding**（Scene 与 Metric 白名单关联）

```sql
CREATE TABLE scene_metric_binding (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene_id              BIGINT       NOT NULL COMMENT '关联 scene.id',
  metric_definition_id  BIGINT       NOT NULL COMMENT '关联 metric_definition.id',
  cache_policy_override JSON         COMMENT 'Scene 级缓存策略覆盖（ttl_seconds），null=使用 metric_definition 默认值',
  created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scene_metric (scene_id, metric_definition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scene 可用 Metric 白名单（D30）；Rule 发布时校验 AST 引用的 metricCode 必须在此列表内';
```

**scene_action_binding**（Scene 与 ActionType 白名单关联，仅 PUSH/HYBRID Scene）

```sql
CREATE TABLE scene_action_binding (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene_id          BIGINT       NOT NULL COMMENT '关联 scene.id',
  action_type       VARCHAR(64)  NOT NULL COMMENT 'ActionHandler 注册的 actionType，如 ticket.create',
  default_params    JSON         COMMENT 'Scene 级默认参数，与 Decision.actions[n].params 合并（Decision 级优先）',
  rate_limit_override JSON       COMMENT 'Scene 级频控覆盖，null=使用 ActionHandler 默认',
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scene_action (scene_id, action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scene 可用 ActionType 白名单（D27）；仅 PUSH/HYBRID Scene 使用；PULL Scene 发布时校验 actions 为空';
```

**job_definition**（定时触发规则，非一等公民）

```sql
CREATE TABLE job_definition (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  scene_id        BIGINT       NOT NULL COMMENT '关联 scene.id，PULL Scene 不允许配置 Job（发布拒绝）',
  code            VARCHAR(128) NOT NULL COMMENT 'Job 标识，Scene 内唯一',
  name            VARCHAR(255) NOT NULL,
  cron_expression VARCHAR(128) NOT NULL COMMENT 'Cron 表达式，如 0 2 * * *',
  subject_query   JSON         NOT NULL COMMENT '主体集合查询配置（SQL / API），到点批量拉取触发主体',
  event_type      VARCHAR(64)  NOT NULL COMMENT '合成 RuleEvent 时使用的 eventType',
  payload_template JSON        COMMENT '合成 RuleEvent.payload 的模板（支持占位符替换）',
  status          ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scene_code (scene_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时触发规则配置（§3.10）；调度器到点合成 RuleEvent 注入标准评估链路';
```

**job_execution**（每次 Job 运行记录）

```sql
CREATE TABLE job_execution (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_definition_id BIGINT     NOT NULL COMMENT '关联 job_definition.id',
  tenant_id       BIGINT       NOT NULL,
  trigger_at      DATETIME(3)  NOT NULL COMMENT '调度器触发时间',
  status          ENUM('RUNNING','SUCCESS','PARTIAL_FAIL','FAILED') NOT NULL DEFAULT 'RUNNING',
  subject_count   INT          NOT NULL DEFAULT 0 COMMENT '本次批次主体总数',
  success_count   INT          NOT NULL DEFAULT 0 COMMENT '成功注入评估链路的主体数',
  error_count     INT          NOT NULL DEFAULT 0 COMMENT '失败数（含主体查询失败 + 事件注入失败）',
  error_summary   TEXT         COMMENT '失败摘要（抽样错误信息）',
  finished_at     DATETIME(3),
  KEY idx_job_trigger (job_definition_id, trigger_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Job 每次运行记录（§3.10）';
```

**audit_log**（D14：人的行为，同步事务，永久保留）

```sql
CREATE TABLE audit_log (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  actor           VARCHAR(64)  NOT NULL COMMENT '操作人（来自请求头 X-Actor-Id，D14）',
  actor_type      ENUM('USER','SYSTEM','JOB') NOT NULL DEFAULT 'USER' COMMENT 'D14：操作方类型（来自请求头 X-Actor-Type）',
  action          VARCHAR(64)  NOT NULL COMMENT 'CREATE / UPDATE / PUBLISH / PUBLISH_FAILED / ENABLE / DISABLE / DELETE',
  target_type     VARCHAR(64)  NOT NULL COMMENT 'rule_definition / scene / metric_definition 等',
  target_id       VARCHAR(128) NOT NULL,
  before_snapshot JSON         COMMENT '变更前快照',
  after_snapshot  JSON         COMMENT '变更后快照（PUBLISH_FAILED 时含 errorCode 字段）',
  trace_id        VARCHAR(128) COMMENT '请求链路 trace id，便于关联日志',
  operated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_tenant_target (tenant_id, target_type, target_id),
  KEY idx_operated_at (operated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置变更审计（D14，同步事务写）';
```

### 3.2 评估层

**evaluation_session**（D11/D21 同步写；幂等 UK；30 天 TTL）

```sql
CREATE TABLE evaluation_session (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL,
  event_id         VARCHAR(128) NOT NULL COMMENT '业务事件 id（幂等键第二列，D11）',
  scene_code       VARCHAR(64)  NOT NULL,
  event_type       VARCHAR(64)  NOT NULL COMMENT '业务事件类型（Matcher 路由三元组之一）',
  subject_id       VARCHAR(128) NOT NULL,
  source           ENUM('PUSH','PULL','REPLAY') NOT NULL DEFAULT 'PUSH' COMMENT 'D23：评估触发方式（PUSH=异步推送 / PULL=同步调用 / REPLAY=事件回放），与 RuleEvent.source（HTTP/MQ/JOB/SDK/REPLAY）不同维度，不改幂等语义',
  status           ENUM('PENDING','HIT','MISS','BLOCKED','ERROR','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=进行中；HIT/MISS/BLOCKED/ERROR=D22 四态；FAILED=异常崩溃',
  final_decision   VARCHAR(64)  COMMENT '最终决策码（nullable，未命中或 BLOCKED 时为 null）',
  hit_decisions    JSON         COMMENT '命中的所有决策码列表',
  blocked_by       VARCHAR(64)  COMMENT '仅 status=BLOCKED 时有值，合法值：ROLLOUT / WHITELIST / BLACKLIST / RATE_LIMIT / MUTEX（D22，共 5 种）',
  error_code       VARCHAR(64)  COMMENT '仅 status=ERROR 时有值，D15 EvalResult.errorCode',
  candidate_rule_count INT      NOT NULL DEFAULT 0 COMMENT 'Matcher 命中的候选 RuleVersion 数量',
  hit_rule_count   INT          NOT NULL DEFAULT 0 COMMENT 'AST 求值满足（HIT）的 Rule 数量',
  occurred_at      DATETIME(3)  NOT NULL COMMENT '业务事件时间',
  started_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '引擎开始评估时间',
  finished_at      DATETIME(3)  COMMENT 'status 从 PENDING 更新为终态的时间',
  eval_duration_ms INT          COMMENT '整 session 耗时（ms）',
  UNIQUE KEY uk_tenant_event (tenant_id, event_id),
  KEY idx_scene_subject (scene_code, subject_id),
  KEY idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估会话主记录（D11/D21，v1 同步写）';
```

**node_trace**（D7/D21 异步批写；30 天 TTL）

```sql
CREATE TABLE node_trace (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_session_id BIGINT       NOT NULL COMMENT '关联 evaluation_session.id',
  tenant_id             BIGINT       NOT NULL,
  rule_version_id       BIGINT       NOT NULL,
  node_path             VARCHAR(256) NOT NULL COMMENT 'AST 路径，如 "0.1.2"（根=0）',
  node_type             VARCHAR(64)  NOT NULL COMMENT 'AndNode / OrNode / NotNode / ConditionNode / PRE_GATE_BLOCKED',
  condition_type        VARCHAR(64)  COMMENT 'nullable，仅 ConditionNode',
  metric_code           VARCHAR(128) COMMENT 'nullable，仅 metric 类 conditionType',
  params                JSON         COMMENT '节点参数快照',
  actual_value          JSON         COMMENT '节点实际取到的值（nullable，短路跳过为 null）',
  result                TINYINT(1)   COMMENT '1=满足/0=不满足/NULL=短路跳过',
  error_code            VARCHAR(64)  COMMENT 'nullable，METRIC_FETCH_FAIL / CONDITION_EVAL_ERROR 等',
  value_source          ENUM('PROVIDED','FETCHED') COMMENT 'D30：指标来源（nullable，仅 metric 类 ConditionNode）',
  evaluated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_id (evaluation_session_id),
  KEY idx_tenant_evaluated (tenant_id, evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AST 节点求值 trace（D7/D21，异步批写）';
```

**action_execution**

```sql
CREATE TABLE action_execution (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_session_id BIGINT       NOT NULL COMMENT '关联 evaluation_session.id',
  tenant_id             BIGINT       NOT NULL,
  event_id              VARCHAR(128) NOT NULL COMMENT '来自 evaluation_session.event_id 的冗余字段，用于幂等 UK（D27）',
  action_id             VARCHAR(128) NOT NULL COMMENT 'Decision.actions[n].actionId',
  action_type           VARCHAR(64)  NOT NULL,
  decision_code         VARCHAR(64)  NOT NULL COMMENT '触发本 Action 的 Decision 码（D27）',
  status                ENUM('PENDING','SUCCESS','FAILED','SKIPPED','RETRYING') NOT NULL DEFAULT 'PENDING',
  error_code            VARCHAR(64)  COMMENT 'TIMEOUT / BUSINESS_REJECTED / PREDECESSOR_FAILED 等',
  retryable             TINYINT(1)   COMMENT '1=可重试；0=不可重试；NULL=尚未执行',
  retry_count           INT          NOT NULL DEFAULT 0,
  executed_at           DATETIME(3)  COMMENT '最后一次执行时间',
  created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_idempotency (tenant_id, event_id, decision_code, action_id) COMMENT 'D27 幂等 UK：DB 层最终防重',
  KEY idx_session_id (evaluation_session_id),
  KEY idx_status_retryable (status, retryable)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Action 派发执行记录（D4/D27）';
```

**dry_run_session**（与 prod evaluation_session 同结构，7 天 TTL，D7）

```sql
CREATE TABLE dry_run_session (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL,
  event_id         VARCHAR(128) NOT NULL,
  scene_code       VARCHAR(64)  NOT NULL,
  event_type       VARCHAR(64)  NOT NULL COMMENT '业务事件类型',
  subject_id       VARCHAR(128) NOT NULL,
  rule_version_id  BIGINT       NOT NULL,
  status           ENUM('PENDING','HIT','MISS','BLOCKED','ERROR','FAILED') NOT NULL DEFAULT 'PENDING',
  final_decision   VARCHAR(64),
  hit_decisions    JSON,
  blocked_by       VARCHAR(64),
  error_code       VARCHAR(64),
  occurred_at      DATETIME(3)  NOT NULL,
  started_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at      DATETIME(3),
  trigger          ENUM('MANUAL','API') NOT NULL DEFAULT 'API' COMMENT 'dry-run 触发来源',
  requested_by     VARCHAR(64)  COMMENT 'dry-run 发起人（来自请求头 X-Actor-Id，D14）',
  target_rule_version_id BIGINT COMMENT '指定预览的 RuleVersion id；null 时使用 current_version，可提前预览未发布版本',
  KEY idx_tenant_started (tenant_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='dry-run 评估主记录（与 prod 隔离，D7）';
```

**dry_run_node_trace**（与 node_trace 同结构，7 天 TTL）

```sql
CREATE TABLE dry_run_node_trace (
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
  evaluated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_id (dry_run_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='dry-run 节点 trace（与 prod 隔离，D7）';
```

---

## 四、索引设计

### 评估热路径索引（运行期读，性能关键）

| 表 | 索引 | 查询模式 |
|---|---|---|
| `evaluation_session` | UK `uk_tenant_event (tenant_id, event_id)` | 幂等检查：D11 下半层 DB uk |
| `evaluation_session` | `idx_started_at (started_at)` | 定时清理：按时间范围删除 30 天外数据 |
| `node_trace` | `idx_session_id (evaluation_session_id)` | 按 session 查 trace（排障 / dry-run 对比） |

Matcher 路由不走 DB（运行时内存倒排索引，D17 派生）。

### 运营查询索引（非热路径，管理面）

| 表 | 索引 | 查询模式 |
|---|---|---|
| `evaluation_session` | `idx_scene_subject (scene_code, subject_id)` | 按用户查历史评估记录 |
| `node_trace` | `idx_tenant_evaluated (tenant_id, evaluated_at)` | 对账：按租户 + 时间范围聚合 trace 量 |
| `action_execution` | UK `uk_idempotency (tenant_id, event_id, decision_code, action_id)` | Action 派发幂等检查（D27 DB 层最终防重） |
| `action_execution` | `idx_status_retryable (status, retryable)` | 重试队列扫描（查 status=FAILED AND retryable=1） |
| `action_execution` | `idx_session_id (evaluation_session_id)` | 按 session 查 action 执行记录 |
| `rule_definition` | `idx_scene_id (scene_id)` | 按 Scene 查规则列表 |
| `rule_version` | UK `uk_def_version (rule_definition_id, version)` | 版本唯一性约束 + 按规则查所有版本 |
| `audit_log` | `idx_tenant_target (tenant_id, target_type, target_id)` | 查某个规则/Scene 的所有变更记录 |
| `audit_log` | `idx_operated_at (operated_at)` | 按时间范围查审计日志 |
| `decision_definition` | UK `uk_tenant_code (tenant_id, code)` | Tenant 内 Decision 码唯一性约束 + 发布时查 Decision |
| `rule_decision_binding` | UK `uk_rule_decision (rule_definition_id, decision_id)` | 规则与 Decision 绑定唯一性 |
| `scene_metric_binding` | UK `uk_scene_metric (scene_id, metric_definition_id)` | Rule 发布时验证 metricCode 在白名单内 |
| `scene_action_binding` | UK `uk_scene_action (scene_id, action_type)` | Rule 发布时验证 actionType 在白名单内 |
| `job_definition` | UK `uk_scene_code (scene_id, code)` | Scene 内 Job 唯一性约束 |
| `job_execution` | `idx_job_trigger (job_definition_id, trigger_at)` | 按 Job 查运行历史 |

### 分区建议（v1 不做，v2 演进）

`node_trace` 和 `evaluation_session` 数据量最大（百万~亿/天），v1 靠定时 DELETE 清理 30 天外数据，v2 按 `evaluated_at` 月分区（见 `08-evolution.md` §2.5 trace 冷热分级）。

---

## 五、不可变快照与数据保留

### 不可变快照策略（D19）

`rule_version` 行一旦发布（`published_at` 非 null）永不 UPDATE / DELETE：

- 修改规则 = 创建新 version（version 单调递增，per rule_definition）
- 旧 version `status` 改为 `SUPERSEDED`（仍可被 `node_trace.rule_version_id` 引用，历史评估节点 trace 可追溯至对应版本；同时可通过 `action_execution.decision_code` 关联 `rule_version.decision_bindings`，追溯 Action 派发时所绑定的 Decision 快照）
- 新 version INSERT，Matcher 倒排索引热更指向新 version（≤15s 全实例收敛，D17）
- 回滚 = 用旧 version 的 `condition_ast` / `decision_bindings` 内容新建草稿 → 走标准发布流程产出新 version 号，不是直接切回旧 version（避免 current_version 倒退造成审计断层）

### 数据保留策略（D9：v1 全 MySQL，30 天保留）

| 表 | 保留期 | 清理方式 |
|---|---|---|
| `evaluation_session` | 30 天 | 定时任务 `DELETE WHERE started_at < NOW() - INTERVAL 30 DAY LIMIT 5000` |
| `node_trace` | 30 天 | 同上，`LIMIT 10000` |
| `action_execution` | 30 天 | 同 evaluation_session（跟随其生命周期） |
| `dry_run_session` | 7 天 | 定时任务 `DELETE WHERE started_at < NOW() - INTERVAL 7 DAY LIMIT 2000` |
| `dry_run_node_trace` | 7 天 | 同上 |
| `audit_log` | **永久** | 不清理 |
| 配置层所有表 | **永久** | 不清理（rule_version 不可删，D19） |

### Flyway 命名规范（DDL 版本管理）

文件命名：`V{major}_{minor}__{描述}.sql`，如 `V1_0__init_schema.sql`

v1 所有建表 SQL 合并到 `V1_0__init_schema.sql`，后续变更新增 `V1_1__xxx.sql`（不改已有 migration 文件）。

---

## 六、维护原则

- 本文档**唯一持有 DDL**——01-concepts 字段表与本文档 SQL 类型变更必须同步。
- 新增表必须在 §二 + §三 + §四 三处同步登记。
- 索引变更要在 §四 注明"承载哪个查询模式"，避免后人不敢删未知用途索引。
- 字段语义讨论留 01-concepts，本文档只列"SQL 类型 + 索引 + 写入路径"。
