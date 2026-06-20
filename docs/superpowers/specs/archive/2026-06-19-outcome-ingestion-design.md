# Track #2:OUTCOME_INGESTION executor(B32 回灌 job)— 设计

新框架(`docs/superpowers/specs/2026-06-19-distributed-ready-scheduling-and-propagation-design.md`)上的第一个新 `TaskExecutor`,验证"加 executor 零主干改动"。把 B32 决策效果闭环补全:定时从外部源**增量拉真实结果标签 → upsert `decision_outcome`**(B32 的 `OutcomeService` 已落地)。

## 1. 目标与边界

- 定时(`scheduled_task` TRIGGER 之外的 `OUTCOME_INGESTION` 型)从业务真值源按 watermark(存 `scheduled_task.run_cursor` 列,非 config)**增量批量**拉标签行,经 `OutcomeService.recordOutcomes` 幂等 upsert。
- 源机制做成 **`OutcomeSource` SPI(丙)**,首个实现 **SQL-direct(甲)**复用既有 `MetricDataSourceRegistry`;留口给将来 HTTP/其它源。
- 守 B32 边界:引擎只搬运标签(transport),不解释业务语义(什么算 fraud)。watermark + 幂等 upsert 使重叠窗口重拉安全。

## 2. 模块放置(两个硬约束决定)

1. **sealed 约束**:`TaskConfig` sealed,unnamed module 下 permitted 子类型必须**同包** `com.sstlfsj.rule.job.api`。→ `OutcomeIngestionConfig` 必须放 **rule-job-svc api**(与 `TaskConfig`/`TriggerConfig` 同包)。
2. **模块依赖方向** `rule-job-svc → rule-eval-svc`(`TriggerExecutor` 已依赖 `EvalService`)。SQL 拉数依赖 eval-svc internal 的 `MetricDataSourceRegistry`,job-svc 够不着。→ **`OutcomeSource` SPI + `SqlOutcomeSource` + ingest facade 落 rule-eval-svc**;executor 落 rule-job-svc 调 eval-svc facade。

| 类型 | 模块/包 | 说明 |
|---|---|---|
| `OutcomeSourceConfig`(sealed)+ `SqlOutcomeSourceConfig` | **eval-svc api** | 源配置;sealed permits 同包(eval-svc api);job-svc 依赖 eval-svc 故可在 `OutcomeIngestionConfig` 内引用 |
| `OutcomeSource` SPI + `OutcomePullResult` | **eval-svc api** | 批量增量拉标签的扩展点(丙) |
| `SqlOutcomeSource` + `OutcomeSourceRegistry` | **eval-svc internal** | SQL 实现(甲)复用 `MetricDataSourceRegistry`;按源 config 类型路由 |
| `OutcomeIngestionService` facade + `IngestResult` | **eval-svc api/internal** | `ingest(tenantId, source, watermark)`:pull→`OutcomeService.upsert`→返回 accepted + 新 watermark |
| `OutcomeIngestionConfig` | **job-svc api** | `implements TaskConfig`(sealed permit,同包);静态定义只持 `OutcomeSourceConfig source`(无 watermark——运行态游标见 `scheduled_task.run_cursor` 列) |
| `OutcomeIngestionExecutor` | **job-svc internal** | `TaskExecutor<OutcomeIngestionConfig>`;调 eval-svc `OutcomeIngestionService` + 读/写 `scheduled_task.run_cursor` 列 |

## 3. SPI 微调:`TaskExecutor.execute(TaskRunContext, config)`

当前 `execute(long taskRunId, long tenantId, C config)` 不给 taskId,而 OUTCOME_INGESTION executor 要把新 watermark **写回 `scheduled_task.run_cursor` 列**(需 taskId 定位行)。改为传上下文:

```java
record TaskRunContext(long taskRunId, long taskId, long tenantId) {}
interface TaskExecutor<C extends TaskConfig> {
    TaskType type();
    Class<C> configType();
    TaskRunResult execute(TaskRunContext ctx, C config);
}
```
- `TaskExecutorRegistry.dispatch(ScheduledTask task, long taskRunId)` 内构造 `TaskRunContext(taskRunId, task.getId(), task.getTenantId())` 传入。
- `TriggerExecutor` 改用 `ctx.taskRunId()` 做 eventId(语义不变)。
- 更可扩展(将来加字段不再改签名)。

## 4. OutcomeSource SPI(丙)+ SQL 实现(甲)

```java
// eval-svc api
sealed interface OutcomeSourceConfig permits SqlOutcomeSourceConfig {}
record SqlOutcomeSourceConfig(String datasource, String sql) implements OutcomeSourceConfig {}

record OutcomePullResult(List<OutcomeService.OutcomeRecord> records, Instant newWatermark) {}

interface OutcomeSource<C extends OutcomeSourceConfig> {
    Class<C> configType();
    /** 拉 watermark 之后的标签行;watermark 为 null 表示首次全量。返回行 + 新 watermark(= 本批 max labeledAt,无行则原样返回入参 watermark)。 */
    OutcomePullResult pull(C source, Instant watermark, Long tenantId);
}
```

**`SqlOutcomeSource implements OutcomeSource<SqlOutcomeSourceConfig>`**(eval-svc internal):
- 复用 `MetricDataSourceRegistry.template(datasource)`(已为 SQL metric source 注册的数据源)。
- 执行 `config.sql()`,绑定 `:watermark`(可空)+ `:tenantId`。约定 SQL **SELECT 固定列别名**:`event_id, outcome_label, outcome_value, labeled_at`(executor/source 按固定列名读,不另搞 fieldMapping 配置——最简)。典型 SQL:
  ```sql
  SELECT event_id, outcome_label, outcome_value, labeled_at
  FROM biz_fraud_disposition
  WHERE tenant_id = :tenantId AND (:watermark IS NULL OR labeled_at > :watermark)
  ORDER BY labeled_at ASC LIMIT 1000
  ```
- 行 → `OutcomeService.OutcomeRecord(eventId, outcomeLabel, outcomeValue, labeledAt, source="ingest:<datasource>", note=null)`。
- `newWatermark` = 本批 max(`labeled_at`);空批返回入参 watermark(不前进)。
- `OutcomeSourceRegistry`(eval-svc internal,仿 `TaskExecutorRegistry`):`Map<Class<? extends OutcomeSourceConfig>, OutcomeSource>` 按 config 类型路由。

## 5. ingest facade(eval-svc)

```java
// eval-svc api
record IngestResult(int accepted, Instant newWatermark) {}
interface OutcomeIngestionService {
    /** 从 source 拉 watermark 之后的标签并 upsert;返回落库条数 + 新 watermark。 */
    IngestResult ingest(Long tenantId, OutcomeSourceConfig source, Instant watermark);
}
```
`OutcomeIngestionServiceImpl`(eval-svc internal,`@Transactional`):`OutcomePullResult r = registry.route(source).pull(...)` → `OutcomeService.recordOutcomes(tenantId, r.records())` → `new IngestResult(r.records().size(), r.newWatermark())`。

## 6. OUTCOME_INGESTION executor(job-svc)

```java
// job-svc api —— 与 TaskConfig 同包(sealed 约束);静态定义,无 watermark
record OutcomeIngestionConfig(OutcomeSourceConfig source) implements TaskConfig {
    public TaskType type() { return TaskType.OUTCOME_INGESTION; }
}
```
`TaskConfig` 的 `@JsonSubTypes` + permits 加上 `OutcomeIngestionConfig`(kind="OUTCOME_INGESTION")。

运行态 watermark **不在 config**(config 是不可变静态定义),存于独立 `scheduled_task.run_cursor` 列(opaque VARCHAR,OUTCOME_INGESTION 存 ISO-8601 watermark)——对齐 Kafka Connect offset / Airbyte state 的 state-not-config 模式。

**`OutcomeIngestionExecutor implements TaskExecutor<OutcomeIngestionConfig>`**(job-svc internal):
- 注入 `OutcomeIngestionService`(eval-svc)+ `ScheduledTaskMapper`(读/写 run_cursor 列)。
- `execute(ctx, config)`:
  1. `selectById(ctx.taskId())` 取 task;null 则返回 FAILED。从 `task.getRunCursor()` 解析 watermark(null=首次全量)。
  2. `IngestResult r = ingestionService.ingest(ctx.tenantId(), config.source(), watermark)`。
  3. **游标前进则写回**:`r.newWatermark() != watermark` 时,`task.setRunCursor(r.newWatermark().toString())` → `updateById`(只更新 run_cursor 列,config 不动)。
  3. 返回 `TaskRunResult(SUCCESS, processedCount=accepted, successCount=accepted, errorCount=0, null)`;pull/ingest 抛异常由 `ScheduledTaskScheduleManager.doRun` 兜底记 FAILED(已有)。

## 7. 测试

- **eval-svc**:`SqlOutcomeSource` 单测(mock `MetricDataSourceRegistry` 的 template,验 SQL 绑定 + 固定列映射 + newWatermark=max + 空批不前进 + watermark=null 首拉);`OutcomeIngestionServiceImpl` 单测(mock source registry + OutcomeService,验 pull→upsert→IngestResult)。`OutcomeSourceConfig` 多态 JSON round-trip。
- **job-svc**:`OutcomeIngestionExecutor` 单测(mock ingestionService + ScheduledTaskMapper,验:ingest 调用、watermark 前进时写回 / 不前进不写、TaskRunResult 计数);`TaskExecutorRegistry`/`TriggerExecutor`/`ScheduleManager` 因 SPI 改 `TaskRunContext` 同步改测试。`OutcomeIngestionConfig` 多态 round-trip(加进 TaskConfig 测试)。
- **集成测试**:扩 `ScheduledTaskAnnotationIntegrationTest` 或新增——真实 MySQL:建一张业务标签表 + 一条 OUTCOME_INGESTION `scheduled_task`,`runOnce` → 验 `decision_outcome` 真 upsert + watermark 写回 `scheduled_task.run_cursor` 列。
- **真实服务 e2e**(schema/落库链路):起服务,造 OUTCOME_INGESTION 任务 + 业务标签源表,触发 → 查 `decision_outcome` 落库 + 第二次触发只拉增量(watermark 生效)+ 清理。

## 8. 非目标(YAGNI)

- 不做 HTTP/其它 OutcomeSource 实现(SPI 留口,SQL 一个够验证扩展性)。
- 不做 fieldMapping 配置(固定列名约定)。
- OUTCOME_INGESTION 任务的创建入口:本轮可经 SQL 直插或最简 admin 入口验证;完整 CRUD admin(创建 OUTCOME_INGESTION 任务)可随前端 track 再补——本 spec 聚焦 executor + 源 SPI + ingest 链路。
- 不碰 RETENTION/ALARM、传播层(track #3)。

## 9. 落地后

`docs/08-evolution.md` §2.27 标注 B32 回灌 ingestion 落地;`docs/05-storage.md` 标 OUTCOME_INGESTION config 形状;架构 spec §4.1 的 `OutcomeIngestionConfig` 占位更新为实际;decision 日志按需追加。每任务后审代码↔文档对齐。
