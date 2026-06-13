# @MetricSource 方法式取数注解 — 设计

> 状态:已实现 · 日期:2026-06-12 · 关联决策:D65(草案,见文末) · 前置:D61(注解规则)、D64(非 boolean 注解原语)
> 范围:SDK 取数**供给**侧的声明式糖。引擎取数管线与接口式 `MetricSourceHandler` SPI 不动。

## 1. 背景与动机

D61/D64 让规则的判定逻辑(`@Condition`/`@Decide`/`@Score`)写成"带注解的方法"——靠扫描器把方法**合成包装**成引擎要的 SPI 对象,用户不必实现接口。唯独 metric **取数供给**侧没享受这层糖:接入方仍要

1. 实现 `MetricSourceHandler` 接口(`fetch(MetricQuery)`)并标 `@MetricSourceType`;
2. **另外**写一份 `MetricDescriptor` 定义(走哪个 sourceType / dataType / ttl / allowProvided),经 `MetricDefinitionSource` 注册。

样例 `RecentTxnCountHandler` + `MetricDemoConfig` 正是这两步的体现 —— 一个派生指标要两个类。本设计补上对称的方法式糖 `@MetricSource`,把这两步塌缩成**一个带注解的方法**:

```java
@Component
public class VelocityMetrics {                 // 也可 co-locate 到规则类(metric 私有于该规则时)
    @MetricSource(value = "recent_txn_count", cacheTtlSeconds = 60)
    public long recentTxnCount(@Fact("subjectId") String subjectId) {
        return repo.countRecentTxns(subjectId);
    }
}
```
消费侧 `@Metric("recent_txn_count")` 一字不改。

### 非目标

- **不动引擎取数管线**(`EvalContextAssembler`/`MetricDefinitionResolver`/缓存)与 `MetricSourceHandler` SPI。
- **不废弃接口式 handler**:一个 handler 服务多个 metric、或复杂取数,仍用接口(`RuleEngineClientFetchTest` 为其参考)。`@MetricSource` 是"一 metric 一方法"常见场景的糖。
- **不做 descriptor `params` 注入**(`@Fact` 读 `query.params`):自动生成的 descriptor params 为空,YAGNI。

## 2. 归属:可放任意 bean,不强绑规则类

metric 与 condition/action 的共享模型不同:condition/action 规则私有,metric 是**具名、共享、可复用**的数据源(任何规则经 `metricCode` 用 `@Metric` 引用同一个)。故:

- `@MetricSource` 可标在**任意 Spring bean** 的方法上,扫描器从所有 bean 收集(同 `@OnDecision` 不只从规则类收集)。
- **默认**放独立 metrics bean(被多规则共享);**允许** co-locate 到规则类(该 metric 私有于这条规则时)。框架不强制。

## 3. 注解 `@MetricSource`

`rule-sdk` 包 `com.sstlfsj.rule.sdk.annotation`,方法级:

```java
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
public @interface MetricSource {
    /** metric 编码,= 消费侧 @Metric 的值,(tenant 内)全局唯一。 */
    String value();
    /** 取数结果缓存 ttl 秒;0 = 不缓存。 */
    int cacheTtlSeconds() default 0;
    /** 是否允许调用方推值(providedMetrics)覆盖 fetch;默认 false=恒走本方法。 */
    boolean allowProvided() default false;
}
```

## 4. 参数注入:确定性逐参数解析

`@MetricSource` 方法跑在**取数阶段**,引擎给的是 `MetricQuery`(`metricCode/tenantId/subjectId/params/eventPayload/now`),不是 `EvalContext`。参数注入按下表**从上到下、命中即停**,机械可判、无二义:

| 参数形态 | 注入 | 说明 |
|---|---|---|
| 类型为 `MetricQuery` | 原始 query(逃生口) | 此参数**不得**再标 `@Fact`(标了=扫描期报错) |
| 标 `@Fact("x")` | 从 query 取具名值 | `subjectId`→`query.subjectId()`;`x`→`query.eventPayload().get("x")`;元数据键 `tenantId/metricCode/now` |
| 标 `@Metric` | **扫描期报错** | metric 方法内不可依赖 metric(预拉不递归) |
| 无注解且非 MetricQuery | **扫描期报错** | "参数须标 @Fact 或为 MetricQuery 类型" |

一个参数**要么** MetricQuery 类型、**要么** `@Fact`,二者不可兼标。`@Fact("subjectId")` 在规则/动作/metric 三处含义一致(都取主体)。

**指引(写进 Javadoc + 样例)**:默认用 `@Fact`(取 subjectId / 个别 payload 字段);需全字段/复杂逻辑才用 `MetricQuery` 参数(逃生口)。样例只示范 `@Fact`,`MetricQuery` 作一处进阶示例。

### 组件 `MetricQueryResolver`(新,rule-sdk)

解析 `@MetricSource` 方法参数:按上表解析 `@Fact`(over `MetricQuery`)/ `MetricQuery` 类型;复用 `FactResolver.coerce` 做类型转换。`@Fact` 取名复用 `FactResolver.factName`(缺省回退参数名,D63)。`validate(Parameter[])` 在扫描期校验上表的报错项。

> 与 `FactResolver`(over `EvalContext`)并列,不合并:两者元数据键集合不同(MetricQuery 无 eventId/decisionCode),各自独立更清晰。

## 5. 合成包装 + 自动定义

返回类型 → `DataType`:`long/int→LONG`、`double/float→DOUBLE`、`BigDecimal→DECIMAL`、`boolean→BOOLEAN`、`String→STRING`;其余 → 扫描期报错(要求显式可映射类型)。

**扫描器 `AnnotatedMetricScanner`(新,rule-sdk)** 从所有 bean 收集 `@MetricSource` 方法,每个产出:
- 合成 `MetricSourceHandler`(lambda):`MetricQueryResolver.resolve` 注入参数 → 反射调方法 → 包成 `MetricValue(返回值, dataType.tag(), ValueSource.FETCHED.tag())`;方法抛错 → `MetricValue.error("METRIC_SOURCE_EVAL_ERROR")`。
- 合成 sourceType:`__anno_metric:` + metricCode(隐藏,用户不感知)。
- 自动 `MetricDescriptor`(metricCode, 合成 sourceType, dataType, allowProvided, cacheTtlSeconds, `{}`)。
- 归属租户:client 配置租户(`props.getTenantId()`,与注解规则装载租户一致)。

`ScanResult`:`Map<合成sourceType, MetricSourceHandler> handlers` + `List<MetricDescriptor> descriptors`(按租户)。

## 6. 装配

**`RuleEngineClient.Builder`**(RuleEngineClient.java:59-67、133、307):合成 handler 无 `@MetricSourceType` 注解,现有 `toSourceTypeMap` 按注解推 sourceType 走不通。新增:
- 字段 `Map<String, MetricSourceHandler> explicitSourceHandlers` + 方法 `addMetricSourceHandler(String sourceType, MetricSourceHandler)`。
- build() 的 sourceType map = `toSourceTypeMap(metricHandlers)` ∪ `explicitSourceHandlers`;`fetchEnabled` 同时考虑二者非空。
- descriptor 经现成 `localMetric(tenant, descriptor)` 注册(与 §5 自动 descriptor 对接,确保"定义有、handler 也有",绕过 build() 的"定义无 handler 报错"校验)。

**`RuleEngineClientAutoConfiguration`**:收集所有含 `@MetricSource` 方法的 bean → `AnnotatedMetricScanner` → `handlers` 经 `addMetricSourceHandler` 灌入、`descriptors` 经 `localMetric` 灌入。

## 7. 样例塌缩

- 删 `RecentTxnCountHandler`(接口式)+ `MetricDemoConfig`(descriptor),换成一个 `@MetricSource` 方法(置于 `metric/VelocityMetrics`,`@Fact("subjectId")` 注入)。
- `VelocityRule` 不变;`VelocityRuleIT`/`MetricDemoApplication` 改用新 bean(断言不变:frequent-user→命中、normal-user→不命中)。
- 接口式取数的参考保留在 `RuleEngineClientFetchTest`(SDK 测试),作"进阶/多 metric"示范。

## 8. 校验(扫描期 fail-fast)

- `value()`(metricCode)非空。
- 参数解析按 §4 表:`@Metric` 报错、无注解非 MetricQuery 报错、`@Fact`+MetricQuery 兼标报错。
- 返回类型须映射到已知 `DataType`,否则报错。
- 同一 client 内 `@MetricSource` 的 metricCode 重复 → 报错(坐标冲突)。

## 9. 测试

- `MetricQueryResolver`:`@Fact("subjectId")`/payload 字段/元数据注入、MetricQuery 类型注入、`@Metric` 拒绝、漏标拒绝、coerce。
- dataType 推断:各返回类型 → DataType;不可映射类型报错。
- 合成 handler:正常返回包 MetricValue、方法抛错降级 error。
- `AnnotatedMetricScanner`:多 bean 收集、重复 metricCode 报错、co-locate 到规则类也能收集。
- AutoConfiguration 切片端到端:`@MetricSource` 供给 + `@Metric` 规则消费 → fetched 值驱动决策(frequent/normal 对照)。
- 样例 IT 更新。收尾全量 `$MVN clean test`。

## 10. 决策日志条目草案(D65)

> 待评审通过后追加到 `docs/00-decisions.md`。

**D65 @MetricSource 方法式取数注解 | A**

补齐取数**供给**侧的声明式糖(对称 D61/D64 的判定侧):`@MetricSource(value, cacheTtlSeconds, allowProvided)` 标在任意 Spring bean 的方法上,扫描器(`AnnotatedMetricScanner`)把方法**合成包装**成 `MetricSourceHandler` + 自动生成 `MetricDescriptor`(sourceType=`__anno_metric:<code>` 合成隐藏,dataType 由返回类型推),把"实现接口 + 写 descriptor 定义"两步塌缩成一个方法。参数注入用 `@Fact` over `MetricQuery`(新 `MetricQueryResolver`,与 over-EvalContext 的 `FactResolver` 并列),逃生口为 `MetricQuery` 类型参数;逐参数确定性解析无二义,`@Metric`/漏标/兼标扫描期 fail-fast。装配:`RuleEngineClient.Builder` 加按显式 sourceType 注册 handler 的路 + 经 `localMetric` 注册自动 descriptor;AutoConfiguration 收集 `@MetricSource` bean。**归属**:可放任意 bean(含规则类),不强绑——metric 是共享具名资源。**保留**:接口式 `MetricSourceHandler` SPI(多 metric / 复杂取数的进阶选项)。**样例**:`RecentTxnCountHandler`+`MetricDemoConfig` 塌缩成单个 `@MetricSource` 方法。设计见 `specs/2026-06-12-annotation-metric-source-design.md`。
