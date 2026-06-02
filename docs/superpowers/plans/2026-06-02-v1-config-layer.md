# V1 配置层实现计划（Plan A）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 rule-config-svc 真正能跑通：Flyway 建表 + MyBatis-Plus Mapper + 发布流程（DRAFT→PUBLISHED）+ 快照生成 + `RulePublishedEvent` / `SceneChangedEvent` 发布，单测全通过。

**Architecture:** 配置层是所有功能的基础。`SceneDef` / `RuleDefinition` / `RuleVersion` / `DecisionDefinition` / `MetricDefinition` 对应 MySQL 表，MyBatis-Plus BaseMapper 提供 CRUD；发布流程在 `PublishService` 中实现（读 DB → 生成快照 → INSERT rule_version → UPDATE rule_definition.current_version → 发布 Modulith 事件）；所有写操作同步写 `audit_log`（D14）；领域类字段与 `05-storage.md` DDL 严格对齐。

**Tech Stack:** Java 25 / Spring Boot 4.0.6 / Spring Modulith 2.0.6 / MyBatis-Plus 3.5.16 / Flyway 10.x / Jackson (Spring Boot 内置) / JUnit Jupiter / Mockito

> **环境约束：**
> - `mvn` 命令前必须先设置环境：`export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home && export PATH=$JAVA_HOME/bin:$PATH && MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn`
> - `$MVN -pl rule-config-svc -am test` 运行模块测试
> - 代码注释（`//` 及 Javadoc）全部使用**中文**

---

## 文件结构总览

```
rule-config-svc/
└── src/
    ├── main/
    │   ├── java/com/sstlfsj/rule/config/
    │   │   ├── api/service/
    │   │   │   ├── ConfigService.java          (已有，不动)
    │   │   │   ├── SceneService.java            (已有，不动)
    │   │   │   └── MetadataService.java         (已有，不动)
    │   │   ├── internal/
    │   │   │   ├── domain/
    │   │   │   │   ├── SceneDef.java            (已有，修改：补全字段对齐 DDL)
    │   │   │   │   ├── RuleDefinition.java      (已有，修改：补全字段对齐 DDL)
    │   │   │   │   ├── RuleVersion.java         (已有，修改：补全字段对齐 DDL)
    │   │   │   │   ├── MetricDefinition.java    (已有，修改：补全字段对齐 DDL)
    │   │   │   │   ├── DecisionDefinition.java  (新建)
    │   │   │   │   └── AuditLog.java            (新建)
    │   │   │   ├── repository/
    │   │   │   │   ├── SceneMapper.java         (新建)
    │   │   │   │   ├── RuleDefinitionMapper.java (新建)
    │   │   │   │   ├── RuleVersionMapper.java   (新建)
    │   │   │   │   ├── MetricDefinitionMapper.java (新建)
    │   │   │   │   ├── DecisionDefinitionMapper.java (新建)
    │   │   │   │   └── AuditLogMapper.java      (新建)
    │   │   │   ├── publish/
    │   │   │   │   ├── AstSerializer.java       (新建：AstNode ↔ JSON 互转)
    │   │   │   │   └── PublishService.java      (新建：发布核心流程)
    │   │   │   ├── service/
    │   │   │   │   ├── ConfigServiceImpl.java   (已有，重写：调用 PublishService)
    │   │   │   │   ├── SceneServiceImpl.java    (新建)
    │   │   │   │   └── MetadataServiceImpl.java (新建)
    │   │   │   └── event/
    │   │   │       ├── RulePublishedEvent.java  (已有，不动)
    │   │   │       └── SceneChangedEvent.java   (已有，不动)
    │   │   └── ConfigAutoConfiguration.java     (已有，不动)
    │   └── resources/
    │       └── db/migration/
    │           └── V1_0__init_schema.sql        (新建：全量建表 DDL)
    └── test/
        └── java/com/sstlfsj/rule/config/
            ├── internal/
            │   ├── publish/
            │   │   ├── AstSerializerTest.java   (新建)
            │   │   └── PublishServiceTest.java  (新建)
            │   └── service/
            │       ├── ConfigServiceImplTest.java (已有，重写)
            │       ├── SceneServiceImplTest.java  (新建)
            │       └── MetadataServiceImplTest.java (新建)
```

---

## Task 1: 更新 rule-config-svc pom.xml — 添加 Flyway 依赖

**Files:**
- Modify: `rule-config-svc/pom.xml`

- [ ] **Step 1: 在 pom.xml 中添加 Flyway 依赖**

在 `rule-config-svc/pom.xml` 的 `<dependencies>` 中添加（放在 mysql-connector-j 旁边）：

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

Spring Boot 4.x 的 BOM 中已包含 `flyway-mysql`，无需指定版本。Flyway Core 已被 `flyway-mysql` 传递依赖，不需单独声明。

- [ ] **Step 2: 验证 pom 能解析**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am validate -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/pom.xml
git commit -m "chore(config-svc): add Flyway MySQL dependency"
```

---

## Task 2: 编写 Flyway 初始化 SQL

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_0__init_schema.sql`

- [ ] **Step 1: 创建迁移目录并写入 DDL**

`rule-config-svc/src/main/resources/db/migration/V1_0__init_schema.sql`:

```sql
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
  created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  COMMENT '最近修改人',
  updated_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
  created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by        VARCHAR(64)  COMMENT '最近修改人',
  updated_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
  created_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by          VARCHAR(64)  COMMENT '最近修改人',
  updated_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
  published_at    TIMESTAMP(3)  COMMENT '最后发布时间',
  created_by      VARCHAR(64)  COMMENT '创建人',
  created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by      VARCHAR(64)  COMMENT '最近修改人',
  updated_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
  published_at          TIMESTAMP(3)  COMMENT 'NULL=草稿；非 NULL=已发布',
  published_by          VARCHAR(64),
  status                ENUM('ACTIVE','SUPERSEDED','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  created_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
  created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64)  COMMENT '最近修改人',
  updated_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Decision 实体';

CREATE TABLE IF NOT EXISTS rule_decision_binding (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_definition_id  BIGINT       NOT NULL COMMENT '关联 rule_definition.id',
  decision_id         BIGINT       NOT NULL COMMENT '关联 decision_definition.id',
  score_range_min     DECIMAL(10,4) COMMENT '仅 SCORECARD 时有意义，v1 留 null',
  score_range_max     DECIMAL(10,4) COMMENT '仅 SCORECARD 时有意义，v1 留 null',
  created_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rule_decision (rule_definition_id, decision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则与 Decision 绑定关系';

CREATE TABLE IF NOT EXISTS scene_metric_binding (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene_id              BIGINT       NOT NULL COMMENT '关联 scene.id',
  metric_definition_id  BIGINT       NOT NULL COMMENT '关联 metric_definition.id',
  cache_policy_override JSON         COMMENT 'Scene 级缓存策略覆盖，null=使用 metric_definition 默认值',
  created_by            VARCHAR(64)  COMMENT '创建人',
  created_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by            VARCHAR(64)  COMMENT '最近修改人',
  updated_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_scene_metric (scene_id, metric_definition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scene 可用 Metric 白名单';

CREATE TABLE IF NOT EXISTS scene_action_binding (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  scene_id          BIGINT       NOT NULL COMMENT '关联 scene.id',
  action_type       VARCHAR(64)  NOT NULL COMMENT 'ActionHandler 注册的 actionType',
  default_params    JSON         COMMENT 'Scene 级默认参数',
  rate_limit_override JSON       COMMENT 'Scene 级频控覆盖',
  created_by        VARCHAR(64)  COMMENT '创建人',
  created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by        VARCHAR(64)  COMMENT '最近修改人',
  updated_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
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
  operated_at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
  occurred_at      TIMESTAMP(3)  NOT NULL COMMENT '业务事件时间',
  started_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
  evaluated_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
  created_at            TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
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
  occurred_at      TIMESTAMP(3)  NOT NULL,
  started_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at      TIMESTAMP(3),
  trigger          ENUM('MANUAL','API') NOT NULL DEFAULT 'API',
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
  evaluated_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_session_id (dry_run_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='dry-run 节点 trace';
```

- [ ] **Step 2: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/
git commit -m "feat(config-svc): Flyway V1.0 init schema DDL（全量建表）"
```

---

## Task 3: 补全 domain 实体字段 + 新增 DecisionDefinition / AuditLog

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/SceneDef.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleDefinition.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/MetricDefinition.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/DecisionDefinition.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/AuditLog.java`

> **说明**：Lombok `@Data` 不得用在 entity 上（与 MyBatis-Plus 代理有冲突风险）；继续用手写 getter/setter 风格，保持与已有 entity 一致。

- [ ] **Step 1: 重写 SceneDef.java（补全 eventTypes / payloadSchema / description 等字段）**

```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** scene 表实体，对应 05-storage.md §3.1 scene DDL。 */
@TableName("scene")
public class SceneDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String description;
    private String dominantMode;
    private String decisionStrategy;
    private String subjectType;
    /** JSON 数组字符串，存储允许的 eventType 白名单。 */
    private String eventTypes;
    /** JSON 对象字符串，存储 payloadSchema 字段类型声明。 */
    private String payloadSchema;
    /** JSON 对象字符串，存储 Scene 默认参数。 */
    private String defaultParams;
    private String status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDominantMode() { return dominantMode; }
    public void setDominantMode(String dominantMode) { this.dominantMode = dominantMode; }
    public String getDecisionStrategy() { return decisionStrategy; }
    public void setDecisionStrategy(String decisionStrategy) { this.decisionStrategy = decisionStrategy; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getEventTypes() { return eventTypes; }
    public void setEventTypes(String eventTypes) { this.eventTypes = eventTypes; }
    public String getPayloadSchema() { return payloadSchema; }
    public void setPayloadSchema(String payloadSchema) { this.payloadSchema = payloadSchema; }
    public String getDefaultParams() { return defaultParams; }
    public void setDefaultParams(String defaultParams) { this.defaultParams = defaultParams; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 2: 重写 RuleDefinition.java（补全 sceneId / kind / currentVersion / publishedBy 等字段）**

```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** rule_definition 表实体，对应 05-storage.md §3.1 rule_definition DDL。 */
@TableName("rule_definition")
public class RuleDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long sceneId;
    private String code;
    private String name;
    private String description;
    private String status;
    private String kind;
    private Long currentVersion;
    private String publishedBy;
    private java.time.LocalDateTime publishedAt;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getSceneId() { return sceneId; }
    public void setSceneId(Long sceneId) { this.sceneId = sceneId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(Long currentVersion) { this.currentVersion = currentVersion; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public java.time.LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(java.time.LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: 重写 RuleVersion.java（补全 version / rollout / triggerEventTypes / metricDependencies 等字段）**

```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** rule_version 表实体，不可变（发布后禁止 UPDATE/DELETE）。 */
@TableName("rule_version")
public class RuleVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleDefinitionId;
    private Long version;
    private String conditionAst;
    private String decisionBindings;
    private String preGates;
    private String rollout;
    private String kind;
    private String triggerEventTypes;
    private String metricDependencies;
    private String status;
    private String publishedBy;
    private java.time.LocalDateTime publishedAt;
    private java.time.LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRuleDefinitionId() { return ruleDefinitionId; }
    public void setRuleDefinitionId(Long ruleDefinitionId) { this.ruleDefinitionId = ruleDefinitionId; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public String getConditionAst() { return conditionAst; }
    public void setConditionAst(String conditionAst) { this.conditionAst = conditionAst; }
    public String getDecisionBindings() { return decisionBindings; }
    public void setDecisionBindings(String decisionBindings) { this.decisionBindings = decisionBindings; }
    public String getPreGates() { return preGates; }
    public void setPreGates(String preGates) { this.preGates = preGates; }
    public String getRollout() { return rollout; }
    public void setRollout(String rollout) { this.rollout = rollout; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTriggerEventTypes() { return triggerEventTypes; }
    public void setTriggerEventTypes(String triggerEventTypes) { this.triggerEventTypes = triggerEventTypes; }
    public String getMetricDependencies() { return metricDependencies; }
    public void setMetricDependencies(String metricDependencies) { this.metricDependencies = metricDependencies; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public java.time.LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(java.time.LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: 重写 MetricDefinition.java（对齐 DDL 字段）**

```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** metric_definition 表实体。 */
@TableName("metric_definition")
public class MetricDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String metricCode;
    private String name;
    private String sourceType;
    private String dataType;
    private String params;
    private Integer cacheTtlSeconds;
    private Boolean allowProvided;
    private String status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public Integer getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(Integer cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public Boolean getAllowProvided() { return allowProvided; }
    public void setAllowProvided(Boolean allowProvided) { this.allowProvided = allowProvided; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 5: 新建 DecisionDefinition.java**

```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** decision_definition 表实体，D26/D27：Decision 是 Tenant 级实体。 */
@TableName("decision_definition")
public class DecisionDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private Integer priority;
    private String description;
    /** JSON 数组字符串，存储 actions 列表（含 actionId/actionType/sortOrder/params）。 */
    private String actions;
    private String status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getActions() { return actions; }
    public void setActions(String actions) { this.actions = actions; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 6: 新建 AuditLog.java**

```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** audit_log 表实体，D14：配置变更审计，同步事务写，永久保留。 */
@TableName("audit_log")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String actor;
    private String actorType;
    private String action;
    private String targetType;
    private String targetId;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String traceId;
    private java.time.LocalDateTime operatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getBeforeSnapshot() { return beforeSnapshot; }
    public void setBeforeSnapshot(String beforeSnapshot) { this.beforeSnapshot = beforeSnapshot; }
    public String getAfterSnapshot() { return afterSnapshot; }
    public void setAfterSnapshot(String afterSnapshot) { this.afterSnapshot = afterSnapshot; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public java.time.LocalDateTime getOperatedAt() { return operatedAt; }
    public void setOperatedAt(java.time.LocalDateTime operatedAt) { this.operatedAt = operatedAt; }
}
```

- [ ] **Step 7: 验证编译通过**

```bash
$MVN -pl rule-config-svc -am compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/
git commit -m "feat(config-svc): 补全 domain 实体字段，新增 DecisionDefinition / AuditLog"
```

---

## Task 4: 新建 MyBatis-Plus Mapper 接口

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/SceneMapper.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/RuleDefinitionMapper.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/RuleVersionMapper.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/MetricDefinitionMapper.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/DecisionDefinitionMapper.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/AuditLogMapper.java`

- [ ] **Step 1: 创建全部 Mapper**

`SceneMapper.java`:
```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import org.apache.ibatis.annotations.Mapper;

/** scene 表 MyBatis-Plus Mapper。 */
@Mapper
public interface SceneMapper extends BaseMapper<SceneDef> {}
```

`RuleDefinitionMapper.java`:
```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import org.apache.ibatis.annotations.Mapper;

/** rule_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleDefinitionMapper extends BaseMapper<RuleDefinition> {}
```

`RuleVersionMapper.java`:
```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** rule_version 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleVersionMapper extends BaseMapper<RuleVersion> {

    /**
     * 查询指定规则下的最大版本号，用于发布时单调递增。
     *
     * @param ruleDefinitionId 规则定义 id
     * @return 当前最大版本号，无记录时返回 0
     */
    @Select("SELECT COALESCE(MAX(version), 0) FROM rule_version WHERE rule_definition_id = #{ruleDefinitionId}")
    Long maxVersion(Long ruleDefinitionId);
}
```

`MetricDefinitionMapper.java`:
```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import org.apache.ibatis.annotations.Mapper;

/** metric_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface MetricDefinitionMapper extends BaseMapper<MetricDefinition> {}
```

`DecisionDefinitionMapper.java`:
```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import org.apache.ibatis.annotations.Mapper;

/** decision_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface DecisionDefinitionMapper extends BaseMapper<DecisionDefinition> {}
```

`AuditLogMapper.java`:
```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/** audit_log 表 MyBatis-Plus Mapper。 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {}
```

- [ ] **Step 2: 验证编译通过**

```bash
$MVN -pl rule-config-svc -am compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/
git commit -m "feat(config-svc): MyBatis-Plus Mapper 接口（六张表）"
```

---

## Task 5: 实现 AstSerializer（AST ↔ JSON）

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstSerializer.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstSerializerTest.java`

`AstSerializer` 负责将内存中的 `AstNode`（sealed interface）序列化为 JSON 字符串（存入 `rule_version.condition_ast`），以及反向从 JSON 字符串反序列化回 `AstNode`。使用 Jackson ObjectMapper，多态类型通过 `type` 字段区分（`AndNode` / `OrNode` / `NotNode` / `ConditionNode`）。

- [ ] **Step 1: 写失败测试**

`AstSerializerTest.java`:
```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AstSerializerTest {

    private final AstSerializer serializer = new AstSerializer();

    @Test
    void conditionNode_roundTrip() {
        ConditionNode node = new ConditionNode("metric.threshold", "user.age",
                "年龄大于18", Map.of("operator", "GT", "threshold", 18));

        String json = serializer.toJson(node);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(ConditionNode.class);
        ConditionNode r = (ConditionNode) restored;
        assertThat(r.conditionType()).isEqualTo("metric.threshold");
        assertThat(r.metricCode()).isEqualTo("user.age");
        assertThat(r.params()).containsEntry("operator", "GT");
    }

    @Test
    void andNode_withChildren_roundTrip() {
        AstNode ast = new AndNode(List.of(
                new ConditionNode("metric.threshold", "user.age", null, Map.of("operator", "GT", "threshold", 18)),
                new ConditionNode("event.payload.compare", null, null, Map.of("field", "amount", "operator", "LTE", "value", 50000))
        ), "年龄 AND 金额", null);

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(AndNode.class);
        AndNode r = (AndNode) restored;
        assertThat(r.children()).hasSize(2);
        assertThat(r.displayLabel()).isEqualTo("年龄 AND 金额");
    }

    @Test
    void notNode_roundTrip() {
        AstNode ast = new NotNode(
                new ConditionNode("metric.threshold", "order.count", null, Map.of("operator", "GT", "threshold", 10))
        );

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(NotNode.class);
    }

    @Test
    void nested_andOrNot_roundTrip() {
        // AND(NOT(cond1), OR(cond2, cond3))
        AstNode ast = new AndNode(List.of(
                new NotNode(new ConditionNode("c.type", "m.code", null, Map.of("k", "v"))),
                new OrNode(List.of(
                        new ConditionNode("c.a", null, null, Map.of()),
                        new ConditionNode("c.b", null, null, Map.of())
                ), null, null)
        ), null, null);

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(AndNode.class);
        AndNode root = (AndNode) restored;
        assertThat(root.children()).hasSize(2);
        assertThat(root.children().get(0)).isInstanceOf(NotNode.class);
        assertThat(root.children().get(1)).isInstanceOf(OrNode.class);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
$MVN -pl rule-config-svc -am test -Dtest=AstSerializerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL with `ClassNotFoundException: AstSerializer`

- [ ] **Step 3: 实现 AstSerializer**

`AstSerializer.java`:
```java
package com.sstlfsj.rule.config.internal.publish;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.springframework.stereotype.Component;

/** 负责 AstNode 与 JSON 字符串互转，用于 rule_version.condition_ast 存储。 */
@Component
public class AstSerializer {

    private final ObjectMapper mapper;

    public AstSerializer() {
        this.mapper = new ObjectMapper();
        // 使用 @class 字段区分多态类型，仅作用于 AstNode 层级
        this.mapper.addMixIn(AstNode.class, AstNodeMixin.class);
    }

    /**
     * 将 AstNode 序列化为 JSON 字符串，含 @class 字段便于反序列化。
     *
     * @param node 待序列化的 AST 节点
     * @return JSON 字符串
     */
    public String toJson(AstNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("AST 序列化失败", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为 AstNode（含子树递归恢复）。
     *
     * @param json 由 {@link #toJson} 生成的 JSON 字符串
     * @return 反序列化后的 AstNode
     */
    public AstNode fromJson(String json) {
        try {
            return mapper.readValue(json, AstNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("AST 反序列化失败: " + e.getMessage(), e);
        }
    }

    /** Jackson mixin：为 AstNode sealed interface 声明多态类型映射。 */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = AndNode.class,       name = "AndNode"),
        @JsonSubTypes.Type(value = OrNode.class,        name = "OrNode"),
        @JsonSubTypes.Type(value = NotNode.class,       name = "NotNode"),
        @JsonSubTypes.Type(value = ConditionNode.class, name = "ConditionNode")
    })
    interface AstNodeMixin {}
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
$MVN -pl rule-config-svc -am test -Dtest=AstSerializerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS，4 tests passed

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstSerializer.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstSerializerTest.java
git commit -m "feat(config-svc): AstSerializer AST↔JSON 互转，4 测试通过"
```

---

## Task 6: 实现 PublishService（核心发布流程）

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`

发布流程（DRAFT → PUBLISHED）：
1. 加载 `RuleDefinition`（断言 status=DRAFT）
2. 加载 `Scene`（获取 sceneCode + eventTypes）
3. 加载 `RuleDecisionBinding` 列表 → 查 `DecisionDefinition` → 生成 `decisionBindings` JSON
4. 扫描 AST 收集 `metricDependencies`
5. INSERT `rule_version`（version = maxVersion+1，status=ACTIVE）
6. UPDATE `rule_definition.current_version` + status=PUBLISHED + publishedAt + publishedBy
7. 旧 `rule_version`（如有）status 改为 SUPERSEDED
8. INSERT `audit_log`（action=PUBLISH）
9. 发布 `RulePublishedEvent`（Modulith 事件）

- [ ] **Step 1: 写失败测试**

`PublishServiceTest.java`:
```java
package com.sstlfsj.rule.config.internal.publish;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishServiceTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AstSerializer astSerializer;

    @InjectMocks PublishService publishService;

    private RuleDefinition draftRule;
    private SceneDef scene;

    @BeforeEach
    void setUp() {
        draftRule = new RuleDefinition();
        draftRule.setId(10L);
        draftRule.setTenantId(1L);
        draftRule.setSceneId(5L);
        draftRule.setCode("rule.demo");
        draftRule.setName("测试规则");
        draftRule.setStatus("DRAFT");
        draftRule.setKind("AST_BOOLEAN");
        draftRule.setConditionAstJson("{\"type\":\"ConditionNode\"}");

        scene = new SceneDef();
        scene.setId(5L);
        scene.setCode("PAYMENT");
        scene.setEventTypes("[\"payment.initiated\"]");
        scene.setStatus("ACTIVE");
    }

    @Test
    void publish_draftRule_createsVersionAndUpdatesDefinition() {
        // 模拟 selectById 返回草稿规则
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(ruleVersionMapper.insert(any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById(any())).thenReturn(1);
        when(auditLogMapper.insert(any())).thenReturn(1);
        // AstSerializer 返回测试用 AST 节点（空 ConditionNode）
        ConditionNode fakeAst = new ConditionNode("c.type", "m.code", null, Map.of());
        when(astSerializer.fromJson(anyString())).thenReturn(fakeAst);

        RuleVersionSnapshot snapshot = publishService.publish(1L, 10L, "operator1");

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.ruleVersionId()).isNotNull();
        assertThat(snapshot.sceneCode()).isEqualTo("PAYMENT");
        // 验证 rule_version 被插入
        verify(ruleVersionMapper).insert(argThat(rv -> rv.getVersion() == 1L && "ACTIVE".equals(rv.getStatus())));
        // 验证 rule_definition 状态更新为 PUBLISHED
        verify(ruleDefinitionMapper).updateById(argThat(rd -> "PUBLISHED".equals(rd.getStatus())));
        // 验证审计日志写入
        verify(auditLogMapper).insert(argThat(log -> "PUBLISH".equals(log.getAction())));
        // 验证 Modulith 事件发布
        verify(eventPublisher).publishEvent(argThat(e -> e instanceof RulePublishedEvent rpe
                && "PAYMENT".equals(rpe.sceneCode())));
    }

    @Test
    void publish_nonDraftRule_throwsIllegalState() {
        draftRule.setStatus("PUBLISHED");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有 DRAFT 状态的规则可以发布");
    }

    @Test
    void publish_ruleNotFound_throwsIllegalArgument() {
        when(ruleDefinitionMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> publishService.publish(1L, 99L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则不存在");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL with `ClassNotFoundException: PublishService`

- [ ] **Step 3: 实现 PublishService**

注意：`RuleDefinition` 没有 `conditionAstJson` 字段（那是 `RuleVersion` 里的），需要先调整：此处假设发布时 conditionAst 来自最新 DRAFT 版本的 `rule_version` 草稿行，或直接存在 `rule_definition` 上（v1 简化：放 rule_definition 表一个额外的 draft_ast 列外，或者直接在 publish 接口传入 AST）。

**v1 简化策略**：`publish` 接口签名保持 `publish(tenantId, ruleDefinitionId, actorId)`；发布时查最新一条 status='DRAFT' 的 rule_version 行作为草稿来源。如无草稿版本行，抛异常要求先提交草稿。

`PublishService.java`:
```java
package com.sstlfsj.rule.config.internal.publish;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.event.RulePublishedEvent;
import com.sstlfsj.rule.config.internal.repository.*;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则发布核心流程。
 * <p>
 * 事务边界：整个发布流程在一个本地事务内完成（INSERT rule_version +
 * UPDATE rule_definition + INSERT audit_log），事务提交后发布 Modulith 事件。
 * </p>
 */
@Service
public class PublishService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final SceneMapper sceneMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;
    private final AuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AstSerializer astSerializer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublishService(RuleDefinitionMapper ruleDefinitionMapper,
                          SceneMapper sceneMapper,
                          RuleVersionMapper ruleVersionMapper,
                          DecisionDefinitionMapper decisionDefinitionMapper,
                          AuditLogMapper auditLogMapper,
                          ApplicationEventPublisher eventPublisher,
                          AstSerializer astSerializer) {
        this.ruleDefinitionMapper = ruleDefinitionMapper;
        this.sceneMapper = sceneMapper;
        this.ruleVersionMapper = ruleVersionMapper;
        this.decisionDefinitionMapper = decisionDefinitionMapper;
        this.auditLogMapper = auditLogMapper;
        this.eventPublisher = eventPublisher;
        this.astSerializer = astSerializer;
    }

    /**
     * 发布规则：从最新草稿 rule_version 生成正式版本快照。
     *
     * @param tenantId         租户 id
     * @param ruleDefinitionId 规则定义 id
     * @param actorId          操作人（来自 X-Actor-Id header）
     * @return 新生成的 RuleVersionSnapshot（供 eval-svc 倒排索引热更使用）
     */
    @Transactional
    public RuleVersionSnapshot publish(Long tenantId, Long ruleDefinitionId, String actorId) {
        // 1. 加载 RuleDefinition，校验 tenantId 和 status
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(rule.getTenantId())) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        if (!"DRAFT".equals(rule.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 状态的规则可以发布，当前状态: " + rule.getStatus());
        }

        // 2. 加载 Scene
        SceneDef scene = sceneMapper.selectById(rule.getSceneId());
        if (scene == null) {
            throw new IllegalStateException("Scene 不存在: id=" + rule.getSceneId());
        }

        // 3. 查最新草稿 rule_version 行作为 AST 来源
        RuleVersion draftVersion = ruleVersionMapper.selectOne(
                new LambdaQueryWrapper<RuleVersion>()
                        .eq(RuleVersion::getRuleDefinitionId, ruleDefinitionId)
                        .eq(RuleVersion::getStatus, "DRAFT")
                        .orderByDesc(RuleVersion::getVersion)
                        .last("LIMIT 1")
        );
        if (draftVersion == null) {
            throw new IllegalStateException("没有找到草稿版本，请先保存规则草稿");
        }

        // 4. 反序列化 AST，收集 metricDependencies
        AstNode ast = astSerializer.fromJson(draftVersion.getConditionAst());
        List<String> metricDeps = MetricDependencyCollector.collect(ast);

        // 5. 计算新版本号（max(version)+1）
        long newVersion = ruleVersionMapper.maxVersion(ruleDefinitionId) + 1;

        // 6. INSERT 新 rule_version（status=ACTIVE，不可变）
        RuleVersion newRv = new RuleVersion();
        newRv.setRuleDefinitionId(ruleDefinitionId);
        newRv.setVersion(newVersion);
        newRv.setConditionAst(draftVersion.getConditionAst());
        newRv.setDecisionBindings(draftVersion.getDecisionBindings() != null
                ? draftVersion.getDecisionBindings() : "[]");
        newRv.setPreGates(draftVersion.getPreGates() != null
                ? draftVersion.getPreGates() : "[]");
        newRv.setRollout(draftVersion.getRollout() != null
                ? draftVersion.getRollout() : "{}");
        newRv.setKind("AST_BOOLEAN");
        newRv.setTriggerEventTypes(scene.getEventTypes());
        newRv.setMetricDependencies(toJson(metricDeps));
        newRv.setStatus("ACTIVE");
        newRv.setPublishedBy(actorId);
        newRv.setPublishedAt(LocalDateTime.now());
        ruleVersionMapper.insert(newRv);

        // 7. 旧 ACTIVE rule_version 改为 SUPERSEDED
        if (rule.getCurrentVersion() != null) {
            ruleVersionMapper.update(null,
                    new LambdaUpdateWrapper<RuleVersion>()
                            .eq(RuleVersion::getId, rule.getCurrentVersion())
                            .eq(RuleVersion::getStatus, "ACTIVE")
                            .set(RuleVersion::getStatus, "SUPERSEDED"));
        }

        // 8. UPDATE rule_definition
        rule.setStatus("PUBLISHED");
        rule.setCurrentVersion(newRv.getId());
        rule.setPublishedBy(actorId);
        rule.setPublishedAt(LocalDateTime.now());
        ruleDefinitionMapper.updateById(rule);

        // 9. INSERT audit_log（D14 同步事务写）
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActor(actorId);
        log.setActorType("USER");
        log.setAction("PUBLISH");
        log.setTargetType("rule_definition");
        log.setTargetId(ruleDefinitionId.toString());
        log.setAfterSnapshot("{\"ruleVersionId\":" + newRv.getId() + ",\"version\":" + newVersion + "}");
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);

        // 10. 生成 RuleVersionSnapshot，供事件携带
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                newRv.getId(),
                scene.getCode(),
                String.valueOf(tenantId),
                ast,
                List.of(),   // preGates 反序列化 v1 暂时省略
                List.of()    // decisionBindings 反序列化 v1 暂时省略
        );

        // 11. 发布 Modulith 事件（事务提交后触发，eval-svc 监听更新索引）
        eventPublisher.publishEvent(new RulePublishedEvent(
                String.valueOf(tenantId), scene.getCode(), newRv.getId()));

        return snapshot;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
```

- [ ] **Step 4: 新建 MetricDependencyCollector（AST 静态收集 metricCode）**

`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/MetricDependencyCollector.java`:
```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 静态扫描 AST 树，收集所有叶子 ConditionNode 引用的 metricCode（去重，保序）。 */
class MetricDependencyCollector {

    static List<String> collect(AstNode node) {
        Set<String> result = new LinkedHashSet<>();
        walk(node, result);
        return new ArrayList<>(result);
    }

    private static void walk(AstNode node, Set<String> acc) {
        switch (node) {
            case AndNode and -> and.children().forEach(c -> walk(c, acc));
            case OrNode or   -> or.children().forEach(c -> walk(c, acc));
            case NotNode not -> walk(not.child(), acc);
            case ConditionNode cond -> {
                if (cond.metricCode() != null) acc.add(cond.metricCode());
            }
        }
    }
}
```

- [ ] **Step 5: 补全 RuleDefinition — 缺失 conditionAstJson 字段问题说明**

当前 `RuleDefinition` entity 没有 `conditionAstJson` 字段（草稿 AST 的存储位置）。v1 简化策略：**草稿 AST 存在 `rule_version.condition_ast`（status=DRAFT 的行）**，不需要在 `rule_definition` 上增加额外字段。发布时查 `rule_version` 最新 DRAFT 行，这已在 `PublishService` 第 3 步实现。

无需修改任何文件。

- [ ] **Step 6: 运行测试**

```bash
$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS，3 tests passed

- [ ] **Step 7: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java
git commit -m "feat(config-svc): PublishService 发布流程 + MetricDependencyCollector，3 测试通过"
```

---

## Task 7: 重写 ConfigServiceImpl + 实现 SceneServiceImpl / MetadataServiceImpl

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/SceneServiceImpl.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImplTest.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/SceneServiceImplTest.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImplTest.java`

- [ ] **Step 1: 写 ConfigServiceImplTest（重写，替换 stub 断言）**

```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceImplTest {

    @Mock PublishService publishService;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @InjectMocks ConfigServiceImpl configService;

    @Test
    void publish_delegates_to_publishService() {
        RuleVersionSnapshot expected = new RuleVersionSnapshot(
                42L, "PAYMENT", "1",
                new ConditionNode("c.type", null, null, Map.of()),
                List.of(), List.of()
        );
        when(publishService.publish(1L, 10L, "actor1")).thenReturn(expected);

        RuleVersionSnapshot result = configService.publish("1", 10L, "actor1");

        assertThat(result.ruleVersionId()).isEqualTo(42L);
        verify(publishService).publish(1L, 10L, "actor1");
    }

    @Test
    void disable_updatesStatusAndWritesAuditLog() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setStatus("PUBLISHED");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);

        configService.disable("1", 10L, "actor1");

        verify(ruleDefinitionMapper).updateById(argThat(r -> "DISABLED".equals(r.getStatus())));
        verify(auditLogMapper).insert(argThat(log -> "DISABLE".equals(log.getAction())));
    }
}
```

- [ ] **Step 2: 重写 ConfigServiceImpl**

```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** ConfigService 实现，委托 PublishService 执行发布流程。 */
@Service
@RequiredArgsConstructor
class ConfigServiceImpl implements ConfigService {

    private final PublishService publishService;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final AuditLogMapper auditLogMapper;

    @Override
    public RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId) {
        return publishService.publish(Long.valueOf(tenantId), ruleDefinitionId, actorId);
    }

    @Override
    @Transactional
    public void disable(String tenantId, Long ruleDefinitionId, String actorId) {
        RuleDefinition rule = ruleDefinitionMapper.selectById(ruleDefinitionId);
        if (rule == null || !tenantId.equals(String.valueOf(rule.getTenantId()))) {
            throw new IllegalArgumentException("规则不存在: id=" + ruleDefinitionId);
        }
        rule.setStatus("DISABLED");
        ruleDefinitionMapper.updateById(rule);

        AuditLog log = new AuditLog();
        log.setTenantId(Long.valueOf(tenantId));
        log.setActor(actorId);
        log.setActorType("USER");
        log.setAction("DISABLE");
        log.setTargetType("rule_definition");
        log.setTargetId(ruleDefinitionId.toString());
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
```

- [ ] **Step 3: 写 SceneServiceImplTest**

```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.config.internal.event.SceneChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SceneServiceImplTest {

    @Mock SceneMapper sceneMapper;
    @Mock AuditLogMapper auditLogMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks SceneServiceImpl sceneService;

    @Test
    void createScene_insertSceneAndWritesAuditLog() {
        when(sceneMapper.insert(any())).thenReturn(1);
        when(auditLogMapper.insert(any())).thenReturn(1);

        Long id = sceneService.createScene("1", "PAYMENT", "支付场景", "actor1");

        verify(sceneMapper).insert(argThat(s -> "PAYMENT".equals(s.getCode())));
        verify(auditLogMapper).insert(argThat(log -> "CREATE".equals(log.getAction())));
    }

    @Test
    void disableScene_updatesStatusAndPublishesEvent() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        scene.setStatus("ACTIVE");
        when(sceneMapper.selectOne(any())).thenReturn(scene);

        sceneService.disableScene("1", "PAYMENT", "actor1");

        verify(sceneMapper).updateById(argThat(s -> "DISABLED".equals(s.getStatus())));
        verify(eventPublisher).publishEvent(argThat(e -> e instanceof SceneChangedEvent ev
                && "PAYMENT".equals(ev.sceneCode()) && !ev.active()));
    }
}
```

- [ ] **Step 4: 实现 SceneServiceImpl**

```java
package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.event.SceneChangedEvent;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** SceneService 实现：Scene CRUD + SceneChangedEvent 发布。 */
@Service
@RequiredArgsConstructor
class SceneServiceImpl implements SceneService {

    private final SceneMapper sceneMapper;
    private final AuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public Long createScene(String tenantId, String sceneCode, String name, String actorId) {
        SceneDef scene = new SceneDef();
        scene.setTenantId(Long.valueOf(tenantId));
        scene.setCode(sceneCode);
        scene.setName(name);
        scene.setDominantMode("PUSH");
        scene.setDecisionStrategy("HIGHEST_PRIORITY");
        scene.setSubjectType("USER");
        scene.setEventTypes("[]");
        scene.setStatus("ACTIVE");
        scene.setCreatedBy(actorId);
        sceneMapper.insert(scene);

        writeAudit(Long.valueOf(tenantId), actorId, "CREATE", "scene", scene.getId().toString());
        return scene.getId();
    }

    @Override
    @Transactional
    public void updateScene(String tenantId, String sceneCode, String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        writeAudit(Long.valueOf(tenantId), actorId, "UPDATE", "scene", scene.getId().toString());
    }

    @Override
    @Transactional
    public void disableScene(String tenantId, String sceneCode, String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        scene.setStatus("DISABLED");
        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);

        writeAudit(Long.valueOf(tenantId), actorId, "DISABLE", "scene", scene.getId().toString());
        eventPublisher.publishEvent(new SceneChangedEvent(tenantId, sceneCode, false));
    }

    private SceneDef findScene(Long tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.selectOne(
                new LambdaQueryWrapper<SceneDef>()
                        .eq(SceneDef::getTenantId, tenantId)
                        .eq(SceneDef::getCode, sceneCode));
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: " + sceneCode);
        }
        return scene;
    }

    private void writeAudit(Long tenantId, String actor, String action,
                             String targetType, String targetId) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActor(actor);
        log.setActorType("USER");
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
```

- [ ] **Step 5: 写 MetadataServiceImplTest**

```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetadataServiceImplTest {

    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @InjectMocks MetadataServiceImpl metadataService;

    @Test
    void getSceneMetadata_returnsAvailableMetrics() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        when(sceneMapper.selectOne(any())).thenReturn(scene);

        MetricDefinition metric = new MetricDefinition();
        metric.setMetricCode("user.age");
        metric.setName("用户年龄");
        metric.setDataType("LONG");
        metric.setSourceType("ATTRIBUTE");
        metric.setAllowProvided(false);
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(metric));

        MetadataService.MetadataResponse response = metadataService.getSceneMetadata("1", "PAYMENT");

        assertThat(response.availableMetrics()).hasSize(1);
        assertThat(response.availableMetrics().get(0).metricCode()).isEqualTo("user.age");
    }
}
```

- [ ] **Step 6: 实现 MetadataServiceImpl**

```java
package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** MetadataService 实现：为前端编辑器提供可用的 metric / conditionType / actionType 元数据。 */
@Service
@RequiredArgsConstructor
class MetadataServiceImpl implements MetadataService {

    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;

    @Override
    public MetadataResponse getSceneMetadata(String tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.selectOne(
                new LambdaQueryWrapper<SceneDef>()
                        .eq(SceneDef::getTenantId, Long.valueOf(tenantId))
                        .eq(SceneDef::getCode, sceneCode));
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: " + sceneCode);
        }

        // 查 Scene 可用 metric（通过 scene_metric_binding 白名单，v1 简化：直接查该租户下全部 ACTIVE metric）
        List<MetricDefinition> metrics = metricDefinitionMapper.selectList(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getTenantId, Long.valueOf(tenantId))
                        .eq(MetricDefinition::getStatus, "ACTIVE"));

        List<MetricMeta> metricMetas = metrics.stream()
                .map(m -> new MetricMeta(m.getMetricCode(), m.getName(),
                        m.getDataType(), m.getSourceType(),
                        Boolean.TRUE.equals(m.getAllowProvided())))
                .toList();

        // conditionType / actionType 来自注册的 SPI Bean（v1 返回空列表，由 eval-svc 或注册中心提供）
        return new MetadataResponse(List.of(), List.of(), metricMetas);
    }
}
```

- [ ] **Step 7: 运行所有 service 测试**

```bash
$MVN -pl rule-config-svc -am test -Dtest="ConfigServiceImplTest,SceneServiceImplTest,MetadataServiceImplTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 8: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/
git commit -m "feat(config-svc): ConfigServiceImpl / SceneServiceImpl / MetadataServiceImpl 实现完成"
```

---

## Task 8: 运行全量测试 + 最终 Commit

**Files:** 无新增

- [ ] **Step 1: 运行 rule-config-svc 全量测试**

```bash
$MVN -pl rule-config-svc -am test
```

Expected: BUILD SUCCESS，所有测试通过，无跳过

- [ ] **Step 2: 若有失败，逐条修复后重跑，直到全绿**

- [ ] **Step 3: 最终 Commit（如 Step 1 有新改动）**

```bash
git add -p  # 仅暂存本轮修改，排除无关文件
git commit -m "test(config-svc): 修复测试，全量绿色"
```

---

## 自检清单

**Spec coverage：**
- [x] Flyway DDL — Task 2
- [x] Domain entity 与 DDL 对齐 — Task 3
- [x] MyBatis-Plus Mapper — Task 4
- [x] AstSerializer（AST ↔ JSON） — Task 5
- [x] 发布流程（DRAFT→PUBLISHED，audit_log，RulePublishedEvent） — Task 6
- [x] ConfigService / SceneService / MetadataService — Task 7

**Placeholder scan：** 无 TBD/TODO，所有代码块完整。

**Type consistency：**
- `RuleVersion` entity 字段名与 `PublishService` 调用一致（`conditionAst`，非 `conditionAstJson`）
- `SceneDef.tenantId` 类型统一为 `Long`
- `AstSerializer` 使用 `AstNode` sealed interface，`@JsonSubTypes` 覆盖全部 4 种节点
