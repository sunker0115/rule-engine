# B 档剩余缺口评估（2026-06-13）

> 背景：B 档共有 B1（action 派发超时）、B2（数据保留 job）、B3（可观测告警阈值）、§2.13（评估期预编译 + alpha 共享）四个待办。B2 已完成。本文件评估其余三者的当前状态和是否值得做。

---

## B1（action 派发超时）—— 已过时，不做

**原始需求**：`engine.rule.action.default-timeout-ms` 占位配置，action 派发时按超时阈值异步等结果，超时记 `TIMEOUT` errorCode。

**当前状态**：**已不存在。**

D60（2026-06-12）把整个动作子系统移除了——引擎纯决策化，只产出 `Decision`（`finalDecision` + `hitDecisions`），不派发任何动作。`ActionDispatcher` 类、`ActionResult`、action 相关 errorCode（`TIMEOUT` / `QUEUE_OVERFLOW` / `HANDLER_EXCEPTION`）已随 D60 一并删除。

`application.yml` 中不存在 `engine.rule.action.*` 配置项。

**结论**：不做。需求前提（动作派发）已被 D60 移除。

---

## B3（可观测告警阈值）—— 占位未实现

### 当前状态

**YAML 配置已占位**（`rule-app/src/main/resources/application.yml:75-78`）：

```yaml
# 规划中：告警阈值逻辑未实现
observability:
  eval-error-rate-threshold: 0.05
  trace-queue-full-threshold: 0.8
```

**Prometheus 指标名常量已定义**（`rule-observability/.../api/metrics/RuleMetrics.java`）：

```java
EVAL_ERROR_TOTAL   = "rule_eval_error_total";
TRACE_QUEUE_SIZE   = "rule_trace_queue_size";
```

### 缺失的部分

1. **Micrometer 埋点未注册**：eval-svc 评估路径里没有 `MeterRegistry.counter(...)` / `Gauge.builder(...)`。`RuleMetrics` 常量只是字符串，没有被任何地方引用。
2. **阈值判断逻辑为空**：没有读取 `eval-error-rate-threshold` / `trace-queue-full-threshold` 的 `@ConfigurationProperties` bean，也没有定期比对 Micrometer 实际值 vs 阈值的告警检查。
3. **告警通道未定义**：超阈值后做什么（打 WARN 日志、发 Spring Event、暴露 health endpoint）未定。

### 如果要落地的改动量

| 模块 | 文件 | 改动 |
|------|------|------|
| `rule-eval-svc` | `EvalEngine` 评估路径 | 加 `Counter evalErrorCounter` + `Gauge traceQueueGauge`，MeterRegistry 埋点（~15 行） |
| `rule-eval-svc` | `PushEventDispatcher` | queue size gauge 注册（~5 行） |
| `rule-observability` | 新建 `ObservabilityAlarmProperties` | `@ConfigurationProperties("engine.rule.observability")` 绑定两个阈值（~25 行） |
| `rule-observability` | 新建 `ObservabilityAlarmChecker` | `@Scheduled` 定期读 Micrometer 值，超阈值打 WARN 日志（~50 行） |
| `rule-observability` | `ObservabilityAutoConfiguration` | 注册 `ObservabilityAlarmChecker` bean（~5 行） |
| 测试 | 各对应 test | 阈值触发 / 未触发场景（~80 行） |

**总计 ~180 行新代码 + 80 行测试，2 个新类。纯增量，不动现有逻辑。**

### 设计注意点

- **告警通道选最简单的**：v1 打 WARN 日志即可，不发事件、不接外部告警系统。未来有需要再加。
- **`@Scheduled` 检查频率**：建议 60s 一次，窗口足够短、开销可忽略。
- **error rate 计算**：用 Micrometer `Counter` 的 `rate()` 方法（需配 `MeterRegistry` 的步长），或手动维护滑动窗口。推荐直接用 Counter rate——Micrometer 已内置。
- **trace queue 满判断**：按 `queue.size() / queue.capacity()` 计算利用率，与 `trace-queue-full-threshold` 比较。
- **不做 health endpoint 降级**：告警阈值和 Spring Actuator health 是两条独立链路，告警是"提前知道"，health 是"已经坏了"。v1 只做告警。

### 结论

**B3 可做，改动小（~260 行），纯增量。** 不需要改动 kernel SPI、不影响评估热路径（Micrometer Counter 是原子长整数 increment，纳秒级）。

---

## §2.13 评估期预编译完全切换（来源 D20 / 08-evolution）

### 当前状态

**SPI 已就位**：`RuleVersionExecutor` 接口 + `InterpretedExecutor` 默认实现（Visitor 树遍历解释器）。

**DB 列已预留**：`rule_version.compiled_predicate_ref VARCHAR(256) NULL`，v1 始终留空。

**`ExecutorRegistry` 已存在**：按 `RuleKind` 注册 executor，切换维度已就位。

**现有评估链路**：
1. `SceneRuleIndex` 倒排索引 `(tenantId, sceneCode, eventType)` → 候选 `RuleVersionSnapshot` 列表
2. 每条快照经 `InterpretedExecutor` 递归 walk AST，`ConditionNode` 调 `ConditionEvaluator` SPI
3. 规则间条件独立求值，不共享结果（如 100 条规则都有 `amount > 1000`，算 100 次）

**§2.13 计划做的事（08-evolution.md §2.13）**：
- a. `CompiledExecutor`：发布期把 AST 编译为单一 `Predicate<EvalContext>` lambda
- b. `RuleVersionCache`：按 `ruleVersionId` 缓存编译产物，publish/disable 时 evict
- c. alpha 节点共享：跨规则 ConditionNode hash 去重，同一 `EvalContext` 内同条件只算一次
- d. 灰度切换：`ExecutorRegistry` 按配置选 `InterpretedExecutor` / `CompiledExecutor`

### 缺失的部分

| 组件 | 文件（新建） | 说明 |
|------|-------------|------|
| AST→Predicate 编译器 | `rule-kernel/.../internal/compiler/AstCompiler` | 递归遍历 `AstNode` 树，`ConditionNode` 包装为 `ctx -> evaluator.evaluate(node, ctx)`，AND/OR/NOT 用 `&&`/`||`/`!` 组合。核心复杂度在 LambdaMetafactory 方法句柄生成（~200 行） |
| `CompiledExecutor` | `rule-kernel/.../internal/evaluator/CompiledExecutor` | 实现 `RuleVersionExecutor`，`execute()` 直接调 `predicate.test(ctx)`，无需 walk 树（~100 行） |
| `RuleVersionCache` | `rule-kernel/.../internal/compiler/RuleVersionCache` | `ConcurrentHashMap<Long, Predicate<EvalContext>>`，按 ruleVersionId 缓存；publish/disable 时 evict（~60 行） |
| alpha 共享层 | `rule-kernel/.../internal/compiler/ConditionDeduplicator` | 同 `(sceneCode, eventType)` 下所有规则 AST 做 ConditionNode hash 去重，生成共享求值计划（~200 行，可拆） |
| 缓存 evict 监听 | `rule-eval-svc/.../internal/listener/CompiledPredicateEvictor` | 监听 `RulePublishedEvent` / `SceneChangedEvent` 触发 evict + recompile（~50 行） |
| 灰度配置 | `rule-eval-svc` / `application.yml` | 新增 `engine.rule.eval.compiled-executor.enabled` + 规则级白名单（~30 行） |

以上为新建文件，以下为修改已有文件：

| 组件 | 文件（修改） | 说明 |
|------|-------------|------|
| `EvalEngine` | `rule-kernel/.../internal/engine/EvalEngine` | 增加"存在编译产物时走 CompiledExecutor"分支（~20 行） |
| `IndexRebuilder` | `rule-eval-svc` | 全量重载索引时同步 warm-up 编译产物（~20 行，可选 lazy 则不动） |

### 改动量汇总

| 范围 | 代码量 | 模块 |
|------|--------|------|
| AST 编译器 | ~200 行 | rule-kernel |
| CompiledExecutor | ~100 行 | rule-kernel |
| RuleVersionCache | ~60 行 | rule-kernel |
| alpha 共享去重 | ~200 行（可拆） | rule-kernel |
| evict 监听 | ~50 行 | rule-eval-svc |
| 灰度配置 | ~30 行 | rule-eval-svc |
| EvalEngine 改 | ~20 行 | rule-kernel |
| 测试 | ~300 行 | 两模块 |
| **合计（不含 alpha）** | **~560 行新代码 + ~200 行测试** | |
| **合计（含 alpha）** | **~860 行新代码 + ~300 行测试** | |

### 与既有系统的关系

| 既有组件 | 是否改动 |
|----------|---------|
| `SceneRuleIndex` 倒排索引 | **不动**——仍做粗筛，编译只替换 AST 求值方式 |
| `ConditionEvaluator` SPI 及所有实现 | **不动**——编译后的 Predicate 内部仍调同一套 evaluator |
| `InterpretedExecutor` | **不动**——保留做灰度兜底 |
| `EvalContextAssembler` | **不动**——取数逻辑零影响 |
| Pre-Gate / 决策合成 / API 层 | **不动** |
| DB schema | **不动**——`compiled_predicate_ref` 列已预留 |

### 设计要点

**编译方式选 LambdaMetafactory，不用 Janino**：Janino 需新增依赖 + 源码生成 + 编译步骤，且 D62 已解除 kernel 反射禁令，LambdaMetafactory invokedynamic 零依赖、类加载开销更小。

**编译时机默认 lazy，可选 eager**：首次评估时编译 + 缓存（lazy），publish 时 evict；与 EXPRESSION_SCRIPT 的 `script.precompile.mode: LAZY/EAGER` 对齐设计。lazy 不动 publish 流程，简单。

**alpha 共享拆开做**：编译版本身已有 5–10x 提升（单规则 5–10μs → 0.3–1μs），alpha 共享是乘法优化（N 条重叠规则省 N-1 次重复求值），独立验证后再叠。

**trace 兼容性**：trace 仍按 RuleVersion 视图展开（D7 不变），底层共享求值对运营透明——即使条件被 alpha 共享只算一次，每条规则的 NodeTrace 仍各自写一行。

**灰度切换**：先 `compiled-executor.enabled=true` + 规则白名单（少量规则走编译版验证），确认 trace 输出一致后全量切。出问题关开关即可退回到 `InterpretedExecutor`。

### 为什么改动不算大

已有 SPI + 不可变快照 + 预留列 + ExecutorRegistry 四者把路铺好了。§2.13 本质上是**写一个新的 `RuleVersionExecutor` 实现 + 一个编译工具类 + 一个缓存层**，骨架不动，纯增量。与之前加 6 个表达式引擎（CEL/Aviator/QLExpress/JsonLogic/JEXL/Groovy）的工作量在同一量级。

---

## 汇总

| 功能 | 状态 | 决定 |
|------|------|------|
| B1 action 派发超时 | 已被 D60 移除 | 不做 |
| B2 数据保留 job | ✅ 已完成 | — |
| B3 可观测告警阈值 | 仅 YAML 占位 + 常量 | 可做，~260 行纯增量 |
| §2.13 预编译 + alpha 共享 | SPI/预留列/Registry 已就位，缺编译器和缓存 | 可做，~560 行（不含 alpha）/ ~860 行（含 alpha）；alpha 建议拆开 |
