# D36 Condition DSL 实现计划

> **Goal：** 在 `rule-sdk` 中新建 `Condition` 工厂类，提供链式 API 隐藏 `ConditionNode` / `AndNode` 的构造细节，降低代码定义规则的门槛。`Condition.toAst()` 生成标准 `AstNode`，与已有评估链路完全兼容。
>
> **决策依据：** D36（`00-decisions.md`）

---

## 影响范围

| 模块 | 文件 | 变更类型 |
|------|------|---------|
| `rule-sdk` | `Condition.java` | 新建 |
| `rule-sdk` | `ConditionTest.java` | 新建 |

**不改的**：`ConditionNode` / `AndNode` / `OrNode` / `NotNode` 等 record 定义，`EvalEngine`，任何服务端模块。

---

## Maven 环境

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## API 设计速查

```java
// 叶子条件（内置算子）
Condition.gt("amount", 1000)                  // amount > 1000
Condition.gte("amount", 1000)                 // amount >= 1000
Condition.lt("score", 60)                     // score < 60
Condition.lte("score", 60)                    // score <= 60
Condition.eq("status", "ACTIVE")              // status == ACTIVE
Condition.neq("status", "BLOCKED")            // status != BLOCKED
Condition.in("country", "CN", "HK")           // country IN [CN, HK]
Condition.notIn("country", "US")              // country NOT_IN [US]
Condition.between("age", 18, 65)              // age BETWEEN [18, 65]
Condition.contains("name", "corp")            // name 包含 "corp"
Condition.matches("email", ".*@corp\\.com")   // email 正则匹配
Condition.startsWith("code", "VIP")           // code 前缀
Condition.endsWith("code", "PRO")             // code 后缀

// 自定义算子（需配合 addEvaluator 注册）
Condition.of("BLACKLIST_HIT", "device_id", Map.of("list", blocklist))

// 恒真 / 恒假
Condition.always()   // 空 AND 节点，永远返回 true
Condition.never()    // 空 OR 节点，永远返回 false（无子节点时）

// 逻辑组合（链式）
Condition.gt("amount", 1000).and(Condition.in("country", "CN", "HK"))
Condition.gt("amount", 1000).or(Condition.eq("vip", true))
Condition.gt("amount", 1000).not()

// 转为 AstNode（供 RuleVersionSnapshot.builder().conditionAst() 使用）
AstNode ast = condition.toAst();
```

---

## Task 1：先写测试

新建 `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/ConditionTest.java`：

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionTest {

    @Test
    void gt_producesConditionNode() {
        AstNode ast = Condition.gt("amount", 1000).toAst();
        assertThat(ast).isInstanceOf(ConditionNode.class);
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("GT");
        assertThat(node.metricCode()).isEqualTo("amount");
        assertThat(node.params().get("threshold")).isEqualTo(1000);
        assertThat(node.weight()).isEqualTo(0.0);
        assertThat(node.displayLabel()).isNull();
    }

    @Test
    void in_producesConditionNodeWithValuesList() {
        AstNode ast = Condition.in("country", "CN", "HK").toAst();
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("IN");
        assertThat(node.params().get("values")).isEqualTo(List.of("CN", "HK"));
    }

    @Test
    void and_producesAndNode() {
        AstNode ast = Condition.gt("amount", 1000)
                .and(Condition.in("country", "CN", "HK"))
                .toAst();
        assertThat(ast).isInstanceOf(AndNode.class);
        AndNode and = (AndNode) ast;
        assertThat(and.children()).hasSize(2);
    }

    @Test
    void or_producesOrNode() {
        AstNode ast = Condition.gt("amount", 1000)
                .or(Condition.eq("vip", true))
                .toAst();
        assertThat(ast).isInstanceOf(OrNode.class);
        OrNode or = (OrNode) ast;
        assertThat(or.children()).hasSize(2);
    }

    @Test
    void not_producesNotNode() {
        AstNode ast = Condition.eq("blocked", true).not().toAst();
        assertThat(ast).isInstanceOf(NotNode.class);
        NotNode not = (NotNode) ast;
        assertThat(not.child()).isInstanceOf(ConditionNode.class);
    }

    @Test
    void always_producesEmptyAndNode() {
        AstNode ast = Condition.always().toAst();
        assertThat(ast).isInstanceOf(AndNode.class);
        assertThat(((AndNode) ast).children()).isEmpty();
    }

    @Test
    void of_customOperator_producesConditionNode() {
        AstNode ast = Condition.of("BLACKLIST_HIT", "device_id",
                Map.of("list", List.of("dev-001"))).toAst();
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("BLACKLIST_HIT");
        assertThat(node.params().get("list")).isEqualTo(List.of("dev-001"));
    }

    @Test
    void between_producesConditionNodeWithMinMax() {
        AstNode ast = Condition.between("age", 18, 65).toAst();
        ConditionNode node = (ConditionNode) ast;
        assertThat(node.conditionType()).isEqualTo("BETWEEN");
        assertThat(node.params().get("min")).isEqualTo(18);
        assertThat(node.params().get("max")).isEqualTo(65);
    }

    @Test
    void chained_andConditions_flattenedIntoOneAndNode() {
        // a AND b AND c 应生成一个 AND 节点含三子，不嵌套
        AstNode ast = Condition.gt("a", 1)
                .and(Condition.gt("b", 2))
                .and(Condition.gt("c", 3))
                .toAst();
        assertThat(ast).isInstanceOf(AndNode.class);
        assertThat(((AndNode) ast).children()).hasSize(3);
    }
}
```

运行确认编译失败：

```bash
$MVN -pl rule-sdk -am test -Dtest='ConditionTest' -Dsurefire.failIfNoSpecifiedTests=false
```

---

## Task 2：实现 `Condition` 类

新建 `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/Condition.java`：

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * DSL 工厂类，链式构造规则条件，隐藏 AST 节点构造细节。
 * toAst() 生成标准 AstNode，与 EvalEngine 评估链路完全兼容。
 */
public final class Condition {

    private final AstNode ast;

    private Condition(AstNode ast) {
        this.ast = ast;
    }

    /** 将条件转换为 AstNode，供 RuleVersionSnapshot.builder().conditionAst() 使用。 */
    public AstNode toAst() { return ast; }

    // ── 叶子条件工厂方法 ──────────────────────────────────────────────────────

    public static Condition gt(String metric, Object threshold) {
        return leaf("GT", metric, Map.of("threshold", threshold));
    }

    public static Condition gte(String metric, Object threshold) {
        return leaf("GTE", metric, Map.of("threshold", threshold));
    }

    public static Condition lt(String metric, Object threshold) {
        return leaf("LT", metric, Map.of("threshold", threshold));
    }

    public static Condition lte(String metric, Object threshold) {
        return leaf("LTE", metric, Map.of("threshold", threshold));
    }

    public static Condition eq(String metric, Object value) {
        return leaf("EQ", metric, Map.of("value", value));
    }

    public static Condition neq(String metric, Object value) {
        return leaf("NEQ", metric, Map.of("value", value));
    }

    public static Condition in(String metric, Object... values) {
        return leaf("IN", metric, Map.of("values", Arrays.asList(values)));
    }

    public static Condition notIn(String metric, Object... values) {
        return leaf("NOT_IN", metric, Map.of("values", Arrays.asList(values)));
    }

    public static Condition between(String metric, Object min, Object max) {
        return leaf("BETWEEN", metric, Map.of("min", min, "max", max));
    }

    public static Condition contains(String metric, String value) {
        return leaf("CONTAINS", metric, Map.of("value", value));
    }

    public static Condition matches(String metric, String pattern) {
        return leaf("MATCHES", metric, Map.of("pattern", pattern));
    }

    public static Condition startsWith(String metric, String value) {
        return leaf("STARTS_WITH", metric, Map.of("value", value));
    }

    public static Condition endsWith(String metric, String value) {
        return leaf("ENDS_WITH", metric, Map.of("value", value));
    }

    /** 自定义算子（需配合 RuleEngineClient.Builder.addEvaluator() 注册）。 */
    public static Condition of(String conditionType, String metric, Map<String, Object> params) {
        return leaf(conditionType, metric, params);
    }

    /** 恒真条件（空 AND 节点）。 */
    public static Condition always() {
        return new Condition(new AndNode(List.of(), null, null));
    }

    /** 恒假条件（空 OR 节点，无子节点时求值 false）。 */
    public static Condition never() {
        return new Condition(new OrNode(List.of(), null, null));
    }

    // ── 逻辑组合 ─────────────────────────────────────────────────────────────

    /** 与当前条件 AND 组合，同级多个 and() 调用会展平到同一 AndNode。 */
    public Condition and(Condition other) {
        List<AstNode> children = new ArrayList<>();
        // 展平：如果 this 已经是 AND 节点则展平其子节点
        if (ast instanceof AndNode and) {
            children.addAll(and.children());
        } else {
            children.add(ast);
        }
        // other 同理展平
        if (other.ast instanceof AndNode and) {
            children.addAll(and.children());
        } else {
            children.add(other.ast);
        }
        return new Condition(new AndNode(children, null, null));
    }

    /** 与当前条件 OR 组合，同级多个 or() 调用会展平到同一 OrNode。 */
    public Condition or(Condition other) {
        List<AstNode> children = new ArrayList<>();
        if (ast instanceof OrNode or) {
            children.addAll(or.children());
        } else {
            children.add(ast);
        }
        if (other.ast instanceof OrNode or) {
            children.addAll(or.children());
        } else {
            children.add(other.ast);
        }
        return new Condition(new OrNode(children, null, null));
    }

    /** 对当前条件取反，生成 NOT 节点。 */
    public Condition not() {
        return new Condition(new NotNode(ast));
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────────

    private static Condition leaf(String conditionType, String metric, Map<String, Object> params) {
        return new Condition(new ConditionNode(conditionType, metric, null, params, 0.0));
    }
}
```

---

## Task 3：运行测试，确认通过

```bash
$MVN -pl rule-sdk -am test -Dtest='ConditionTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，所有测试通过

---

## Task 4：运行 rule-sdk 全量测试

```bash
$MVN -pl rule-sdk -am test
```

---

## Task 5：全模块验证

```bash
$MVN test
```

---

## Task 6：Commit

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/Condition.java \
        rule-sdk/src/test/java/com/sstlfsj/rule/sdk/ConditionTest.java
git commit -m "feat(sdk): D36 Condition DSL——链式构造规则条件，隐藏 AST 构造细节"
```
