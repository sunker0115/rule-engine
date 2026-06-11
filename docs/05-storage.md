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
  is_default  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认租户（单业务线启动时只有一个 default 租户）',
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '取值: ACTIVE/DISABLED',
  created_by  VARCHAR(64)  COMMENT '创建人（来自 X-Actor-Id header，D14）',
  created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  COMMENT '最近修改人（来自 X-Actor-Id header，D14）',
  updated_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
  description       TEXT         COMMENT '给运营看的业务说明',
  dominant_mode     VARCHAR(16) NOT NULL COMMENT '取值: PUSH/PULL/HYBRID；PUSH=异步派发/PULL=同步返回/HYBRID=两者',
  decision_strategy VARCHAR(32) NOT NULL DEFAULT 'HIGHEST_PRIORITY' COMMENT '取值: HIGHEST_PRIORITY；D29 v1 仅实现 HIGHEST_PRIORITY；v2 扩展 MAJORITY / CUSTOM_SPI 时需 ALTER TABLE MODIFY COLUMN（MySQL ENUM 增值为改列操作，非加列）',
  subject_type      VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT '取值: USER/ACCOUNT/DEVICE/ORDER/CUSTOM',
  event_types       JSON         NOT NULL COMMENT 'D13：允许的 eventType 白名单数组，发布校验 + 事件接入双校验',
  payload_schema    JSON         COMMENT 'payloadSchema D13，字段类型 + required 声明',
  default_params    JSON         COMMENT '如 timezone: Asia/Shanghai',
  status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '取值: ACTIVE/DISABLED',
  created_by        VARCHAR(64)  COMMENT '创建人（D14）',
  created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by        VARCHAR(64)  COMMENT '最近修改人（D14）',
  updated_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务域（Scene）元数据';
```

**metric_definition**

```sql
CREATE TABLE metric_definition (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id           BIGINT       NOT NULL,
  metric_code         VARCHAR(128) NOT NULL COMMENT 'metricCode，同 tenant + version 下唯一',
  version             INT          NOT NULL DEFAULT 1 COMMENT 'B6：指标版本号，单调递增；同 metricCode 升版 = INSERT 新行，旧行 status 改为 SUPERSEDED',
  name                VARCHAR(128) NOT NULL,
  source_type         VARCHAR(16) NOT NULL COMMENT '取值: ATTRIBUTE/SQL_AGGREGATE/EXTERNAL_HTTP/STREAM',
  data_type           VARCHAR(16) NOT NULL COMMENT '取值: LONG/DOUBLE/STRING/BOOLEAN/LIST/DATE/DATETIME；B20 新增 DATE（LocalDate，日历日期）/ DATETIME（Instant/带时区偏移，时区相关）；由 Flyway V1_5__add_date_datetime_to_metric_datatype.sql 扩展',
  params              JSON         NOT NULL COMMENT 'sourceType 专属参数（B21）：SQL_AGGREGATE={datasource(命名只读源),sql(:now命名参数)}；EXTERNAL_HTTP={endpoint(命名端点),path,jsonPath}；ATTRIBUTE={table,column}',
  cache_ttl_seconds   INT          NOT NULL DEFAULT 60 COMMENT '取数结果缓存 TTL，0=不缓存',
  allow_provided      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'D30：是否允许调用方通过 providedMetrics 覆盖；DEFAULT 0 为 SQL_AGGREGATE/STREAM 兜底，ATTRIBUTE/EXTERNAL_HTTP 应用层写入时需显式设为 1（见 04-extension §4.3）',
  status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '取值: ACTIVE/DISABLED/SUPERSEDED；B6：SUPERSEDED=被新版本取代（旧行保留，规则仍可按版本解析）',
  created_by          VARCHAR(64)  COMMENT '创建人（D14）',
  created_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by          VARCHAR(64)  COMMENT '最近修改人（D14）',
  updated_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code_version (tenant_id, metric_code, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标元数据（sourceType / params / cacheTtl / allowProvided / version B6）';
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
  status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '取值: DRAFT/PUBLISHING/PUBLISHED/PUBLISH_FAILED/DISABLED；D19 状态机：DRAFT→PUBLISHING→PUBLISHED/PUBLISH_FAILED；DISABLED=关停',
  kind            VARCHAR(32) NOT NULL DEFAULT 'AST_BOOLEAN' COMMENT '取值: AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE/EXPRESSION_SCRIPT；D12：Rule 类型占位，v1 仅 AST_BOOLEAN 实装，其他枚举值发布时拒绝',
  current_version BIGINT       COMMENT '当前有效 rule_version.id（注意：存的是 rule_version 表的主键 id，而非 rule_version.version 字段）；DRAFT/PUBLISHING/PUBLISH_FAILED 时为 null',
  published_by    VARCHAR(64)  COMMENT '最后发布人（来自 audit_log.actor）',
  published_at    TIMESTAMP(3)  COMMENT '最后发布时间',
  created_by      VARCHAR(64)  COMMENT '创建人（D14）',
  created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64)  COMMENT '最近修改人（D14）',
  updated_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code),
  KEY idx_scene_id (scene_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则主记录（D12 kind 占位，D19 状态机）';
```

**rule_version**（不可变，D19 — 写入后永不 UPDATE/DELETE）

```sql
CREATE TABLE rule_version (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_definition_id    BIGINT       NOT NULL COMMENT '关联 rule_definition.id',
  version               BIGINT       NOT NULL COMMENT '单调递增，per rule_definition（Long 型，匹配概念层 RuleVersion.version）',
  condition_ast         JSON         NOT NULL COMMENT '完整 AST 节点树，不可变',
  decision_bindings     JSON         NOT NULL COMMENT 'D27/D28：含 actions 快照的 Decision 绑定',
  pre_gates             JSON         NOT NULL COMMENT 'Pre-Gate 列表（v1 仅 ROLLOUT，D52；RATE_LIMIT/MUTEX 已移除，黑白名单改走 BOOLEAN metric+condition）；灰度由 ROLLOUT 项承载，params 含 percentage / bucketStart / bucketEnd / experimentId（详见 10-api-contract）',
  kind                  VARCHAR(32) NOT NULL DEFAULT 'AST_BOOLEAN' COMMENT '取值: AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE/EXPRESSION_SCRIPT；D12：规则形态冻结；v1 仅 AST_BOOLEAN 实装，其他占位',
  trigger_event_types   JSON         NOT NULL COMMENT '触发事件类型列表',
  metric_dependencies   JSON         NOT NULL COMMENT 'B6：AST 引用的 (metricCode, metricVersion) 对象数组（发布期冻结当前 ACTIVE 版本），格式 [{metricCode,metricVersion},...]',
  compiled_predicate_ref VARCHAR(256) NULL     COMMENT 'D20 §5：编译产物引用键，v1 留空，v1.5 预编译优化时启用',
  published_at          TIMESTAMP(3)           COMMENT 'NULL = 草稿；非 NULL = 已发布',
  published_by          VARCHAR(64),
  status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '取值: ACTIVE/SUPERSEDED/DISABLED；ACTIVE=当前有效/SUPERSEDED=被新版本取代/DISABLED=手动禁用',
  created_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_def_version (rule_definition_id, version),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则版本快照（不可变，D19）';
```

> **已废弃列 `rollout`**：D6 初版曾设独立 `rollout JSON` 列存灰度快照，但 ROLLOUT 改由 `pre_gates` 承载后该列只写不读，已于迁移 `V1_4__drop_rollout.sql` 删除。灰度配置（percentage / 桶区间 / experimentId）统一在 `pre_gates` 的 ROLLOUT 项。

**decision_definition**（D26/D27）

```sql
CREATE TABLE decision_definition (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  code        VARCHAR(64)  NOT NULL COMMENT '决策码，Tenant 内唯一，如 REJECT/REVIEW/PASS',
  name        VARCHAR(128) NOT NULL COMMENT '决策名称，如"拒绝"/"人工审核"/"放行"',
  priority    INT          NOT NULL COMMENT '优先级，值越小越高（D26：HIGHEST_PRIORITY 策略取 priority 最小的命中决策）',
  description TEXT         COMMENT '给运营/风控看的业务说明',
  actions     JSON         NOT NULL DEFAULT '[]' COMMENT 'D27：Action 列表（命中此 Decision 时派发），含 actionId/actionType/sortOrder/failFast/params',
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '取值: ACTIVE/DISABLED',
  created_by  VARCHAR(64)  COMMENT '创建人（D14）',
  created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  COMMENT '最近修改人（D14）',
  updated_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
  created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rule_decision (rule_definition_id, decision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则与 Decision 绑定关系（D26）；score 区间 v1 为 null 占位，SCORECARD kind 时启用';
```

> **scene_metric_binding 已移除（D54，V1_22）**：metric 在 tenant 级对所有 scene 可用，不再有 scene 级 metric 白名单。
> **scene_action_binding 已移除（D54，V1_23）**：action 触发源唯一 = decision（tenant 级，与 scene 无关，D27 实装），不再有 scene 级 actionType 白名单 + `default_params`；D50 写 API 作废。actionType 合法性降级为运行期 NO_HANDLER skip（D53 best-effort）。

**job_definition**（定时触发规则，非一等公民）

```sql
CREATE TABLE job_definition (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  scene_code      VARCHAR(64)  NOT NULL COMMENT '关联 scene.code，PULL Scene 不允许配置 Job（发布拒绝）',
  code            VARCHAR(64)  NOT NULL COMMENT 'Job 标识，租户 + 场景内唯一',
  name            VARCHAR(128) NOT NULL,
  cron_expression VARCHAR(128) NOT NULL COMMENT 'Spring 6 段 cron（秒 分 时 日 月 周）',
  subject_query   JSON         NOT NULL COMMENT '主体集合查询配置（D48）：type=BEAN_METHOD，ref=<bean>#<method> 指向 @RuleJob 注解的业务查询方法（EXTERNAL_HTTP / METRIC_RESULT 后续）',
  event_type      VARCHAR(64)  NOT NULL COMMENT '合成 RuleEvent 时使用的 eventType',
  payload_template JSON        COMMENT 'D49 遗留列，已不再使用——payload 改由 @RuleJob 方法返回的 JobTarget.payload 直接携带，不做占位符渲染',
  status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '取值: ACTIVE/DISABLED',
  created_by      VARCHAR(64)  COMMENT '创建人（D14）',
  created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64)  COMMENT '最近修改人（D14）',
  updated_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_scene_code (tenant_id, scene_code, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时触发规则配置（§3.10，迁移 V1_7）；调度器到点合成 RuleEvent 注入标准评估链路';
```

**job_execution**（每次 Job 运行记录）

```sql
CREATE TABLE job_execution (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_definition_id BIGINT     NOT NULL COMMENT '关联 job_definition.id',
  tenant_id       BIGINT       NOT NULL,
  trigger_at      TIMESTAMP(3)  NOT NULL COMMENT '调度器触发时间',
  status          VARCHAR(32) NOT NULL DEFAULT 'RUNNING' COMMENT '取值: RUNNING/SUCCESS/PARTIAL_FAIL/FAILED',
  subject_count   INT          NOT NULL DEFAULT 0 COMMENT '查询到的主体总数',
  success_count   INT          NOT NULL DEFAULT 0 COMMENT '成功注入评估链路的主体数',
  error_count     INT          NOT NULL DEFAULT 0 COMMENT '失败数（含主体查询失败 + 事件注入失败）',
  error_summary   TEXT         COMMENT '失败摘要（抽样错误信息）',
  finished_at     TIMESTAMP(3),
  KEY idx_job_trigger (job_definition_id, trigger_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Job 每次运行记录（§3.10）';
```

**audit_log**（D14：人的行为，同步事务，永久保留）

```sql
CREATE TABLE audit_log (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT       NOT NULL,
  actor           VARCHAR(64)  NOT NULL COMMENT '操作人（来自请求头 X-Actor-Id，D14）',
  actor_type      VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT '取值: USER/SYSTEM/JOB；D14：操作方类型（来自请求头 X-Actor-Type）',
  action          VARCHAR(64)  NOT NULL COMMENT 'CREATE / UPDATE / PUBLISH / PUBLISH_FAILED / ENABLE / DISABLE / DELETE / IMPORT',
  target_type     VARCHAR(64)  NOT NULL COMMENT 'rule_definition / scene / metric_definition 等',
  target_id       VARCHAR(128) NOT NULL,
  before_snapshot JSON         COMMENT '变更前快照',
  after_snapshot  JSON         COMMENT '变更后快照（PUBLISH_FAILED 时含 errorCode 字段）',
  trace_id        VARCHAR(128) COMMENT '请求链路 trace id，便于关联日志',
  operated_at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
  source           VARCHAR(16) NOT NULL DEFAULT 'HTTP' COMMENT '取值: HTTP/MQ/JOB/SDK/REPLAY；D49：事件来源渠道，取自 RuleEvent.source（由注入入口权威设置）',
  mode             VARCHAR(8) NOT NULL DEFAULT 'PULL' COMMENT '取值: PUSH/PULL；D49：评估模式，由 EvalService 入口判定（acceptEvent=PUSH 异步 / evaluate·dryRun=PULL 同步）；与 source 渠道正交',
  status           VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '取值: PENDING/HIT/MISS/BLOCKED/ERROR/FAILED；PENDING=进行中；HIT/MISS/BLOCKED/ERROR=D22 四态；FAILED=异常崩溃',
  final_decision   VARCHAR(64)  COMMENT '最终决策码（nullable，未命中或 BLOCKED 时为 null）',
  hit_decisions    JSON         COMMENT '命中的所有决策码列表',
  blocked_by       VARCHAR(64)  COMMENT '仅 status=BLOCKED 时有值，合法值：ROLLOUT（D52 收敛后仅此一种；黑白名单改走 condition 归 MISS）',
  error_code       VARCHAR(64)  COMMENT '仅 status=ERROR 时有值，D15 EvalResult.errorCode',
  candidate_rule_count INT      NOT NULL DEFAULT 0 COMMENT 'Matcher 命中的候选 RuleVersion 数量',
  hit_rule_count   INT          NOT NULL DEFAULT 0 COMMENT 'AST 求值满足（HIT）的 Rule 数量',
  occurred_at      TIMESTAMP(3)  NOT NULL COMMENT '业务事件时间',
  started_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '引擎开始评估时间',
  finished_at      TIMESTAMP(3)  COMMENT 'status 从 PENDING 更新为终态的时间',
  eval_duration_ms INT          COMMENT '整 session 耗时（ms）',
  context_snapshot JSON         COMMENT 'EvalContext 取数快照；嵌套格式 {"metrics":{metricCode:value,...},"evalNow":"<ISO-8601 instant>"}（B20）；构建失败时为 null（排障 / dry-run 重放用）',
  UNIQUE KEY uk_tenant_event (tenant_id, event_id),
  KEY idx_scene_subject (scene_code, subject_id),
  KEY idx_started_at (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估会话主记录（D11/D21，v1 同步写）';
```

> **D49 渠道/模式拆分**：原 `source ENUM('PUSH','PULL','REPLAY')` 把"评估模式"塞进了来源列。迁移 `V1_8__session_source_mode.sql` 将 `source` 改为渠道枚举（`HTTP/MQ/JOB/SDK/REPLAY`，取自 `RuleEvent.source`），并新增 `mode ENUM('PUSH','PULL')` 存评估模式（入口判定，与渠道正交）。

**node_trace**（D7/D21 异步批写；30 天 TTL）

```sql
CREATE TABLE node_trace (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  evaluation_session_id BIGINT       NOT NULL COMMENT '关联 evaluation_session.id',
  tenant_id             BIGINT       NOT NULL,
  rule_version_id       BIGINT       NOT NULL,
  rule_code             VARCHAR(128) COMMENT '冗余逻辑键（来源 V1_26，D59）；nullable，与 rule_version_id 并列保留',
  rule_version          BIGINT       COMMENT '冗余逻辑键（来源 V1_26，D59）；nullable，与 rule_version_id 并列保留',
  node_path             VARCHAR(256) NOT NULL COMMENT 'AST 路径，如 "0.1.2"（根=0）',
  node_type             VARCHAR(64)  NOT NULL COMMENT 'AndNode / OrNode / NotNode / ConditionNode / PRE_GATE_BLOCKED',
  condition_type        VARCHAR(64)  COMMENT 'nullable，仅 ConditionNode',
  metric_code           VARCHAR(128) COMMENT 'nullable，仅 metric 类 conditionType',
  params                JSON         COMMENT '节点参数快照',
  actual_value          JSON         COMMENT '节点实际取到的值（nullable，短路跳过为 null）',
  result                TINYINT(1)   COMMENT '1=满足/0=不满足/NULL=短路跳过',
  error_code            VARCHAR(64)  COMMENT 'nullable，METRIC_FETCH_FAIL / CONDITION_EVAL_ERROR 等',
  value_source          VARCHAR(16) COMMENT '取值: PROVIDED/FETCHED/PAYLOAD；D30：取值来源（nullable；PAYLOAD=payload 直接引用节点 valueRef=PAYLOAD）',
  evaluated_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
  status                VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '取值: PENDING/SUCCESS/FAILED/SKIPPED',
  error_code            VARCHAR(64)  COMMENT 'NO_WEBHOOK_URL / ALERT_DELIVERY_FAILED / PREDECESSOR_FAILED 等',
  executed_at           TIMESTAMP(3)  COMMENT '最后一次执行时间',
  created_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_idempotency (tenant_id, event_id, decision_code, action_id) COMMENT 'D27 幂等 UK：DB 层最终防重（落库去重）',
  KEY idx_session_id (evaluation_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Action 派发执行记录（D4/D27；best-effort 投递 D53，retry/补偿列已 V1_21 移除）';
```

> **best-effort 化（D53，V1_21）**：原 `retryable`/`retry_count`/`compensated`/`compensated_at`/`compensated_by` 列 + `idx_status_retryable` 索引已移除。Action 投递为 best-effort fire-and-forget，不重试不补偿；重复防护仅靠 `uk_idempotency` 落库去重。可靠投递（MQ）/ 业务补偿（saga）未来另设计。

**dry_run_session**（共享 evaluation_session 主体字段，追加 dry-run 专有列，7 天 TTL，D7）

```sql
CREATE TABLE dry_run_session (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        BIGINT       NOT NULL,
  event_id         VARCHAR(128) NOT NULL,
  scene_code       VARCHAR(64)  NOT NULL,
  event_type       VARCHAR(64)  NOT NULL COMMENT '业务事件类型',
  subject_id       VARCHAR(128) NOT NULL,
  rule_version_id  BIGINT       NOT NULL,
  rule_code        VARCHAR(128) COMMENT '冗余逻辑键（来源 V1_26，D59）；nullable，与 rule_version_id 并列保留',
  rule_version     BIGINT       COMMENT '冗余逻辑键（来源 V1_26，D59）；nullable，与 rule_version_id 并列保留',
  status           VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '取值: PENDING/HIT/MISS/BLOCKED/ERROR/FAILED',
  final_decision   VARCHAR(64),
  hit_decisions    JSON,
  blocked_by       VARCHAR(64),
  error_code       VARCHAR(64),
  occurred_at      TIMESTAMP(3)  NOT NULL,
  started_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at      TIMESTAMP(3),
  trigger          VARCHAR(16) NOT NULL DEFAULT 'API' COMMENT '取值: MANUAL/API；dry-run 触发来源',
  requested_by     VARCHAR(64)  COMMENT 'dry-run 发起人（来自请求头 X-Actor-Id，D14）',
  target_rule_version_id BIGINT COMMENT '指定预览的 RuleVersion id；null 时使用 current_version，可提前预览未发布版本',
  context_snapshot JSON         COMMENT 'dry-run 试算时 EvalContext 取数快照；嵌套格式 {"metrics":{metricCode:value,...},"evalNow":"<ISO-8601 instant>"}（B20）；构建失败时为 null（排障 / 重放对比用）',
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
  rule_code             VARCHAR(128) COMMENT '冗余逻辑键（来源 V1_26，D59）；nullable，与 rule_version_id 并列保留',
  rule_version          BIGINT       COMMENT '冗余逻辑键（来源 V1_26，D59）；nullable，与 rule_version_id 并列保留',
  node_path             VARCHAR(256) NOT NULL,
  node_type             VARCHAR(64)  NOT NULL,
  condition_type        VARCHAR(64),
  metric_code           VARCHAR(128),
  params                JSON,
  actual_value          JSON,
  result                TINYINT(1),
  error_code            VARCHAR(64),
  value_source          VARCHAR(16) COMMENT '取值: PROVIDED/FETCHED/PAYLOAD',
  evaluated_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_id (dry_run_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='dry-run 节点 trace（与 prod 隔离，D7）';
```

---

## 四、索引设计

### 评估热路径索引（运行期读，性能关键）

| 表 | 索引 | 查询模式 |
|---|---|---|
| `evaluation_session` | UK `uk_tenant_event (tenant_id, event_id)`<br>`idx_started_at (started_at)` | 幂等检查：D11 下半层 DB uk<br>定时清理：按时间范围删除 30 天外数据 |
| `node_trace` | `idx_session_id (evaluation_session_id)` | 按 session 查 trace（排障 / dry-run 对比） |

Matcher 路由不走 DB（运行时内存倒排索引，D17 派生）。

### 运营查询索引（非热路径，管理面）

| 表 | 索引 | 查询模式 |
|---|---|---|
| `evaluation_session` | `idx_scene_subject (scene_code, subject_id)` | 按用户查历史评估记录 |
| `evaluation_session` | （无专用索引）按规则查历史 session 走 `node_trace.rule_version_id` IN 该规则所有版本 id → 取 `evaluation_session_id` → JOIN evaluation_session；不在 evaluation_session 加规则外键索引以避免写热点，JOIN 量小可接受（见 10-api-contract §6.4） | |
| `node_trace` | `idx_tenant_evaluated (tenant_id, evaluated_at)` | 对账：按租户 + 时间范围聚合 trace 量 |
| `action_execution` | UK `uk_idempotency (tenant_id, event_id, decision_code, action_id)`<br>`idx_session_id (evaluation_session_id)` | Action 派发幂等检查（D27 DB 层最终防重，best-effort 落库去重）<br>按 session 查 action 执行记录 |
| `rule_definition` | `idx_scene_id (scene_id)` | 按 Scene 查规则列表 |
| `rule_version` | UK `uk_def_version (rule_definition_id, version)` | 版本唯一性约束 + 按规则查所有版本 |
| `audit_log` | `idx_tenant_target (tenant_id, target_type, target_id)`<br>`idx_operated_at (operated_at)` | 查某个规则/Scene 的所有变更记录<br>按时间范围查审计日志 |
| `decision_definition` | UK `uk_tenant_code (tenant_id, code)` | Tenant 内 Decision 码唯一性约束 + 发布时查 Decision |
| `rule_decision_binding` | UK `uk_rule_decision (rule_definition_id, decision_id)` | 规则与 Decision 绑定唯一性 |
| `job_definition` | UK `uk_tenant_scene_code (tenant_id, scene_code, code)` | 租户 + 场景内 Job 唯一性约束 |
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

### 数据保留策略（D9：v1 全 MySQL；各模块 `@Scheduled` 定时清理）

各属主模块各一个 `@Scheduled` 清理 bean（observability 清 trace 两表、eval-svc 清 session/action 三表），按 `engine.rule.retention.*` 配置（`cron` 默认每日 03:30、`batch-size` 默认 1000、`enabled` 总开关）分批 `DELETE ... LIMIT batch-size` 循环删超期行（短事务、幂等、可恢复）。无 FK,删除顺序为逻辑安全。

| 表 | 保留期(默认) | age 列 | 配置键 |
|---|---|---|---|
| `evaluation_session` | 90 天 | `started_at` | `evaluation-session-days` |
| `node_trace` | 30 天 | `evaluated_at` | `node-trace-days` |
| `action_execution` | 90 天（跟随 evaluation_session 生命周期） | `created_at` | `action-execution-days` |
| `dry_run_session` | 7 天 | `started_at` | `dry-run-session-days` |
| `dry_run_node_trace` | 7 天 | `evaluated_at` | `dry-run-session-days`（同管 dry_run 两表） |
| `audit_log` | **永久** | — | 不清理 |
| 配置层所有表 | **永久** | — | 不清理（rule_version 不可删，D19） |

### Flyway 命名规范（DDL 版本管理）

文件命名：`V{major}_{minor}__{描述}.sql`，如 `V1_0__init_schema.sql`

v1 所有建表 SQL 合并到 `V1_0__init_schema.sql`，后续变更新增 `V1_1__xxx.sql`（不改已有 migration 文件）。

> **D51（R10）**：表中原 MySQL ENUM 列已全部改为 VARCHAR，允许取值的真相源在 app 层 Java enum（按 name 往返），列 COMMENT 仅作参考。

---

## 六、维护原则

- 本文档**唯一持有 DDL**——01-concepts 字段表与本文档 SQL 类型变更必须同步。
- 新增表必须在 §二 + §三 + §四 三处同步登记。
- 索引变更要在 §四 注明"承载哪个查询模式"，避免后人不敢删未知用途索引。
- 字段语义讨论留 01-concepts，本文档只列"SQL 类型 + 索引 + 写入路径"。
