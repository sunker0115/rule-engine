# 04 — 扩展指南

> **位置定位**：本文档承载"我要加一个新条件 / 动作 / 指标源，怎么落地"的**复制粘贴级指南**——SPI 接口签名 / Bean 注册 / 注解声明 / 元数据契约 / 实现建议（timeout / retry / 熔断 默认值）。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) §3.6 / §3.7 / §3.9、[`09-skeleton.md`](./09-skeleton.md) §四 SPI 接口落点
>
> **解决什么疑问**："加一个新 ConditionType 要改哪些文件？""ActionHandler 的返回值契约是什么？""MetricSource 怎么声明缓存策略 / 类型级 params schema？""前端怎么知道我的新条件有哪些参数？"
>
> **职责边界**——
> - ✅ SPI 接口签名 / Bean 注册 / 元数据声明 / 实现建议值
> - ❌ 不写 SPI 模块归属（→ 09-skeleton §四）、不写运行时调度（→ 02-runtime）、不写 AST 操作符（→ 03-rule-expression）、不写表结构（→ 05-storage）、不写前端 UI（→ 06-frontend）、不写运维参数默认值（→ 07-operability）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 加 ConditionType | ✅ 已展开 |
| §三 加 ActionType | ✅ 已展开 |
| §四 加 MetricSource | ✅ 已展开 |
| §五 元数据契约 | ✅ 已展开 |
| §六 实现指南 | ✅ 已展开 |

---

## 二、加 ConditionType

### 2.1 SPI 接口

```java
// 包路径 TBD（见 09-skeleton §四）
public interface ConditionEvaluator {
    /**
     * @param node  规则 AST 中的 ConditionNode（含 conditionType + params）
     * @param ctx   本次评估的不可变上下文（含 payload.* / metrics.* / now 等）
     * @return true = 条件满足；false = 不满足
     * @throws 任意 RuntimeException → 引擎按 D15 单节点降级处理
     */
    boolean evaluate(ConditionNode node, EvalContext ctx);
}
```

### 2.2 注解声明

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConditionType {
    String value();           // 与规则 JSON 中 conditionType 字段对应，全局唯一
    String paramsSchema() default "{}";  // OpenAPI 3.0 inline JSON Schema，用于前端表单渲染
    String displayName() default "";    // 人类可读名称，前端下拉显示
}
```

### 2.3 注册方式

Spring Bean + 注解扫描：引擎启动时扫 `@ConditionType` 注解的 Bean，注册到 `ConditionTypeRegistry`（全局唯一 + 重复注册报错）。

### 2.4 实现示例（metric.threshold）

```java
@Component
@ConditionType(
    value = "metric.threshold",
    displayName = "指标阈值比较",
    paramsSchema = """
        {
          "type": "object",
          "required": ["operator"],
          "properties": {
            "operator": { "type": "string", "enum": ["EQ","GT","GTE","LT","LTE","BETWEEN","NOT_BETWEEN"] },
            "value":    { "type": "number" },
            "min":      { "type": "number" },
            "max":      { "type": "number" }
          }
        }
    """
)
public class MetricThresholdEvaluator implements ConditionEvaluator {
    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Object metricVal = ctx.getMetric(node.getMetricCode());
        if (metricVal == null) throw new MetricNotFoundException(node.getMetricCode());
        // 按 node.getParams().get("operator") 做比较
        return compare(metricVal, node.getParams());
    }
}
```

### 2.5 实现约束

- 不在 evaluate() 内发起任何网络 / DB 调用（EvalContext 已预拉所有 metric，D20）
- 如果 params 缺必填字段 → 抛 `IllegalArgumentException`，引擎归 `CONDITION_EVAL_ERROR`
- evaluate() 必须无副作用（不能修改 EvalContext，D20 §1 EvalContext 不可变）
- 单元测试模板：构造 `MockConditionNode`（直接设 params）+ `MockEvalContext`（直接注入 metric 值）→ 断言 evaluate() 返回预期布尔值

---

## 三、加 ActionType

### 3.1 SPI 接口

```java
// 包路径 TBD（见 09-skeleton §四）
public interface ActionHandler {
    /**
     * 执行 Action。幂等性由 Handler 自行保证。
     * @param ctx  含 action 定义（actionId / actionType / params）+ EvalContext + actionExecutionId（用于幂等键）
     * @return ActionResult（不要抛异常，catch 后归一为 FAILED）
     */
    ActionResult execute(ActionContext ctx);

    /**
     * 补偿（回滚）。由外部对账任务调用，非引擎自动触发（D18）。
     */
    default ActionResult compensate(ActionContext ctx) {
        return ActionResult.notSupported();
    }
}
```

**ActionContext 字段说明：**

```java
ActionContext {
    actionId:          String          // Action 实例 id（对应 Decision.actions[n].actionId）
    actionType:        String          // 与 @ActionType.value 对应
    params:            Map<String,Any> // Action 绑定参数（来自 Scene.decisions[x].actions[n].params）
    evalContext:       EvalContext     // 本次评估上下文（含 eventId / subjectId / payload 等）
    actionExecutionId: Long            // action_execution 表行 id（用于幂等键）
    decisionCode:      String          // 触发本 Action 的 Decision 码（D27）
}
```

> `ActionContext.evalContext.getEventId()` 是推荐幂等键组成之一，见 §3.5 实现约束。

### 3.2 ActionResult 契约（D16 / D18 派生）

```java
ActionResult {
    status:       SUCCESS | FAILED | SKIPPED   // SKIPPED 只由引擎填（PREDECESSOR_FAILED）
    errorCode:    String?                       // 见 01-concepts §3.7 errorCode 清单
    errorMessage: String?                       // 人类可读错误信息，不作程序判断
    retryable:    Boolean                       // true = 入重试队列；false = 直接落库 FAILED
}
```

### 3.3 注解声明

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActionType {
    String value();                // 全局唯一，与规则 JSON 中 actionType 字段对应
    String displayName() default "";
    String paramsSchema() default "{}";   // 前端表单 schema
    int timeoutMs() default 3000;  // Handler 声明的超时预算；引擎据此设置调用上限，超时归 TIMEOUT errorCode
    boolean compensatable() default false; // 是否声明补偿支持（前端提示）
}
```

### 3.4 实现示例（ticket.create）

```java
@Component
@ActionType(
    value = "ticket.create",
    displayName = "创建工单",
    paramsSchema = """
        {
          "type": "object",
          "required": ["title", "assignee"],
          "properties": {
            "title":    { "type": "string" },
            "priority": { "type": "string", "enum": ["LOW","MEDIUM","HIGH"] },
            "assignee": { "type": "string" }
          }
        }
    """,
    timeoutMs = 3000,
    compensatable = true
)
public class TicketCreateHandler implements ActionHandler {
    @Override
    public ActionResult execute(ActionContext ctx) {
        String eventId = ctx.getEvalContext().getEventId();
        if (ticketService.existsByEventId(eventId)) {
            return ActionResult.success();  // 幂等：已建单直接成功
        }
        try {
            ticketService.create(buildRequest(ctx));
            return ActionResult.success();
        } catch (TimeoutException e) {
            return ActionResult.failed("TIMEOUT", true);
        } catch (BusinessException e) {
            return ActionResult.failed("BUSINESS_REJECTED", false);
        }
    }

    @Override
    public ActionResult compensate(ActionContext ctx) {
        ticketService.close(ctx.getEvalContext().getEventId());
        return ActionResult.success();
    }
}
```

### 3.5 实现约束

- execute() 内**必须**做幂等检查（幂等键推荐：`tenantId + eventId + decisionCode + actionId`，与 D27 迁移后的幂等键设计对齐）
- 超时处理分两种场景：
  - **Handler 主动处理**：catch TimeoutException 后返回 `ActionResult.failed("TIMEOUT", true)`（推荐，便于区分业务超时和引擎中断）
  - **引擎强制中断**：Handler 超过 timeoutMs 仍未返回时，引擎中断调用并归为 `ActionResult { status=FAILED, errorCode=TIMEOUT, retryable=true }`；Handler 无需额外处理，但不会收到任何回调
- compensate() 如不支持，返回 `ActionResult.notSupported()`（不要抛异常）
- Action 失败**不影响** EvalResult.satisfied（D18，评估阶段已结束）

---

## 四、加 MetricSource

### 4.1 SPI 接口

```java
// 包路径 TBD（见 09-skeleton §四）
public interface MetricSourceHandler {
    /**
     * 取单个 metric 值。引擎在 EvalContext 构建阶段并发调用（D20）。
     * @param query  含 metricCode + params + subjectId + eventPayload
     * @return MetricValue（含值 + valueType）；取数失败 → 抛异常（引擎归 METRIC_FETCH_FAIL，D15）
     */
    MetricValue fetch(MetricQuery query);
}
```

### 4.2 注解声明

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MetricSourceType {
    String value();   // "ATTRIBUTE" / "SQL_AGGREGATE" / "EXTERNAL_HTTP" / "STREAM" / 自定义
    String paramsSchema() default "{}";        // metric_definition.params 的 JSON Schema
    int defaultTimeoutMs() default 1000;
    int defaultCacheTtlSeconds() default 60;
}
```

### 4.3 内置 sourceType 建议参数

| sourceType | 推荐 timeoutMs | 推荐 cacheTtl | allowProvided 默认 |
|---|---|---|---|
| ATTRIBUTE | 100ms | 300s | true（D30：调用方画像通常更新） |
| SQL_AGGREGATE | 500ms | 3600s | false（D30：聚合结果由 DB 权威） |
| EXTERNAL_HTTP | 300ms | 60s | true（D30：外部服务通常更新） |
| STREAM | 200ms | 0（实时窗口，不 cache） | false（D30：流聚合由引擎侧计算） |

### 4.4 实现要求

- fetch() 自行管 timeout / retry / circuit breaker（引擎核心不重试，D15）
- 结果按 metric 的 `cachePolicyDefault.ttl` 写 Redis；key 格式占位规范：`rule:metric:{tenantId}:{metricCode}:{subjectId}`（待 `07-operability.md` §九展开时确认）；ttl=0 则不写缓存（强一致场景）
- 无法取数时**抛异常**（不返回 null），引擎统一处理
- `allowProvided` 在 metric_definition 配置，不在 Handler 里判断（Handler 不感知 providedMetrics，D30）

---

## 五、元数据契约

### 5.1 获取路径

前端进入编辑器时调用：

```
GET /api/v1/scenes/{sceneCode}/metadata?tenantId=demo-tenant
```

（该接口定义见 `10-api-contract.md` §五，本节只说元数据结构）

### 5.2 元数据响应结构（JSON 示意）

```json
{
  "conditionTypes": [
    {
      "code": "metric.threshold",
      "displayName": "指标阈值比较",
      "paramsSchema": {
        "type": "object",
        "properties": {
          "operator": { "type": "string", "enum": ["EQ","GT","GTE","LT","LTE","BETWEEN","NOT_BETWEEN"] },
          "value": { "type": "number" },
          "min": { "type": "number" },
          "max": { "type": "number" }
        }
      },
      "requiresMetric": true
    },
    {
      "code": "event.payload.compare",
      "displayName": "Payload 字段比较",
      "paramsSchema": { "type": "object", "properties": { "field": { "type": "string" }, "operator": { "type": "string" }, "value": {} } },
      "requiresMetric": false
    }
  ],
  "actionTypes": [
    {
      "code": "ticket.create",
      "displayName": "创建工单",
      "paramsSchema": { "type": "object", "required": ["title", "assignee"], "properties": { "title": { "type": "string" }, "assignee": { "type": "string" } } },
      "compensatable": true
    }
  ],
  "availableMetrics": [
    {
      "metricCode": "user.account.age.days",
      "name": "账户开立天数",
      "dataType": "LONG",
      "sourceType": "SQL_AGGREGATE",
      "allowProvided": false
    }
  ]
}
```

### 5.3 约束

- `availableMetrics` 只返回 Scene.metricBindings 白名单内的 metric，不暴露全局 metric 列表
- `requiresMetric=true` 的 ConditionType 在前端选择时同时渲染 metric 下拉框（来自 availableMetrics）
- `paramsSchema` 是 JSON Schema Draft-07 格式，前端按此动态渲染参数表单（不硬编码表单字段）
- 好处：业务方新注册 `@ConditionType` 后，前端无需改代码，元数据接口自动返回新类型

---

## 六、实现指南

### 6.1 通用原则

1. 不改 EvalContext：三种 SPI 实现均不能修改传入的 EvalContext（不可变合约，D20 §1）
2. 异常归一：实现方可抛任意 RuntimeException；引擎在边界处 catch 并按类型归一为 errorCode
3. 幂等自管：ActionHandler 自行保证 execute() 幂等（引擎不提供幂等包装）
4. 无状态 Bean：Handler / Evaluator 应设计为无状态 Spring singleton，不在实例字段存评估中间状态

### 6.2 超时与熔断建议

| 维度 | ATTRIBUTE | SQL_AGGREGATE | EXTERNAL_HTTP | STREAM |
|---|---|---|---|---|
| connect timeout | 50ms | 200ms | 200ms | 100ms |
| read timeout | 100ms | 500ms | 300ms | 200ms |
| retry 次数 | 0 | 0 | 1（仅幂等接口） | 0 |
| 熔断阈值 | 50%/10s | 30%/30s | 50%/10s | 50%/10s |

### 6.3 Bean 生命周期

MetricSourceHandler 在 Scene 激活时由 SceneWatcher 触发 `init()`（可选接口），Scene 卸载时触发 `destroy()`，用于 JDBC 连接池 / HTTP client 资源管理。ActionHandler 类似，但 PULL Scene 不预热（只 PUSH/HYBRID Scene 预热，详见 01-concepts §3.2 Scene 字段 dominantMode 说明）。

---

## 七、维护原则

- 本文档只描述**SPI 接口契约 + 注册指南**，不重复 SPI 模块归属（→ 09-skeleton §四）、不写运维参数默认值（→ 07-operability §九）。
- 新增第四类 SPI（如未来 Watcher / Scheduler / TraceWriter 开放给业务方实现）必须在本文档增章节 + 同步 09-skeleton §四 SPI 落点表。
- 实现建议值（§六）只列**建议**，不列默认值；默认值由 07-operability 集中管理。
