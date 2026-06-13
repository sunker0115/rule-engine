# 算子元数据统一 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 升级 `@ConditionType` 为 typed 字段，删掉 `spec()` 接口方法和 `ConditionTypeMeta` 冗余类型，`ConditionTypeCatalog` 改读注解，`MetadataService` 直接用 `OperatorSpec`，支持自定义算子通过 `@Bean OperatorSpec` 进 catalog。

**Architecture:** 单向迁移：先升级注解字段 → 给 19 个 evaluator 贴注解 → catalog 改读注解 → 再删 spec() 接口及 override → 最后更新 MetadataService/Impl。每步独立可验证。

**Tech Stack:** Java 25，Lombok @Builder，JUnit5+AssertJ，Mockito。前置：`mvn-env` skill 设 `$MVN`（JDK25）。

---

## 文件结构

**kernel：**
- Modify: `api/annotation/ConditionType.java`（升级字段）
- Modify: 19 个 evaluator（贴注解 + 删 spec()）
- Modify: `api/spi/condition/ConditionEvaluator.java`（删 spec()）
- Modify: `internal/condition/ConditionTypeCatalog.java`（改读注解）
- Modify: `api/operator/OperatorSpec.java`（更新 Javadoc）

**config-svc：**
- Modify: `api/service/MetadataService.java`（删 ConditionTypeMeta，改 MetadataResponse）
- Modify: `internal/service/MetadataServiceImpl.java`（加 customSpecs 构造参数 + 合并逻辑）
- Modify: `internal/service/MetadataServiceImplTest.java`（补第5参 + 新增自定义用例）

---

## Task 1: @ConditionType 升级（typed 字段，删 paramsSchema）

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/ConditionType.java`

- [ ] **Step 1: 替换注解字段**

完整替换为：

```java
package com.sstlfsj.rule.kernel.api.annotation;

import com.sstlfsj.rule.kernel.api.model.DataType;
import java.lang.annotation.*;

/**
 * 标注 ConditionEvaluator 实现类的条件类型标识与元数据。
 * 供 {@link com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog} 收集，
 * 同时作为自定义算子向元数据接口暴露自身规格的入口。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    /** conditionType 编码，全局唯一（= ConditionNode.conditionType / evaluator 注册键）。 */
    String value();
    /** 运营可读名；留空回退到 {@link #value()}。 */
    String displayName() default "";
    /** 必填 param 键（{@link com.sstlfsj.rule.kernel.api.model.ConditionParams} 常量）。 */
    String[] requiredParamKeys() default {};
    /** 允许的 metric/payload dataType（DataType 枚举，编译期常量）。 */
    DataType[] allowedDataTypes() default {};
    /** 是否需要绑定 metric/payload 字段（time.* 内置路径为 false）。 */
    boolean requiresMetric() default true;
}
```

- [ ] **Step 2: 编译确认**

Run: `$MVN -pl rule-kernel -am -DskipTests compile`
Expected: BUILD SUCCESS（暂时忽略 ConditionTypeCatalog 读 spec() 的未变动部分）。

- [ ] **Step 3: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/ConditionType.java
git commit -m "feat(kernel): @ConditionType 升级为 typed 字段(删 paramsSchema JSON 串)"
```

---

## Task 2: 19 个 evaluator 贴注解（保留 spec() 到 T4 统一删）

**Files:**
- Modify: 19 个 evaluator 文件，全在 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/`

- [ ] **Step 1: 给每个 evaluator 加 @ConditionType 注解**

import 模板（每个文件按需补）：
```java
import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
```

**完整注解列表：**

```java
// EqEvaluator
@ConditionType(value = ConditionTypes.EQ, displayName = "等于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL,
                        DataType.STRING, DataType.BOOLEAN, DataType.DATE, DataType.DATETIME})
public class EqEvaluator implements ConditionEvaluator { ... }

// NeqEvaluator
@ConditionType(value = ConditionTypes.NEQ, displayName = "不等于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL,
                        DataType.STRING, DataType.BOOLEAN, DataType.DATE, DataType.DATETIME})
public class NeqEvaluator implements ConditionEvaluator { ... }

// GtEvaluator
@ConditionType(value = ConditionTypes.GT, displayName = "大于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL})
public class GtEvaluator extends AbstractNumericEvaluator { ... }

// GteEvaluator
@ConditionType(value = ConditionTypes.GTE, displayName = "大于等于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL})
public class GteEvaluator extends AbstractNumericEvaluator { ... }

// LtEvaluator
@ConditionType(value = ConditionTypes.LT, displayName = "小于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL})
public class LtEvaluator extends AbstractNumericEvaluator { ... }

// LteEvaluator
@ConditionType(value = ConditionTypes.LTE, displayName = "小于等于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL})
public class LteEvaluator extends AbstractNumericEvaluator { ... }

// BetweenEvaluator
@ConditionType(value = ConditionTypes.BETWEEN, displayName = "区间内",
    requiredParamKeys = {ConditionParams.MIN, ConditionParams.MAX},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL,
                        DataType.DATE, DataType.DATETIME})
public class BetweenEvaluator implements ConditionEvaluator { ... }

// NotBetweenEvaluator
@ConditionType(value = ConditionTypes.NOT_BETWEEN, displayName = "区间外",
    requiredParamKeys = {ConditionParams.MIN, ConditionParams.MAX},
    allowedDataTypes = {DataType.LONG, DataType.DOUBLE, DataType.DECIMAL,
                        DataType.DATE, DataType.DATETIME})
public class NotBetweenEvaluator implements ConditionEvaluator { ... }

// InEvaluator
@ConditionType(value = ConditionTypes.IN, displayName = "属于集合",
    requiredParamKeys = {ConditionParams.VALUES},
    allowedDataTypes = {DataType.LONG, DataType.STRING})
public class InEvaluator implements ConditionEvaluator { ... }

// NotInEvaluator
@ConditionType(value = ConditionTypes.NOT_IN, displayName = "不属于集合",
    requiredParamKeys = {ConditionParams.VALUES},
    allowedDataTypes = {DataType.LONG, DataType.STRING})
public class NotInEvaluator implements ConditionEvaluator { ... }

// ContainsEvaluator
@ConditionType(value = ConditionTypes.CONTAINS, displayName = "集合包含",
    requiredParamKeys = {ConditionParams.ELEMENT},
    allowedDataTypes = {DataType.LIST})
public class ContainsEvaluator implements ConditionEvaluator { ... }

// NotContainsEvaluator
@ConditionType(value = ConditionTypes.NOT_CONTAINS, displayName = "集合不包含",
    requiredParamKeys = {ConditionParams.ELEMENT},
    allowedDataTypes = {DataType.LIST})
public class NotContainsEvaluator implements ConditionEvaluator { ... }

// StartsWithEvaluator
@ConditionType(value = ConditionTypes.STARTS_WITH, displayName = "前缀匹配",
    requiredParamKeys = {ConditionParams.PREFIX},
    allowedDataTypes = {DataType.STRING})
public class StartsWithEvaluator implements ConditionEvaluator { ... }

// EndsWithEvaluator
@ConditionType(value = ConditionTypes.ENDS_WITH, displayName = "后缀匹配",
    requiredParamKeys = {ConditionParams.SUFFIX},
    allowedDataTypes = {DataType.STRING})
public class EndsWithEvaluator implements ConditionEvaluator { ... }

// MatchesEvaluator
@ConditionType(value = ConditionTypes.MATCHES, displayName = "正则匹配",
    requiredParamKeys = {ConditionParams.REGEX},
    allowedDataTypes = {DataType.STRING})
public class MatchesEvaluator implements ConditionEvaluator { ... }

// DateBeforeEvaluator
@ConditionType(value = ConditionTypes.DATE_BEFORE, displayName = "早于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.DATE, DataType.DATETIME})
public class DateBeforeEvaluator implements ConditionEvaluator { ... }

// DateAfterEvaluator
@ConditionType(value = ConditionTypes.DATE_AFTER, displayName = "晚于",
    requiredParamKeys = {ConditionParams.THRESHOLD},
    allowedDataTypes = {DataType.DATE, DataType.DATETIME})
public class DateAfterEvaluator implements ConditionEvaluator { ... }

// TimeWindowEvaluator
@ConditionType(value = ConditionTypes.TIME_WINDOW, displayName = "时间窗口",
    requiredParamKeys = {ConditionParams.START, ConditionParams.END},
    allowedDataTypes = {},
    requiresMetric = false)
public class TimeWindowEvaluator implements ConditionEvaluator { ... }

// OccurredAtEvaluator
@ConditionType(value = ConditionTypes.TIME_OCCURRED_AT, displayName = "事件发生时间",
    requiredParamKeys = {ConditionParams.OPERATOR},
    allowedDataTypes = {},
    requiresMetric = false)
public class OccurredAtEvaluator implements ConditionEvaluator { ... }
```

> 注：每个文件只在 class 声明行之前加注解，`spec()` 方法**暂时保留**（T4 统一删）。确认注解字段与文件现有 spec() override 内容一致。

- [ ] **Step 2: 编译**

Run: `$MVN -pl rule-kernel -am -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/
git commit -m "feat(kernel): 19 个 evaluator 贴 @ConditionType 注解(typed 字段)"
```

---

## Task 3: ConditionTypeCatalog 改读注解

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalog.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalogKernelTest.java`（既有，验证行为不变）

- [ ] **Step 1: 先跑既有 catalog 测试确认基线**

Run: `$MVN -pl rule-kernel -am test -Dtest='ConditionTypeCatalogKernelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（用于确认改完后行为不变）。

- [ ] **Step 2: 替换 buildFromEvaluators()**

把 `ConditionTypeCatalog.java` 中的 `buildFromEvaluators()` 方法体全部替换为：

```java
import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import java.util.Arrays;
import java.util.LinkedHashSet;

private static Map<String, OperatorSpec> buildFromEvaluators() {
    Map<String, OperatorSpec> m = new LinkedHashMap<>();
    KernelEvaluators.defaults().forEach((code, ev) -> {
        ConditionType ann = ev.getClass().getAnnotation(ConditionType.class);
        if (ann != null) {
            Set<String> paramKeys = ann.requiredParamKeys().length > 0
                    ? Set.of(ann.requiredParamKeys()) : Set.of();
            Set<String> dataTypes = Arrays.stream(ann.allowedDataTypes())
                    .map(DataType::tag)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
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

加 import：`com.sstlfsj.rule.kernel.api.annotation.ConditionType`、`java.util.Arrays`。`DataType` 和 `OperatorSpec`、`KernelEvaluators` 已在 import 列表。

- [ ] **Step 3: 跑 catalog 测试（验证行为不变）**

Run: `$MVN -pl rule-kernel -am test -Dtest='ConditionTypeCatalogKernelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（all_covers19Operators=19；GT spec 正确；time.window requiresMetric=false）。

- [ ] **Step 4: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalog.java
git commit -m "refactor(kernel): ConditionTypeCatalog 改读 @ConditionType 注解(不再调 spec())"
```

---

## Task 4: 删 spec()——接口方法 + 19 个 override

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/condition/ConditionEvaluator.java`
- Modify: 19 个 evaluator（删 spec() override 及相关 import）

- [ ] **Step 1: ConditionEvaluator.java 删 spec()**

删掉整个 `spec()` default 方法及其 import（`OperatorSpec`、`Optional`）。接口只保留：

```java
package com.sstlfsj.rule.kernel.api.spi.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

/** 对单个 ConditionNode 在当前执行上下文中进行条件求值。 */
public interface ConditionEvaluator {
    /**
     * 在当前执行上下文中对单个条件节点求值。
     *
     * @param node 待求值的条件节点
     * @param ctx  当前评估上下文
     * @return 条件是否满足
     */
    boolean evaluate(ConditionNode node, EvalContext ctx);
}
```

- [ ] **Step 2: 19 个 evaluator 删 spec() override + 孤儿 import**

每个文件删掉：
- `@Override public Optional<OperatorSpec> spec() { ... }` 整个方法体
- 随之变孤儿的 import：`com.sstlfsj.rule.kernel.api.operator.OperatorSpec`、`java.util.Optional`、`java.util.Set`（若 Set 只在 spec() 里用到则删，否则保留）

> 操作提示：用 grep 确认每个 evaluator 的 spec() 方法范围，精确删除。`Set` 在矩阵算子里仅被 spec() 使用（原来用 `Set.of(...)` 构建 allowedDataTypes），可以一并删 `import java.util.Set`；时间算子里 `Set` 可能有其他用途，先检查再删。

- [ ] **Step 3: kernel 全量测试**

Run: `$MVN -pl rule-kernel -am test`
Expected: 全绿。`ConditionEvaluatorTest`（若存在）仍绿；catalog 测试仍绿；evaluator 测试仍绿（19 个 spec 测试须同步删掉——那些测试是对 spec() 方法的直接断言，接口方法删后应跟着删）。

> 若既有测试里有 `evaluator.spec().orElseThrow()` 类型的断言（T4 前各 evaluator 加了 spec_describesOperator 用例），一并删掉或改为验证 `@ConditionType` 注解值。

- [ ] **Step 4: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/condition/ConditionEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/
git commit -m "refactor(kernel): 删 ConditionEvaluator.spec() 接口方法及 19 个 override(@ConditionType 取代)"
```

---

## Task 5: OperatorSpec Javadoc + MetadataService API 变更

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/operator/OperatorSpec.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetadataService.java`

- [ ] **Step 1: OperatorSpec Javadoc 更新**

把 Javadoc 里 `由 ConditionEvaluator#spec() 声明` 改为 `由 @ConditionType 注解或 @Bean OperatorSpec 声明`：

```java
/**
 * 算子规格：必填 param 键 + 允许 dataType + 元数据，供发布期校验与元数据暴露使用。
 * 内置算子由 {@link com.sstlfsj.rule.kernel.api.annotation.ConditionType} 注解声明，
 * 自定义算子注册 {@code @Bean OperatorSpec} 进入元数据目录。
 * ...
 */
```

- [ ] **Step 2: MetadataService.java 删 ConditionTypeMeta，改 MetadataResponse**

```java
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;

// 删掉：record ConditionTypeMeta(...)
// MetadataResponse 改为：
record MetadataResponse(
    java.util.List<OperatorSpec> conditionTypes,
    java.util.List<MetricMeta> availableMetrics
) {}
```

（`MetricMeta` 保持不变。）

- [ ] **Step 3: 编译（预期部分失败——MetadataServiceImpl 还在用旧类型）**

Run: `$MVN -pl rule-config-svc -am -DskipTests compile`
Expected: 编译错误（`MetadataServiceImpl` 引用了 `ConditionTypeMeta`，以及测试里的引用）——这是预期的，下一 task 修复。

- [ ] **Step 4: Commit（仅这两个文件）**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/operator/OperatorSpec.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetadataService.java
git commit -m "refactor: OperatorSpec Javadoc 更新 + MetadataService 删 ConditionTypeMeta / 改 MetadataResponse<OperatorSpec>"
```

---

## Task 6: MetadataServiceImpl 修复 + 自定义算子合并 + 测试

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImplTest.java`

- [ ] **Step 1: MetadataServiceImpl 修改**

**1a. 加字段 + 修构造器（在现有 4 个字段/参数之后追加）：**

```java
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog;
import java.util.LinkedHashMap;
// ...
private final List<OperatorSpec> customSpecs;

// 构造器加第5参：
public MetadataServiceImpl(SceneMapper sceneMapper,
                            MetricDefinitionMapper metricDefinitionMapper,
                            RuleDefinitionMapper ruleDefinitionMapper,
                            RuleVersionMapper ruleVersionMapper,
                            List<OperatorSpec> customSpecs) {
    this.sceneMapper = sceneMapper;
    this.metricDefinitionMapper = metricDefinitionMapper;
    this.ruleDefinitionMapper = ruleDefinitionMapper;
    this.ruleVersionMapper = ruleVersionMapper;
    this.customSpecs = customSpecs != null ? customSpecs : List.of();
}
```

**1b. getSceneMetadata 里替换 conditionTypes 构建：**

```java
        // 1. 内置（catalog 优先）
        Map<String, OperatorSpec> merged = new LinkedHashMap<>();
        ConditionTypeCatalog.all().forEach(s -> merged.put(s.code(), s));
        // 2. 自定义 @Bean OperatorSpec（内置已有 code 则跳过）
        customSpecs.forEach(s -> merged.putIfAbsent(s.code(), s));
        List<OperatorSpec> conditionTypes = List.copyOf(merged.values());
        return new MetadataResponse(conditionTypes, metricMetas);
```

删掉原来的 `ConditionTypeMeta` import 和构建代码（`ConditionTypeCatalog.all().stream().map(s -> new ConditionTypeMeta(...))`）。

- [ ] **Step 2: 修复测试（补第5参 + 新增自定义用例）**

`MetadataServiceImplTest.java` 有两种构造方式：

**A. `@InjectMocks MetadataServiceImpl metadataService;`** — 改成显式构造（Mockito @InjectMocks 不注入 List<OperatorSpec>）：
```java
// @InjectMocks MetadataServiceImpl metadataService; // 删掉
// 在 @BeforeEach 或直接改为：
MetadataServiceImpl metadataService = new MetadataServiceImpl(
    sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper, List.of());
```

**B. 所有 `new MetadataServiceImpl(sceneMapper, metricDef, ruleDef, ruleVersionMapper)` 4参构造**，全部加 `, List.of()` 变5参。

**C. 断言类型从 `ConditionTypeMeta` 改为 `OperatorSpec`：**
- 原 `resp.conditionTypes().get(0).code()` 不变（OperatorSpec 也有 code()）。
- 原 `assertThat(resp.conditionTypes()).isNotEmpty()` 不变。
- 若有 `.displayName()` / `.paramsSchema()` 断言，改为 `.displayName()` / `.requiredParamKeys()`。

**D. 新增自定义 OperatorSpec 用例：**
```java
@Test
void getSceneMetadata_customSpecBean_appearsInConditionTypes() {
    // given
    when(sceneMapper.findByCode(any(), any())).thenReturn(minimalScene());
    when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of());
    OperatorSpec custom = OperatorSpec.builder()
            .code("geo.within").displayName("地理围栏")
            .requiredParamKeys(Set.of("lat", "lng", "radius"))
            .allowedDataTypes(Set.of())
            .requiresMetric(false).build();
    MetadataServiceImpl serviceWithCustom = new MetadataServiceImpl(
            sceneMapper, metricDefinitionMapper, ruleDefinitionMapper,
            ruleVersionMapper, List.of(custom));

    // when
    MetadataService.MetadataResponse resp =
            serviceWithCustom.getSceneMetadata("1", "PAYMENT");

    // then
    assertThat(resp.conditionTypes())
            .extracting(OperatorSpec::code)
            .contains("geo.within");
}

@Test
void getSceneMetadata_customSpec_doesNotOverrideBuiltin() {
    // 自定义 spec 与内置同 code(GT)，内置优先
    when(sceneMapper.findByCode(any(), any())).thenReturn(minimalScene());
    when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of());
    OperatorSpec fake = OperatorSpec.builder()
            .code("GT").displayName("fake").requiredParamKeys(Set.of())
            .allowedDataTypes(Set.of()).requiresMetric(false).build();
    MetadataServiceImpl serviceWithFake = new MetadataServiceImpl(
            sceneMapper, metricDefinitionMapper, ruleDefinitionMapper,
            ruleVersionMapper, List.of(fake));

    MetadataService.MetadataResponse resp =
            serviceWithFake.getSceneMetadata("1", "PAYMENT");

    OperatorSpec gt = resp.conditionTypes().stream()
            .filter(s -> "GT".equals(s.code())).findFirst().orElseThrow();
    assertThat(gt.displayName()).isEqualTo("大于"); // 内置值，不被覆盖
}
```

> `minimalScene()` 参考该测试类既有的 scene mock 辅助方法（Read 文件确认方法名）。

- [ ] **Step 3: 跑 config-svc 全量**

Run: `$MVN -pl rule-config-svc -am test`
Expected: 全绿。包含新增 2 个用例。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImplTest.java
git commit -m "feat(config): MetadataServiceImpl 合并自定义 @Bean OperatorSpec + 改返回 List<OperatorSpec>"
```

---

## Task 7: 全量 clean test

- [ ] **Step 1: 全量**

Run: `$MVN clean test`
Expected: 27 模块全绿。

---

## Self-Review

**Spec 覆盖：**
- §2.1 @ConditionType 升级 → T1 ✓
- §2.2 spec() 删除 → T4 ✓
- §2.3 19 evaluator 贴注解+删 spec() → T2(贴) + T4(删) ✓
- §2.4 catalog 改读注解 → T3 ✓
- §2.5 OperatorSpec Javadoc → T5 ✓
- §2.6 MetadataService 删 ConditionTypeMeta / 改 MetadataResponse → T5 ✓
- §2.7 MetadataServiceImpl customSpecs + 合并 → T6 ✓
- §2.8 自定义算子使用方式（@Bean OperatorSpec）→ T6 新增测试覆盖 ✓

**占位扫描：** T6 Step2D 中 `minimalScene()` 辅助方法名需 Read 确认——这是执行时的定位指引，非占位，因为给出了完整的"Read 文件确认"动作。T4 Step3 中"若有 spec 测试则删"是确定性动作，非模糊 hedge。

**类型一致性：** `OperatorSpec`（kernel api/operator）T2/T3/T5/T6 全程引用同一类型；`MetadataResponse(List<OperatorSpec>, List<MetricMeta>)` T5 定义，T6 使用一致；`ConditionType` 注解 T1 定义，T2 贴注解时引用字段名一致（`requiredParamKeys`/`allowedDataTypes`/`requiresMetric`）。
