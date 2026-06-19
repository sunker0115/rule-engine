# 分布式就绪:调度 / 任务 / 事件传播架构 — 设计

重写 job 子系统并把"多实例事件传播"提前钉进抽象。greenfield、未上线,不背向后兼容(可重建 schema、迁移既有 job)。B32 回灌 ingestion 作为新架构上的第一个 `OUTCOME_INGESTION` executor 验证落位。

## 1. 问题

- **job 子系统别扭**:`job_definition` 把"何时调度 / 执行什么 / 评估语义"焊死在一个聚合(`scene_code`/`event_type`/`subject_query` 全 NOT NULL),`JobRunner` 硬绑"合成 RuleEvent → 评估"。塞任何非评估的调度工作(如 B32 回灌)都是打补丁。
- **散落的定时工作**:`SessionRetentionCleaner` / `TraceRetentionCleaner` / `ObservabilityAlarmChecker` 各自 `@Scheduled`,无统一单跑保障。
- **分布式未就绪**:进程内 Spring 事件不跨实例;多实例下定时任务会每实例重复触发、配置变更不跨实例收敛(现仅靠 15s/30s 轮询)。

## 2. 核心判断:拆开纠缠的三层

当前把三件正交的事揉成一团。重写按三层解耦:

| 层 | 只负责 | 抽象 |
|---|---|---|
| **调度 WHEN** | cron 触发 + 集群内恰好一次 | `SchedulerAdapter`(已有) |
| **任务 WHAT** | 一个工作单元的执行 | `ScheduledTask` + `TaskExecutor` SPI(新) |
| **传播 HOW** | 变更/事件如何到达所有实例 | 按 A/B/C 分类,每类一种 transport |

## 3. 调度层:XXL-JOB 骨干,藏 `SchedulerAdapter` 后

- 保留既有 `SchedulerAdapter` 缝(`XxlJobSchedulerAdapter` 分布式 / `ThreadPoolSchedulerAdapter` 单机 dev)。
- 骨干用 **XXL-JOB**(已集成):中心调度 + executor 集群 + 控制台 + 单实例派发(天然解决重复触发)。
- 调度器**只回答"何时 + 哪个实例跑",不碰评估/回灌语义**。将来切 **PowerJob**(云原生 / broadcast / MapReduce / DAG)只换 adapter 实现,框架不动。
- 选型依据:中小规模 + 已集成 + 要控制台 → XXL-JOB;PowerJob 留作规模/工作流升级档(对比见 §9)。

### 3.1 `scheduled_task`(我们的表) ↔ XXL(`xxl_job_info`)关系

两者是**不同的东西,不是同一份数据的两套存储**(现有 `job_definition`↔XXL 已是此模型,`scheduled_task` 只是一般化):

| | `scheduled_task` | XXL-JOB（`xxl_job_info`/log） |
|---|---|---|
| 存什么 | **应用域真相源**：task 身份 + `task_type` + 富 `config`（connector/mapping/subjectQuery）+ 业务数据 + 初始 cron/status | **触发引擎的薄镜像**：`jobCode→handler` 绑定 + cron，只为"何时触发、派哪个实例" |
| 懂不懂 config | 是 | **完全不懂**，只认 jobCode + cron |

经 `Scheduler` SPI（`schedule(code, cron, Runnable)` / `unschedule(code)`）解耦：

```
建/启用 scheduled_task → ScheduledTaskScheduleManager.register
  → Scheduler.schedule(taskCode, cron, () -> runById(taskId))
  → XxlJobSchedulerAdapter：注册 handler + adminClient.ensureJobSeeded(taskCode, cron)

XXL admin 按 cron 触发（集群内派一个实例）→ handler 跑 runById(taskId)
  → 回 DB 重载最新 scheduled_task（config 永远新鲜，XXL 内不存 config）
  → 按 task_type 查 executor → execute → 写 scheduled_task_execution
```

关键点（沿用现有 `JobScheduleManager` 模式）：
1. **载荷只带 taskId，不带 config** —— `runById` 触发时回 DB 重查，config 改了立即生效，XXL 内永无过期业务配置。
2. **cron 归属**：`scheduled_task.cron` 是 **seed 初值**；seed 后（"有了不管"，`ensureJobSeeded` 不覆盖）**XXL 控制台对 cron/启停运行时权威**，运维可在 console 直调。唯一重叠字段 cron = app seed → admin 运行时权威。
3. **执行记录互补**：`scheduled_task_execution` = 域级结果（subject/success/error 计数、error_summary）；XXL log = 派发级 infra 日志。看的是不同层。
4. **后端无关**：`SchedulerAdapter` 抽象使换 `ThreadPoolSchedulerAdapter`（dev）/ 将来 PowerJob 时，`scheduled_task` 与 executor 一字不改。

## 4. 任务层:`ScheduledTask` + `TaskExecutor` SPI(消除别扭的重写)

### 4.1 单表 + 类型判别 + 类型化 config

```
scheduled_task
  id, tenant_id, code, name
  task_type   VARCHAR  -- TaskType enum(封闭),仅 TRIGGER / OUTCOME_INGESTION
  cron        VARCHAR  -- 交调度器
  config      JSON     -- sealed TaskConfig(typed,静态定义,见 4.2)
  run_cursor  VARCHAR  -- 增量任务运行游标(state-not-config;OUTCOME_INGESTION 存 ISO-8601 watermark,其余 null)
  status      VARCHAR  -- ACTIVE / DISABLED
  created_by/at, updated_by/at
  UNIQUE(tenant_id, code)
```

`TaskType` 封闭集**只覆盖动态/租户配置型**(grounded,不发明):

```java
enum TaskType { TRIGGER, OUTCOME_INGESTION }
```

- `TRIGGER`:合成 RuleEvent → 评估(现 `JobRunner` 降级为一个 executor)。
- `OUTCOME_INGESTION`（B32 已实装）:经 `OutcomeSource` SPI（首个实现 `SqlOutcomeSource`）增量拉标签 → `OutcomeService` upsert，watermark 取本批 max `labeled_at` 写回独立 `scheduled_task.run_cursor` 列推进游标（state-not-config,对齐 Kafka Connect offset / Airbyte state——运行态游标与不可变 config 分离）。

### 4.2 config 是 sealed 类型族,不是裸 Map/JSON-String

照 `RuleVersion` typed-JSON 模板（`@TableName(autoResultMap=true)` + typed 字段 + `Jackson3TypeHandler`），守数据类型纪律：

```java
sealed interface TaskConfig permits TriggerConfig, OutcomeIngestionConfig {}
record TriggerConfig(String sceneCode, String eventType, SubjectQuery subjectQuery) implements TaskConfig {}
record OutcomeIngestionConfig(OutcomeSourceConfig source) implements TaskConfig {}
// OutcomeSourceConfig 亦为 sealed 多态族：SqlOutcomeSourceConfig(String datasource, String sql) 为首个实现（@JsonTypeInfo kind 自描述）
// 注：增量 watermark 不在 config（不可变静态定义），存于独立 scheduled_task.run_cursor 列（运行态，executor 管理；对齐 Kafka Connect offset / Airbyte state 的 state-not-config 模式）
```

- 多态自描述：`@JsonTypeInfo(use=NAME, property="kind")` + `@JsonSubTypes`，TypeHandler 按 kind 还原子类型。
- `task_type` 列**冗余一份仅供查询/分发**（不解析 JSON 即可筛、可路由 executor），写入时从 `config` 的类型派生，单一真相在 config 的多态 tag。

### 4.3 executor SPI（Spring 自动收集，照 `ExpressionEngine`/`MetricSourceHandler` 定式）

```java
interface TaskExecutor<C extends TaskConfig> {
    TaskType type();
    Class<C> configType();
    TaskRunResult execute(long taskRunId, long tenantId, C config);
}
```

调度器触发 `ScheduledTask` → dispatcher 按 `task_type` 查 `Map<TaskType, TaskExecutor>` → 反序列化 config 到 `executor.configType()` → `execute`。**加新调度工作 = 加一个 enum 值 + 一个 executor + 一个 config record，零主干改动**。executor 实现落各自模块（TRIGGER 在 job-svc，OUTCOME_INGESTION 在 eval-svc 侧），实现 SPI、Spring 收集，不跨 Modulith 边界乱伸。

### 4.4 统一执行记录

`scheduled_task_execution`（收编现有 `job_execution`）：通用形状（task_id / status / 各类计数 / error_summary / trigger_at / finished_at），所有 task 类型同口径落库 + 观测。

### 4.5 既有迁移 —— 删 / 留 / 迁清单（破坏性，greenfield 无向后兼容）

**删除/替换（job-as-评估触发聚合）：**
- 表：`job_definition`、`job_execution`（V1_7）→ 新建 `scheduled_task`、`scheduled_task_execution`。
- 实体/Mapper：`JobDefinition`/`JobDefinitionMapper`、`JobExecution`/`JobExecutionMapper`/`JobExecutionStatus`/`JobStatus` → `ScheduledTask*`/`ScheduledTaskExecution*`。
- 服务/控制器/DTO：`JobService(Impl)`、`JobController`、`JobDefinitionDto`/`JobExecutionVO`/`JobPage` → `ScheduledTask*`。
- `JobRunner` → `TriggerExecutor`（`TaskExecutor<TriggerConfig>`）。
- **API 契约**：`/admin/v1/jobs*` → `/admin/v1/scheduled-tasks*`；对外术语统一为「调度任务 / scheduled-task」（去除"job=评估触发"旧语义）。
- **前端**：`pages/job-list`、`pages/job-detail`、`api/job.ts`、`ROUTES.JOBS/JOB_DETAIL`、`ENDPOINTS.JOB_*`、`menu.jobs`、i18n `job` → 改为调度任务管理（含 `task_type` 维度）。

**保留/复用（调度 infra + 触发机制，无问题不重写）：**
- `Scheduler` SPI（kernel）、`XxlJobSchedulerAdapter`/`ThreadPoolSchedulerAdapter`、`XxlJobAdminClient`/`HttpXxlJobAdminClient`、XXL autoconfig。
- `JobScheduleManager`/`JobStartupRegistrar` → 一般化为注册 `ScheduledTask`（重命名 `ScheduledTaskScheduleManager` 等）。
- `@RuleJob`/`RuleJobScanner`/`SubjectQuery`/`SubjectQueryRunner`/`BeanMethod*`/`JobTarget`/`EventIdHasher` —— TRIGGER 的取主体+合成事件机制，**收编进 `TriggerExecutor` 内部**，不删。

**迁移方式**：greenfield 可重建——新 Flyway 建 `scheduled_task`/`scheduled_task_execution`，drop `job_definition`/`job_execution`（无生产数据需保留）。

## 5. 系统固定维护：`@Scheduled` + ShedLock（**不进**任务框架）

**决策（业界标准）**：RETENTION（session + trace 清理）、ALARM（告警巡检）是系统固定型——全局常量 cron、无业务 config、改它要发版。按主流做法**不塞 DB 任务表**：

- 留 `@Scheduled`（调度就近贴逻辑，version-controlled、可测）。
- 多实例唯一真问题"重复触发"用 **ShedLock**（共享锁 DB/Redis）解决，极轻。ShedLock 即为此而生：只需保证 `@Scheduled` 集群内单跑、无需上重型调度平台。
- 顺手把两个 retention cleaner 统一成一致风格（同一 `RetentionProperties` 口径），但仍是 `@Scheduled`，不进框架。

**为什么不统一进框架**：给固定常量套 DB-config + seed/迁移仪式 = 过度设计，divorce 了调度与逻辑；平台的动态 CRUD/控制台价值对固定维护任务是浪费。代价：观测分两处（平台 console 看业务 task / 监控日志看维护任务），可接受。

## 6. 传播层：按语义分 A/B/C，每类一种 transport

**铁律**：事件分**广播**（每实例都反应）与**竞争消费**（仅一个实例反应），两者 transport 不同，绝不通吃。

### A 类 config→eval 收敛（广播）
- **真相源 = DB**；内存倒排索引是每实例缓存。
- **基线安全网 = 现有 poll watcher**（`DbPollingRuleWatcher` 15s / `DbPollingSceneWatcher` 30s），保留不删。
- **加 push 广播**：config-svc 发布/改场景时经 `ConfigChangePublisher` 发轻量"失效(tenant, scene)"信号 → 各实例订阅后从 DB 重载该片。Transport 首选 **Redis Pub/Sub**，落成现有 `MqRuleWatcher` SPI 的第一个实现。
- **分层**：单实例走 Modulith 进程内事件（毫秒）；多实例 Redis 广播（亚秒）+ poll（15s 兜底）。漏信号有 poll 兜底。

### B 类 同事务强一致（audit_log）
- **不动**。`@TransactionalEventListener(BEFORE_COMMIT)`，同事务同实例，分布式与它无关。

### C 类 异步 best-effort（trace/audit/outcome 落库）
- 现状多实例**已够用**：每实例进程内队列各自落自己评估的审计，不冲突、`event_id` uk 兜底去重。
- **要崩溃不丢 / 跨实例消费 / 接外部 MQ 时** → **Transactional Outbox**：业务写同事务写 `outbox` 表 → CDC（Debezium）或轮询发布 → MQ → 任意实例消费落库。落成 `DomainEventPublisher` 缝的第二个实现（InProcess→Outbox），缝已预留。
- **本轮不实现 Outbox**，保留缝。

## 7. 实现节奏

- **现在立抽象**：`ScheduledTask` + `TaskExecutor` SPI + 统一执行记录、`ConfigChangePublisher/Subscriber` 缝。（`SchedulerAdapter` / `DomainEventPublisher` / `MqRuleWatcher` SPI 已有）
- **真上多实例再填实现**：Redis Pub/Sub watcher、ShedLock、Outbox。抽象立对，填实现不返工。

## 8. 分解为 track（各自 plan）

| # | track | 内容 | 依赖 | 节奏 |
|---|---|---|---|---|
| 1 | `scheduled-task-framework` | §3+§4：`ScheduledTask`/executor SPI + 调度 + 统一执行记录 + 删/迁 job_definition/JobRunner（含前端 job→scheduled-task 页，§4.5） | 无 | 地基，先实现 |
| 2 | `outcome-ingestion` | §4 的 `OUTCOME_INGESTION` executor + ingestion config + watermark（B32 回灌后端） | #1 | 次之，自有 spec |
| 3 | `cross-instance-propagation` | §6 实现：Redis 广播 watcher + ShedLock + (Outbox 缝) | 正交 | 真上多实例时 |
| 4 | `b32-frontend` | 决策效果报表页 + 手工回灌表单 | 仅依赖已落地 API | 独立，可并行 |

## 9. 开源参照

- **调度选型**：XXL-JOB（中小默认、控制台）/ PowerJob（云原生、broadcast/DAG）/ Elastic-Job（重分片）/ Quartz（遗留）/ ShedLock（仅集群单跑，非调度器）。本方案 XXL 骨干 + ShedLock 管系统维护。
- **传播**：分布式缓存失效 / Outbox / CDC（Debezium）；广播 transport Redis Pub/Sub（轻）vs Kafka（durable）vs CDC（零 dual-write，最重）。分层（broadcast + poll 兜底）是公认稳健做法。

## 10. 非目标（YAGNI）

- 不本轮实现 Redis 广播 / ShedLock / Outbox（立缝，多实例时填）。
- 不把 RETENTION/ALARM 收进任务框架（§5）。
- 不引 Kafka / Debezium / 配置中心（Redis/Outbox 缝足够演进，规模到了再升）。
- 不动 B 类同事务事件。

## 11. 测试策略

- 任务框架：executor 分发（按 TaskType 路由 + config 反序列化到正确子类型）、`TaskConfig` 多态 JSON round-trip、统一执行记录落库；TRIGGER executor 迁移后行为等价（现有 job 集成测试沿用/改造）。
- 迁移：`scheduled_task` 表 Flyway + 既有 job 数据迁移脚本（greenfield 可重建）。
- ShedLock/Redis/Outbox 本轮不实现 → 不测，仅留抽象与契约测试（SPI 形状）。
- 跨模块改动带 `-am`，最终 `clean test` 兜底；涉 schema/落库链路 track 真实服务 e2e。
