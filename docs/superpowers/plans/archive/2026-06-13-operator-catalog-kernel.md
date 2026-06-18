# OperatorCatalog 归位 kernel + SPI 自暴露 schema 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把算子规格(`OperatorSpec`)移进 kernel `api/operator`，让 19 个内置 evaluator 各自声明自身 spec（单一真相源），`ConditionTypeCatalog` 从硬编码 map 改为从 evaluators 收集，config-svc 消费者仅改 import。

**Architecture:** ① `OperatorSpec` record 放 kernel `api/operator`（纯数据，全模块可见）。② `ConditionEvaluator.spec()` 加 default 方法，19 个 evaluator override。③ `ConditionTypeCatalog` 移入 kernel `internal/condition`，靠 `KernelEvaluators.defaults()` + `ev.spec()` 动态构建。④ config-svc 的 ConditionParamValidator / AstDataTypeResolver / MetadataServiceImpl 改 import，行为零变。

**Tech Stack:** Java 25、Lombok @Builder、JUnit5 + AssertJ。前置：跑 Maven 前用 `mvn-env` skill 设 `$MVN`（JDK25）。

---

## 文件结构

**新建（kernel）:**
- `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/operator/OperatorSpec.java`
- `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalog.java`（重写）
- `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/operator/OperatorSpecTest.java`
- `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalogKernelTest.java`

**修改（kernel）:**
- `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ConditionParams.java`（加时间算子键）
- `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/condition/ConditionEvaluator.java`（加 `spec()` default）
- 19 个 evaluator（各自 override `spec()`）
- `TimeWindowEvaluator.java` / `OccurredAtEvaluator.java`（消魔法串）

**修改（config-svc）:**
- `ConditionParamValidator.java`（改 import）
- `AstDataTypeResolver.java`（改 import，`ConditionTypeCatalog.Spec` → `OperatorSpec`）
- `MetadataServiceImpl.java`（改 import）
- 删除 `rule-config-svc/.../publish/ConditionTypeCatalog.java`

---

## Task 1: ConditionParams 加时间算子键

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ConditionParams.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ConditionParamsTest.java`（已存在，追加）

- [ ] **Step 1: 追加测试（ConditionParamsTest）**

```java
@Test
void timeWindowKeys() {
    assertThat(ConditionParams.START).isEqualTo("start");
    assertThat(ConditionParams.END).isEqualTo("end");
    assertThat(ConditionParams.DATES_EXCLUDE).isEqualTo("datesExclude");
    assertThat(ConditionParams.DAYS_OF_WEEK).isEqualTo("daysOfWeek");
    assertThat(ConditionParams.OPERATOR).isEqualTo("operator");
    assertThat(ConditionParams.VALUE).isEqualTo("value");
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest='ConditionParamsTest#timeWindowKeys' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（常量不存在）。

- [ ] **Step 3: 实现**

在 `ConditionParams` 末尾加（`TIMEZONE` 之后）：

```java
    /** time.window 开始时间（HH:mm）。 */
    public static final String START         = "start";
    /** time.window / time.occurred_at 结束时间（HH:mm 或 ISO-8601）。 */
    public static final String END           = "end";
    /** time.window 排除日期列表（MM-DD 字符串数组，可选）。 */
    public static final String DATES_EXCLUDE = "datesExclude";
    /** time.window 生效星期（MON/TUE/... 字符串数组，可选）。 */
    public static final String DAYS_OF_WEEK  = "daysOfWeek";
    /** time.occurred_at 比较运算符（BEFORE / AFTER / BETWEEN）。 */
    public static final String OPERATOR      = "operator";
    /** time.occurred_at 单端比较目标值（ISO-8601 或 $now）。 */
    public static final String VALUE         = "value";
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-kernel -am test -Dtest='ConditionParamsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ConditionParams.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ConditionParamsTest.java
git commit -m "feat(kernel): ConditionParams 加时间算子键常量(START/END/DATES_EXCLUDE/DAYS_OF_WEEK/OPERATOR/VALUE)"
```

---

## Task 2: OperatorSpec（kernel `api/operator`）

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/operator/OperatorSpec.java`
- Create: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/operator/OperatorSpecTest.java`

- [ ] **Step 1: 写测试**

```java
package com.sstlfsj.rule.kernel.api.operator;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.DataType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorSpecTest {
    @Test
    void builder_roundTrip() {
        OperatorSpec spec = OperatorSpec.builder()
                .code("GT").displayName("大于")
                .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
                .allowedDataTypes(Set.of(DataType.LONG.tag()))
                .requiresMetric(true).build();
        assertThat(spec.code()).isEqualTo("GT");
        assertThat(spec.requiresMetric()).isTrue();
        assertThat(spec.requiredParamKeys()).containsExactly(ConditionParams.THRESHOLD);
    }
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest='OperatorSpecTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

```java
package com.sstlfsj.rule.kernel.api.operator;

import lombok.Builder;

import java.util.Set;

/**
 * 算子规格：必填 param 键 + 允许 dataType + 元数据，供发布期校验与元数据暴露使用。
 * 对标 Calcite {@code SqlOperandTypeChecker}，随算子实现携带（由 {@link
 * com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator#spec()} 声明）。
 *
 * @param code             conditionType 编码
 * @param displayName      运营可读名
 * @param requiredParamKeys 必填 param 键（{@link com.sstlfsj.rule.kernel.api.model.ConditionParams} 常量）
 * @param allowedDataTypes 允许的 metric/payload dataType（{@link com.sstlfsj.rule.kernel.api.model.DataType} tag）
 * @param requiresMetric   是否需要绑定 metric/payload 字段
 */
@Builder
public record OperatorSpec(String code, String displayName,
                           Set<String> requiredParamKeys,
                           Set<String> allowedDataTypes,
                           boolean requiresMetric) {}
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-kernel -am test -Dtest='OperatorSpecTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/operator/OperatorSpec.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/operator/OperatorSpecTest.java
git commit -m "feat(kernel): OperatorSpec record（算子规格，api/operator 包，@Builder）"
```

---

## Task 3: ConditionEvaluator.spec() 接口 default 方法

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/condition/ConditionEvaluator.java`

- [ ] **Step 1: 加 default 方法**

在接口末尾加（import `OperatorSpec` 和 `Optional`）：

```java
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import java.util.Optional;

// ...已有 evaluate() 方法不动，末尾追加：

    /**
     * 可选：算子声明自身规格，供发布期 param 键校验与元数据暴露使用。
     * 默认返回 {@link Optional#empty()}（= 发布期放行 + 元数据不可见），向后兼容。
     * 内置算子 override 此方法实现单一真相源；自定义 SPI 算子可 opt-in 声明。
     *
     * @return 算子规格；empty = 不声明（放行）
     */
    default Optional<OperatorSpec> spec() {
        return Optional.empty();
    }
```

- [ ] **Step 2: 编译确认（现有实现不 override = 返回 empty = 行为不变）**

Run: `$MVN -pl rule-kernel -am -DskipTests compile`
Expected: BUILD SUCCESS（default 方法，现有实现无需改动）。

- [ ] **Step 3: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/condition/ConditionEvaluator.java
git commit -m "feat(kernel): ConditionEvaluator.spec() default 方法（算子自描述 SPI 扩展点）"
```

---

## Task 4: 19 个 evaluator override spec()

每个 evaluator 在本文件里 override `spec()`，同时消魔法串（TimeWindow/OccurredAt 改用 `ConditionParams.*`）。

**19 个文件，全在 `rule-kernel/.../internal/condition/`：**
EqEvaluator / NeqEvaluator / GtEvaluator / GteEvaluator / LtEvaluator / LteEvaluator /
InEvaluator / NotInEvaluator / BetweenEvaluator / NotBetweenEvaluator /
ContainsEvaluator / NotContainsEvaluator / StartsWithEvaluator / EndsWithEvaluator /
MatchesEvaluator / DateBeforeEvaluator / DateAfterEvaluator / TimeWindowEvaluator / OccurredAtEvaluator

- [ ] **Step 1: 为每个 evaluator 追加 spec() override**

**重复模板（逐文件套用）：**

```java
// 所需 import（每个文件按需加）
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.DataType;
import java.util.Optional;
import java.util.Set;
```

**每个 evaluator 的 spec() 内容（完整列表）：**

```java
// EqEvaluator — EQ
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.EQ).displayName("等于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.DOUBLE.tag(),DataType.DECIMAL.tag(),
                    DataType.STRING.tag(),DataType.BOOLEAN.tag(),DataType.DATE.tag(),DataType.DATETIME.tag()))
            .requiresMetric(true).build());
}

// NeqEvaluator — NEQ（allowedDataTypes 同 EQ）
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.NEQ).displayName("不等于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.DOUBLE.tag(),DataType.DECIMAL.tag(),
                    DataType.STRING.tag(),DataType.BOOLEAN.tag(),DataType.DATE.tag(),DataType.DATETIME.tag()))
            .requiresMetric(true).build());
}

// GtEvaluator — GT
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.GT).displayName("大于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.DOUBLE.tag(),DataType.DECIMAL.tag()))
            .requiresMetric(true).build());
}

// GteEvaluator — GTE
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.GTE).displayName("大于等于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.DOUBLE.tag(),DataType.DECIMAL.tag()))
            .requiresMetric(true).build());
}

// LtEvaluator — LT
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.LT).displayName("小于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.DOUBLE.tag(),DataType.DECIMAL.tag()))
            .requiresMetric(true).build());
}

// LteEvaluator — LTE
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.LTE).displayName("小于等于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.DOUBLE.tag(),DataType.DECIMAL.tag()))
            .requiresMetric(true).build());
}

// InEvaluator — IN
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.IN).displayName("属于集合")
            .requiredParamKeys(Set.of(ConditionParams.VALUES))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.STRING.tag()))
            .requiresMetric(true).build());
}

// NotInEvaluator — NOT_IN
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.NOT_IN).displayName("不属于集合")
            .requiredParamKeys(Set.of(ConditionParams.VALUES))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.STRING.tag()))
            .requiresMetric(true).build());
}

// BetweenEvaluator — BETWEEN
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.BETWEEN).displayName("区间内")
            .requiredParamKeys(Set.of(ConditionParams.MIN,ConditionParams.MAX))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.DOUBLE.tag(),DataType.DECIMAL.tag(),
                    DataType.DATE.tag(),DataType.DATETIME.tag()))
            .requiresMetric(true).build());
}

// NotBetweenEvaluator — NOT_BETWEEN
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.NOT_BETWEEN).displayName("区间外")
            .requiredParamKeys(Set.of(ConditionParams.MIN,ConditionParams.MAX))
            .allowedDataTypes(Set.of(DataType.LONG.tag(),DataType.DOUBLE.tag(),DataType.DECIMAL.tag(),
                    DataType.DATE.tag(),DataType.DATETIME.tag()))
            .requiresMetric(true).build());
}

// ContainsEvaluator — CONTAINS
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.CONTAINS).displayName("集合包含")
            .requiredParamKeys(Set.of(ConditionParams.ELEMENT))
            .allowedDataTypes(Set.of(DataType.LIST.tag()))
            .requiresMetric(true).build());
}

// NotContainsEvaluator — NOT_CONTAINS
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.NOT_CONTAINS).displayName("集合不包含")
            .requiredParamKeys(Set.of(ConditionParams.ELEMENT))
            .allowedDataTypes(Set.of(DataType.LIST.tag()))
            .requiresMetric(true).build());
}

// StartsWithEvaluator — STARTS_WITH
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.STARTS_WITH).displayName("前缀匹配")
            .requiredParamKeys(Set.of(ConditionParams.PREFIX))
            .allowedDataTypes(Set.of(DataType.STRING.tag()))
            .requiresMetric(true).build());
}

// EndsWithEvaluator — ENDS_WITH
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.ENDS_WITH).displayName("后缀匹配")
            .requiredParamKeys(Set.of(ConditionParams.SUFFIX))
            .allowedDataTypes(Set.of(DataType.STRING.tag()))
            .requiresMetric(true).build());
}

// MatchesEvaluator — MATCHES
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.MATCHES).displayName("正则匹配")
            .requiredParamKeys(Set.of(ConditionParams.REGEX))
            .allowedDataTypes(Set.of(DataType.STRING.tag()))
            .requiresMetric(true).build());
}

// DateBeforeEvaluator — DATE_BEFORE
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.DATE_BEFORE).displayName("早于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.DATE.tag(),DataType.DATETIME.tag()))
            .requiresMetric(true).build());
}

// DateAfterEvaluator — DATE_AFTER
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.DATE_AFTER).displayName("晚于")
            .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
            .allowedDataTypes(Set.of(DataType.DATE.tag(),DataType.DATETIME.tag()))
            .requiresMetric(true).build());
}

// TimeWindowEvaluator — time.window（requiresMetric=false，无 allowedDataTypes）
// 同时把方法体内 params.get("start")/"end"/"datesExclude"/"daysOfWeek" 改用 ConditionParams.*
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.TIME_WINDOW).displayName("时间窗口")
            .requiredParamKeys(Set.of(ConditionParams.START, ConditionParams.END))
            .allowedDataTypes(Set.of())
            .requiresMetric(false).build());
}

// OccurredAtEvaluator — time.occurred_at（requiresMetric=false）
// 同时把 params.get("operator")/"value"/"start"/"end" 改用 ConditionParams.*
@Override public Optional<OperatorSpec> spec() {
    return Optional.of(OperatorSpec.builder().code(ConditionTypes.TIME_OCCURRED_AT).displayName("事件发生时间")
            .requiredParamKeys(Set.of(ConditionParams.OPERATOR))
            .allowedDataTypes(Set.of())
            .requiresMetric(false).build());
}
```

> **注意 TimeWindow/OccurredAt**：同时在 `evaluate()` 方法体内把所有 `params.get("start")`/`params.get("end")`/`params.get("datesExclude")`/`params.get("daysOfWeek")`/`params.get("operator")`/`params.get("value")` 替换为 `params.get(ConditionParams.START)` 等。既有行为不变，只消魔法串。

- [ ] **Step 2: 跑 kernel 全量**

Run: `$MVN -pl rule-kernel -am test`
Expected: 全绿（spec() 方法被 ConditionTypeCatalogKernelTest 之前会先编译正确，ConditionParamValidator/AstDataTypeResolver 在 config-svc 暂不受影响）。

- [ ] **Step 3: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/
git commit -m "feat(kernel): 19 个 evaluator override spec()（算子自描述单一真相源）+ 时间算子消魔法串"
```

---

## Task 5: ConditionTypeCatalog 移入 kernel，从 evaluators 动态构建

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalog.java`（全新实现）
- Create: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalogKernelTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionTypeCatalogKernelTest {

    @Test
    void spec_gt_requiredParamKeys() {
        OperatorSpec spec = ConditionTypeCatalog.spec(ConditionTypes.GT);
        assertThat(spec).isNotNull();
        assertThat(spec.requiredParamKeys()).containsExactly(ConditionParams.THRESHOLD);
    }

    @Test
    void all_covers19Operators() {
        assertThat(ConditionTypeCatalog.all()).hasSize(19);
    }

    @Test
    void spec_timeWindow_requiresMetricFalse() {
        OperatorSpec spec = ConditionTypeCatalog.spec(ConditionTypes.TIME_WINDOW);
        assertThat(spec).isNotNull();
        assertThat(spec.requiresMetric()).isFalse();
        assertThat(spec.requiredParamKeys()).contains(ConditionParams.START, ConditionParams.END);
    }

    @Test
    void spec_unknown_returnsNull() {
        assertThat(ConditionTypeCatalog.spec("CUSTOM_OP")).isNull();
    }
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest='ConditionTypeCatalogKernelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（kernel 中还没有 ConditionTypeCatalog）。

- [ ] **Step 3: 新建 ConditionTypeCatalog（kernel）**

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置条件算子目录：从 {@link KernelEvaluators#defaults()} 中每个 evaluator 的
 * {@link com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator#spec()} 动态构建，
 * 消除与 evaluator 实现的双重声明。算子契约与实现共同演进，不再需要外部维护硬编码列表。
 *
 * <p>对标 Apache Calcite {@code SqlOperatorTable}：算子目录由算子注册表驱动。
 * SPI 自定义算子 override {@code spec()} 即可获得发布期校验 + 元数据暴露。
 */
public final class ConditionTypeCatalog {

    private ConditionTypeCatalog() {}

    private static final Map<String, OperatorSpec> CATALOG = buildFromEvaluators();

    private static Map<String, OperatorSpec> buildFromEvaluators() {
        Map<String, OperatorSpec> m = new LinkedHashMap<>();
        KernelEvaluators.defaults().forEach((code, ev) ->
                ev.spec().ifPresent(spec -> m.put(code, spec)));
        return Map.copyOf(m);
    }

    /**
     * 查算子规格；目录缺席的 conditionType（SPI 自定义 / 未声明 spec）返回 null（放行）。
     *
     * @param conditionType 算子编码
     * @return 规格；null = 缺席放行
     */
    public static OperatorSpec spec(String conditionType) {
        return CATALOG.get(conditionType);
    }

    /** @return 全部已注册算子规格（保序），供 MetadataService 暴露算子目录 */
    public static Collection<OperatorSpec> all() {
        return CATALOG.values();
    }
}
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-kernel -am test -Dtest='ConditionTypeCatalogKernelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（19 个算子，GT spec 正确，time.window requiresMetric=false）。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalog.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/ConditionTypeCatalogKernelTest.java
git commit -m "feat(kernel): ConditionTypeCatalog 移入 kernel internal/condition，从 evaluators 动态构建"
```

---

## Task 6: config-svc 消费者改 import + 删旧 catalog

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConditionParamValidator.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java`
- Delete: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConditionTypeCatalog.java`

- [ ] **Step 1: 三处 import 替换**

**ConditionParamValidator.java** — 把：
```java
// 现有（隐式同包，无 import，直接用 ConditionTypeCatalog.Spec）
```
改为加 import：
```java
import com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
```
类型引用 `ConditionTypeCatalog.Spec` → `OperatorSpec`（两处：validateLeaf 里 `ConditionTypeCatalog.Spec spec = ...`）。

**AstDataTypeResolver.java** — 加 import，类型引用同上（两处 `ConditionTypeCatalog.Spec spec = ...`）：
```java
import com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
```

**MetadataServiceImpl.java** — 替换 import：
```java
// 删：import com.sstlfsj.rule.config.internal.publish.ConditionTypeCatalog;
// 加：
import com.sstlfsj.rule.kernel.internal.condition.ConditionTypeCatalog;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
```
`.stream().map(s -> ...)` 里的 `s`（lambda 变量）类型变为 `OperatorSpec`，但 lambda 无显式类型声明时无需改动；只改 `ConditionTypeCatalog.Spec` 显式引用（若有）。

- [ ] **Step 2: 删除旧 ConditionTypeCatalog**

```bash
git rm rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConditionTypeCatalog.java
```

若 config-svc 有 ConditionTypeCatalogTest，同步删除：
```bash
find rule-config-svc/src/test -name "ConditionTypeCatalogTest.java" -exec git rm {} \;
```

- [ ] **Step 3: config-svc 全量测试（验证行为零变）**

Run: `$MVN -pl rule-config-svc -am test`
Expected: 全绿（行为完全不变，仅 import 路径和类型名变化）。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/ConditionParamValidator.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java
git commit -m "refactor(config): ConditionTypeCatalog / OperatorSpec import 指向 kernel，删旧 config-svc catalog"
```

---

## Task 7: 全量 clean test

- [ ] **Step 1: 全量**

Run: `$MVN clean test`
Expected: 27 模块全绿。

---

## Self-Review

**Spec 覆盖：** §1 OperatorSpec→T2、§3 ConditionEvaluator.spec()→T3、§4 evaluator override→T4（19 个含时间算子消魔法串）、§5 catalog 移 kernel 动态构建→T5、§6 config-svc 改 import 删旧→T6、§3 新增时间算子 ConditionParams 键→T1。全覆盖。

**类型一致性：** `OperatorSpec` T2 定义，T3/T4 evaluators return `OperatorSpec`，T5 `ConditionTypeCatalog.spec()` 返回 `OperatorSpec`，T6 config-svc 改类型引用。签名链全一致。

**占位扫描：** Step 1 T6 的 import 替换含"若有"说明——Read 确认 MetadataServiceImpl stream lambda 无显式 Spec 类型声明（lambda 推断）后无需改，非占位。其余均完整代码。
