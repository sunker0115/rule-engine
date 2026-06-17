# OperatorCatalog 归位 kernel + ConditionEvaluator SPI 自暴露 schema

> 日期:2026-06-13。来源:D69 后续②+③。
>
> **②** 把 `ConditionTypeCatalog`(算子规格目录)移入 kernel,修正"算子契约住在 config-svc"的架构边界错误。
> **③** `ConditionEvaluator` SPI 加 `spec()` 默认方法,17 个内置评估器各自声明自身规格(单一真相源),自定义 SPI 算子 opt-in 获得发布期校验 + 元数据可见性。
>
> 对标 Apache Calcite `SqlOperatorTable`(算子目录)+ `SqlOperandTypeChecker`(操作数规格随算子走)。

## 1. 问题与目标

**现状问题:**
- `ConditionTypeCatalog`(规格目录)在 config-svc `internal/publish`——算子契约跨模块维护,kernel evaluator 实现与其描述在两处。
- 新增内置 evaluator 要改两处:kernel(实现)+ config-svc(目录声明)——易漏。
- rule-sdk、规则导入、未来 IDE 插件拿不到目录(config-svc `internal` 不可见)。
- `ConditionEvaluator` SPI 无自描述能力,自定义算子发布期永远放行、metadata 不可见(SPI 不完整)。

**目标:**
- `OperatorSpec`(纯数据)放 **kernel `api/operator`**,全模块可见。
- `ConditionTypeCatalog` 移入 **kernel `internal/condition`**,靠 evaluator 自身的 `spec()` 构建,删掉硬编码 CATALOG map。
- `ConditionEvaluator.spec()` default 方法,内置 17+2 evaluator 各自 override——算子契约住在算子实现里。
- 消费者(config-svc 3 处)只改 import 路径,行为不变。

## 2. 架构约束(关键)

`api/` 不能 import `internal/`。`ConditionTypeCatalog` 要调 `KernelEvaluators.defaults()`,后者在 `internal/condition`——故 Catalog 本身**不能放 `api/`**。

正确位置:
- `OperatorSpec`(纯 data record,无 internal 依赖)→ `api/operator`(public,所有消费者 import)。
- `ConditionTypeCatalog`(依赖 KernelEvaluators)→ `internal/condition`(public class,config-svc 可 import)。
- `ConditionEvaluator.spec()` 返回 `Optional<OperatorSpec>` → `api/spi/condition`(import `api/operator`,方向合法)。

## 3. 新增:ConditionParams 时间算子键常量

TimeWindow/OccurredAt evaluator 现用裸字符串 `"start"/"end"/"datesExclude"/"daysOfWeek"/"operator"/"value"`——一并收口。在 kernel `api/model/ConditionParams` 加:

```java
public static final String START         = "start";
public static final String END           = "end";
public static final String DATES_EXCLUDE = "datesExclude";
public static final String DAYS_OF_WEEK  = "daysOfWeek";
public static final String OPERATOR      = "operator";
public static final String VALUE         = "value";
```

TimeWindow/OccurredAt evaluator 同步改用这些常量(消魔法串)。

## 4. OperatorSpec(kernel `api/operator`)

新建包 `com.sstlfsj.rule.kernel.api.operator`。

```java
@lombok.Builder
public record OperatorSpec(
        /** conditionType 编码。 */
        String code,
        /** 运营可读名。 */
        String displayName,
        /** 必填 param 键({@link com.sstlfsj.rule.kernel.api.model.ConditionParams} 常量)。 */
        Set<String> requiredParamKeys,
        /** 允许的 metric/payload dataType({@link com.sstlfsj.rule.kernel.api.model.DataType} tag)。 */
        Set<String> allowedDataTypes,
        /** 是否需要绑定 metric/payload 字段(time.* 内置路径为 false)。 */
        boolean requiresMetric
) {}
```

## 5. ConditionEvaluator.spec()(kernel `api/spi/condition`)

```java
/** 可选:算子声明自身规格,供发布期校验 + 元数据暴露。默认空 = 放行(向后兼容)。 */
default java.util.Optional<OperatorSpec> spec() { return java.util.Optional.empty(); }
```

## 6. 内置 evaluator 各自 override spec()

19 个 evaluator(17 矩阵 + time.window + time.occurred_at)全部 override。以 GtEvaluator 为例:

```java
@Override
public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder()
            .code(ConditionTypes.GT).displayName("大于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag()))
            .requiresMetric(true).build());
}
```

时间算子(requiresMetric=false,allowedDataTypes=Set.of()):
- `TimeWindowEvaluator`: requiredParamKeys = `{START, END}`;datesExclude/daysOfWeek/timezone 可选不列。
- `OccurredAtEvaluator`: requiredParamKeys = `{OPERATOR}`;value/start/end 根据 operator 运行期校。

DateBefore/DateAfter 的 requiredParamKeys = `{THRESHOLD}`,allowedDataTypes = `{DATE,DATETIME}`(已在矩阵 17 内)。

## 7. ConditionTypeCatalog 重构(移至 kernel `internal/condition`)

删掉硬编码 `CATALOG` map,改为从 evaluators 收集:

```java
package com.sstlfsj.rule.kernel.internal.condition;

public final class ConditionTypeCatalog {

    // 类加载时构建一次;KernelEvaluators.defaults() 是确定集合,不变
    private static final Map<String, OperatorSpec> CATALOG = buildFromEvaluators();

    private static Map<String, OperatorSpec> buildFromEvaluators() {
        Map<String, OperatorSpec> m = new LinkedHashMap<>();
        KernelEvaluators.defaults().forEach((code, ev) ->
                ev.spec().ifPresent(spec -> m.put(code, spec)));
        return Map.copyOf(m);
    }

    public static OperatorSpec spec(String conditionType) {
        return CATALOG.get(conditionType);
    }

    public static Collection<OperatorSpec> all() {
        return CATALOG.values();
    }
}
```

**消费者 API 完全不变**;仅 import 路径从 `config.internal.publish` 改 `kernel.internal.condition`。

## 8. config-svc 消费者变化(仅 import)

| 文件 | 改动 |
|---|---|
| `ConditionParamValidator` | import `ConditionTypeCatalog` + `OperatorSpec` → 新路径 |
| `AstDataTypeResolver` | 同上 |
| `MetadataServiceImpl` | 同上;`ConditionTypeMeta` 的 displayName 来自 evaluator.spec(),与之前 catalog 填一致 |
| `ConditionTypeCatalog`(旧) | 删除 |

## 9. 自定义 SPI evaluator(opt-in)

第三方实现 `ConditionEvaluator` 后 override `spec()`:
- 不 override = `Optional.empty()` = 发布期放行 + metadata 不可见(行为与今天完全一致,零破坏)。
- Override = 获得发布期 param 键校验 + `GET /metadata` conditionTypes 可见。

`ConditionTypeCatalog` 只在 kernel 初始化时从 `KernelEvaluators.defaults()` 收集,**不自动收自定义 evaluator**。自定义算子的 spec 若要进 catalog,需通过 config-svc 的 `MetadataServiceImpl` 扩展点读 Spring-managed `ConditionEvaluator` Bean(留作 ③ 后续扩展,本轮不做)。

## 10. 成功判据

1. `OperatorSpec` 在 `api/operator`,所有模块可 import。
2. `ConditionTypeCatalog.all()` 返回 19 个算子规格,与现 17 + 2 时间算子一致(内容数值由 evaluator.spec() 提供,不另起静态 map)。
3. 既有 config-svc 全量测试全绿(行为不变,仅 import 变)。
4. 现有 ConditionParamValidator/AstDataTypeResolver 测试全绿。
5. kernel 全量测试全绿(含 19 个 evaluator 的 spec 单测)。
6. 自定义 evaluator 不 override spec() → 发布期放行(向后兼容)。

## 11. 明确不做

- `ConditionTypes`/`ConditionParams` 不移包(在 `api/model`,使用成本无问题,无架构必要性)。
- 自定义 evaluator 的 spec 自动注入 ConditionTypeCatalog(留 ③ 后续;需 Spring 收集 Bean,超出本轮 scope)。
- `ConditionTypeCatalog` 不放 `api/operator`(架构约束:依赖 KernelEvaluators → 必须在 internal)。

## 12. 实施顺序

1. `ConditionParams` 加时间算子键常量(+测试,消魔法串)。
2. `api/operator/OperatorSpec`(+测试)。
3. `ConditionEvaluator.spec()` 接口 default 方法。
4. 19 个 evaluator override `spec()`(同时用 ConditionParams.* 替换裸键)。
5. `ConditionTypeCatalog` 移入 kernel `internal/condition`,重构从 evaluators 构建。
6. config-svc 消费者改 import 路径 + 全量测试绿。
7. kernel 全量测试绿。
