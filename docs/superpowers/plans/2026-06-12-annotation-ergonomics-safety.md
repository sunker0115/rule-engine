# Easy Rules 注解易用性与启动防呆 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 D61 注解规则补一组不动引擎的易用性与启动防呆增强(A/B/C/D/G/H + 类型转换)。

**Architecture:** 全部落在 `rule-sdk`(`FactResolver`/`Fact`/`Metric`/`AnnotatedRuleScanner`)与 `rule-sdk-spring-boot-starter`(`OnDecision`/`OnDecisionInvoker`/`AutoConfiguration`);引擎 `EvalEngine`/executor 一律不动。设计依据见 `docs/superpowers/specs/2026-06-12-annotation-ergonomics-safety-design.md`(D63)。

**Tech Stack:** Java 25、JUnit 5 + AssertJ、Spring Boot AutoConfiguration、Maven 多模块。

**前置:** 跑 Maven 前先用 `mvn-env` skill 设环境(JDK 25),命令形如 `$MVN -pl <module> -am test -Dtest=Xxx -Dsurefire.failIfNoSpecifiedTests=false`;跨模块改动必带 `-am`;整轮收尾用全量 `$MVN clean test`。注释/Javadoc 用中文,测试方法名用英文。

---

## 文件结构

**rule-sdk**(包 `com.sstlfsj.rule.sdk`)
- `annotation/Fact.java` — `value()` 改可选 + 加 `required()`/`defaultValue()`
- `annotation/Metric.java` — `value()` 改可选
- `MissingFactException.java`(新)— required 缺失异常
- `FactResolver.java` — 统一取名、嵌套路径、required/default、`validate`、`coerce` 扩展
- `source/AnnotatedRuleScanner.java` — 统一取名声明 metric 依赖 + 扫描期 `validate`

**rule-sdk-spring-boot-starter**(包 `com.sstlfsj.rule.sdk.starter`)
- `annotation/OnDecision.java`(实际在 rule-sdk 的 `com.sstlfsj.rule.sdk.annotation`)— 加 `async()`
- `OnDecisionInvoker.java` — 构造期 `validate`、`subscribedCodes()`、`async` executor 分支
- `RuleEngineClientAutoConfiguration.java` — orphan warn 交叉核对 + 注入 `onDecisionExecutor`

**rule-samples**(包 `com.sstlfsj.rule.samples.annotation`)
- `NestedOrderRule.java`(新)— C 嵌套路径示例
- `test/.../NestedOrderRuleIT.java`(新)— 嵌套路径端到端

---

## Task 1: coerce 扩展(String → 数值/Boolean/enum)

支撑 B 的 `defaultValue`(字面量 String→参数类型)与更宽的 payload 容错。

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverCoerceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.*;

class FactResolverCoerceTest {

    enum Color { RED, GREEN }

    static class Holder {
        public void m(Integer i, Long l, Boolean b, Color c) {}
    }

    private static Object coerce(Object v, Class<?> t) throws Exception {
        Method m = FactResolver.class.getDeclaredMethod("coerce", Object.class, Class.class);
        m.setAccessible(true);
        return m.invoke(null, v, t);
    }

    @Test
    void coerces_stringLiterals_toTargetTypes() throws Exception {
        assertThat(coerce("7", Integer.class)).isEqualTo(7);
        assertThat(coerce("9", Long.class)).isEqualTo(9L);
        assertThat(coerce("true", Boolean.class)).isEqualTo(true);
        assertThat(coerce("RED", Color.class)).isEqualTo(Color.RED);
    }

    @Test
    void invalidString_throwsIllegalArgument() {
        assertThatThrownBy(() -> coerce("notInt", Integer.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverCoerceTest`
Expected: FAIL(String→enum/Boolean 未支持,`coerce("RED", Color.class)` 返回原 String)

- [ ] **Step 3: 替换 `coerce` 方法**

把 `FactResolver.java` 现有 `coerce`(约 80-90 行)整体替换为:

```java
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Object v, Class<?> t) {
        if (v == null || t.isInstance(v)) return v;
        if (v instanceof Number n) {
            if (t == Integer.class || t == int.class)       return n.intValue();
            if (t == Long.class    || t == long.class)      return n.longValue();
            if (t == Double.class  || t == double.class)    return n.doubleValue();
            if (t == BigDecimal.class)                      return new BigDecimal(n.toString());
        }
        if (v instanceof String s) {
            try {
                if (t == Integer.class || t == int.class)     return Integer.valueOf(s);
                if (t == Long.class    || t == long.class)    return Long.valueOf(s);
                if (t == Double.class  || t == double.class)  return Double.valueOf(s);
                if (t == BigDecimal.class)                    return new BigDecimal(s);
                if (t == Boolean.class || t == boolean.class) return Boolean.valueOf(s);
                if (t == String.class)                        return s;
                if (t.isEnum())                               return Enum.valueOf((Class) t, s);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "无法把 \"" + s + "\" 解析为 " + t.getName(), ex);
            }
        }
        return v;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverCoerceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverCoerceTest.java
git commit -m "feat(sdk): coerce 支持 String→数值/Boolean/enum"
```

---

## Task 2: A — `@Fact`/`@Metric` value 可选,回退参数名

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Fact.java`
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Metric.java`
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java`
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverNameFallbackTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactResolverNameFallbackTest {

    static class Holder {
        public void m(@Fact Integer amount, @Metric Integer total) {}
    }

    @Test
    void emptyValue_fallsBackToParameterName() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, Integer.class);
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("amount", 8000)).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null,
                Map.of("total", new MetricValue(42, "INT", "FETCHED")), Instant.now());

        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);
        assertThat(args[0]).isEqualTo(8000);   // @Fact 无 value → 参数名 amount
        assertThat(args[1]).isEqualTo(42);     // @Metric 无 value → 参数名 total
    }
}
```

> 注:`-parameters` 已由 Spring Boot parent 默认开启(构建日志 `javac [debug parameters]`),`Parameter#getName()` 返回真实名。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverNameFallbackTest`
Expected: 编译失败(`@Fact` 无 value 当前不合法,`value()` 是必填)

- [ ] **Step 3: 注解 value 改可选**

`Fact.java` 把 `String value();` 改为:
```java
    /** payload 字段名 / 元数据键名;留空则回退方法参数名(需编译期 -parameters)。 */
    String value() default "";
```

`Metric.java` 把 `String value();` 改为:
```java
    /** metric 编码;留空则回退方法参数名(需编译期 -parameters)。 */
    String value() default "";
```

- [ ] **Step 4: FactResolver 统一取名**

在 `FactResolver` 内新增两个静态取名方法(放在 `resolveOne` 上方):
```java
    /** @Fact 名:注解 value 非空用之,否则回退参数名。 */
    static String factName(Parameter p, Fact fact) {
        return fact.value().isEmpty() ? p.getName() : fact.value();
    }

    /** @Metric 名:注解 value 非空用之,否则回退参数名。 */
    static String metricName(Parameter p, Metric metric) {
        return metric.value().isEmpty() ? p.getName() : metric.value();
    }
```

把 `resolveOne` 中 `ctx.getMetric(metric.value())` 改为 `ctx.getMetric(metricName(p, metric))`;把 `String name = fact.value();` 改为 `String name = factName(p, fact);`。

- [ ] **Step 5: 扫描器声明 metric 依赖也用统一取名**

`AnnotatedRuleScanner.scan` 的 metric 依赖循环(约 78-81 行)替换为:
```java
            for (Parameter p : condition.getParameters()) {
                Metric m = p.getAnnotation(Metric.class);
                if (m != null) b.addMetricDependency(FactResolver.metricName(p, m), m.version());
            }
```

- [ ] **Step 6: 跑测试确认通过 + 模块回归**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverNameFallbackTest` 然后 `$MVN -pl rule-sdk -am test`
Expected: 均 PASS(原有 `FactResolverTest`/`AnnotatedRuleScannerTest` 用了显式 value,不受影响)

- [ ] **Step 7: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Fact.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Metric.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverNameFallbackTest.java
git commit -m "feat(sdk): @Fact/@Metric value 可选,缺省回退参数名"
```

---

## Task 3: C — `@Fact` 嵌套路径 + samples 例子

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverNestedPathTest.java`
- Create: `rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/NestedOrderRule.java`
- Test: `rule-samples/src/test/java/com/sstlfsj/rule/samples/annotation/NestedOrderRuleIT.java`

- [ ] **Step 1: 写失败测试(单元)**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactResolverNestedPathTest {

    static class Holder {
        public void m(@Fact("order.amount") Integer amount,
                      @Fact("order.missing") Integer missing) {}
    }

    @Test
    void resolvesNestedPayloadPath_andNullWhenBroken() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, Integer.class);
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("order", Map.of("amount", 8000))).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null, Map.of(), Instant.now());

        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);
        assertThat(args[0]).isEqualTo(8000);  // order.amount 下钻命中
        assertThat(args[1]).isNull();         // order.missing 断链 → null
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverNestedPathTest`
Expected: FAIL(`args[0]` 为 null —— 当前按整串 key `"order.amount"` 查平铺 payload 取不到)

- [ ] **Step 3: FactResolver 加 NOT_FOUND 哨兵 + 嵌套查找,改 resolveOne 的 payload 分支**

在类顶部字段区加哨兵:
```java
    private static final Object NOT_FOUND = new Object();
```

新增嵌套查找方法(放 `metadata` 上方):
```java
    /** 在 payload 中按名取值,支持 a.b.c 逐级下钻;缺键/断链返回 NOT_FOUND(区别于"取到 null")。 */
    private static Object lookupPayload(Map<String, Object> payload, String name) {
        if (payload == null) return NOT_FOUND;
        if (name.indexOf('.') < 0) {
            return payload.containsKey(name) ? payload.get(name) : NOT_FOUND;
        }
        Object cur = payload;
        for (String seg : name.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m) || !m.containsKey(seg)) return NOT_FOUND;
            cur = m.get(seg);
        }
        return cur;
    }
```

把 `resolveOne` 的 payload 分支(现 `if (event != null && event.payload().containsKey(name)) { return coerce(...); } return metadata(...);`)替换为:
```java
        Object fromPayload = event == null ? NOT_FOUND : lookupPayload(event.payload(), name);
        if (fromPayload != NOT_FOUND) {
            return coerce(fromPayload, p.getType());
        }
        return metadata(name, event, fired);
```

- [ ] **Step 4: 跑单元测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverNestedPathTest`
Expected: PASS

- [ ] **Step 5: 写 samples 嵌套路径示例规则**

`NestedOrderRule.java`:
```java
package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.annotation.DecisionBinding;
import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.annotation.Condition;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.springframework.stereotype.Component;

/**
 * 嵌套 payload 路径示例:事件 payload 为 {"order":{"amount":N,"channel":"X"}} 结构时,
 * 用 @Fact("order.amount") 直接下钻取嵌套字段,无需在调用方先摊平。
 */
@RuleDef(code = "nested-order", sceneCode = "order-demo", trigger = "order",
        decisions = @DecisionBinding(code = "REVIEW", priority = 10))
@Component
public class NestedOrderRule {

    /** 嵌套大额订单(order.amount > 10000)→ 复核。 */
    @Condition
    public boolean bigNestedOrder(@Fact("order.amount") Integer amount) {
        return amount != null && amount > 10000;
    }
}
```

- [ ] **Step 6: 写 samples 端到端 IT**

`NestedOrderRuleIT.java`:
```java
package com.sstlfsj.rule.samples.annotation;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 嵌套路径端到端:payload.order.amount 经 @Fact("order.amount") 注入并命中规则。 */
class NestedOrderRuleIT {

    @Test
    void nestedPayloadPath_drivesCondition() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.sstlfsj.rule.sdk.starter.RuleEngineClientAutoConfiguration.class))
                .withBean(NestedOrderRule.class)
                .run(ctx -> {
                    RuleEngineClient client = ctx.getBean(RuleEngineClient.class);

                    RuleEvent big = RuleEvent.builder().tenantId("").sceneCode("order-demo")
                            .eventType("order").subjectId("u").eventId("o-1").occurredAt(Instant.now())
                            .payload(Map.of("order", Map.of("amount", 20000)))
                            .source(EventSource.SDK).build();
                    assertThat(client.evaluate(big).ruleHit()).isTrue();

                    RuleEvent small = big.toBuilder().eventId("o-2")
                            .payload(Map.of("order", Map.of("amount", 5000))).build();
                    assertThat(client.evaluate(small).ruleHit()).isFalse();
                });
    }
}
```

- [ ] **Step 7: 跑 samples IT 确认通过**

Run: `$MVN -pl rule-samples -am test -Dtest=NestedOrderRuleIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverNestedPathTest.java rule-samples/src/main/java/com/sstlfsj/rule/samples/annotation/NestedOrderRule.java rule-samples/src/test/java/com/sstlfsj/rule/samples/annotation/NestedOrderRuleIT.java
git commit -m "feat(sdk): @Fact 支持嵌套路径 a.b.c + samples 嵌套订单示例"
```

---

## Task 4: B — `@Fact` required + defaultValue

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Fact.java`
- Create: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/MissingFactException.java`
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverRequiredDefaultTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FactResolverRequiredDefaultTest {

    static class Holder {
        public void m(@Fact(value = "level", defaultValue = "3") Integer level,
                      @Fact(value = "mustHave", required = true) String mustHave) {}
    }

    private EvalContext emptyCtx() {
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.SDK).build();
        return new EvalContext("t", e, null, Map.of(), Instant.now());
    }

    @Test
    void defaultValue_appliedWhenMissing() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, String.class);
        // 只解析第 0 个参数(level):用 payload 提供 mustHave 避免 required 抛错
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("mustHave", "x")).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null, Map.of(), Instant.now());
        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);
        assertThat(args[0]).isEqualTo(3);     // defaultValue "3" → Integer 3
        assertThat(args[1]).isEqualTo("x");
    }

    @Test
    void required_missing_throwsMissingFact() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, String.class);
        assertThatThrownBy(() -> new FactResolver().resolve(m.getParameters(), emptyCtx(), null))
                .isInstanceOf(MissingFactException.class)
                .hasMessageContaining("mustHave");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverRequiredDefaultTest`
Expected: 编译失败(`@Fact` 无 `required`/`defaultValue`、`MissingFactException` 不存在)

- [ ] **Step 3: 扩 `@Fact` 注解**

`Fact.java` 在 `value()` 后加:
```java
    /** 取不到(payload+元数据皆无)时是否报错;默认 false=注入默认值/null。 */
    boolean required() default false;
    /** 取不到时的回退字面量;非空则按参数类型解析注入(优先级低于实际取值,高于 null)。 */
    String defaultValue() default "";
```

- [ ] **Step 4: 新建 `MissingFactException`**

```java
package com.sstlfsj.rule.sdk;

import java.lang.reflect.Parameter;

/** required 的 @Fact 在 payload 与元数据中都取不到值时抛出。 */
public class MissingFactException extends RuntimeException {
    public MissingFactException(String factName, Parameter param) {
        super("必填 @Fact \"" + factName + "\" 取值为空(payload/元数据均无): " + param);
    }
}
```

- [ ] **Step 5: 改 `resolveOne` 的 `@Fact` 分支(纳入 default/required)**

把 `resolveOne` 的 `@Fact` 分支(`String name = factName(...)` 起,到方法结束)替换为:
```java
        String name = factName(p, fact);
        RuleEvent event = ctx == null ? null : ctx.event();
        Object fromPayload = event == null ? NOT_FOUND : lookupPayload(event.payload(), name);
        if (fromPayload != NOT_FOUND) {
            return coerce(fromPayload, p.getType());
        }
        Object meta = metadata(name, event, fired);
        if (meta != null) {
            return coerce(meta, p.getType());
        }
        if (!fact.defaultValue().isEmpty()) {
            return coerce(fact.defaultValue(), p.getType());
        }
        if (fact.required()) {
            throw new MissingFactException(name, p);
        }
        return null;
```

- [ ] **Step 6: 跑测试确认通过 + 模块回归**

Run: `$MVN -pl rule-sdk -am test -Dtest=FactResolverRequiredDefaultTest` 然后 `$MVN -pl rule-sdk -am test`
Expected: 均 PASS(注:原 `FactResolverTest.resolves_..._andNullForMissing` 的 `@Fact("missing")` 无 required/default,仍返回 null,不受影响)

- [ ] **Step 7: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/Fact.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/MissingFactException.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/FactResolverRequiredDefaultTest.java
git commit -m "feat(sdk): @Fact 增加 required/defaultValue + MissingFactException"
```

---

## Task 5: D — 参数漏标校验上移到扫描期

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java`
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java`
- Modify: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvoker.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScannerValidateTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.annotation.RuleDef;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.Condition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AnnotatedRuleScannerValidateTest {

    @RuleDef(code = "bad-param", sceneCode = "demo")
    static class UnannotatedParamRule {
        @Condition
        public boolean c(Integer noAnnotation) { return true; }
    }

    @Test
    void scan_rejectsUnannotatedConditionParam_atStartup() {
        assertThatThrownBy(() ->
                new AnnotatedRuleScanner(new FactResolver(), "t").scan(List.of(new UnannotatedParamRule())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@Fact");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedRuleScannerValidateTest`
Expected: FAIL(当前漏标参数到求值期才抛,scan 阶段不报错)

- [ ] **Step 3: FactResolver 加 validate**

在 `FactResolver` 加(放 `resolve` 下方):
```java
    /** 启动期校验:每个参数必须标 @Fact 或 @Metric,否则抛 IllegalStateException(带方法+参数名)。 */
    public void validate(Parameter[] params) {
        for (Parameter p : params) {
            if (p.getAnnotation(Fact.class) == null && p.getAnnotation(Metric.class) == null) {
                throw new IllegalStateException(
                        "参数必须标注 @Fact 或 @Metric: " + p.getDeclaringExecutable() + " 的 " + p);
            }
        }
    }
```

- [ ] **Step 4: 扫描器在 wrap 前 validate**

`AnnotatedRuleScanner.scan` 在 `evaluators.put(condType, wrap(bean, condition));` **之前**插入:
```java
            factResolver.validate(condition.getParameters());
```

- [ ] **Step 5: OnDecisionInvoker 构造期 validate**

`OnDecisionInvoker` 构造函数内,`m.setAccessible(true);` 之后、`for (String code : ann.value())` 之前插入:
```java
                factResolver.validate(m.getParameters());
```

- [ ] **Step 6: 跑测试确认通过 + 两模块回归**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedRuleScannerValidateTest` 然后 `$MVN -pl rule-sdk,rule-sdk-spring-boot-starter -am test`
Expected: 均 PASS

- [ ] **Step 7: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/FactResolver.java rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScanner.java rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvoker.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedRuleScannerValidateTest.java
git commit -m "feat(sdk): 参数漏标 @Fact/@Metric 校验上移到扫描期 fail-fast"
```

---

## Task 6: G — orphan `@OnDecision` 启动 warn

**Files:**
- Modify: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvoker.java`
- Modify: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java`
- Test: `rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvokerTest.java`(追加)

- [ ] **Step 1: 写失败测试(追加到 OnDecisionInvokerTest)**

在 `OnDecisionInvokerTest` 末尾(最后一个 `}` 前)追加:
```java
    @Test
    void subscribedCodes_exposesAllRegisteredCodes() {
        OnDecisionInvoker invoker = new OnDecisionInvoker(new FactResolver(), List.of(new Handlers()));
        assertThat(invoker.subscribedCodes()).containsExactly("REVIEW");
    }
```
并在文件顶部确保 `import java.util.Set;`(若编译报缺再加;`Handlers` 内两个 `@OnDecision("REVIEW")` 去重后即 {"REVIEW"})。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=OnDecisionInvokerTest`
Expected: 编译失败(`subscribedCodes()` 不存在)

- [ ] **Step 3: OnDecisionInvoker 暴露 subscribedCodes**

在 `OnDecisionInvoker` 加(`hasHandlerFor` 旁):
```java
    /** @return 所有已登记订阅的 decision code(不可变视图),供启动期 orphan 核对。 */
    public java.util.Set<String> subscribedCodes() {
        return java.util.Collections.unmodifiableSet(byCode.keySet());
    }
```

- [ ] **Step 4: AutoConfiguration 交叉核对 warn**

在 `RuleEngineClientAutoConfiguration` 装配注解规则 + 动作派发那段之后(`builder.decisionContextListener(...)` 之后、`return builder.build();` 之前)插入:
```java
        // orphan @OnDecision 启动核对:订阅了"没有任何本地注解规则产出"的决策码 → warn(疑似拼写)
        java.util.Set<String> producedCodes = new java.util.HashSet<>();
        for (Object bean : annotatedRuleBeans) {
            com.sstlfsj.rule.kernel.api.annotation.RuleDef rd =
                    bean.getClass().getAnnotation(com.sstlfsj.rule.kernel.api.annotation.RuleDef.class);
            if (rd != null) {
                for (com.sstlfsj.rule.kernel.api.annotation.DecisionBinding d : rd.decisions()) {
                    producedCodes.add(d.code());
                }
            }
        }
        for (String code : onDecisionInvoker.subscribedCodes()) {
            if (!producedCodes.contains(code)) {
                log.warn("@OnDecision 订阅的决策码 '{}' 没有任何本地注解规则产出,疑似拼写错误或依赖服务端规则", code);
            }
        }
```

> 注:`annotatedRuleBeans`/`onDecisionInvoker` 是该方法内已有的局部变量(D61 装配块)。`log` 若类内无 logger,在类顶部加 `private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RuleEngineClientAutoConfiguration.class);`。

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=OnDecisionInvokerTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvoker.java rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvokerTest.java
git commit -m "feat(starter): orphan @OnDecision 启动期 warn(盘活 subscribedCodes 核对)"
```

---

## Task 7: H — `@OnDecision` async 开关 + 线程语义

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/OnDecision.java`
- Modify: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvoker.java`
- Modify: `rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java`
- Test: `rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/OnDecisionAsyncTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk.starter;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.DecisionFiredEvent;
import com.sstlfsj.rule.sdk.FactResolver;
import com.sstlfsj.rule.sdk.annotation.OnDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

class OnDecisionAsyncTest {

    static class AsyncHandler {
        final AtomicReference<String> thread = new AtomicReference<>();
        @OnDecision(value = "REVIEW", async = true)
        public void onReview() { thread.set(Thread.currentThread().getName()); }
    }

    @Test
    void async_runsOffCallerThread() {
        AsyncHandler h = new AsyncHandler();
        OnDecisionInvoker invoker = new OnDecisionInvoker(new FactResolver(), List.of(h),
                Executors.newSingleThreadExecutor(r -> new Thread(r, "ondecision-pool")));

        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null, Map.of(), Instant.now());
        invoker.accept(new DecisionFiredEvent("REVIEW", 1, null, "r", 1L, e, ctx));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(h.thread.get()).isEqualTo("ondecision-pool"));
    }
}
```

> 注:`awaitility` 已随 `spring-boot-starter-test` 传递,可直接用。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=OnDecisionAsyncTest`
Expected: 编译失败(`@OnDecision.async` 不存在、`OnDecisionInvoker` 无三参构造)

- [ ] **Step 3: `@OnDecision` 加 async + 线程语义 Javadoc**

`OnDecision.java` 类 Javadoc 改为:
```java
/**
 * 标在处理器方法上,按 decision code 订阅;命中对应决策时被调用,参数支持 @Fact/@Metric 注入。
 * <p><b>线程语义</b>:默认 {@code async=false},处理器在评估线程(RuleEngineClient.evaluate 调用栈)内
 * 同步执行,慢处理器会阻塞评估返回;置 {@code async=true} 则交由 starter 的独立线程池异步执行。
 */
```
在 `fromRuleCode()` 后加:
```java
    /** true=处理器在独立线程池异步执行,不阻塞评估;默认 false=评估线程同步执行。 */
    boolean async() default false;
```

- [ ] **Step 4: OnDecisionInvoker 支持 async executor**

把 `Handler` record 改为带 async 标志:
```java
    private record Handler(Object bean, Method method, String fromRuleCode, boolean async) {}
```
构造函数签名加一个 `java.util.concurrent.Executor asyncExecutor` 形参(放末尾),并保存为字段 `private final java.util.concurrent.Executor asyncExecutor;`(在构造体首行赋值);登记 Handler 时带上 `ann.async()`:
```java
                    .add(new Handler(bean, m, ann.fromRuleCode(), ann.async()));
```
保留原**双参**构造(向后兼容既有测试)委托到三参,asyncExecutor 传一个同步执行器:
```java
    public OnDecisionInvoker(FactResolver factResolver, List<?> handlerBeans) {
        this(factResolver, handlerBeans, Runnable::run);
    }
```
把 `accept` 的调用部分改为按 async 分流:
```java
            Handler handler = h;  // effectively final for lambda
            Runnable task = () -> {
                try {
                    Object[] args = factResolver.resolve(handler.method().getParameters(), event.context(), event);
                    handler.method().invoke(handler.bean(), args);
                } catch (Exception ex) {
                    log.error("@OnDecision 处理器执行失败,已吞:decision={} handler={}#{}",
                            event.decisionCode(), handler.bean().getClass().getName(), handler.method().getName(), ex);
                }
            };
            if (handler.async()) asyncExecutor.execute(task); else task.run();
```
(即把原 try/catch 同步体抽成 `task`,按 `async()` 选同步 `task.run()` 或 `asyncExecutor.execute(task)`;fromRuleCode 过滤的 `continue` 保持在 task 构造之前。)

- [ ] **Step 5: AutoConfiguration 注入 onDecisionExecutor**

在 `RuleEngineClientAutoConfiguration` 构造 `OnDecisionInvoker` 处(`new OnDecisionInvoker(factResolver, ...)`)改为传入独立线程池:
```java
        java.util.concurrent.Executor onDecisionExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1, 4, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(1024),
                r -> { Thread t = new Thread(r, "ondecision-async"); t.setDaemon(true); return t; },
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        OnDecisionInvoker onDecisionInvoker = new OnDecisionInvoker(
                factResolver, new ArrayList<>(beansWithOnDecision(ctx)), onDecisionExecutor);
```
(替换原两参 `new OnDecisionInvoker(...)`。)

- [ ] **Step 6: 跑测试确认通过 + starter 回归**

Run: `$MVN -pl rule-sdk-spring-boot-starter -am test -Dtest=OnDecisionAsyncTest` 然后 `$MVN -pl rule-sdk-spring-boot-starter -am test`
Expected: 均 PASS(双参构造保留,既有 `OnDecisionInvokerTest`/`AnnotatedRuleWiringTest` 不受影响)

- [ ] **Step 7: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/annotation/OnDecision.java rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/OnDecisionInvoker.java rule-sdk-spring-boot-starter/src/main/java/com/sstlfsj/rule/sdk/starter/RuleEngineClientAutoConfiguration.java rule-sdk-spring-boot-starter/src/test/java/com/sstlfsj/rule/sdk/starter/OnDecisionAsyncTest.java
git commit -m "feat(sdk): @OnDecision async 开关 + 线程语义文档(独立线程池 CallerRuns)"
```

---

## Task 8: 全量回归 + spec 状态更新

**Files:**
- Modify: `docs/superpowers/specs/2026-06-12-annotation-ergonomics-safety-design.md`(状态行)

- [ ] **Step 1: 全量编译测试**

Run: `$MVN clean test`
Expected: 全绿(14 模块);只有 `clean` 才强制重编所有 test 类,兜住跨模块过期增量编译。

- [ ] **Step 2: 更新 spec 状态行**

把文档首行 `> 状态:设计待评审` 改为 `> 状态:已实现`。

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-06-12-annotation-ergonomics-safety-design.md
git commit -m "docs: 注解易用性/防呆(D63)标记为已实现"
```

---

## 自查清单(已核)

- **spec 覆盖**:A(Task2)/B(Task4)/C(Task3,含 samples 例子)/D(Task5)/G(Task6)/H(Task7)/coerce 附项(Task1)。spec §3 各点均有对应 task。
- **类型一致**:`FactResolver.factName/metricName/validate/lookupPayload/coerce` 签名跨 task 一致;`NOT_FOUND` 哨兵 Task3 引入后 Task4 复用;`OnDecisionInvoker` 双参构造保留 + 三参新增,`Handler(bean,method,fromRuleCode,async)` Task7 定稿。
- **API 真实性**:`Parameter#getName()`(-parameters 已开)、`RuleVersionSnapshot.Builder.addMetricDependency`、`OnDecisionInvoker.byCode`、`RuleEngineClientAutoConfiguration` 内 `annotatedRuleBeans`/`onDecisionInvoker` 局部变量均经源码核对存在。
- **不动引擎**:无 `rule-kernel`/`EvalEngine`/executor 改动。

---

## 已知未覆盖(留作后续)

- `@Metric` 暂不加 required/default(metric 缺失语义=降级 null,与取数失败一致,不混入"必填"概念)。
- `defaultValue` 仅 `@Fact`;`@Metric` 不设默认(metric 由取数链路决定)。
- async 线程池参数(core/max/queue)硬编码,未做可配置(YAGNI,有需求再经 `SdkProperties` 暴露)。
