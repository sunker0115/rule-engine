# 算子元数据统一设计：@ConditionType 升级 + ConditionTypeMeta 消除 + 自定义算子进 catalog

> 日期：2026-06-13。来源：D69 后续②+③ 收尾。
>
> **清理三个历史遗留问题，完成"单一真相源"收口**：
> - `@ConditionType` 字段是 JSON 串（paramsSchema），与 typed `OperatorSpec` 并存冗余。
> - `ConditionEvaluator.spec()` 接口方法和 `@ConditionType` 注解是两套并行机制。
> - `ConditionTypeMeta`（config-svc）是 `OperatorSpec`（kernel）的弱化版，多余类型。

## 1. 目标

- **`@ConditionType` 成为内置算子唯一声明机制**：typed 字段（`requiredParamKeys` + `allowedDataTypes`），不再有裸 JSON 串。
- **删掉一切冗余**：`spec()` 接口方法、`ConditionTypeMeta` record、`paramsSchema` JSON 串字段——无历史包袱。
- **自定义算子进 catalog**：注册 `@Bean OperatorSpec` 即可在 metadata API 可见，与执行路径解耦。
- **构造器注入保持一致**：`MetadataServiceImpl` 通过构造器接收 `List<OperatorSpec> customSpecs`。

## 2. 变更清单

### 2.1 kernel `api/annotation/ConditionType.java`（升级）

删除 `paramsSchema` 字段，新增 typed 字段：

```java
public @interface ConditionType {
    /** conditionType 编码（ConditionTypes.* 常量）。 */
    String value();
    /** 运营可读名；留空回退 value()。 */
    String displayName() default "";
    /** 必填 param 键（ConditionParams.* 常量）。 */
    String[] requiredParamKeys() default {};
    /** 允许的 metric/payload dataType（DataType 枚举；编译期常量）。 */
    DataType[] allowedDataTypes() default {};
    /** 是否需要绑定 metric/payload 字段（time.* 内置路径为 false）。 */
    boolean requiresMetric() default true;
    // 删除：String paramsSchema() default "{}";
}
```

### 2.2 kernel `api/spi/condition/ConditionEvaluator.java`（删 spec()）

删掉整个 `spec()` default 方法及相关 import（`OperatorSpec`、`Optional`）。接口只保留 `evaluate()`。

### 2.3 19 个 evaluator（贴注解 + 删 spec() override）

每个 evaluator 类上加 `@ConditionType`，删掉 `spec()` 方法。示例：

```java
@ConditionType(
    value = ConditionTypes.GT,
    displayName = "大于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL}
)
public class GtEvaluator extends AbstractNumericEvaluator { ... }
// 删掉原 spec() override
```

时间算子（`requiresMetric=false`，`allowedDataTypes` 为空）：

```java
@ConditionType(
    value = ConditionTypes.TIME_WINDOW,
    displayName = "时间窗口",
    requiredParamKeys = {ConditionParams.START, ConditionParams.END},
    allowedDataTypes = {},
    requiresMetric = false
)
public class TimeWindowEvaluator implements ConditionEvaluator { ... }
```

### 2.4 kernel `internal/condition/ConditionTypeCatalog.java`（改读注解）

`buildFromEvaluators()` 改为从注解读取（不再调 `spec()`）：

```java
private static Map<String, OperatorSpec> buildFromEvaluators() {
    Map<String, OperatorSpec> m = new LinkedHashMap<>();
    KernelEvaluators.defaults().forEach((code, ev) -> {
        ConditionType ann = ev.getClass().getAnnotation(ConditionType.class);
        if (ann != null) {
            Set<String> paramKeys = ann.requiredParamKeys().length > 0
                    ? Set.of(ann.requiredParamKeys()) : Set.of();
            Set<String> dataTypes = Arrays.stream(ann.allowedDataTypes())
                    .map(DataType::tag).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            m.put(ann.value(), OperatorSpec.builder()
                    .code(ann.value())
                    .displayName(ann.displayName().isBlank() ? ann.value() : ann.displayName())
                    .requiredParamKeys(paramKeys)
                    .allowedDataTypes(Set.copyOf(dataTypes))
                    .requiresMetric(ann.requiresMetric())
                    .build());
        }
    });
    return Map.copyOf(m);
}
```

### 2.5 kernel `api/operator/OperatorSpec.java`（更新 Javadoc）

删掉引用已删 `spec()` 方法的 Javadoc 句子（`由 ConditionEvaluator#spec() 声明`改为`由 @ConditionType 注解声明`）。

### 2.6 config-svc `MetadataService.java`（删 ConditionTypeMeta，改响应）

- 删 `ConditionTypeMeta` record。
- `MetadataResponse.conditionTypes` 改为 `List<OperatorSpec>`：

```java
record MetadataResponse(
    List<OperatorSpec> conditionTypes,
    List<MetricMeta> availableMetrics
) {}
```

加 import `com.sstlfsj.rule.kernel.api.operator.OperatorSpec`。

### 2.7 config-svc `MetadataServiceImpl.java`（构造器注入 customSpecs + 合并）

构造器加 `List<OperatorSpec> customSpecs` 参数（Spring 无匹配 bean 时自动注入空列表）：

```java
public MetadataServiceImpl(SceneMapper sceneMapper,
                            MetricDefinitionMapper metricDefinitionMapper,
                            RuleDefinitionMapper ruleDefinitionMapper,
                            RuleVersionMapper ruleVersionMapper,
                            List<OperatorSpec> customSpecs) {
    ...
    this.customSpecs = customSpecs;
}
```

`getSceneMetadata` 合并逻辑：

```java
// 1. 内置（catalog 优先）
Map<String, OperatorSpec> merged = new LinkedHashMap<>();
ConditionTypeCatalog.all().forEach(s -> merged.put(s.code(), s));
// 2. 自定义 OperatorSpec bean（内置已有 code 则跳过）
customSpecs.forEach(s -> merged.putIfAbsent(s.code(), s));
List<OperatorSpec> conditionTypes = List.copyOf(merged.values());
return new MetadataResponse(conditionTypes, metricMetas);
```

### 2.8 自定义算子作者的使用方式

声明执行：
```java
@Bean
ConditionEvaluator myEvaluator() { return new MyGeoEvaluator(); }
```

声明元数据（独立 bean，与执行解耦）：
```java
@Bean
OperatorSpec myOperatorSpec() {
    return OperatorSpec.builder()
        .code("geo.within").displayName("地理围栏")
        .requiredParamKeys(Set.of("lat", "lng", "radius"))
        .allowedDataTypes(Set.of())
        .requiresMetric(false).build();
}
```

→ `GET /metadata` conditionTypes 中出现 `geo.within`，无需改动任何框架代码。

## 3. 删除项汇总

| 删除 | 位置 | 原因 |
|---|---|---|
| `ConditionEvaluator.spec()` | kernel api/spi | 注解取代 |
| `spec()` 19 个 override | kernel internal/condition | 注解取代 |
| `OperatorSpec` import in evaluators | kernel internal/condition | 随 spec() 删 |
| `@ConditionType.paramsSchema` | kernel api/annotation | typed 字段取代 |
| `ConditionTypeMeta` record | config-svc MetadataService | OperatorSpec 取代 |
| `ConditionTypeMeta` import/reference | 3 处消费者测试 | 随 record 删 |

## 4. 测试

- **`ConditionTypeCatalogKernelTest`**：all() 返回 19；GT spec requiredParamKeys={threshold}；time.window requiresMetric=false。（改注解后此测试内容不变，只是底层从 spec() 改为注解驱动）
- **`MetadataServiceImplTest`**：conditionTypes 非空（List<OperatorSpec>，原 List<ConditionTypeMeta> 的用例改类型）；自定义 spec bean 进 merged；内置不被自定义覆盖。
- **kernel 全量 + eval-svc 全量 + config-svc 全量**：全绿证行为不变。

## 5. 成功判据

1. 全量 clean test 27 模块绿。
2. `GET /metadata` conditionTypes 返回 `List<OperatorSpec>`（含 19 条内置）。
3. 注册 `@Bean OperatorSpec` bean → 出现在 conditionTypes（自定义算子元数据路径通）。
4. 代码中无 `ConditionTypeMeta`、无 `spec()`、无 `paramsSchema` JSON 串字段。
