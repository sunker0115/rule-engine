# B19 类型化比较策略 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给条件比较算子引入基于 metric `dataType` 的类型化策略，消除当前"先试 number 再退 string"的运行时猜测，停止 `double` 精度损失与字符串被错误当数值比较的 bug；发布期将 `dataType` 冻结进 `ConditionNode`，并校验算子×dataType 兼容性。

**Architecture:** 在 `rule-kernel` 新建 `strategy/` 包，定义 `ComparisonStrategy` 接口及四个实现（Numeric/String/Boolean/Default），工厂 `ComparisonStrategyFactory` 按 dataType 返回缓存单例；10 个条件算子改为委托策略而非直接猜类型。在 `rule-config-svc` 新建 `AstDataTypeResolver` 遍历 AST 并冻结 dataType，`PublishService` 接线后将 resolvedAst 写入 `condition_ast` 字段。

**Tech Stack:** Java 21, JUnit 5, AssertJ, Mockito (`@ExtendWith(MockitoExtension.class)`), MyBatis-Plus `LambdaQueryWrapper`, Jackson record 序列化（无额外 Spring 上下文）

```
# 运行测试前设置 JDK 25 环境
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## 文件总览

### 新建（rule-kernel）

| 文件 | 职责 |
|------|------|
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategy.java` | 比较策略接口：`compare`/`equals` |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/NumericComparisonStrategy.java` | BigDecimal 内核：精确数值比较 |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/StringComparisonStrategy.java` | 字符串字典序比较 |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/BooleanComparisonStrategy.java` | 布尔相等；compare 抛 UnsupportedOperationException |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DefaultComparisonStrategy.java` | 按 actual 运行时 Java 类型推断 |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactory.java` | `forType(String)` 返回缓存单例 |
| `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/NumericComparisonStrategyTest.java` | Numeric 策略单测 |
| `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/StringComparisonStrategyTest.java` | String 策略单测 |
| `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/BooleanComparisonStrategyTest.java` | Boolean 策略单测 |
| `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DefaultComparisonStrategyTest.java` | Default 策略单测 |
| `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactoryTest.java` | 工厂路由单测 |

### 修改（rule-kernel）

| 文件 | 变更内容 |
|------|---------|
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNode.java` | 加第 6 组件 `String dataType` + 5 参便捷构造器 |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/AbstractNumericEvaluator.java` | 改用 `ComparisonStrategyFactory.forType(node.dataType()).compare(...)` |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluator.java` | 改用 `strategy.equals(...)` |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NeqEvaluator.java` | 改用 `!strategy.equals(...)` |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluator.java` | 改用两次 `strategy.compare(...)` |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NotBetweenEvaluator.java` | 改用两次 `strategy.compare(...)` |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/InEvaluator.java` | 改用 `strategy.equals(...)` |
| `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NotInEvaluator.java` | 改用 `!strategy.equals(...)` |

### 新建（rule-config-svc）

| 文件 | 职责 |
|------|------|
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java` | 递归遍历 AST 冻结 dataType + 兼容性校验 |
| `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolverTest.java` | resolver 单测 |

### 修改（rule-config-svc）

| 文件 | 变更内容 |
|------|---------|
| `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java` | 注入 `MetricDefinitionMapper`，发布流程加 resolve 步骤 |
| `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java` | 补 `@Mock MetricDefinitionMapper` + 新测试 |
| `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstSerializerTest.java` | 补 dataType round-trip + 缺字段 null 兼容测试 |

---

## Task 1：ConditionNode 加 dataType 字段（保证全仓编译）

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNode.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNodeTest.java`（已有，不新建；后续加 dataType 验证用例）

- [ ] **Step 1：写失败测试**

在已有 `ConditionNodeTest.java` 末尾添加以下两个测试（文件已有其他测试，只补新 case）：

```java
@Test
void sixArgConstructor_dataTypePreserved() {
    ConditionNode node = new ConditionNode("EQ", "amount", null, Map.of(), 0.0, "LONG");
    assertThat(node.dataType()).isEqualTo("LONG");
}

@Test
void fiveArgConstructor_dataTypeIsNull() {
    ConditionNode node = new ConditionNode("EQ", "amount", null, Map.of(), 0.0);
    assertThat(node.dataType()).isNull();
}
```

- [ ] **Step 2：运行测试，确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -Dtest=ConditionNodeTest -q 2>&1 | tail -20
```

预期：编译失败（`ConditionNode` 无 6 参构造器）或测试失败。

- [ ] **Step 3：修改 ConditionNode**

将 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNode.java` 全文替换为：

```java
package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.Map;

/**
 * 叶子节点：持有具体条件类型标识及参数，由对应 ConditionEvaluator 求值。
 * dataType 由发布期 AstDataTypeResolver 冻结（LONG/DOUBLE/STRING/BOOLEAN/LIST）；
 * DSL 构造时为 null，求值期走 DefaultComparisonStrategy 按值推断。
 */
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params,
        /** 评分卡权重；AST_BOOLEAN kind 时忽略，SCORECARD kind 时由 ScorecardExecutor 累加。 */
        Double weight,
        /** 发布期冻结的 metric 数据类型（LONG/DOUBLE/STRING/BOOLEAN/LIST）；DSL 路径为 null。 */
        String dataType
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
    }

    /** 未声明类型的构造入口（DSL、DecisionTableExecutor 合成节点等），dataType=null（走 Default 策略）。 */
    public ConditionNode(String conditionType, String metricCode, String displayLabel,
                         Map<String, Object> params, Double weight) {
        this(conditionType, metricCode, displayLabel, params, weight, null);
    }
}
```

- [ ] **Step 4：运行全模块测试，确认通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -q 2>&1 | tail -20
```

预期：`BUILD SUCCESS`（所有现有测试靠 5 参便捷构造器兼容，新两个 case 通过）。

- [ ] **Step 5：commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNode.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNodeTest.java
git commit -m "feat(ast): ConditionNode 加 dataType 字段 + 5 参便捷构造器（B19）"
```

---

## Task 2：ComparisonStrategy 接口 + 策略实现

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategy.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/NumericComparisonStrategy.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/StringComparisonStrategy.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/BooleanComparisonStrategy.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DefaultComparisonStrategy.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/NumericComparisonStrategyTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/StringComparisonStrategyTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/BooleanComparisonStrategyTest.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DefaultComparisonStrategyTest.java`

- [ ] **Step 1：写四个策略的失败测试**

新建 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/NumericComparisonStrategyTest.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class NumericComparisonStrategyTest {

    private final NumericComparisonStrategy strategy = new NumericComparisonStrategy();

    @Test
    void compare_integerEqual_returnsZero() {
        assertThat(strategy.compare(100, 100)).isEqualTo(0);
    }

    @Test
    void compare_integerLess_returnsNegative() {
        assertThat(strategy.compare(99, 100)).isLessThan(0);
    }

    @Test
    void compare_integerGreater_returnsPositive() {
        assertThat(strategy.compare(101, 100)).isGreaterThan(0);
    }

    @Test
    void equals_bigDecimalScaleDifference_returnsTrue() {
        // 50000.00 与 50000 应视为相等（BigDecimal.compareTo，不用 equals）
        assertThat(strategy.equals(new BigDecimal("50000.00"), new BigDecimal("50000"))).isTrue();
    }

    @Test
    void equals_largeLong_noDoublePrecisionLoss() {
        // 9007199254740993 超过 double 精度边界，double 会丢失精度
        long a = 9007199254740993L;
        long b = 9007199254740994L;
        assertThat(strategy.equals(a, b)).isFalse();
    }

    @Test
    void equals_numericStringAndInt_returnsTrue() {
        // 数值路径：字符串 "100" 转 BigDecimal 后与 Integer 100 相等
        // （STRING 类型下 "0100"≠"100" 的 bug 修复由 StringComparisonStrategy 负责，不在本策略）
        assertThat(strategy.equals("100", 100)).isTrue();
    }

    @Test
    void compare_nan_returnsSentinel() {
        // Double.NaN 无法转 BigDecimal -> toBigDecimal 返回 null -> compare 返回哨兵 Integer.MAX_VALUE
        // （调用方 AbstractNumericEvaluator 据此判 false），equals 返回 false
        assertThat(strategy.compare(Double.NaN, 1.0)).isEqualTo(Integer.MAX_VALUE);
        assertThat(strategy.equals(Double.NaN, 1.0)).isFalse();
    }

    @Test
    void infinity_equalsFalse_compareSentinel() {
        assertThat(strategy.equals(Double.POSITIVE_INFINITY, 1.0)).isFalse();
        assertThat(strategy.compare(Double.POSITIVE_INFINITY, 1.0)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void nullActual_equalsFalse_compareSentinel() {
        // null -> toBigDecimal 返回 null -> equals false、compare 哨兵
        assertThat(strategy.equals(null, 100)).isFalse();
        assertThat(strategy.compare(null, 100)).isEqualTo(Integer.MAX_VALUE);
    }
}
```

新建 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/StringComparisonStrategyTest.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringComparisonStrategyTest {

    private final StringComparisonStrategy strategy = new StringComparisonStrategy();

    @Test
    void equals_sameString_returnsTrue() {
        assertThat(strategy.equals("ACTIVE", "ACTIVE")).isTrue();
    }

    @Test
    void equals_differentString_returnsFalse() {
        assertThat(strategy.equals("ACTIVE", "INACTIVE")).isFalse();
    }

    @Test
    void equals_zero100_notEqualTo_100() {
        // STRING 类型："0100" 不等于 "100"（关键 bug 修复验证）
        assertThat(strategy.equals("0100", "100")).isFalse();
    }

    @Test
    void compare_lexicographicOrder() {
        // "abc" < "abd"
        assertThat(strategy.compare("abc", "abd")).isLessThan(0);
        assertThat(strategy.compare("abd", "abc")).isGreaterThan(0);
        assertThat(strategy.compare("abc", "abc")).isEqualTo(0);
    }

    @Test
    void compare_numericStringLexicographic() {
        // 字符串路径下 "100" vs "99"：字典序 "1" < "9"，所以 "100" < "99"
        assertThat(strategy.compare("100", "99")).isLessThan(0);
    }
}
```

新建 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/BooleanComparisonStrategyTest.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BooleanComparisonStrategyTest {

    private final BooleanComparisonStrategy strategy = new BooleanComparisonStrategy();

    @Test
    void equals_trueAndStringTrue_returnsTrue() {
        assertThat(strategy.equals(true, "true")).isTrue();
    }

    @Test
    void equals_trueAndStringFalse_returnsFalse() {
        assertThat(strategy.equals(true, "false")).isFalse();
    }

    @Test
    void equals_falseAndFalse_returnsTrue() {
        assertThat(strategy.equals(false, false)).isTrue();
    }

    @Test
    void equals_stringTrueAndStringTrue_returnsTrue() {
        assertThat(strategy.equals("true", "true")).isTrue();
    }

    @Test
    void compare_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> strategy.compare(true, false))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

新建 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DefaultComparisonStrategyTest.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultComparisonStrategyTest {

    private final DefaultComparisonStrategy strategy = new DefaultComparisonStrategy();

    @Test
    void equals_integerActual_usesNumericPath() {
        // actual 是 Number -> 走数值路径，100 == 100
        assertThat(strategy.equals(100, 100)).isTrue();
    }

    @Test
    void equals_doubleActual_usesNumericPath() {
        assertThat(strategy.equals(99.5, 99.5)).isTrue();
    }

    @Test
    void equals_booleanActual_usesBooleanPath() {
        assertThat(strategy.equals(true, "true")).isTrue();
        assertThat(strategy.equals(false, true)).isFalse();
    }

    @Test
    void equals_stringActual_usesStringPath() {
        // actual 是 String -> 走字符串路径
        assertThat(strategy.equals("0100", "100")).isFalse();
        assertThat(strategy.equals("ACTIVE", "ACTIVE")).isTrue();
    }

    @Test
    void compare_numberActual_usesNumericPath() {
        assertThat(strategy.compare(50, 100)).isLessThan(0);
        assertThat(strategy.compare(100, 50)).isGreaterThan(0);
    }

    @Test
    void compare_stringActual_usesStringPath() {
        assertThat(strategy.compare("abc", "abd")).isLessThan(0);
    }
}
```

- [ ] **Step 2：运行测试，确认编译失败（类不存在）**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test-compile -q 2>&1 | tail -20
```

预期：编译错误，找不到 `NumericComparisonStrategy` 等类。

- [ ] **Step 3：新建 ComparisonStrategy 接口**

新建 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategy.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 类型化比较策略接口。
 * 实现类针对特定 dataType 执行精确比较，策略无状态、可共享单例。
 */
public interface ComparisonStrategy {

    /**
     * 对 actual 与 operand 进行排序比较，返回负数/零/正数（约定同 Comparable）。
     * 不支持排序的类型（如 Boolean）抛 {@link UnsupportedOperationException}。
     *
     * @param actual  指标实际值
     * @param operand 条件操作数
     * @return 负数表示 actual < operand，0 表示相等，正数表示 actual > operand
     */
    int compare(Object actual, Object operand);

    /**
     * 判断 actual 与 operand 是否相等（类型语义下的相等）。
     *
     * @param actual  指标实际值
     * @param operand 条件操作数
     * @return 相等返回 true
     */
    boolean equals(Object actual, Object operand);
}
```

- [ ] **Step 4：新建 NumericComparisonStrategy**

新建 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/NumericComparisonStrategy.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import java.math.BigDecimal;

/**
 * 数值比较策略：内核使用 BigDecimal，避免 double 精度丢失。
 * 适用于 dataType=LONG 和 dataType=DOUBLE。
 * null、NaN、Infinity 无法转换时：compare 返回哨兵值 Integer.MAX_VALUE，equals 返回 false。
 */
class NumericComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        BigDecimal a = toBigDecimal(actual);
        BigDecimal b = toBigDecimal(operand);
        if (a == null || b == null) return Integer.MAX_VALUE;
        return a.compareTo(b);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        BigDecimal a = toBigDecimal(actual);
        BigDecimal b = toBigDecimal(operand);
        if (a == null || b == null) return false;
        // compareTo==0 忽略 scale（50000.00 == 50000），不用 BigDecimal.equals
        return a.compareTo(b) == 0;
    }

    /**
     * 将 Object 转为 BigDecimal：Number 走 toString 路径（保留精度），
     * String 直接 new BigDecimal(s)；NaN/Infinity 的 toString 无法解析 -> 返回 null。
     */
    static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                // Double.NaN / Infinity 的 toString 不可解析
                return null;
            }
        }
        if (o instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
```

- [ ] **Step 5：新建 StringComparisonStrategy**

新建 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/StringComparisonStrategy.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 字符串比较策略：两侧均 String.valueOf 后按字典序比较。
 * 适用于 dataType=STRING。
 */
class StringComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        return String.valueOf(actual).compareTo(String.valueOf(operand));
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        return String.valueOf(actual).equals(String.valueOf(operand));
    }
}
```

- [ ] **Step 6：新建 BooleanComparisonStrategy**

新建 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/BooleanComparisonStrategy.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 布尔比较策略：支持 Boolean 对象及字符串形式（"true"/"false"）的相等判定。
 * 布尔值无序，compare 方法不支持，调用时抛 {@link UnsupportedOperationException}。
 * 适用于 dataType=BOOLEAN。
 */
class BooleanComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        throw new UnsupportedOperationException("BOOLEAN 类型不支持排序比较（compare）");
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        return toBoolean(actual) == toBoolean(operand);
    }

    /** 将 Object 转为基础类型 boolean：Boolean 直取，String 走 Boolean.parseBoolean，其余为 false。 */
    private static boolean toBoolean(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }
}
```

- [ ] **Step 7：新建 DefaultComparisonStrategy**

新建 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/DefaultComparisonStrategy.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 默认比较策略：dataType 未声明（null）时，按 actual 运行时 Java 类型推断。
 * 推断顺序：BigDecimal -> Number -> Boolean -> String（顺序敏感，不可调换）。
 * 适用于 DSL 构造的节点（dataType=null）及 LIST/UNKNOWN dataType。
 */
class DefaultComparisonStrategy implements ComparisonStrategy {

    private static final NumericComparisonStrategy NUMERIC = new NumericComparisonStrategy();
    private static final StringComparisonStrategy STRING = new StringComparisonStrategy();
    private static final BooleanComparisonStrategy BOOLEAN = new BooleanComparisonStrategy();

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof java.math.BigDecimal || actual instanceof Number) {
            return NUMERIC.compare(actual, operand);
        }
        if (actual instanceof Boolean) {
            return BOOLEAN.compare(actual, operand); // 抛 UnsupportedOperationException
        }
        return STRING.compare(actual, operand);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        if (actual instanceof java.math.BigDecimal || actual instanceof Number) {
            return NUMERIC.equals(actual, operand);
        }
        if (actual instanceof Boolean) {
            return BOOLEAN.equals(actual, operand);
        }
        return STRING.equals(actual, operand);
    }
}
```

- [ ] **Step 8：运行策略单测，确认通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test \
  -Dtest="NumericComparisonStrategyTest,StringComparisonStrategyTest,BooleanComparisonStrategyTest,DefaultComparisonStrategyTest" \
  -q 2>&1 | tail -20
```

预期：`BUILD SUCCESS`，4 个测试类全通过。

- [ ] **Step 9：commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/
git commit -m "feat(strategy): 新建 ComparisonStrategy 接口及 Numeric/String/Boolean/Default 四个实现（B19）"
```

---

## Task 3：ComparisonStrategyFactory

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactory.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactoryTest.java`

- [ ] **Step 1：写失败测试**

新建 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactoryTest.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonStrategyFactoryTest {

    @Test
    void forType_long_returnsNumeric() {
        assertThat(ComparisonStrategyFactory.forType("LONG"))
                .isInstanceOf(NumericComparisonStrategy.class);
    }

    @Test
    void forType_double_returnsNumeric() {
        assertThat(ComparisonStrategyFactory.forType("DOUBLE"))
                .isInstanceOf(NumericComparisonStrategy.class);
    }

    @Test
    void forType_string_returnsString() {
        assertThat(ComparisonStrategyFactory.forType("STRING"))
                .isInstanceOf(StringComparisonStrategy.class);
    }

    @Test
    void forType_boolean_returnsBoolean() {
        assertThat(ComparisonStrategyFactory.forType("BOOLEAN"))
                .isInstanceOf(BooleanComparisonStrategy.class);
    }

    @Test
    void forType_null_returnsDefault() {
        assertThat(ComparisonStrategyFactory.forType(null))
                .isInstanceOf(DefaultComparisonStrategy.class);
    }

    @Test
    void forType_list_returnsDefault() {
        assertThat(ComparisonStrategyFactory.forType("LIST"))
                .isInstanceOf(DefaultComparisonStrategy.class);
    }

    @Test
    void forType_unknown_returnsDefault() {
        assertThat(ComparisonStrategyFactory.forType("UNKNOWN"))
                .isInstanceOf(DefaultComparisonStrategy.class);
    }

    @Test
    void forType_returnsCachedSingleton() {
        // 相同 dataType 调用两次，返回同一实例（缓存单例，零分配）
        assertThat(ComparisonStrategyFactory.forType("LONG"))
                .isSameAs(ComparisonStrategyFactory.forType("LONG"));
        assertThat(ComparisonStrategyFactory.forType(null))
                .isSameAs(ComparisonStrategyFactory.forType(null));
    }
}
```

- [ ] **Step 2：运行测试，确认编译失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test-compile -q 2>&1 | tail -10
```

预期：编译错误（`ComparisonStrategyFactory` 不存在）。

- [ ] **Step 3：新建 ComparisonStrategyFactory**

新建 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactory.java`：

```java
package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 比较策略工厂：按 dataType 返回缓存单例，零额外分配。
 * LONG/DOUBLE -> Numeric；STRING -> String；BOOLEAN -> Boolean；
 * null/LIST/UNKNOWN/其他未知 -> Default。
 */
public final class ComparisonStrategyFactory {

    private static final NumericComparisonStrategy NUMERIC   = new NumericComparisonStrategy();
    private static final StringComparisonStrategy  STRING    = new StringComparisonStrategy();
    private static final BooleanComparisonStrategy BOOLEAN   = new BooleanComparisonStrategy();
    private static final DefaultComparisonStrategy DEFAULT   = new DefaultComparisonStrategy();

    private ComparisonStrategyFactory() {}

    /**
     * 根据 metric 的 dataType 返回对应策略单例。
     *
     * @param dataType metric_definition.data_type 的值（LONG/DOUBLE/STRING/BOOLEAN/LIST/null）
     * @return 对应的 ComparisonStrategy 单例
     */
    public static ComparisonStrategy forType(String dataType) {
        if (dataType == null) return DEFAULT;
        return switch (dataType) {
            case "LONG", "DOUBLE"  -> NUMERIC;
            case "STRING"          -> STRING;
            case "BOOLEAN"         -> BOOLEAN;
            default                -> DEFAULT;  // LIST/UNKNOWN/其他未知
        };
    }
}
```

- [ ] **Step 4：运行测试，确认通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -Dtest=ComparisonStrategyFactoryTest -q 2>&1 | tail -10
```

预期：`BUILD SUCCESS`。

- [ ] **Step 5：commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactory.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/strategy/ComparisonStrategyFactoryTest.java
git commit -m "feat(strategy): ComparisonStrategyFactory 按 dataType 路由到缓存单例（B19）"
```

---

## Task 4：改造 AbstractNumericEvaluator（GT/GTE/LT/LTE）

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/AbstractNumericEvaluator.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/AbstractNumericEvaluatorTest.java`（已有，补 dataType 路由 case）
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/GtEvaluatorTest.java`（已有，补 dataType 路由 case）

说明：GT/GTE/LT/LTE 继承 AbstractNumericEvaluator，不需要单独修改四个子类文件；子类只有一行 `accept` 方法，与之前的 `compare(int cmp)` 改名保持一致即可。

- [ ] **Step 1：在 GtEvaluatorTest 中添加失败测试**

打开 `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/GtEvaluatorTest.java`，在文件末尾（测试类 `}` 之前）添加：

```java
    @Test
    void gt_withDataTypeLong_usesBigDecimalPrecision() {
        // dataType=LONG 时走 Numeric 策略（BigDecimal），大整数精度不丢失
        GtEvaluator ev = new GtEvaluator();
        long bigVal = 9007199254740994L;
        long bigThreshold = 9007199254740993L;
        ConditionNode node = new ConditionNode("GT", "id", null,
                Map.of("threshold", bigThreshold), 0.0, "LONG");
        EvalContext ctx = ctxWith("id", bigVal);
        assertThat(ev.evaluate(node, ctx)).isTrue();
    }

    @Test
    void gt_withDataTypeNull_fallsBackToDefault() {
        // dataType=null 走 Default，Number 实际值按数值路径，100 > 50 => true
        GtEvaluator ev = new GtEvaluator();
        ConditionNode node = new ConditionNode("GT", "score", null,
                Map.of("threshold", 50), 0.0, null);
        EvalContext ctx = ctxWith("score", 100);
        assertThat(ev.evaluate(node, ctx)).isTrue();
    }
```

注意：`GtEvaluatorTest` 中需要一个 `ctxWith` 辅助方法。查看现有文件是否有类似的工具方法，如果没有则补充：

```java
    private EvalContext ctxWith(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                java.time.Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event,
                new com.sstlfsj.rule.kernel.api.model.Subject("sub1",
                        com.sstlfsj.rule.kernel.api.model.SubjectType.USER, Map.of()),
                Map.of(metric, new com.sstlfsj.rule.kernel.api.model.MetricValue(value, "UNKNOWN", "PROVIDED")));
    }
```

（如果现有 GtEvaluatorTest 已有类似辅助，不必重复添加，直接复用。）

- [ ] **Step 2：运行测试，确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -Dtest=GtEvaluatorTest -q 2>&1 | tail -20
```

预期：新增的 `gt_withDataTypeLong_usesBigDecimalPrecision` 测试失败（大整数精度损失，当前 double 比较误判为相等）。

- [ ] **Step 3：改造 AbstractNumericEvaluator**

将 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/AbstractNumericEvaluator.java` 全文替换为：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

/**
 * 数值比较算子基类（GT/GTE/LT/LTE 继承）。
 * 委托 {@link ComparisonStrategyFactory} 按 node.dataType() 选策略，
 * 子类实现 {@link #accept(int)} 方法解读 compare 符号结果。
 * 指标值或阈值缺失/无法比较时返回 false（不抛异常）。
 */
abstract class AbstractNumericEvaluator implements ConditionEvaluator {

    /**
     * 根据 actual.compare(threshold) 的结果决定条件是否成立。
     *
     * @param cmp 策略 compare 返回值：负数/零/正数
     * @return 条件是否满足
     */
    protected abstract boolean accept(int cmp);

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(node.dataType());
        int cmp = strategy.compare(mv.value(), threshold);
        // compare 返回 Integer.MAX_VALUE 表示转换失败（null/NaN/Infinity），视为 false
        if (cmp == Integer.MAX_VALUE) return false;
        return accept(cmp);
    }
}
```

- [ ] **Step 4：运行全模块测试，确认通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -q 2>&1 | tail -20
```

预期：`BUILD SUCCESS`。

注意：GtEvaluator 的 `compare(int cmp)` 方法现在名称变为了 `accept(int cmp)`，要同步修改 GtEvaluator、GteEvaluator、LtEvaluator、LteEvaluator 四个文件，将方法名改过来（内容完全不变，只改方法名从 `compare` -> `accept`，`@Override` 注解保留）：

`GtEvaluator.java` 修改后：
```java
package com.sstlfsj.rule.kernel.internal.condition;

/** GT（大于）条件算子：actual > threshold。 */
public class GtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp > 0; }
}
```

`GteEvaluator.java` 修改后（当前是 `compare(int cmp)` -> 改为 `accept`）：
```java
package com.sstlfsj.rule.kernel.internal.condition;

/** GTE（大于等于）条件算子：actual >= threshold。 */
public class GteEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp >= 0; }
}
```

`LtEvaluator.java` 修改后：
```java
package com.sstlfsj.rule.kernel.internal.condition;

/** LT（小于）条件算子：actual < threshold。 */
public class LtEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp < 0; }
}
```

`LteEvaluator.java` 修改后：
```java
package com.sstlfsj.rule.kernel.internal.condition;

/** LTE（小于等于）条件算子：actual <= threshold。 */
public class LteEvaluator extends AbstractNumericEvaluator {
    @Override
    protected boolean accept(int cmp) { return cmp <= 0; }
}
```

- [ ] **Step 5：确认全测通过后 commit**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -q 2>&1 | tail -10
```

预期：`BUILD SUCCESS`。

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/AbstractNumericEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/GtEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/GteEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/LtEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/LteEvaluator.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/GtEvaluatorTest.java
git commit -m "refactor(evaluator): AbstractNumericEvaluator 委托 ComparisonStrategy，GT/GTE/LT/LTE 方法名 compare->accept（B19）"
```

---

## Task 5：改造 EqEvaluator 和 NeqEvaluator

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NeqEvaluator.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluatorTest.java`（补 dataType 路由 case）

- [ ] **Step 1：在 EqEvaluatorTest 中添加失败测试**

在 `EqEvaluatorTest.java` 末尾（测试类 `}` 之前）添加：

```java
    @Test
    void eq_stringDataType_zeroPrefix_notEqualTo100() {
        // STRING dataType：字符串 "0100" 不等于 "100"（关键 bug 修复）
        EqEvaluator ev = new EqEvaluator();
        ConditionNode node = new ConditionNode("EQ", "code", null,
                Map.of("threshold", "100"), 0.0, "STRING");
        EvalContext ctx = ctx("code", "0100");
        assertThat(ev.evaluate(node, ctx)).isFalse();
    }

    @Test
    void eq_longDataType_bigInteger_notEqualWhenDifferent() {
        // LONG dataType：大整数精确比较，不走 double
        EqEvaluator ev = new EqEvaluator();
        long a = 9007199254740993L;
        long b = 9007199254740994L;
        ConditionNode node = new ConditionNode("EQ", "id", null,
                Map.of("threshold", b), 0.0, "LONG");
        EvalContext ctx = ctx("id", a);
        assertThat(ev.evaluate(node, ctx)).isFalse();
    }

    @Test
    void eq_booleanDataType_trueEqualsStringTrue() {
        // BOOLEAN dataType：true 等于字符串 "true"
        EqEvaluator ev = new EqEvaluator();
        ConditionNode node = new ConditionNode("EQ", "flag", null,
                Map.of("threshold", "true"), 0.0, "BOOLEAN");
        EvalContext ctx = ctx("flag", true);
        assertThat(ev.evaluate(node, ctx)).isTrue();
    }
```

- [ ] **Step 2：运行测试，确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -Dtest=EqEvaluatorTest -q 2>&1 | tail -20
```

预期：新增的三个测试失败（当前 EqEvaluator 不读 dataType）。

- [ ] **Step 3：改造 EqEvaluator**

将 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluator.java` 全文替换为：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

/**
 * EQ（等于）条件算子：按 node.dataType() 选策略后调用 strategy.equals()。
 * dataType=null（DSL）时走 DefaultComparisonStrategy，按 actual 运行时类型推断。
 */
public class EqEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;
        return ComparisonStrategyFactory.forType(node.dataType()).equals(mv.value(), threshold);
    }
}
```

- [ ] **Step 4：改造 NeqEvaluator**

将 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NeqEvaluator.java` 全文替换为：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

/**
 * NEQ（不等于）条件算子：按 node.dataType() 选策略后取 equals 的非。
 * dataType=null（DSL）时走 DefaultComparisonStrategy，按 actual 运行时类型推断。
 */
public class NeqEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object threshold = node.params().get("threshold");
        if (threshold == null) return false;
        return !ComparisonStrategyFactory.forType(node.dataType()).equals(mv.value(), threshold);
    }
}
```

- [ ] **Step 5：运行测试，确认通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -Dtest="EqEvaluatorTest,NeqEvaluatorTest" -q 2>&1 | tail -10
```

预期：`BUILD SUCCESS`。

- [ ] **Step 6：commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NeqEvaluator.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/EqEvaluatorTest.java
git commit -m "refactor(evaluator): EqEvaluator/NeqEvaluator 委托 ComparisonStrategy（B19）"
```

---

## Task 6：改造 BetweenEvaluator、NotBetweenEvaluator、InEvaluator、NotInEvaluator

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NotBetweenEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/InEvaluator.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NotInEvaluator.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluatorTest.java`（补 case）
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/InEvaluatorTest.java`（补 dataType 路由 case）
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/NotInEvaluatorTest.java`（补 dataType 路由 case，不改现有 null 语义 case）

- [ ] **Step 1：在 BetweenEvaluatorTest 中添加失败测试**

在 `BetweenEvaluatorTest.java` 末尾（测试类 `}` 之前）添加：

```java
    @Test
    void between_longDataType_bigDecimalPrecision() {
        // LONG dataType 时走 BigDecimal 精确比较，不走 double
        BetweenEvaluator ev = new BetweenEvaluator();
        // 50000.00 == 50000（scale 不同，BigDecimal.compareTo 视为相等）
        ConditionNode node = new ConditionNode("BETWEEN", "amount", null,
                Map.of("min", new java.math.BigDecimal("50000.00"), "max", 60000), 0.0, "LONG");
        EvalContext ctx = ctxWith("amount", 50000);
        assertThat(ev.evaluate(node, ctx)).isTrue();
    }
```

（`ctxWith` 辅助方法：与上面 GtEvaluatorTest 中的一致，如果现有测试文件已有请直接复用。）

在 `InEvaluatorTest.java` 末尾（测试类 `}` 之前）添加：

```java
    @Test
    void in_stringDataType_zeroPrefix_notMatchPlain100() {
        // STRING dataType：列表 ["100"]，actual="0100" 不命中
        InEvaluator ev = new InEvaluator();
        ConditionNode node = new ConditionNode("IN", "code", null,
                Map.of("values", java.util.List.of("100")), 0.0, "STRING");
        EvalContext ctx = ctxWith("code", "0100");
        assertThat(ev.evaluate(node, ctx)).isFalse();
    }

    @Test
    void in_longDataType_numericMatch() {
        // LONG dataType：列表 [100, 200]，actual=100L 命中
        InEvaluator ev = new InEvaluator();
        ConditionNode node = new ConditionNode("IN", "score", null,
                Map.of("values", java.util.List.of(100, 200)), 0.0, "LONG");
        EvalContext ctx = ctxWith("score", 100L);
        assertThat(ev.evaluate(node, ctx)).isTrue();
    }
```

在 `NotInEvaluatorTest.java` 末尾（测试类 `}` 之前）添加（不改现有 null 语义 case；null 语义遵循 03-rule-expression §3.2 不变）：

```java
    @Test
    void notIn_stringDataType_zeroPrefix_notMatchPlain100_returnsTrue() {
        // STRING dataType："0100" 不在列表 ["100"] 中 -> true
        NotInEvaluator ev = new NotInEvaluator();
        ConditionNode node = new ConditionNode("NOT_IN", "code", null,
                Map.of("values", java.util.List.of("100")), 0.0, "STRING");
        EvalContext ctx = ctxWith("code", "0100");
        assertThat(ev.evaluate(node, ctx)).isTrue();
    }

    @Test
    void notIn_stringDataType_exactMatch_returnsFalse() {
        // STRING dataType："100" 在列表 ["100"] 中 -> false
        NotInEvaluator ev = new NotInEvaluator();
        ConditionNode node = new ConditionNode("NOT_IN", "code", null,
                Map.of("values", java.util.List.of("100")), 0.0, "STRING");
        EvalContext ctx = ctxWith("code", "100");
        assertThat(ev.evaluate(node, ctx)).isFalse();
    }
```

- [ ] **Step 2：运行测试，确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -Dtest="BetweenEvaluatorTest,InEvaluatorTest,NotInEvaluatorTest" -q 2>&1 | tail -20
```

预期：新增的 dataType 路由 case 失败；现有 null 语义 case（`metricMissing_returnsTrue` 等）保持通过不受影响。

- [ ] **Step 3：改造 BetweenEvaluator**

将 `BetweenEvaluator.java` 全文替换为：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

/**
 * BETWEEN 条件算子：min <= actual <= max（双端闭区间）。
 * params 格式：{"min": ..., "max": ...}
 * 按 node.dataType() 选策略做两次 compare。
 */
public class BetweenEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object min = node.params().get("min");
        Object max = node.params().get("max");
        if (min == null || max == null) return false;
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(node.dataType());
        int cmpMin = strategy.compare(mv.value(), min);
        int cmpMax = strategy.compare(mv.value(), max);
        // compare 返回 MAX_VALUE 表示转换失败，视为不满足
        if (cmpMin == Integer.MAX_VALUE || cmpMax == Integer.MAX_VALUE) return false;
        return cmpMin >= 0 && cmpMax <= 0;
    }
}
```

- [ ] **Step 4：改造 NotBetweenEvaluator**

将 `NotBetweenEvaluator.java` 全文替换为：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

/**
 * NOT_BETWEEN 条件算子：actual &lt; min 或 actual &gt; max（BETWEEN 取反）。
 * params 格式：{"min": ..., "max": ...}
 * 按 node.dataType() 选策略做两次 compare。
 */
public class NotBetweenEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object min = node.params().get("min");
        Object max = node.params().get("max");
        if (min == null || max == null) return false;
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(node.dataType());
        int cmpMin = strategy.compare(mv.value(), min);
        int cmpMax = strategy.compare(mv.value(), max);
        if (cmpMin == Integer.MAX_VALUE || cmpMax == Integer.MAX_VALUE) return false;
        return cmpMin < 0 || cmpMax > 0;
    }
}
```

- [ ] **Step 5：改造 InEvaluator**

将 `InEvaluator.java` 全文替换为：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

import java.util.Collection;

/**
 * IN 条件算子：actual 在 params.values 列表中（按 node.dataType() 选策略做 equals 判定）。
 * params 格式：{"values": ["v1","v2",...]}
 */
public class InEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object valuesObj = node.params().get("values");
        if (!(valuesObj instanceof Collection<?> values)) return false;
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(node.dataType());
        return values.stream().anyMatch(v -> strategy.equals(mv.value(), v));
    }
}
```

- [ ] **Step 6：改造 NotInEvaluator**

将 `NotInEvaluator.java` 全文替换为：

```java
package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategy;
import com.sstlfsj.rule.kernel.internal.condition.strategy.ComparisonStrategyFactory;

import java.util.Collection;

/**
 * NOT_IN 条件算子：actual 不在 params.values 列表中（按 node.dataType() 选策略做 equals 判定）。
 * params 格式：{"values": ["v1","v2",...]}
 * null 语义遵循 03-rule-expression §3.2 不变：指标缺失时返回 true；values 不是 Collection 时返回 true。
 */
public class NotInEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return true;
        Object valuesObj = node.params().get("values");
        if (!(valuesObj instanceof Collection<?> values)) return true;
        ComparisonStrategy strategy = ComparisonStrategyFactory.forType(node.dataType());
        return values.stream().noneMatch(v -> strategy.equals(mv.value(), v));
    }
}
```

- [ ] **Step 7：运行全模块测试，确认通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -q 2>&1 | tail -20
```

预期：`BUILD SUCCESS`，包括 `NotInEvaluatorTest` 中 `metricMissing_returnsTrue` 等现有 null 语义 case 保持不变。

- [ ] **Step 8：commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NotBetweenEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/InEvaluator.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/condition/NotInEvaluator.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/BetweenEvaluatorTest.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/InEvaluatorTest.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/condition/NotInEvaluatorTest.java
git commit -m "refactor(evaluator): BETWEEN/NOT_BETWEEN/IN/NOT_IN 委托 ComparisonStrategy（B19）"
```

---

## Task 7：rule-kernel 全测 + AstSerializer dataType round-trip

**Files:**
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstSerializerTest.java`（补 dataType case）

- [ ] **Step 1：在 AstSerializerTest 中添加 dataType round-trip 测试**

在 `AstSerializerTest.java` 末尾（测试类 `}` 之前）添加：

```java
    @Test
    void conditionNode_withDataType_roundTrip() {
        // dataType 字段进出 JSON 完整保留
        ConditionNode node = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 100), 0.0, "LONG");

        String json = serializer.toJson(node);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(ConditionNode.class);
        assertThat(((ConditionNode) restored).dataType()).isEqualTo("LONG");
    }

    @Test
    void conditionNode_missingDataTypeField_deserializesToNull() {
        // 缺 dataType 字段的 JSON（如旧格式）反序列化时 dataType 为 null，不抛异常
        String json = "{\"type\":\"ConditionNode\",\"conditionType\":\"GT\","
                + "\"metricCode\":\"amount\",\"params\":{\"threshold\":100},\"weight\":0.0}";
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(ConditionNode.class);
        assertThat(((ConditionNode) restored).dataType()).isNull();
    }
```

- [ ] **Step 2：运行 rule-kernel 全测 + AstSerializerTest**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -q 2>&1 | tail -10
$MVN -pl rule-config-svc -am test -Dtest=AstSerializerTest -q 2>&1 | tail -10
```

预期：两次均 `BUILD SUCCESS`。如果 `conditionNode_missingDataTypeField_deserializesToNull` 失败，检查 Jackson 对 record 的处理方式：record 的 canonical 构造器中 `dataType` 缺失时 Jackson 是否自动补 null。如果 Jackson 报错（不接受缺字段），需要在 `ConditionNode` 上加 `@JsonCreator` + `@JsonProperty` 注解并声明 `dataType` 有 `@JsonProperty` 默认值（见下方备注）。

> 备注：Jackson 对 record 的处理取决于版本。当前项目已有 record round-trip 测试通过，可以先跑测试看是否失败。如果失败，在 `ConditionNode` record 的正则构造器参数上加 `@JsonProperty` 并对 `dataType` 使用 `defaultValue`，或改用 `@JsonCreator` + 单独的参数注解（参照项目中 `AstNode` 的 Jackson 配置方式）。

- [ ] **Step 3：commit**

```bash
git add rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstSerializerTest.java
git commit -m "test(serializer): 补 ConditionNode.dataType round-trip 及缺字段容错测试（B19）"
```

---

## Task 8：新建 AstDataTypeResolver

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolverTest.java`

- [ ] **Step 1：写失败测试**

新建 `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolverTest.java`：

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AstDataTypeResolverTest {

    // ── 基础冻结 ──────────────────────────────────────────────────────────────

    @Test
    void resolve_conditionNode_freezesDataType() {
        ConditionNode cond = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 100), 0.0);
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap);

        assertThat(result).isInstanceOf(ConditionNode.class);
        assertThat(((ConditionNode) result).dataType()).isEqualTo("LONG");
    }

    @Test
    void resolve_conditionNode_metricNotInMap_dataTypeRemainsNull() {
        ConditionNode cond = new ConditionNode("GT", "unknown_metric", null,
                Map.of("threshold", 100), 0.0);
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap);

        // 查不到的 metric -> 跳过冻结，dataType=null（不报错）
        assertThat(((ConditionNode) result).dataType()).isNull();
    }

    @Test
    void resolve_andNode_recursivelyFreezesChildren() {
        AndNode and = new AndNode(List.of(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 100), 0.0),
                new ConditionNode("EQ", "status", null, Map.of("threshold", "ACTIVE"), 0.0)
        ), null, null);
        Map<String, String> typeMap = Map.of("amount", "LONG", "status", "STRING");

        AstNode result = AstDataTypeResolver.resolve(and, typeMap);

        assertThat(result).isInstanceOf(AndNode.class);
        List<AstNode> children = ((AndNode) result).children();
        assertThat(((ConditionNode) children.get(0)).dataType()).isEqualTo("LONG");
        assertThat(((ConditionNode) children.get(1)).dataType()).isEqualTo("STRING");
    }

    @Test
    void resolve_notNode_recursivelyFreezesChild() {
        NotNode not = new NotNode(new ConditionNode("EQ", "flag", null,
                Map.of("threshold", "true"), 0.0));
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        AstNode result = AstDataTypeResolver.resolve(not, typeMap);

        assertThat(result).isInstanceOf(NotNode.class);
        assertThat(((ConditionNode)((NotNode) result).child()).dataType()).isEqualTo("BOOLEAN");
    }

    @Test
    void resolve_orNode_recursivelyFreezesChildren() {
        OrNode or = new OrNode(List.of(
                new ConditionNode("EQ", "type", null, Map.of("threshold", "A"), 0.0)
        ), null, null);
        Map<String, String> typeMap = Map.of("type", "STRING");

        AstNode result = AstDataTypeResolver.resolve(or, typeMap);

        assertThat(result).isInstanceOf(OrNode.class);
        assertThat(((ConditionNode)((OrNode) result).children().get(0)).dataType())
                .isEqualTo("STRING");
    }

    @Test
    void resolve_xorNode_recursivelyFreezesChildren() {
        XorNode xor = new XorNode(List.of(
                new ConditionNode("EQ", "code", null, Map.of("threshold", "A"), 0.0)
        ), null);
        Map<String, String> typeMap = Map.of("code", "STRING");

        AstNode result = AstDataTypeResolver.resolve(xor, typeMap);

        assertThat(result).isInstanceOf(XorNode.class);
        assertThat(((ConditionNode)((XorNode) result).children().get(0)).dataType())
                .isEqualTo("STRING");
    }

    @Test
    void resolve_scorecardRootNode_freezesLeafDataTypes() {
        ScorecardRootNode sc = new ScorecardRootNode(List.of(
                new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4)
        ), 0.6);
        Map<String, String> typeMap = Map.of("score", "DOUBLE");

        AstNode result = AstDataTypeResolver.resolve(sc, typeMap);

        assertThat(result).isInstanceOf(ScorecardRootNode.class);
        assertThat(((ScorecardRootNode) result).conditions().get(0).dataType())
                .isEqualTo("DOUBLE");
    }

    @Test
    void resolve_ifNode_recursivelyFreezesConditionAndBranches() {
        IfNode ifn = new IfNode(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0),
                new DecisionLeafNode("BLOCK", "HIGH"),
                new DecisionLeafNode("PASS", "LOW")
        );
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(ifn, typeMap);

        assertThat(result).isInstanceOf(IfNode.class);
        IfNode resolved = (IfNode) result;
        assertThat(((ConditionNode) resolved.condition()).dataType()).isEqualTo("LONG");
        // DecisionLeafNode 原样返回（无 dataType 概念）
        assertThat(resolved.thenBranch()).isInstanceOf(DecisionLeafNode.class);
    }

    @Test
    void resolve_decisionTableNode_returnedAsIs() {
        // B19 不冻结决策表列的 dataType，DecisionTableNode 原样返回
        DecisionTableNode dt = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT")),
                List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK"))
        );
        Map<String, String> typeMap = Map.of("amount", "LONG");

        AstNode result = AstDataTypeResolver.resolve(dt, typeMap);

        assertThat(result).isSameAs(dt);
    }

    // ── 兼容性校验 ────────────────────────────────────────────────────────────

    @Test
    void resolve_gtWithBoolean_throwsIllegalArgument() {
        // GT 不允许 BOOLEAN dataType -> 发布期报错
        ConditionNode cond = new ConditionNode("GT", "flag", null,
                Map.of("threshold", "true"), 0.0);
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT")
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    void resolve_inWithBoolean_throwsIllegalArgument() {
        // IN 允许 LONG/STRING，BOOLEAN 不在允许集 -> 报错
        ConditionNode cond = new ConditionNode("IN", "flag", null,
                Map.of("values", List.of("true")), 0.0);
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        assertThatThrownBy(() -> AstDataTypeResolver.resolve(cond, typeMap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IN")
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    void resolve_eqWithBoolean_ok() {
        // EQ 允许 BOOLEAN -> 不报错
        ConditionNode cond = new ConditionNode("EQ", "flag", null,
                Map.of("threshold", "true"), 0.0);
        Map<String, String> typeMap = Map.of("flag", "BOOLEAN");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap);
        assertThat(((ConditionNode) result).dataType()).isEqualTo("BOOLEAN");
    }

    @Test
    void resolve_dataTypeNull_skipsCompatibilityCheck() {
        // metric 查不到（dataType=null）-> 跳过校验，不报错
        ConditionNode cond = new ConditionNode("GT", "unknown", null,
                Map.of("threshold", 100), 0.0);
        // 不在 typeMap 里
        AstNode result = AstDataTypeResolver.resolve(cond, Map.of());
        assertThat(((ConditionNode) result).dataType()).isNull();
    }

    @Test
    void resolve_dataTypeList_skipsCompatibilityCheck() {
        // LIST dataType 跳过校验（CONTAINS/NOT_CONTAINS 自洽，B19 不做矩阵校验）
        ConditionNode cond = new ConditionNode("CONTAINS", "tags", null,
                Map.of("value", "vip"), 0.0);
        Map<String, String> typeMap = Map.of("tags", "LIST");

        AstNode result = AstDataTypeResolver.resolve(cond, typeMap);
        assertThat(((ConditionNode) result).dataType()).isEqualTo("LIST");
    }
}
```

- [ ] **Step 2：运行测试，确认编译失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am test-compile -q 2>&1 | tail -10
```

预期：编译错误（`AstDataTypeResolver` 不存在）。

- [ ] **Step 3：新建 AstDataTypeResolver**

新建 `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java`：

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发布期 AST 遍历器：给每个 ConditionNode 冻结 dataType，并校验算子×dataType 兼容性。
 * 遍历方式仿 MetricDependencyCollector，但需重建不可变 record 树（ConditionNode 新增 dataType）。
 * DecisionTableNode 原样返回——B19 不冻结决策表列的 dataType（已知边界，留后续）。
 */
class AstDataTypeResolver {

    // 算子允许的 dataType 集合（权威来源：spec §5 / 03-rule-expression §3.1-3.4）
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "EQ",            Set.of("LONG", "DOUBLE", "STRING", "BOOLEAN"),
            "NEQ",           Set.of("LONG", "DOUBLE", "STRING", "BOOLEAN"),
            "GT",            Set.of("LONG", "DOUBLE"),
            "GTE",           Set.of("LONG", "DOUBLE"),
            "LT",            Set.of("LONG", "DOUBLE"),
            "LTE",           Set.of("LONG", "DOUBLE"),
            "BETWEEN",       Set.of("LONG", "DOUBLE"),
            "NOT_BETWEEN",   Set.of("LONG", "DOUBLE"),
            "IN",            Set.of("LONG", "STRING"),
            "NOT_IN",        Set.of("LONG", "STRING")
    );

    /**
     * 递归遍历 AST，给 ConditionNode 冻结 dataType 并校验算子兼容性，返回重建的新树。
     *
     * @param root        原始 AST 根节点
     * @param dataTypeMap metricCode -> dataType 映射（来自 metric_definition 查询结果）
     * @return 含冻结 dataType 的新 AST 树（不可变 record 重建）
     */
    static AstNode resolve(AstNode root, Map<String, String> dataTypeMap) {
        return switch (root) {
            case ConditionNode cond -> resolveCondition(cond, dataTypeMap);
            case AndNode and -> new AndNode(
                    resolveList(and.children(), dataTypeMap),
                    and.displayLabel(), and.weight());
            case OrNode or -> new OrNode(
                    resolveList(or.children(), dataTypeMap),
                    or.displayLabel(), or.weight());
            case NotNode not -> new NotNode(resolve(not.child(), dataTypeMap));
            case XorNode xor -> new XorNode(
                    resolveList(xor.children(), dataTypeMap),
                    xor.displayLabel());
            case ScorecardRootNode sc -> new ScorecardRootNode(
                    resolveConditionList(sc.conditions(), dataTypeMap),
                    sc.threshold());
            case IfNode ifn -> new IfNode(
                    resolve(ifn.condition(), dataTypeMap),
                    resolve(ifn.thenBranch(), dataTypeMap),
                    ifn.elseBranch() != null ? resolve(ifn.elseBranch(), dataTypeMap) : null);
            // DecisionLeafNode/DecisionTableNode 原样返回（B19 不处理）
            case DecisionLeafNode leaf -> leaf;
            case DecisionTableNode dt  -> dt;
        };
    }

    private static ConditionNode resolveCondition(ConditionNode cond,
                                                   Map<String, String> dataTypeMap) {
        String dataType = dataTypeMap.get(cond.metricCode());
        // 校验仅在 dataType 已知且不是 LIST/null 时执行
        if (dataType != null && !"LIST".equals(dataType)) {
            Set<String> allowed = ALLOWED.get(cond.conditionType());
            if (allowed != null && !allowed.contains(dataType)) {
                throw new IllegalArgumentException(
                        "算子 " + cond.conditionType() + " 不支持 dataType=" + dataType
                        + "（metric=" + cond.metricCode() + "）");
            }
        }
        // 重建 ConditionNode，冻结 dataType（查不到的 metric -> dataType=null，原样不变）
        return new ConditionNode(cond.conditionType(), cond.metricCode(),
                cond.displayLabel(), cond.params(), cond.weight(), dataType);
    }

    private static List<AstNode> resolveList(List<AstNode> nodes,
                                              Map<String, String> dataTypeMap) {
        return nodes.stream().map(n -> resolve(n, dataTypeMap)).toList();
    }

    private static List<ConditionNode> resolveConditionList(List<ConditionNode> nodes,
                                                             Map<String, String> dataTypeMap) {
        return nodes.stream().map(n -> resolveCondition(n, dataTypeMap)).toList();
    }
}
```

- [ ] **Step 4：运行 resolver 测试，确认通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am test -Dtest=AstDataTypeResolverTest -q 2>&1 | tail -20
```

预期：`BUILD SUCCESS`。

- [ ] **Step 5：commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolverTest.java
git commit -m "feat(publish): 新建 AstDataTypeResolver，发布期冻结 dataType + 算子兼容性校验（B19）"
```

---

## Task 9：接线 PublishService

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`

- [ ] **Step 1：在 PublishServiceTest 中添加失败测试**

在 `PublishServiceTest.java` 中：

1. 在字段声明区（其他 `@Mock` 之后）添加：
```java
    @Mock MetricDefinitionMapper metricDefinitionMapper;
```

2. 在测试类末尾（已有测试之后、`}` 之前）添加以下测试：

```java
    @Test
    void publish_freezesDataTypeInConditionAst() {
        // 发布后 condition_ast 里的 ConditionNode 应含 dataType
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        // AST: GT 算子，metricCode="amount"
        ConditionNode fakeAst = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 100), 0.0);
        when(astSerializer.fromJson(anyString())).thenReturn(fakeAst);

        // metric_definition 返回 amount -> LONG
        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("amount");
        md.setDataType("LONG");
        when(metricDefinitionMapper.selectList(any()))
                .thenReturn(java.util.List.of(md));

        publishService.publish(1L, 10L, "op");

        // 验证 conditionAst 写入的是 resolvedAst（含 dataType），而非 draft 原始 JSON
        ArgumentCaptor<RuleVersion> rvCaptor = ArgumentCaptor.forClass(RuleVersion.class);
        verify(ruleVersionMapper).insert(rvCaptor.capture());
        // astSerializer.toJson 被调用一次（写 resolvedAst）
        verify(astSerializer).toJson(argThat(node ->
                node instanceof ConditionNode c && "LONG".equals(c.dataType())));
    }

    @Test
    void publish_incompatibleOperatorDataType_throwsIllegalArgument() {
        // GT 算子但 metric dataType=BOOLEAN -> 发布期报错
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);

        ConditionNode badAst = new ConditionNode("GT", "flag", null,
                Map.of("threshold", "true"), 0.0);
        when(astSerializer.fromJson(anyString())).thenReturn(badAst);

        MetricDefinition md = new MetricDefinition();
        md.setMetricCode("flag");
        md.setDataType("BOOLEAN");
        when(metricDefinitionMapper.selectList(any()))
                .thenReturn(java.util.List.of(md));

        assertThatThrownBy(() -> publishService.publish(1L, 10L, "op"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GT")
                .hasMessageContaining("BOOLEAN");
    }
```

- [ ] **Step 2：运行测试，确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am test -Dtest=PublishServiceTest -q 2>&1 | tail -20
```

预期：`publish_freezesDataTypeInConditionAst` 和 `publish_incompatibleOperatorDataType_throwsIllegalArgument` 失败（`PublishService` 未接线 `metricDefinitionMapper`）。

- [ ] **Step 3：修改 PublishService**

在 `PublishService.java` 中做如下修改（外科式，只动必须动的地方）：

**3a. 添加 import（在已有 import 块末尾）：**
```java
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
```

（注意：`LambdaQueryWrapper` 和 `List` 已有 import，不要重复；只加 `MetricDefinition`、`MetricDefinitionMapper`，以及 `Collectors`。）

**3b. 加字段声明（在 `private final ObjectMapper objectMapper;` 之后）：**
```java
    private final MetricDefinitionMapper metricDefinitionMapper;
```

**3c. 构造器加参数**（将原 8 参构造器改为 9 参，在 `ObjectMapper objectMapper` 之后加）：

原构造器签名：
```java
    public PublishService(RuleDefinitionMapper ruleDefinitionMapper,
                          SceneMapper sceneMapper,
                          RuleVersionMapper ruleVersionMapper,
                          DecisionDefinitionMapper decisionDefinitionMapper,
                          AuditLogMapper auditLogMapper,
                          ApplicationEventPublisher eventPublisher,
                          AstSerializer astSerializer,
                          ObjectMapper objectMapper) {
```

改为：
```java
    public PublishService(RuleDefinitionMapper ruleDefinitionMapper,
                          SceneMapper sceneMapper,
                          RuleVersionMapper ruleVersionMapper,
                          DecisionDefinitionMapper decisionDefinitionMapper,
                          AuditLogMapper auditLogMapper,
                          ApplicationEventPublisher eventPublisher,
                          AstSerializer astSerializer,
                          ObjectMapper objectMapper,
                          MetricDefinitionMapper metricDefinitionMapper) {
```

构造器体末尾加：
```java
        this.metricDefinitionMapper = metricDefinitionMapper;
```

**3d. publish 方法中，在第 4 步（反序列化 AST）之后、第 5 步（计算版本号）之前，插入 resolve 步骤：**

找到注释 `// 4. 反序列化 AST，收集 metricDependencies` 所在的代码块，原代码是：
```java
        AstNode ast = astSerializer.fromJson(draftVersion.getConditionAst());
        // ... kind 校验 ...
        List<String> metricDeps = MetricDependencyCollector.collect(ast);
```

修改为（在 `List<String> metricDeps = ...` 之后、`// 5.` 注释之前插入）：

```java
        // 4.5. 查 metric dataType，冻结进 AST（B19）
        AstNode resolvedAst = ast;
        if (!metricDeps.isEmpty()) {
            List<MetricDefinition> metricDefs = metricDefinitionMapper.selectList(
                    new LambdaQueryWrapper<MetricDefinition>()
                            .eq(MetricDefinition::getTenantId, tenantId)
                            .in(MetricDefinition::getMetricCode, metricDeps));
            Map<String, String> dataTypeMap = metricDefs.stream()
                    .collect(Collectors.toMap(
                            MetricDefinition::getMetricCode,
                            MetricDefinition::getDataType));
            resolvedAst = AstDataTypeResolver.resolve(ast, dataTypeMap);
        }
```

**3e. 将 INSERT rule_version 时的 conditionAst 从 draft 原始 JSON 改为 resolved JSON：**

找到：
```java
        newRv.setConditionAst(draftVersion.getConditionAst());
```

改为：
```java
        newRv.setConditionAst(astSerializer.toJson(resolvedAst));
```

**3f. 将 snapshot 构建时传入的 ast 改为 resolvedAst：**

找到：
```java
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                newRv.getId(),
                scene.getCode(),
                String.valueOf(tenantId),
                ast,
```

改为：
```java
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(
                newRv.getId(),
                scene.getCode(),
                String.valueOf(tenantId),
                resolvedAst,
```

- [ ] **Step 4：运行 rule-config-svc 全测，确认通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am test -q 2>&1 | tail -20
```

预期：`BUILD SUCCESS`。

> 如果 `publish_draftRule_createsVersionAndUpdatesDefinition` 等旧测试失败，原因是 `metricDefinitionMapper.selectList` 被调用但未 stub。在这些测试的 `when(astSerializer.fromJson(...)).thenReturn(fakeAst)` 之后添加：
> ```java
> when(metricDefinitionMapper.selectList(any())).thenReturn(java.util.List.of());
> ```

- [ ] **Step 5：commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java
git commit -m "feat(publish): PublishService 接线 MetricDefinitionMapper，发布期冻结 dataType（B19）"
```

---

## Task 10：全量验证

**Files:** 无新增文件，验证所有模块测试通过。

- [ ] **Step 1：rule-kernel 全测**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test 2>&1 | tail -30
```

预期：`BUILD SUCCESS`，所有测试通过，无 FAILURE/ERROR。

- [ ] **Step 2：rule-config-svc 全测**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am test 2>&1 | tail -30
```

预期：`BUILD SUCCESS`，所有测试通过。

- [ ] **Step 3：commit 收尾（如有未提交的测试修复）**

```bash
git status
# 若有残余测试修复未提交，按文件名显式 git add（禁用 git add -A / git add -p）
# 例：git add rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java
git commit -m "test(b19): 修复全量测试残余问题"
```

---

## 自检（计划完成后对照 spec 检查）

| spec 要求 | 对应 Task | 状态 |
|-----------|----------|------|
| ComparisonStrategy 接口 `compare`/`equals` | Task 2 | ✓ |
| NumericComparisonStrategy：BigDecimal 内核，`new BigDecimal(n.toString())`，NaN/Infinity→false，scale 不敏感 | Task 2 | ✓ |
| StringComparisonStrategy：字典序 compare，String.equals | Task 2 | ✓ |
| BooleanComparisonStrategy：equals 比布尔，compare 抛 UnsupportedOperationException | Task 2 | ✓ |
| DefaultComparisonStrategy：BigDecimal→Numeric、Number→Numeric、Boolean→Boolean、否则 String（顺序敏感） | Task 2 | ✓ |
| ComparisonStrategyFactory：final class + 私有构造器 + static forType + 缓存单例 | Task 3 | ✓ |
| ConditionNode 加第 6 组件 `dataType` + 5 参便捷构造器 | Task 1 | ✓ |
| AbstractNumericEvaluator 改模板方法 `accept(int cmp)` 委托策略 | Task 4 | ✓ |
| GT/GTE/LT/LTE 方法名 compare → accept | Task 4 | ✓ |
| EQ/NEQ 走 `strategy.equals` | Task 5 | ✓ |
| BETWEEN/NOT_BETWEEN 走两次 compare，MAX_VALUE 哨兵 → false | Task 6 | ✓ |
| IN/NOT_IN 走 `anyMatch(strategy.equals)` | Task 6 | ✓ |
| 7 个算子（CONTAINS/NOT_CONTAINS/STARTS_WITH/ENDS_WITH/MATCHES/DATE_BEFORE/DATE_AFTER）保持原样 | 未修改 | ✓ |
| DecisionTableExecutor 合成 ConditionNode 用 5 参构造器，dataType=null 走 Default | 5 参构造器兼容 | ✓ |
| AstDataTypeResolver 递归遍历，重建不可变 tree | Task 8 | ✓ |
| DecisionTableNode 原样返回（B19 已知边界） | Task 8 | ✓ |
| 兼容性矩阵校验（仅 dataType 已知且 ∉ LIST/null 时） | Task 8 | ✓ |
| DATE_* 不校验（B20 留后续） | Task 8（ALLOWED 中无 DATE 算子） | ✓ |
| PublishService 注入 MetricDefinitionMapper，一次查出 dataTypeMap | Task 9 | ✓ |
| 发布后 conditionAst 存 resolvedAst（而非 draft 原始 JSON） | Task 9 | ✓ |
| snapshot 传 resolvedAst | Task 9 | ✓ |
| AstSerializer dataType round-trip + 缺字段 null 兼容 | Task 7 | ✓ |
| 注释全中文 Javadoc，禁 TODO/FIXME | 全 Task | ✓ |
| 错误处理：求值期缺值→false；发布期非法→IllegalArgumentException 中文消息 | Task 2/8/9 | ✓ |
