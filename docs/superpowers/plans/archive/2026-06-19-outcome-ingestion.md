# Track #2: OUTCOME_INGESTION executor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.

**Goal:** B32 回灌 ingestion —— 新框架上第一个新 `TaskExecutor`:定时从 `OutcomeSource`(SQL 实现)增量拉真实标签 → `OutcomeService` upsert `decision_outcome`,watermark 写回 config。

**Architecture:** 见 `docs/superpowers/specs/2026-06-19-outcome-ingestion-design.md`。`OutcomeSource` SPI(丙)+`SqlOutcomeSource`(甲,复用 `MetricDataSourceRegistry`)在 eval-svc;`OutcomeIngestionConfig`(sealed TaskConfig permit,job-svc api 同包约束)+`OutcomeIngestionExecutor` 在 job-svc;SPI 改 `execute(TaskRunContext, config)`。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus(typed JSON via Jackson3TypeHandler)/ Jackson3(注解 com.fasterxml.jackson.annotation,databind tools.jackson)。

**环境:** `mvn-env`(Java 25,`$MVN`);跨模块 `-am`;最终 `clean test`。

---

### Task 1: SPI 改 TaskRunContext

**Files:** `rule-job-svc/.../api/TaskRunContext.java`(新)、`api/TaskExecutor.java`、`internal/runner/TaskExecutorRegistry.java`、`internal/runner/TriggerExecutor.java` + 三处测试(`TaskExecutorRegistryTest`/`TriggerExecutorTest`/`ScheduledTaskScheduleManagerTest`)。

- [ ] **Step 1** 新增 `api/TaskRunContext.java`:
```java
package com.sstlfsj.rule.job.api;
/** 一次任务运行的上下文。
 * @param taskRunId scheduled_task_execution.id(每次运行唯一,eventId 幂等键)
 * @param taskId    scheduled_task.id(任务定义,供有状态 executor 写回 config)
 * @param tenantId  租户 id */
public record TaskRunContext(long taskRunId, long taskId, long tenantId) {}
```
- [ ] **Step 2** `TaskExecutor.execute` 签名 → `TaskRunResult execute(TaskRunContext ctx, C config)`;更新 Javadoc。
- [ ] **Step 3** `TaskExecutorRegistry.dispatch(ScheduledTask task, long taskRunId)`:构造 `new TaskRunContext(taskRunId, task.getId(), task.getTenantId())` 传 `executor.execute(ctx, config)`。
- [ ] **Step 4** `TriggerExecutor.execute(TaskRunContext ctx, TriggerConfig config)`:`EventIdHasher.hash(ctx.taskRunId(), subjectId)`;log 用 `ctx.taskRunId()`;`String tenant = String.valueOf(ctx.tenantId())`。
- [ ] **Step 5** 测试同步:`TaskExecutorRegistryTest` stub executor 的 execute(ctx,config);`TriggerExecutorTest` `executor.execute(new TaskRunContext(1L,1L,7L), config)`;`ScheduledTaskScheduleManagerTest` 不变(它 mock registry.dispatch)。
- [ ] **Step 6** `$MVN -pl rule-job-svc -am test`(全模块测试)绿。
- [ ] **Step 7** commit: `refactor(job): TaskExecutor.execute 改传 TaskRunContext(taskRunId/taskId/tenantId)`

---

### Task 2: eval-svc OutcomeSource SPI(丙)+ SqlOutcomeSource(甲)

**Files(eval-svc):** `api/service/OutcomeSourceConfig.java`、`api/service/SqlOutcomeSourceConfig.java`、`api/service/OutcomeSource.java`、`api/service/OutcomePullResult.java`、`internal/outcomesource/SqlOutcomeSource.java`、`internal/outcomesource/OutcomeSourceRegistry.java` + 测试。

> 包放置:源配置/SPI 放 `com.sstlfsj.rule.eval.api.service`(与 `OutcomeService` 同区,job-svc 依赖 eval-svc 可引用);impl 放 `internal/outcomesource/`(新包,@ComponentScan internal 覆盖)。

- [ ] **Step 1** `OutcomeSourceConfig`(sealed,多态 `@JsonTypeInfo(NAME,"kind")` + `@JsonSubTypes` SqlOutcomeSourceConfig)+ `SqlOutcomeSourceConfig(String datasource, String sql)`(kind="SQL")。注解 import `com.fasterxml.jackson.annotation`。
- [ ] **Step 2** `OutcomePullResult(List<OutcomeService.OutcomeRecord> records, java.time.Instant newWatermark)`。
- [ ] **Step 3** `OutcomeSource<C extends OutcomeSourceConfig>` SPI:`Class<C> configType();` `OutcomePullResult pull(C source, java.time.Instant watermark, Long tenantId);`(Javadoc:watermark 可空=首拉;newWatermark=本批 max labeledAt,空批返回入参 watermark)。
- [ ] **Step 4** `SqlOutcomeSource implements OutcomeSource<SqlOutcomeSourceConfig>`(@Component):
  - 注入 `MetricDataSourceRegistry`(先 `grep -rn "class MetricDataSourceRegistry" rule-eval-svc` 确认包 + `template(String)` 方法签名;它返回 `NamedParameterJdbcTemplate`)。
  - `pull`:`var tpl = registry.template(source.datasource())`;null→抛 IllegalStateException("数据源未注册: "+ds)。用 `MapSqlParameterSource` 绑 `watermark`(`watermark==null?null:Timestamp.from(watermark)` 或直接 LocalDateTime)+ `tenantId`。`tpl.query(source.sql(), params, rowMapper)`,rowMapper 读固定列 `event_id`(String)、`outcome_label`(String)、`outcome_value`(BigDecimal,可空)、`labeled_at`(Timestamp→Instant)→ `new OutcomeService.OutcomeRecord(eventId, label, value, labeledAtInstant, "ingest:"+source.datasource(), null)`。
  - newWatermark = records 里 max labeledAt(空→入参 watermark)。
  - **核对** `OutcomeService.OutcomeRecord` 构造器字段顺序/类型(`grep -n "record OutcomeRecord" rule-eval-svc/.../api/service/OutcomeService.java`):当前 `(String eventId, String outcomeLabel, BigDecimal outcomeValue, Instant labeledAt, String source, String note)`。
- [ ] **Step 5** `OutcomeSourceRegistry`(@Component,仿 `TaskExecutorRegistry`):`Map<Class<?>, OutcomeSource<?>>` by `configType()`;`pull(OutcomeSourceConfig, watermark, tenantId)` 按 `config.getClass()` 路由 + cast。
- [ ] **Step 6** 测试:`SqlOutcomeSourceTest`(mock `MetricDataSourceRegistry.template` 返回 mock `NamedParameterJdbcTemplate`;stub `query(...)` 返回构造行;验固定列映射 + newWatermark=max + 空批不前进 + null watermark 首拉 + 数据源缺失抛错)。`OutcomeSourceConfigTest`(多态 JSON round-trip:kind=SQL)。`OutcomeSourceRegistryTest`(路由 + 无源抛错)。
- [ ] **Step 7** `$MVN -pl rule-eval-svc -am test -Dtest=...` 绿;commit: `feat(eval): OutcomeSource SPI + SqlOutcomeSource(复用 MetricDataSourceRegistry)`

---

### Task 3: eval-svc OutcomeIngestionService facade

**Files(eval-svc):** `api/service/IngestResult.java`、`api/service/OutcomeIngestionService.java`、`internal/outcomesource/OutcomeIngestionServiceImpl.java` + 测试。

- [ ] **Step 1** `IngestResult(int accepted, java.time.Instant newWatermark)`。
- [ ] **Step 2** `OutcomeIngestionService`:`IngestResult ingest(Long tenantId, OutcomeSourceConfig source, java.time.Instant watermark);`
- [ ] **Step 3** `OutcomeIngestionServiceImpl`(@Service,`@Transactional`):注入 `OutcomeSourceRegistry` + `OutcomeService`;`OutcomePullResult r = sourceRegistry.pull(source, watermark, tenantId)` → `int n = outcomeService.recordOutcomes(tenantId, r.records())` → `return new IngestResult(n, r.newWatermark())`。
- [ ] **Step 4** 测试:mock registry + OutcomeService,验 pull→recordOutcomes→IngestResult(accepted=recordOutcomes 返回值、newWatermark 透传)。
- [ ] **Step 5** `$MVN -pl rule-eval-svc -am test` 绿;commit: `feat(eval): OutcomeIngestionService —— pull+upsert facade`

---

### Task 4: job-svc OutcomeIngestionConfig + OutcomeIngestionExecutor

**Files:** `rule-job-svc/.../api/OutcomeIngestionConfig.java`、改 `api/TaskConfig.java`(permits+@JsonSubTypes)、`internal/runner/OutcomeIngestionExecutor.java` + 测试。

- [ ] **Step 1** `OutcomeIngestionConfig(OutcomeSourceConfig source, java.time.Instant watermark) implements TaskConfig`(`type()=OUTCOME_INGESTION`);import eval-svc `OutcomeSourceConfig`。
- [ ] **Step 2** 改 `TaskConfig`:`permits TriggerConfig, OutcomeIngestionConfig`;`@JsonSubTypes` 加 `@Type(value=OutcomeIngestionConfig.class, name="OUTCOME_INGESTION")`。
- [ ] **Step 3** `OutcomeIngestionExecutor implements TaskExecutor<OutcomeIngestionConfig>`(@Component):注入 `OutcomeIngestionService`(eval-svc)+ `ScheduledTaskMapper`。
```java
public TaskRunResult execute(TaskRunContext ctx, OutcomeIngestionConfig config) {
    IngestResult r = ingestionService.ingest(ctx.tenantId(), config.source(), config.watermark());
    // watermark 前进则写回 config(Instant 用 equals 比较,不用 !=)
    if (!java.util.Objects.equals(r.newWatermark(), config.watermark())) {
        ScheduledTask task = taskMapper.selectById(ctx.taskId());
        if (task != null) {
            task.setConfig(new OutcomeIngestionConfig(config.source(), r.newWatermark()));
            taskMapper.updateById(task);
        }
    }
    return new TaskRunResult(TaskExecutionStatus.SUCCESS, r.accepted(), r.accepted(), 0, null);
}
```
- [ ] **Step 4** 测试 `OutcomeIngestionExecutorTest`(mock ingestionService + ScheduledTaskMapper):
  - watermark 前进 → 验 ingest 调用 + selectById+updateById 写回新 config(捕获验 config.watermark 更新)。
  - watermark 不变(空批,newWatermark==旧)→ 不调 updateById。
  - TaskRunResult.processedCount/successCount == accepted。
- [ ] **Step 5** `TaskConfigTest` 加 OutcomeIngestionConfig 多态 round-trip(kind=OUTCOME_INGESTION,含嵌套 SqlOutcomeSourceConfig)。
- [ ] **Step 6** `$MVN -pl rule-job-svc -am test` 绿;commit: `feat(job): OutcomeIngestionConfig + OutcomeIngestionExecutor(第二个 executor,零主干改动验证)`

---

### Task 5: 集成测试 + 全量 + e2e + 文档

- [ ] **Step 1** 集成测试(eval-svc 或 job-svc,真实 MySQL 容器):建一张业务标签表(如 `biz_label(event_id, outcome_label, outcome_value, labeled_at, tenant_id)`)插几行 + 一条 OUTCOME_INGESTION `scheduled_task`(config.source=SqlOutcomeSourceConfig 指向该表的 SELECT 固定列 SQL,datasource 用测试已注册的数据源名)→ 经 `ScheduledTaskScheduleManager.runOnce` 或直接 executor → 验 `decision_outcome` 真 upsert + `scheduled_task.config` watermark 写回。**核对**测试如何注册 datasource 到 `MetricDataSourceRegistry`(看现有 SqlAggregateMetricSourceHandler 集成/单测怎么造 datasource)。
- [ ] **Step 2** `$MVN clean test` 全量绿。
- [ ] **Step 3** 真实服务 e2e:起服务,SQL 直插一张业务标签表 + 一条 OUTCOME_INGESTION scheduled_task(datasource 指向已配置数据源)→ `POST /admin/v1/scheduled-tasks/{id}/trigger` → 查 `decision_outcome` 落库 + config watermark 前进;第二次 trigger 只拉增量(加一行新 labeled_at>watermark,验只新增那条)→ 清理。
- [ ] **Step 4** 文档:`docs/08-evolution.md` §2.27 标 B32 回灌 ingestion 落地;架构 spec §4.1 `OutcomeIngestionConfig` 占位更新;`docs/05-storage.md` 标 OUTCOME_INGESTION config 形状(source + watermark)。跨文档先 `doc-consistency-review`;派 `rule-engine-reviewer` 审代码↔文档。commit。

---

## Self-Review

**Spec 覆盖**:OutcomeSource SPI(T2)/SqlOutcomeSource(T2)/ingest facade(T3)/OutcomeIngestionConfig+executor(T4)/SPI TaskRunContext(T1)/watermark 写回(T4)/集成+e2e+docs(T5)✅。模块放置按 spec §2(config 在 job-svc api 同包守 sealed;SPI+impl 在 eval-svc)✅。

**执行期风险**:
1. `MetricDataSourceRegistry` 包/方法签名 + 测试如何注册 datasource——T2/T5 先 grep 现有 SqlAggregate 用法核对。
2. sealed `OutcomeSourceConfig` 跨模块:permits 同包(eval-svc api),job-svc `OutcomeIngestionConfig` 引用它(job-svc→eval-svc 依赖成立)。确认 eval-svc api 包名实际值。
3. Instant watermark 比较用 `Objects.equals` 非 `!=`(T4 已注明)。
4. `Jackson3TypeHandler` 对嵌套 sealed(TaskConfig→OutcomeIngestionConfig→OutcomeSourceConfig)多态还原——T4/T5 集成测试验真实 JSON 列 round-trip。
5. 时间列 labeled_at LocalDateTime↔Instant:与既有 `OutcomeServiceImpl` 同口径(systemDefault);SqlOutcomeSource 读 Timestamp→Instant 注意时区一致。
