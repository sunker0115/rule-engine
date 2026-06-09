# evaluation_session.context_snapshot 按开关回填 设计

> 日期：2026-06-09。状态：设计待批准。源自 DB 空字段审计 #4(`docs/audits/2026-06-09-db-empty-fields-audit.md`):`evaluation_session.context_snapshot` 当前代码无人写(仅 `DryRunPersister` 写 `dry_run_session`,`AuditPersister` 从不 set),~51% 非空是重构前残留。本设计把它**按开关回填**——即「设计了没接线」的补全。

## 1. 背景与定位
- `context_snapshot` = 一次评估的指标值快照 `{metricCode: value}`,用于排障 / 输入取证 / dry-run 重放。
- **它现在很大程度被 node_trace 覆盖**:本仓这轮给 node_trace 加了逐条件 `actualValue`(同源 `MetricValue.value()`),而 `metric_dependencies`(取数范围)从 AST 派生 → 每个取的指标必被某条件引用 → 其值必在 node_trace.actualValue。
- 故保留 context_snapshot 的**独立价值** = 「**不开 full node_trace 也能留一个轻量 session 级输入快照**」(node_trace 是逐节点 N 行、且受 trace 开关控;context_snapshot 是一条 session 一个紧凑 JSON)。

## 2. 架构原则：两个正交的观测开关(不同层、不同闸)
| | `engine.rule.trace.enabled` | `engine.rule.audit.context-snapshot.enabled`(新增) |
|---|---|---|
| 闸类型 | **计算闸**：评估时建不建 NodeTrace | **写入闸**：落库时写不写 context_snapshot |
| 所在层 | kernel 执行器内(纯 Java 求值热路径) | persister 内(异步,评估之后) |
| 机制 | **ScopedValue**(`TraceScope.COLLECT`)穿透纯 Java 调用栈传到执行器 | **注入的 config 字段**(普通 Spring bean) |
| 关掉省什么 | eval 的 CPU/分配(执行器不建 trace) | 一次 DB JSON 写(数据 EvalContext 一直在,只是不序列化) |
| 默认 | true | **false(opt-in)** |

**两开关分开、不合并**:成本画像/层/用途都不同;context_snapshot 的用法恰是「trace 关着时单独开」。
**context_snapshot 不用 ScopedValue**:① persister 是普通 Spring bean,直接读注入字段即可,无「穿透纯 Java 栈」问题;② ScopedValue 作用域只绑在 EvalEngine 执行器那一下,**异步落库时 scope 已关、在别的线程,根本读不到**——结构上也用不了。

## 3. 组件
1. **配置开关** `engine.rule.audit.context-snapshot.enabled`(默认 `false`),`@Value("${engine.rule.audit.context-snapshot.enabled:false}")` 注入 `AuditPersister`(仿 `EvalAutoConfiguration` 给 `EvalEngine` 注入 `traceEnabled` 的方式:在创建 AuditPersister bean 的 @Bean 方法上取 `@Value`,经构造参 `boolean captureContextSnapshot` 传入)。
2. **共享序列化器** `ContextSnapshotSerializer`(落 `rule-eval-svc/.../internal/async/`):把现有 `DryRunPersister.serializeSnapshot(EvalContext)` 逻辑抽成 `static String serialize(ObjectMapper, EvalContext)`,两个 persister 共用。输出形态**完全不变**:`{"metrics": {code: value(null→"null")}, "evalNow": ctx.now().toString()}`;`ctx==null → null`;`JacksonException → 记 warn 写 null`。
3. **AuditPersister** 改造:构建 `EvaluationSession` 时(现已 set startedAt/finishedAt/evalDurationMs 处)加
   ```java
   if (captureContextSnapshot) {
       s.setContextSnapshot(ContextSnapshotSerializer.serialize(objectMapper, e.context()));
   }
   ```
   关 → 保持 null(现状)。`EvaluationSessionMapper` 的 INSERT **已含 `context_snapshot` 列**,无需改 SQL / 无迁移。`AuditRecorded` **已带 `context`**,无需改事件。

## 4. 数据流
```
AuditRecorded(context) ──异步──> AuditPersister
   captureContextSnapshot=false → contextSnapshot 留 null（现状）
   captureContextSnapshot=true  → ContextSnapshotSerializer.serialize(ctx) → JSON → evaluation_session.context_snapshot
```
DryRunPersister 改为调用同一 `ContextSnapshotSerializer`,**行为零变化**(dry-run 仍恒写)。

## 5. 错误处理
- `ctx==null`(context 构建失败的早返回场景)→ serializer 返回 null,session 正常落库。
- 序列化失败 → warn + 写 null,不影响 session 落库(旁路观测,不阻塞)。

## 6. 测试
- `AuditPersisterTest`:开关 on → `EvaluationSession.getContextSnapshot()` 非空且含 event.context 的指标值;开关 off → null。
- `DryRunPersisterTest` 保持绿(共享 serializer 与原 `serializeSnapshot` 输出逐字节一致)。
- 序列化器单测(可选):metrics 映射 + null/异常 → null。

## 7. 非目标
- per-request(单次评估级)快照覆盖:YAGNI;真要做也是在 `AuditRecorded` 带 boolean 透传,**不是 ScopedValue**(落库时 scope 已关)。
- 合并两开关:有意分开(计算闸 vs 写入闸)。
- 删 context_snapshot 列 / 改 mapper / 迁移:不需要(列与 INSERT 已在)。
- node_trace 与 context_snapshot 的去重/取舍:已讨论,保留 context_snapshot 作轻量旁路,不动 node_trace。

## 8. native / 风险
- 纯 eval-svc Spring 改动 + 一个 static 序列化 util,无新反射、无 preview,GraalVM native 零新增风险。
- 默认 false → 上线零行为变化;开启后每条 session 多一段 JSON 写(已知,opt-in 承担)。
