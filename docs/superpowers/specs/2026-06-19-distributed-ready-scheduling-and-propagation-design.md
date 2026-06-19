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

## 3. 调度层:本地默认 + 分布式 opt-in,藏 `Scheduler` SPI 后

调度后端经 `Scheduler` SPI(kernel)解耦,**默认本地、分布式 opt-in**:

- **默认 = 本地进程内 ThreadPool**(`JobAutoConfiguration` 的 `@ConditionalOnMissingBean(Scheduler.class)` → `ThreadPoolSchedulerAdapter`)。**dev 零外部依赖**——不配 `engine.rule.job.scheduler` 就用本地单机调度,不需要起 XXL。
- **分布式 opt-in**:配 `engine.rule.job.scheduler=xxl-job` 才激活 `XxlJobSchedulerAdapter`(`@ConditionalOnProperty`),接管中心调度 + 集群单实例派发 + 控制台。将来 PowerJob 同理 `=power-job`。
- 调度器**只回答"何时 + 哪个/哪些实例跑",不碰评估/回灌语义**。

### 3.0 后端适配器模块布局(ports-adapters)

```
rule-kernel        Scheduler SPI + TaskExecutor SPI + TaskRunContext/TaskRunResult(端口,共享)
rule-job-svc       框架核:scheduled_task 实体 + TaskExecutorRegistry(DI 收集)+ ScheduleManager
                   + TriggerExecutor(注入 EvalService)+ ThreadPoolSchedulerAdapter(本地默认)
                   依赖 = kernel SPI + eval-svc;【不依赖具体调度器、不依赖各 handler 模块】
rule-eval-svc      OUTCOME_INGESTION executor + config(+ OutcomeSource SPI);依赖 = kernel SPI
rule-observability RETENTION(trace)/ALARM executor;依赖 = kernel SPI
rule-job-xxl       XxlJobSchedulerAdapter(@ConditionalOnProperty=xxl-job);依赖 = kernel SPI + xxl-job-core
rule-job-powerjob  (将来)PowerJobSchedulerAdapter(=power-job);依赖 = kernel SPI + powerjob-worker
rule-app           组装:恒含 rule-job-svc + 各 handler 模块 + 选含一个后端适配器模块
```
两条解耦:① **后端适配器**与框架互不依赖,只依赖 kernel `Scheduler` SPI;② **各 task handler**(executor + config)落拥有该领域的模块,只依赖 kernel `TaskExecutor` SPI,**job-svc 经 Spring `List<TaskExecutor>` DI 收集而不依赖它们**(XXL `@XxlJob` 扫描式解耦)。**加后端 = 换适配器模块;加 task 类型 = 在领域模块加 executor bean;两者框架核都零改。**

### 3.0.1 `Scheduler` SPI(可移植契约,禁绕过)

**当前(已实装,不变)**:`Scheduler` 仅 `schedule(code, cron, task)` + `unschedule(code)`——cron 单实例派发。本轮去中心化重构**不改 Scheduler**(只动 `TaskExecutor` SPI,见 §4.3)。

**目标契约(将来,deferred 到多实例,现在不加——YAGNI:无调用方)**:
```java
// 仅当做"多实例广播接线 / on-demand 触发"时才加,届时 SPI + 调用方 + 三适配器实现一起落:
void triggerOnce(String code, String param);              // on-demand 单触发(现手动触发走本地 runOnce,暂不需)
void scheduleBroadcast(String code, Runnable onEachNode);  // 注册广播 handler
void triggerBroadcast(String code, String param);          // 广播到所有实例(§6 A 类 config 收敛)
```
- 实现纪律(届时):ThreadPool 本地退化单机直跑;XXL=分片广播 + admin trigger;PowerJob=broadcast 模式 + OpenAPI。**业务只认 SPI,严禁直连 XXL/PowerJob 原生 API**(不锁死的唯一纪律)。
- **PowerJob 切换无能力缺口**:cron 单派发 / 广播 / on-demand 三者 XXL、PowerJob 都有(PowerJob 还多 Map/MapReduce/DAG)。

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

关键点：
1. **载荷只带 taskId，不带 config** —— `runById` 触发时回 DB 重查，config 改了立即生效，XXL 内永无过期业务配置。
2. **运维面统一在我们后端,cron 权威 = `scheduled_task`(我们的库)**:运维经 `/admin/v1/scheduled-tasks`(我们的 UI)管 cron/启停/触发/看执行记录;**不去 XXL/PowerJob console**(那是隐藏触发引擎,只给 SRE 深度排障)。故 `Scheduler.schedule()` 是 **upsert**(不存在则建、cron 变则更新推到后端),**非"seed 后 admin 权威"**。理由:① 可移植(切后端运维面不变);② 单一真相源不漂移;③ console 不懂我们的 typed config;④ 执行记录我们自己有(`scheduled_task_execution`),不依赖 console 看日志。
3. **执行记录**:`scheduled_task_execution` = 域级结果(我们 UI 展示),无需 XXL log 作运维面。
4. **后端无关**:`SchedulerAdapter` 抽象使换 `ThreadPoolSchedulerAdapter`(dev)/ 将来 PowerJob 时,`scheduled_task` 与 executor 一字不改,**且运维面(我们的 UI)也不变**。

## 4. 任务层:`ScheduledTask` + `TaskExecutor` SPI(消除别扭的重写)

### 4.1 单表 + 类型判别 + 类型化 config

```
scheduled_task
  id, tenant_id, code, name              -- SYSTEM 型 tenant_id 为系统占位
  task_type   VARCHAR  -- TaskType: TRIGGER / OUTCOME_INGESTION / RETENTION / ALARM
  scope       VARCHAR  -- TaskScope: TENANT / SYSTEM
  cron        VARCHAR  -- 交调度器
  config      JSON     -- sealed TaskConfig(typed,静态定义,见 4.2)
  run_cursor  VARCHAR  -- 增量任务运行游标(state-not-config;OUTCOME_INGESTION 存 ISO-8601 watermark,其余 null)
  status      VARCHAR  -- ACTIVE / DISABLED
  created_by/at, updated_by/at
  UNIQUE(tenant_id, code)
```

**`task_type` 是开放 string,不是中心枚举(去中心化,对齐 XXL `@XxlJob` name / Airflow operator type)**:每个 executor 自声明类型名,框架核不持有全集枚举。`scope` 区分 TENANT(API/注解配置)/ SYSTEM(启动 seed,cron 取 properties)。

现有/规划类型(各自落**拥有该领域的模块**,见 §4.3):

| task_type | scope | 落点模块 | 说明 |
|---|---|---|---|
| `TRIGGER` | TENANT | rule-job-svc | 合成 RuleEvent → 评估(现 `JobRunner` 迁来) |
| `OUTCOME_INGESTION` | TENANT | **rule-eval-svc** | `OutcomeSource` SPI 增量拉标签 → upsert;watermark 写 `run_cursor`(已实装,去中心化后从 job-svc 迁回 eval-svc) |
| `RETENTION` | SYSTEM | eval-svc / observability | 清旧 session/trace(收编 `SessionRetentionCleaner`/`TraceRetentionCleaner`) |
| `ALARM` | SYSTEM | observability | 告警巡检(收编 `ObservabilityAlarmChecker`) |

**为什么开放 string + 去中心化(而非中心 enum)**:task 类型本质是**跨模块开放扩展点**(模块各自加类型),不是封闭集——故用开放 string(数据类型纪律的 enum 规则针对封闭集;此处开放)。中心 `TaskType` enum 会让 job-svc 持有全集 → 又把各模块类型拉回 job-svc。去中心化后:**加新类型 = 在拥有它的模块加一个 executor bean,job-svc 零改、零新依赖**(§4.3 DI 收集)。

**为什么维护型也收进框架(修订早先"@Scheduled+ShedLock 分开")**:XXL-first 后维护型反正要上调度后端拿集群单跑,"留 @Scheduled + 引 ShedLock"没意义;统一成一个调度模型(一表/一 dispatch/一执行记录/一控制台,SYSTEM/TENANT 仅 scope 别),单跑由后端白送,**ShedLock 不再需要**(§5)。

### 4.2 config 去中心化:每 handler 自带 typed record,框架核存 JSON(开源 hybrid)

**不设中心 sealed `TaskConfig` 联合体**(它会把所有 config + 其引用的模块类型锁进 job-svc,正是依赖集中的根因)。改用开源调度器的 hybrid([搜索结论](https://airflow.apache.org/docs/apache-airflow/stable/howto/custom-operator.html):按 name 注册 + 派发时 JSON 反序列化成 typed):

- **`scheduled_task.config` 列存原始 JSON**(框架核视为不透明 payload——generic infra 无法为跨模块全部 task 持单一类型,数据类型纪律的"确实无定义"例外)。
- **每个 task 类型的 config 是各自模块里的 typed record**(`TriggerConfig` 在 job-svc、`OutcomeIngestionConfig`+`SqlOutcomeSourceConfig` 在 eval-svc、将来 `RetentionConfig` 在 eval-svc/observability),**不实现任何共享 sealed 基类**——各自独立,无中心耦合。
- **typed 在派发时恢复**:registry 按 executor 的 `configType()` 把 config JSON 反序列化成该 handler 的 typed record(§4.3)。**每个 handler 内仍是 typed record**(数据类型纪律在 handler 边界内满足),只有框架核边界碰 JSON。
- **run_cursor 仍独立列**(state-not-config,不变)。

代价(已认):放弃中心 sealed 的编译期穷举,换运行期开放(各 handler 自注册 type)——这正是 XXL/Quartz/Airflow 的做法,且是去中心化的前提。

### 4.3 executor SPI(下沉 kernel,去中心化;DI 收集而非 job-svc 依赖各模块)

**`TaskExecutor` SPI + `TaskRunContext`/`TaskRunResult`/`TaskExecutionStatus` 放 `rule-kernel`**(共享端口,各模块已依赖 kernel),使各模块实现 executor **只依赖 kernel、不依赖 job-svc**(避免环 + fan-out):

```java
// rule-kernel
record TaskRunContext(long taskRunId, long taskId, long tenantId) {}
interface TaskExecutor<C> {                 // C = handler 自己的 config record,无共享 sealed 基类
    String type();                          // 开放类型名(如 "TRIGGER" / "OUTCOME_INGESTION"),非中心 enum
    Class<C> configType();                  // 供 registry 把 config JSON 反序列化成 C
    TaskRunResult execute(TaskRunContext ctx, C config);
}
```

派发(`TaskExecutorRegistry` 在 job-svc,但**经 Spring `List<TaskExecutor>` 跨模块收集,不依赖 handler 模块**——这正是 XXL `@XxlJob` bean 扫描的解耦,DI 实现):
```
触发 ScheduledTask → registry 按 task_type(String)查 executor
  → objectMapper.readValue(task.getConfig()/*JSON*/, executor.configType()) 得 typed C
  → executor.execute(ctx, C) → TaskRunResult → 写 scheduled_task_execution
```

**加新调度工作 = 在拥有该领域的模块加一个 `TaskExecutor` bean(声明 type + configType + 一个 config record),job-svc 零改、零新依赖**。executor 落点:`TRIGGER` 在 job-svc(注入 EvalService);`OUTCOME_INGESTION` 在 **eval-svc**(注入 OutcomeIngestionService,去中心化后从 job-svc 迁回);`RETENTION`/`ALARM` 在 eval-svc/observability。各只依赖 kernel SPI,Spring 收集进 job-svc 的 registry。

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

## 5. 系统固定维护:收进框架(SYSTEM scope),不要 ShedLock

**决策(已随 XXL-first 修订;取代早先"@Scheduled + ShedLock 分开"方案)**:RETENTION(session/trace 清理)、ALARM(告警巡检)收进 ScheduledTask 框架,作 **SYSTEM scope** task type:

- 启动期 seed 一行 SYSTEM `scheduled_task`(tenant_id=系统占位,cron 取 properties 如 `engine.rule.retention.cron`),经 `Scheduler.schedule` 注册;executor 落各自模块(RETENTION 在 eval-svc/observability、ALARM 在 observability),实现 `TaskExecutor` SPI、Spring 收集。
- **集群单跑由调度后端白送**:本地 ThreadPool=单机天然单跑;XXL/PowerJob=中心派发单实例。**不再需要 ShedLock**。
- 收编原散落的 `SessionRetentionCleaner`/`TraceRetentionCleaner`/`ObservabilityAlarmChecker` 三处 `@Scheduled`,统一成框架内 task。

**为什么从"分开"改成"收进"**:早先按业界"别为单跑上重型调度器"留 `@Scheduled`+ShedLock。**但定了 XXL-first 后,维护型反正要上调度后端拿单跑**,ShedLock 的存在理由(只为 @Scheduled 单跑、免上调度器)就没了。此时"框架外再有一摊裸 @Scheduled/裸 XXL job"是两个调度模型并存——不如**统一成一个**(一表/一 dispatch/一执行记录/一控制台,SYSTEM/TENANT 仅 scope 别)。代价:SYSTEM 型多一行启动 seed(轻),换来单一模型 + 统一观测 + 省掉 ShedLock 依赖。

**与传播层不混**:这里收进框架的是 **cron 定时**维护;§6 的 config→eval 广播是**事件驱动广播**(传播层),不是 scheduled_task。

**为什么不统一进框架**：给固定常量套 DB-config + seed/迁移仪式 = 过度设计，divorce 了调度与逻辑；平台的动态 CRUD/控制台价值对固定维护任务是浪费。代价：观测分两处（平台 console 看业务 task / 监控日志看维护任务），可接受。

## 6. 传播层：按语义分 A/B/C，每类一种 transport

**铁律**：事件分**广播**（每实例都反应）与**竞争消费**（仅一个实例反应），两者 transport 不同，绝不通吃。

### A 类 config→eval 收敛（广播）
- **真相源 = DB**；内存倒排索引是每实例缓存。
- **基线安全网 = 现有 poll watcher**（`DbPollingRuleWatcher` 15s / `DbPollingSceneWatcher` 30s），保留不删。
- **加 push 广播 = 复用调度后端的广播能力(不引 Redis)**：config-svc 发布/改场景时经 `Scheduler.triggerBroadcast(code, "tenant:scene")` 广播 → 各实例 `scheduleBroadcast` 注册的 handler 收到后从 DB 重载该片。Transport 由后端实现:本地=单机直跑、XXL=分片广播路由、PowerJob=broadcast 模式——**业务只调 `Scheduler` SPI,不碰具体调度器 API(§3.0.1 纪律)**。
- **分层**：单实例走 Modulith 进程内事件（毫秒）；多实例经调度后端广播（亚秒）+ poll（15s 兜底）。漏信号有 poll 兜底。
- **为何不用 Redis**：既已 XXL-first,广播能力调度后端就有,无需为这一件冷路径的事再引 Redis 依赖;真未来要 Kafka/CDC 级 durable 传播再升(届时也可把广播迁过去)。

### B 类 同事务强一致（audit_log）
- **不动**。`@TransactionalEventListener(BEFORE_COMMIT)`，同事务同实例，分布式与它无关。

### C 类 异步 best-effort（trace/audit/outcome 落库）
- 现状多实例**已够用**：每实例进程内队列各自落自己评估的审计，不冲突、`event_id` uk 兜底去重。
- **要崩溃不丢 / 跨实例消费 / 接外部 MQ 时** → **Transactional Outbox**：业务写同事务写 `outbox` 表 → CDC（Debezium）或轮询发布 → MQ → 任意实例消费落库。落成 `DomainEventPublisher` 缝的第二个实现（InProcess→Outbox），缝已预留。
- **本轮不实现 Outbox**，保留缝。

## 7. 实现节奏

- **已实装(track #1/#2)**：`ScheduledTask` + `TaskExecutor` SPI + 统一执行记录 + 本地 ThreadPool 默认 + XXL opt-in 适配器;TRIGGER/OUTCOME_INGESTION 两个 executor。
- **现在该做(track #3 近期)**:`Scheduler` SPI 扩 `triggerOnce`/`scheduleBroadcast`/`triggerBroadcast`(三后端实现,本地退化单机)+ 维护型收进框架(SYSTEM scope RETENTION/ALARM,删 ShedLock 计划)。
- **真上多实例再填**:config→eval 广播的 push 触发接线(经 `triggerBroadcast`)+ Outbox(C 类 durable)。抽象立对,填实现不返工。

## 8. 分解为 track（各自 plan）

| # | track | 内容 | 依赖 | 节奏 |
|---|---|---|---|---|
| 1 | `scheduled-task-framework` | §3+§4:`ScheduledTask`/executor SPI + 调度 + 本地默认/XXL opt-in + 删/迁 job_definition | 无 | **已实装** |
| 2 | `outcome-ingestion` | §4 `OUTCOME_INGESTION` executor + OutcomeSource SPI + run_cursor | #1 | **已实装** |
| 3 | `cross-instance-propagation` | §3.0.1 SPI 扩 broadcast/trigger + §5 维护型收进框架(删 ShedLock)+ §6 广播经调度后端 + (Outbox 缝) | 正交 | 近期 SPI+维护统一;广播/Outbox 多实例时填 |
| 4 | `b32-frontend` | 决策效果报表页 + 手工回灌表单 + job→scheduled-task 管理页 | 仅依赖已落地 API | 独立，可并行 |

## 9. 开源参照

- **调度选型**：XXL-JOB（中小、控制台）/ PowerJob（云原生、broadcast/MapReduce/DAG）/ Elastic-Job（重分片）/ Quartz（遗留）/ ShedLock（仅集群单跑,非调度器）。本方案:**本地 ThreadPool 默认 + XXL opt-in,PowerJob 可平替**;既已有中心调度器,**单跑/广播全压调度后端,不再需要 ShedLock**。
- **传播**：分布式缓存失效 / Outbox / CDC（Debezium）。A 类广播 transport 选**调度后端广播**(XXL 分片广播 / PowerJob broadcast),避免为冷路径引 Redis;分层（broadcast + poll 兜底）是公认稳健做法。
- **state-not-config**(增量游标):Kafka Connect offset 独立 topic / Airbyte Singer STATE 消息 / Spring Batch ExecutionContext —— 故 watermark 落 `run_cursor` 列而非 config(§4.1)。

## 10. 非目标（YAGNI）

- 不引 Redis(广播走调度后端)、不引 ShedLock(单跑走调度后端)。
- config→eval 广播 push 接线 + Outbox(C 类 durable)本轮不实现,立抽象,多实例时填。
- 不引 Kafka / Debezium / 配置中心(Outbox/调度后端广播足够演进,规模到了再升)。
- 不动 B 类同事务事件。

## 11. 测试策略

- 任务框架：executor 分发（按 TaskType 路由 + config 反序列化到正确子类型）、`TaskConfig` 多态 JSON round-trip、统一执行记录落库；TRIGGER executor 迁移后行为等价（现有 job 集成测试沿用/改造）。
- 迁移：`scheduled_task` 表 Flyway + 既有 job 数据迁移脚本（greenfield 可重建）。
- Redis/ShedLock 不引入(广播/单跑走调度后端);`Scheduler` SPI 扩 broadcast/trigger → 各 adapter 契约测试 + 本地 ThreadPool 退化行为测试;Outbox/广播 push 多实例时填,仅留 SPI 形状测试。
- 跨模块改动带 `-am`，最终 `clean test` 兜底；涉 schema/落库链路 track 真实服务 e2e。
