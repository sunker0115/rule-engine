# 04 — 扩展指南

> **位置定位**：本文档承载"我要加一个新条件 / 指标源，怎么落地"的**复制粘贴级指南**——SPI 接口签名 / Bean 注册 / 注解声明 / 元数据契约 / 实现建议（timeout / retry / 熔断 默认值）。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) §3.6 / §3.9、[`09-skeleton.md`](./09-skeleton.md) §四 SPI 接口落点
>
> **解决什么疑问**："加一个新 ConditionType 要改哪些文件？""MetricSource 怎么声明缓存策略 / 类型级 params schema？""前端怎么知道我的新条件有哪些参数？"
>
> **职责边界**——
> - ✅ SPI 接口签名 / Bean 注册 / 元数据声明 / 实现建议值
> - ❌ 不写 SPI 模块归属（→ 09-skeleton §四）、不写运行时调度（→ 02-runtime）、不写 AST 操作符（→ 03-rule-expression）、不写表结构（→ 05-storage）、不写前端 UI（→ 06-frontend）、不写运维参数默认值（→ 07-operability）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 加 ConditionType | ✅ 已展开 |
| §四 加 MetricSource | ✅ 已展开 |
| §五 元数据契约 | ✅ 已展开 |
| §六 实现指南 | ✅ 已展开 |
| §七 SubjectLoader 实现指南 | ✅ 已展开 |
| §八 维护原则 | ✅ 已展开 |
| §九 代码定义规则（`@RuleDef` 注解模式） | ✅ 已展开 |

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

### 2.4 实现示例（自定义算子 `geo.distance_within`）

> 内置比较算子（`GT` / `IN` / `BETWEEN` …，见 [`03-rule-expression.md`](./03-rule-expression.md) §三）已由 `KernelEvaluators.defaults()` 注册，**算子码即 conditionType**，无需自己写。下例演示注册一个内置清单里没有的**自定义算子**：判断指标坐标点是否在目标点指定半径内。

```java
@Component
@ConditionType(
    value = "geo.distance_within",
    displayName = "地理半径内",
    paramsSchema = """
        {
          "type": "object",
          "required": ["centerLat", "centerLng", "radiusKm"],
          "properties": {
            "centerLat": { "type": "number" },
            "centerLng": { "type": "number" },
            "radiusKm":  { "type": "number" }
          }
        }
    """
)
public class GeoDistanceWithinEvaluator implements ConditionEvaluator {
    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        Object metricVal = ctx.getMetric(node.getMetricCode());
        if (metricVal == null) throw new MetricNotFoundException(node.getMetricCode());
        // metricVal 为 "lat,lng" 坐标点；按 params 的 center + radiusKm 判定是否在圈内
        return withinRadius(metricVal, node.getParams());
    }
}
```

### 2.5 实现约束

- 不在 evaluate() 内发起任何网络 / DB 调用（EvalContext 已预拉所有 metric，D20）
- 如果 params 缺必填字段 → 抛 `IllegalArgumentException`，引擎归 `CONDITION_EVAL_ERROR`
- evaluate() 必须无副作用（不能修改 EvalContext，D20 §1 EvalContext 不可变）
- 单元测试模板：构造 `MockConditionNode`（直接设 params）+ `MockEvalContext`（直接注入 metric 值）→ 断言 evaluate() 返回预期布尔值

---

## 四、加 MetricSource

> **B21 已实装**：`EvalContextAssembler` 已接线取数管线——按 metric `sourceType` 路由 `MetricSourceHandler`（`@MetricSourceType` 归类）并发 fetch；metric 运行时定义经 **`MetricDefinitionResolver` SPI** 解析（服务端 `DbMetricDefinitionResolver` 读 `metric_definition` 表 + Caffeine 缓存；**数据源无关**，嵌入式 SDK 读下发缓存，见 `specs/2026-06-06-sdk-fetch-design.md`）；取数结果经 **`MetricCache` SPI** 缓存（key = `tenant:metricCode:subjectId:stableHash(params)`，`ttl=0` 不缓存，内核不依赖 Caffeine、由 eval-svc 提供 `CaffeineMetricCache`）。`MetricQuery` 携带 `now`（引擎统一时钟，SQL `:now` 取此值）。SQL_AGGREGATE 走 `MetricDataSourceRegistry` **命名只读源**；EXTERNAL_HTTP 走 `HttpEndpointRegistry` **命名端点**（凭证在 infra 不落 metric）。取数失败统一降级 `METRIC_FETCH_FAIL`（D15 / D45）。发布期 `MetricSafetyValidator` 拒绝 DB 时间函数 / `${}` 拼接 / 未注册资源名。

### 4.1 SPI 接口

```java
// 包路径 TBD（见 09-skeleton §四）
public interface MetricSourceHandler {
    /**
     * 取单个 metric 值。引擎在 EvalContext 构建阶段并发调用（D20）。
     * @param query  含 metricCode + params + subjectId + eventPayload
     * @return MetricValue（成功值；取数失败返回 MetricValue.error(METRIC_FETCH_FAIL) 降级，B21 推荐）；抛异常作退路，assembler 兜底归 METRIC_FETCH_FAIL（D15）
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
}
// 注：defaultTimeoutMs / defaultCacheTtlSeconds 已于 D38 精简删除；超时阈值见 07-operability，TTL 由 metric_definition.cache_ttl_seconds 控制
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
- 结果经 `MetricCache` SPI 缓存（B21 实装，v1 进程内 Caffeine `CaffeineMetricCache`）；key 格式：`{tenantId}:{metricCode}:{subjectId}:{stableHash(params)}`（murmur3，params 排序后哈希）；`cache_ttl_seconds=0` 不缓存（强一致场景）。多实例升 Redis 留 v2
- **推荐错误通道（B21）**：取数失败返回 `MetricValue.error(METRIC_FETCH_FAIL)`（不返回业务值）；抛异常作退路，assembler 用 `.exceptionally()` 兜底统一归 `METRIC_FETCH_FAIL`（D15）
- `allowProvided` 在 metric_definition 配置，不在 Handler 里判断（Handler 不感知 providedMetrics，D30）

---

## 五、元数据契约

### 5.1 获取路径

前端进入编辑器时调用：

```
GET /admin/v1/scenes/{sceneCode}/metadata?tenantId=demo-tenant
```

（该接口定义见 `10-api-contract.md` §五，本节只说元数据结构）

### 5.2 元数据响应结构（JSON 示意）

```json
{
  "conditionTypes": [
    {
      "code": "GT",
      "displayName": "大于",
      "paramsSchema": {
        "type": "object",
        "required": ["threshold"],
        "properties": {
          "threshold": { "type": "number" }
        }
      },
      "requiresMetric": true
    },
    {
      "code": "geo.distance_within",
      "displayName": "地理半径内",
      "paramsSchema": { "type": "object", "required": ["centerLat", "centerLng", "radiusKm"], "properties": { "centerLat": { "type": "number" }, "centerLng": { "type": "number" }, "radiusKm": { "type": "number" } } },
      "requiresMetric": true
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

- `availableMetrics` 返回 tenant 级全部 ACTIVE metric（D54：metric tenant 级可用，无 scene 白名单）
- `requiresMetric=true` 的 ConditionType 在前端选择时同时渲染 metric 下拉框（来自 availableMetrics）
- `paramsSchema` 是 JSON Schema Draft-07 格式，前端按此动态渲染参数表单（不硬编码表单字段）
- 好处：业务方新注册 `@ConditionType` 后，前端无需改代码，元数据接口自动返回新类型

---

## 六、实现指南

### 6.1 通用原则

1. 不改 EvalContext：各 SPI 实现均不能修改传入的 EvalContext（不可变合约，D20 §1）
2. 异常归一：实现方可抛任意 RuntimeException；引擎在边界处 catch 并按类型归一为 errorCode
3. 无状态 Bean：Evaluator / MetricSourceHandler 应设计为无状态 Spring singleton，不在实例字段存评估中间状态

### 6.2 超时与熔断建议

| 维度 | ATTRIBUTE | SQL_AGGREGATE | EXTERNAL_HTTP | STREAM |
|---|---|---|---|---|
| connect timeout | 50ms | 200ms | 200ms | 100ms |
| read timeout | 100ms | 500ms | 300ms | 200ms |
| retry 次数 | 0 | 0 | 1（仅幂等接口） | 0 |
| 熔断阈值 | 50%/10s | 30%/30s | 50%/10s | 50%/10s |

### 6.3 Bean 生命周期

MetricSourceHandler 可实现可选的生命周期接口（`init()` / `destroy()`）。引擎在 Scene 激活时调用 `init()`，Scene 卸载时调用 `destroy()`，用于 JDBC 连接池 / HTTP client 资源管理（引擎在 Scene 状态变更时调度这些回调，不由 handler 直接调用 init()）。PULL Scene 不预热，只 PUSH/HYBRID Scene 预热（详见 01-concepts §3.2 Scene 字段 dominantMode 说明）。

---

## 七、SubjectLoader 实现指南

> 扩展入口：为新的 `subjectType`（如 ACCOUNT / DEVICE / ORDER）提供主体加载实现（D25）。v1 仅 `UserProfileLoader`（USER 类型）实装。

### 7.1 SPI 接口

```java
public interface SubjectLoader {
    /**
     * @param subjectId   主体 id（来自 RuleEvent.subjectId）
     * @param subjectType 主体类型（来自 Scene.subjectType）
     * @param event       完整 RuleEvent（含 payload，可辅助加载）
     * @return Subject（不可变 POJO，含 attributes Map）；取数失败抛 RuntimeException
     */
    Subject load(String subjectId, SubjectType subjectType, RuleEvent event);

    /**
     * 声明本实现支持的 subjectType 列表
     */
    List<SubjectType> supportedTypes();
}
```

### 7.2 注册

```java
@Component
public class AccountLoader implements SubjectLoader {
    @Override public Subject load(...) { ... }
    @Override public List<SubjectType> supportedTypes() { return List.of(SubjectType.ACCOUNT); }
}
```

> **注**：`SubjectLoader` 通过 `supportedTypes()` 方法注册（无需额外注解），与 `@ConditionType` / `@MetricSourceType` 注解风格略有不同。

`SubjectLoaderRegistry` 启动时扫描所有 `SubjectLoader` Bean，按 `supportedTypes()` 建索引；运行时由 `EvalContext` 构建阶段按 `Scene.subjectType` 路由。

### 7.3 实现约束

- `load()` 超时建议 ≤ 200ms（`engine.rule.subject.load-timeout-ms` 配置，见 07-operability §九）；
- `load()` 失败抛出 RuntimeException → 引擎按 D15 语义整 EvalContext 失败（`METRIC_FETCH_FAIL`）；
- 返回的 `Subject.attributes` 键名与 AST 中 `subject.<attribute>` 引用路径对应；v1 USER 类型键名见 `01-concepts.md §3.13 Subject 字段`。

---

## 八、维护原则

- 本文档只描述**SPI 接口契约 + 注册指南**，不重复 SPI 模块归属（→ 09-skeleton §四）、不写运维参数默认值（→ 07-operability §九）。
- 新增第四类 SPI（如未来进一步开放给业务方实现的扩展点）必须在本文档增章节 + 同步 09-skeleton §四 SPI 落点表。
- 实现建议值（§六）只列**建议**，不列默认值；默认值由 07-operability 集中管理。

---

## 九、代码定义规则（`@RuleDef` 注解模式）

> 扩展入口：前述 §二~§四 是为引擎补**算子 / 取数**能力；本节是另一类扩展——用 **Java 代码直接定义规则本身**（嵌入式 SDK 场景，D40 / D59）。规则不再经 admin API 配置 + 数据库存储，而是标注在代码类上，由 SDK 启动时扫描装载到评估索引。适用单测 / 演示 / 离线部署 / 把规则当代码版本管理的场景。

### 9.1 `@RuleDef` 注解（`rule-kernel`）

在 `InlineRuleSpec` 实现类上标注 `@RuleDef` 声明规则的身份与触发 / 决策绑定：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RuleDef {
    /** 业务规则码（逻辑身份，D59）；与 (tenant, sceneCode) 共同作用域内唯一。 */
    String code();
    /** 场景编码。 */
    String sceneCode();
    /** 租户 ID；默认空（继承范围见 §9.1 说明：仅 Spring starter 或显式双参构造时继承，单参构造留空得空租户）。 */
    String tenantId() default "";
    /** 版本号（逻辑身份的一部分，D59）。 */
    long version() default 1;
    /** 触发事件类型；空数组表示通配（等价于装载时写入 "*"）。 */
    String[] trigger() default {};
    /** Decision 绑定列表。 */
    DecisionBinding[] decisions() default {};
}
```

`@DecisionBinding`（嵌套注解，`rule-kernel`）声明命中后绑定的 Decision 码与优先级：

```java
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface DecisionBinding {
    String code();
    int priority() default 0;
}
```

> **身份模型（D59）**：规则逻辑身份 = `(tenant, sceneCode, code, version)`（人可读、名字驱动）；代理键 `ruleVersionId` 不由开发者手填，而由 `AnnotationRuleSource` 按 `(tenant, scene, code)` 稳定哈希派生（确定性投影，保证幂等装载）。
>
> **`tenantId()` 留空继承的精确范围**：继承仅在 **(a) Spring starter 自动装配**（AutoConfiguration 调双参构造，回落到 `rule.sdk.tenant-id`）或 **(b) 显式用双参构造 `new AnnotationRuleSource(specs, tenant)`** 时生效。**非 Spring 用单参 `new AnnotationRuleSource(specs)` 时留空 `tenantId` 会得到空租户 `""`**（单参构造的 `defaultTenantId` 为 `""`，而 `RuleEngineClient.Builder.tenantId(...)` 不会注入到 rule source——builder 把 `RuleSource` 当不透明对象），空租户不会匹配真实租户下的事件。此场景应在 `@RuleDef` 显式写 `tenantId`，或改用双参构造。

### 9.2 `InlineRuleSpec` 接口（`rule-sdk`）

规则类实现 `InlineRuleSpec`，`condition()` 返回 `Condition` DSL 表达式（D36 隐藏 AST 构造细节），由 `AnnotationRuleSource` 调 `toAst()` 转为 AST：

```java
public interface InlineRuleSpec {
    /** 返回规则条件，由 AnnotationRuleSource 调用 toAst() 转为 AST。 */
    Condition condition();
}
```

### 9.3 实现示例

```java
@RuleDef(code = "amount-fraud", sceneCode = "fraud",
         trigger = "TRANSACTION",
         decisions = @DecisionBinding(code = "BLOCK", priority = 100))
public class AmountFraudRule implements InlineRuleSpec {
    @Override
    public Condition condition() {
        return Condition.gt("amount", 1000)
                        .and(Condition.in("country", "CN", "HK"));
    }
}
```

> **无绑定 metric 的自定义算子**：`Condition.of(conditionType, params)` 双参重载用于直接指定 `conditionType` + 参数 Map，**不绑定具体 metric**（适用于不依赖单个指标值的自定义算子，与 §2.4 注册的 `@ConditionType` 配合）。常规带 metric 的比较算子仍用 `Condition.gt` / `Condition.in` 等便捷工厂。

### 9.4 装载方式

- **非 Spring（直接使用）**：把规则实例列表传给 `AnnotationRuleSource`，再交给 `RuleEngineClient.Builder.ruleSource(...)`：

```java
try (RuleEngineClient client = RuleEngineClient.builder()
        .tenantId("t1")
        // 非 Spring 必须用双参构造把租户传给 rule source：单参 new AnnotationRuleSource(List.of(...)) 时
        // @RuleDef 留空的 tenantId 会得到空租户 ""（builder.tenantId 不注入 rule source），不会继承此处的 "t1"。
        .ruleSource(new AnnotationRuleSource(List.of(new AmountFraudRule()), "t1"))
        .build()) {
    EvalResult result = client.evaluate(event);
}
```

- **Spring Boot（自动装配）**：规则类加 `@Component`，`rule-sdk-spring-boot-starter` 的 AutoConfiguration 自动收集容器内所有 `InlineRuleSpec` Bean，构造 `AnnotationRuleSource` 装载，`@Autowired RuleEngineClient` 即可使用，无需手动注册。

### 9.5 实现约束

- `@RuleDef` 与 `InlineRuleSpec` 必须同类标注 + 实现；`AnnotationRuleSource` 跳过未标注 `@RuleDef` 的 spec。
- `code` 在 `(tenant, sceneCode)` 作用域内须唯一且稳定——它参与代理键 `ruleVersionId` 的哈希派生，改 `code` = 换一条规则身份。
- `condition()` 应为纯函数（无副作用、不依赖外部可变状态），与 §2.5 ConditionEvaluator 的无状态约束一致。
