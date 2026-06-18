# 评估链路补全设计

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全 v1 评估链路的两个遗漏：dry-run NodeTrace 隔离写入 `dry_run_node_trace` 表；规则命中后 ActionHandler 调用并落库 `action_execution`。

**Architecture:** 新增 `DryRunTraceWriter` SPI（与 `TraceWriter` 并列），`EvalServiceImpl` 按 `isDryRun` 路由到不同 writer；规则命中后同步调用 `ActionDispatchService`，按 `scene_action_binding` 查找 handler 执行并 INSERT `action_execution`。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / rule-kernel SPI / rule-observability / rule-eval-svc

---

## 1. DryRunTraceWriter SPI + 实现

### 1.1 新增 SPI（rule-kernel）

新建 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriter.java`：

```java
package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

/** dry-run 评估链路追踪数据持久化 SPI，写 dry_run_node_trace 表，与 prod TraceWriter 隔离。 */
public interface DryRunTraceWriter {
    void write(String tenantId, String sessionId, List<NodeTrace> traces);
}
```

同步新增 Noop 实现（测试 / 禁用场景）：
`rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/NoopDryRunTraceWriter.java`

```java
public class NoopDryRunTraceWriter implements DryRunTraceWriter {
    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {}
}
```

### 1.2 新增实体和 Mapper（rule-observability）

**实体** `DryRunNodeTraceEntity`，映射 `dry_run_node_trace` 表，字段与 `NodeTraceEntity` 对应（`id`, `dry_run_session_id`, `tenant_id`, `rule_version_id`, `node_path`, `node_type`, `condition_type`, `metric_code`, `params`, `actual_value`, `result`, `error_code`, `value_source`, `evaluated_at`）。

**Mapper** `DryRunNodeTraceMapper extends BaseMapper<DryRunNodeTraceEntity>`，加 `@Insert` `insertBatch`，SQL 结构与 `NodeTraceMapper.insertBatch` 一致，目标表改为 `dry_run_node_trace`。

### 1.3 新增 Writer 实现（rule-observability）

`DryRunTraceWriterDbImpl implements DryRunTraceWriter, InitializingBean, DisposableBean`：

- 内部结构与 `TraceWriterDbImpl` 完全相同：`BlockingQueue<TraceEntry>` + 虚拟线程消费 + `flushBatch()`
- `flushBatch()` 调 `DryRunNodeTraceMapper.insertBatch()`
- `flattenToList()` 复用相同的 AST 树展开逻辑，`nodePath` 按深度优先编号（`0`, `0.0`, `0.1` ...）

### 1.4 注册 Bean（ObservabilityAutoConfiguration）

在 `ObservabilityAutoConfiguration` 中注册 `DryRunTraceWriterDbImpl` bean：
- `queueCapacity` / `batchSize` / `flushIntervalMs` 与 `TraceWriterDbImpl` 共享同一配置前缀 `rule.observability.trace`

### 1.5 路由（EvalServiceImpl）

`EvalServiceImpl` 注入 `DryRunTraceWriter dryRunTraceWriter`，`doEvaluate()` 末尾：

```java
if (isDryRun) {
    dryRunTraceWriter.write(event.tenantId(), sessionId.toString(), allTraces);
} else {
    traceWriter.write(event.tenantId(), sessionId.toString(), allTraces);
}
```

---

## 2. ActionHandler 调用 + action_execution 落库

### 2.1 新增 ActionDispatchService（rule-eval-svc）

`rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java`

```java
/**
 * 规则命中后同步派发 ActionHandler，结果写 action_execution。
 * v1 handler 均为 stub，同步调用无性能影响；v1.5 接真实 handler 时在此提取异步层。
 */
public class ActionDispatchService {

    private final Map<String, ActionHandler> handlers;        // @ActionType.value → bean
    private final SceneActionBindingReadMapper bindingMapper;
    private final ActionExecutionMapper executionMapper;

    public void dispatch(Long sessionId, String tenantId, String sceneCode,
                         List<Decision> hitDecisions) {
        List<SceneActionBinding> bindings = bindingMapper.findBySceneCode(tenantId, sceneCode);
        if (bindings.isEmpty()) return;

        for (Decision decision : hitDecisions) {
            for (SceneActionBinding binding : bindings) {
                String actionId = UUID.randomUUID().toString();
                ActionHandler handler = handlers.get(binding.actionType());
                ActionResult result;
                if (handler == null) {
                    result = ActionResult.skipped(actionId, binding.actionType(), "NO_HANDLER");
                } else {
                    ActionContext ctx = new ActionContext(actionId, binding.actionType(),
                            decision.code(), binding.defaultParams(), tenantId, sessionId);
                    result = handler.execute(ctx);
                }
                insertExecution(sessionId, tenantId, actionId, binding.actionType(),
                        decision.code(), result);
            }
        }
    }
}
```

幂等键：`action_execution` 表已有 `(tenantId, eventId, actionId)` 唯一约束，`actionId` 用 UUID 生成，无需额外处理。

### 2.2 新增 Mapper（rule-eval-svc）

`SceneActionBindingReadMapper`：只读，`findBySceneCode(tenantId, sceneCode)` 查 `scene_action_binding JOIN scene`，返回轻量 DTO（actionType + defaultParams）。

`ActionExecutionMapper extends BaseMapper<ActionExecutionEntity>`：单条 `insert()`，不批量。

`ActionExecutionEntity`：映射 `action_execution` 表，字段见 DDL。

### 2.3 Stub Handler（rule-eval-svc）

```
BlockTransactionHandler  @Component @ActionType("BLOCK_TRANSACTION")
  execute() → ActionResult.success(actionId, "BLOCK_TRANSACTION")

SendAlertHandler  @Component @ActionType("SEND_ALERT")
  execute() → ActionResult.success(actionId, "SEND_ALERT")
```

两个 handler 均实现 `ActionHandler` SPI，v1 直接返回 `success`，不做任何外部调用。

### 2.4 注入到 EvalServiceImpl

`EvalServiceImpl` 注入 `ActionDispatchService`，`doEvaluate()` 末尾（session 更新之后）：

```java
if (!isDryRun && !hitDecisions.isEmpty()) {
    actionDispatchService.dispatch(sessionId, event.tenantId(),
            event.sceneCode(), hitDecisions);
}
```

dry-run 不派发 action（与文档 D7 一致）。

---

## 3. 错误处理边界

| 场景 | 处理方式 |
|------|---------|
| `scene_action_binding` 为空 | `dispatch()` 直接返回，不写 `action_execution` |
| `ActionHandler` 未注册（`actionType` 无对应 bean） | 写一条 `status=SKIPPED, errorCode=NO_HANDLER` 的记录 |
| `handler.execute()` 返回 `FAILED` | 按实际 status 写库，不影响 `EvalResult` |
| `DryRunTraceWriterDbImpl` 队列满 | 静默丢弃（与 `TraceWriterDbImpl` 一致） |
| `ActionExecutionMapper.insert()` 抛异常 | catch 后记录 warn 日志，不向上传播（不能因审计失败影响 EvalResult） |

---

## 4. 测试边界

| 测试类 | 验证点 |
|--------|--------|
| `DryRunTraceWriterDbImplTest` | `write()` 后 flush，调 `DryRunNodeTraceMapper.insertBatch()`，不调 `NodeTraceMapper` |
| `ActionDispatchServiceTest` | 有 binding + handler → INSERT success；binding 空 → 不 INSERT；handler 缺 → INSERT SKIPPED |
| `EvalServiceImplTest`（扩展） | dry-run → `dryRunTraceWriter.write()` 被调；命中 → `dispatch()` 被调；miss → `dispatch()` 不调 |

---

## 5. 文件变更清单

| 操作 | 文件 |
|------|------|
| 新建 | `rule-kernel/.../spi/trace/DryRunTraceWriter.java` |
| 新建 | `rule-kernel/.../spi/trace/NoopDryRunTraceWriter.java` |
| 新建 | `rule-observability/.../domain/DryRunNodeTraceEntity.java` |
| 新建 | `rule-observability/.../repository/DryRunNodeTraceMapper.java` |
| 新建 | `rule-observability/.../trace/DryRunTraceWriterDbImpl.java` |
| 修改 | `rule-observability/.../ObservabilityAutoConfiguration.java` |
| 新建 | `rule-eval-svc/.../action/ActionDispatchService.java` |
| 新建 | `rule-eval-svc/.../action/BlockTransactionHandler.java` |
| 新建 | `rule-eval-svc/.../action/SendAlertHandler.java` |
| 新建 | `rule-eval-svc/.../repository/SceneActionBindingReadMapper.java` |
| 新建 | `rule-eval-svc/.../repository/ActionExecutionMapper.java` |
| 新建 | `rule-eval-svc/.../domain/ActionExecutionEntity.java` |
| 新建 | `rule-eval-svc/.../domain/SceneActionBindingRow.java` |
| 修改 | `rule-eval-svc/.../service/EvalServiceImpl.java` |
| 修改 | `rule-eval-svc/.../EvalAutoConfiguration.java` |
| 新建（测试） | `DryRunTraceWriterDbImplTest.java` |
| 新建（测试） | `ActionDispatchServiceTest.java` |
| 修改（测试） | `EvalServiceImplTest.java`（扩展 dry-run/action 用例） |
