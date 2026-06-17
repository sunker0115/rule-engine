# Payload 直接引用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让规则能直接引用事件 payload 字段(`valueRef=PAYLOAD`)而不必把每个 payload 字段注册成 metric,恢复 "事件事实(payload) vs 受治理指标(metric)" 的语义区分。

**Architecture:** `ConditionNode` 加 `valueRef`(METRIC/PAYLOAD)字段;payload 值在 `EvalContextAssembler` 装配阶段整体注入 `metrics` map(13 个比较算子零改,`ctx.getMetric(字段名)` 自动命中);发布期遍历 AST 校验 payload 字段必须在 `scene.payloadSchema` 声明,并把 payloadSchema 的类型映射成 `DataType` 冻结进 ConditionNode。`MetricDependencyCollector` 跳过 PAYLOAD 节点(不要求 ACTIVE metric)。

**Tech Stack:** Java 25, Spring Boot 4, MyBatis-Plus, JUnit 5 + AssertJ + Mockito, Flyway。设计见 `docs/superpowers/specs/2026-06-09-payload-direct-reference-design.md`。

**测试环境:** 每次跑 mvn 前用 `mvn-env` skill 设置 `JAVA_HOME`(JDK 25)与 `$MVN`。命令形如 `$MVN -pl <module> -am test -Dtest='Xxx'`。跨模块改动带 `-am`,整轮收尾用全量 `$MVN clean test`。

---

## Task 1: 新增 ValueRef 枚举

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ValueRef.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ValueRefTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueRefTest {
    @Test
    void tag_equalsName() {
        assertEquals("METRIC", ValueRef.METRIC.tag());
        assertEquals("PAYLOAD", ValueRef.PAYLOAD.tag());
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-kernel test -Dtest='ValueRefTest'`,预期编译失败(ValueRef 不存在)。

- [ ] **Step 3: 实现枚举**

```java
package com.sstlfsj.rule.kernel.api.model;

/** ConditionNode 值引用来源:METRIC=受治理指标(走 ctx.metrics 取数/注入);PAYLOAD=事件自带字段(直接读 event.payload)。 */
public enum ValueRef {
    METRIC, PAYLOAD;

    /** 序列化标签(== 枚举名)。 */
    public String tag() {
        return name();
    }
}
```

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-kernel test -Dtest='ValueRefTest'`,预期 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ValueRef.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ValueRefTest.java
git commit -m "feat(kernel): 新增 ValueRef 枚举(METRIC/PAYLOAD)"
```

---

## Task 2: ConditionNode 加 valueRef 字段(默认 METRIC)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNode.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNodeValueRefTest.java`

设计:canonical 构造器变 7 参(末位 `ValueRef valueRef`);compact constructor 把 null valueRef 兜底为 METRIC(保证旧 JSON 反序列化默认 METRIC);保留两个旧便利构造器(6 参 dataType 版、5 参 DSL 版)委托并默认 `valueRef=METRIC`。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.api.model.ast;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionNodeValueRefTest {
    @Test
    void fiveArgConstructor_defaultsToMetric() {
        ConditionNode n = new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0);
        assertEquals(ValueRef.METRIC, n.valueRef());
    }

    @Test
    void nullValueRef_coercedToMetric() {
        ConditionNode n = new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0, "LONG", null);
        assertEquals(ValueRef.METRIC, n.valueRef());
    }

    @Test
    void payloadValueRef_preserved() {
        ConditionNode n = new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0, "DECIMAL", ValueRef.PAYLOAD);
        assertEquals(ValueRef.PAYLOAD, n.valueRef());
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-kernel test -Dtest='ConditionNodeValueRefTest'`,预期编译失败。

- [ ] **Step 3: 实现 — 替换 ConditionNode 全文**

```java
package com.sstlfsj.rule.kernel.api.model.ast;

import com.sstlfsj.rule.kernel.api.model.ValueRef;

import java.util.Map;

/**
 * 叶子节点：持有具体条件类型标识及参数，由对应 ConditionEvaluator 求值。
 * dataType 由发布期 AstDataTypeResolver 冻结（LONG/DOUBLE/STRING/BOOLEAN/LIST）；
 * DSL 构造时为 null，求值期走 DefaultComparisonStrategy 按值推断。
 * valueRef 标识取值来源：METRIC（默认，走 ctx.metrics）/ PAYLOAD（直接读 event.payload，metricCode 复用为字段名）。
 */
public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params,
        /** 评分卡权重；AST_BOOLEAN kind 时忽略，SCORECARD kind 时由 ScorecardExecutor 累加。 */
        Double weight,
        String dataType,
        ValueRef valueRef
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
        // 旧 JSON / 旧构造路径无 valueRef 时默认 METRIC，保证语义不变
        if (valueRef == null) valueRef = ValueRef.METRIC;
    }

    /** 带 dataType 的构造入口（发布期 AstDataTypeResolver 重建），valueRef 默认 METRIC。 */
    public ConditionNode(String conditionType, String metricCode, String displayLabel,
                         Map<String, Object> params, Double weight, String dataType) {
        this(conditionType, metricCode, displayLabel, params, weight, dataType, ValueRef.METRIC);
    }

    /** 未声明类型的构造入口（DSL、DecisionTableExecutor 合成节点等），dataType=null、valueRef=METRIC。 */
    public ConditionNode(String conditionType, String metricCode, String displayLabel,
                         Map<String, Object> params, Double weight) {
        this(conditionType, metricCode, displayLabel, params, weight, null, ValueRef.METRIC);
    }
}
```

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-kernel test -Dtest='ConditionNodeValueRefTest'`,预期 PASS。其它现有 ConditionNode 构造点(5 参/6 参)不受影响。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNode.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNodeValueRefTest.java
git commit -m "feat(kernel): ConditionNode 加 valueRef 字段(默认 METRIC,null 兜底)"
```

---

## Task 3: ValueSource 加 PAYLOAD

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ValueSource.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ValueSourceTest.java`(若不存在则建)

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueSourceTest {
    @Test
    void payload_tag() {
        assertEquals("PAYLOAD", ValueSource.PAYLOAD.tag());
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-kernel test -Dtest='ValueSourceTest'`,预期编译失败(无 PAYLOAD)。

- [ ] **Step 3: 实现 — ValueSource 加 PAYLOAD**

```java
package com.sstlfsj.rule.kernel.api.model;

/** 指标取值来源(契约值,落 node_trace.value_source VARCHAR 列、随 MetricValue 流转)。 */
public enum ValueSource {
    PROVIDED, FETCHED, PAYLOAD;

    /** 持久化/序列化用的字符串标签(== 枚举名,与 DB VARCHAR 列值一致)。 */
    public String tag() {
        return name();
    }
}
```

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-kernel test -Dtest='ValueSourceTest'`,预期 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ValueSource.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ValueSourceTest.java
git commit -m "feat(kernel): ValueSource 加 PAYLOAD(payload 直接引用取值来源)"
```

---

## Task 4: EvalContextAssembler 注入 payload 值

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerPayloadTest.java`

设计:在 `assemble(...)` 两条返回路径(退化路径 line ~105、正常路径 line ~156)返回前,把 `event.payload()` 每个字段 `putIfAbsent` 进值 map,包成 `new MetricValue(value, DataType.UNKNOWN.tag(), ValueSource.PAYLOAD.tag())`。`putIfAbsent` 保证 metric/provided 同名时优先(命名空间约束兜底)。提取私有 helper `injectPayload`。dataType 用 UNKNOWN 即可——比较算子用 `node.dataType()`(发布期冻结)选策略,不读 MetricValue.dataType。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EvalContextAssemblerPayloadTest {

    @Test
    void payloadFields_injectedAsPayloadSource() {
        // definitionResolver=null 走退化路径
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent("1", "demo.login", "login", "u1", "evt1",
                Instant.now(), Map.of("amount", 5000, "currency", "CNY"), Map.of());

        EvalContext ctx = assembler.assemble(event, List.of(), Instant.now());

        MetricValue amount = ctx.getMetric("amount");
        assertNotNull(amount);
        assertEquals(5000, amount.value());
        assertEquals(ValueSource.PAYLOAD.tag(), amount.valueSource());
    }

    @Test
    void providedMetric_winsOverSamePayloadKey() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent("1", "demo.login", "login", "u1", "evt1",
                Instant.now(), Map.of("amount", 5000), Map.of("amount", 99));

        EvalContext ctx = assembler.assemble(event, List.of(), Instant.now());

        // providedMetrics 先 put,payload putIfAbsent 不覆盖
        assertEquals(99, ctx.getMetric("amount").value());
        assertEquals(ValueSource.PROVIDED.tag(), ctx.getMetric("amount").valueSource());
    }
}
```

> 注:`RuleEvent` 构造参数顺序见 `rule-kernel/.../api/model/RuleEvent.java`;若与上例签名不符,按实际签名调整入参(tenantId, sceneCode, eventType, subjectId, eventId, occurredAt, payload, providedMetrics)。

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-kernel test -Dtest='EvalContextAssemblerPayloadTest'`,预期 FAIL(getMetric("amount") 为 null,payload 未注入)。

- [ ] **Step 3: 实现 — 在 assemble 两路径注入 payload**

在退化路径 `return new EvalContext(event.tenantId(), event, subject, provided, now);` 之前插入:

```java
        injectPayload(event, provided);
```

在正常路径 `return new EvalContext(event.tenantId(), event, subject, metrics, now);` 之前插入:

```java
        injectPayload(event, metrics);
```

在类内新增私有方法(放在 assemble 之后):

```java
    /**
     * 把事件 payload 的每个字段以 ValueSource.PAYLOAD 注入值 map（putIfAbsent，
     * 同名 metric/provided 优先）。payload 字段的 dataType 在比较时由 node.dataType()
     * （发布期冻结）决定，故此处统一 UNKNOWN。
     */
    private static void injectPayload(RuleEvent event, Map<String, MetricValue> target) {
        Map<String, Object> payload = event.payload();
        if (payload == null) return;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            target.putIfAbsent(e.getKey(),
                    new MetricValue(e.getValue(), DataType.UNKNOWN.tag(), ValueSource.PAYLOAD.tag()));
        }
    }
```

> 确认 import:`com.sstlfsj.rule.kernel.api.model.MetricValue`、`DataType`、`ValueSource` 已在该文件(assemble 已用 MetricValue/ValueSource.PROVIDED)。

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-kernel test -Dtest='EvalContextAssemblerPayloadTest'`,预期 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssembler.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/context/EvalContextAssemblerPayloadTest.java
git commit -m "feat(kernel): EvalContextAssembler 注入 payload 值(ValueSource.PAYLOAD,putIfAbsent 让 metric 优先)"
```

---

## Task 5: MetricDependencyCollector 跳过 PAYLOAD 节点

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/MetricDependencyCollector.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/MetricDependencyCollectorTest.java`(已存在,追加用例)

设计:`walk` 的 `ConditionNode` 分支与 `ScorecardRootNode` 叶子收集,加 `valueRef != PAYLOAD` 判断。

- [ ] **Step 1: 追加失败测试**

```java
    @Test
    void payloadValueRefNode_notCollected() {
        AstNode ast = new ConditionNode("GT", "amount", null,
                java.util.Map.of("threshold", 1000), 0.0, null,
                com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
        assertThat(MetricDependencyCollector.collect(ast)).isEmpty();
    }

    @Test
    void metricValueRefNode_stillCollected() {
        AstNode ast = new ConditionNode("GT", "user.risk.score", null,
                java.util.Map.of("threshold", 80), 0.0, null,
                com.sstlfsj.rule.kernel.api.model.ValueRef.METRIC);
        assertThat(MetricDependencyCollector.collect(ast)).containsExactly("user.risk.score");
    }
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-config-svc -am test -Dtest='MetricDependencyCollectorTest'`,预期 `payloadValueRefNode_notCollected` FAIL(PAYLOAD 节点仍被收集)。

- [ ] **Step 3: 实现 — walk 加 valueRef 判断**

把 `ConditionNode` 分支:

```java
            case ConditionNode cond -> {
                if (cond.metricCode() != null) acc.add(cond.metricCode());
            }
```

改为:

```java
            case ConditionNode cond -> {
                if (cond.valueRef() != com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD
                        && cond.metricCode() != null) acc.add(cond.metricCode());
            }
```

把 `ScorecardRootNode` 分支:

```java
            case ScorecardRootNode sc -> sc.conditions().forEach(c -> {
                if (c.metricCode() != null) acc.add(c.metricCode());
            });
```

改为:

```java
            case ScorecardRootNode sc -> sc.conditions().forEach(c -> {
                if (c.valueRef() != com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD
                        && c.metricCode() != null) acc.add(c.metricCode());
            });
```

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-config-svc -am test -Dtest='MetricDependencyCollectorTest'`,预期全 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/MetricDependencyCollector.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/MetricDependencyCollectorTest.java
git commit -m "feat(config): MetricDependencyCollector 跳过 valueRef=PAYLOAD 节点"
```

---

## Task 6: payloadSchema type → DataType 映射工具

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PayloadDataTypeMapper.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PayloadDataTypeMapperTest.java`

设计:把 `PayloadFieldSpec.type`(STRING/INTEGER/NUMBER/BOOLEAN/ARRAY/OBJECT)映射到 `DataType.tag()`:NUMBER→DECIMAL、INTEGER→LONG、STRING→STRING、BOOLEAN→BOOLEAN、ARRAY→LIST、其它/OBJECT→UNKNOWN。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.config.internal.publish;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PayloadDataTypeMapperTest {
    @Test
    void mapsSchemaTypeToDataTypeTag() {
        assertEquals("DECIMAL", PayloadDataTypeMapper.toDataTypeTag("NUMBER"));
        assertEquals("LONG", PayloadDataTypeMapper.toDataTypeTag("INTEGER"));
        assertEquals("STRING", PayloadDataTypeMapper.toDataTypeTag("STRING"));
        assertEquals("BOOLEAN", PayloadDataTypeMapper.toDataTypeTag("BOOLEAN"));
        assertEquals("LIST", PayloadDataTypeMapper.toDataTypeTag("ARRAY"));
        assertEquals("UNKNOWN", PayloadDataTypeMapper.toDataTypeTag("OBJECT"));
        assertEquals("UNKNOWN", PayloadDataTypeMapper.toDataTypeTag(null));
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-config-svc -am test -Dtest='PayloadDataTypeMapperTest'`,预期编译失败。

- [ ] **Step 3: 实现**

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.DataType;

/** 把 payloadSchema 的 JSON Schema type 映射到 kernel DataType 标签,供发布期注入 ConditionNode.dataType。 */
final class PayloadDataTypeMapper {
    private PayloadDataTypeMapper() {}

    /**
     * @param schemaType PayloadFieldSpec.type（STRING/INTEGER/NUMBER/BOOLEAN/ARRAY/OBJECT）
     * @return 对应的 DataType.tag()；无法识别（含 null/OBJECT）返回 UNKNOWN
     */
    static String toDataTypeTag(String schemaType) {
        if (schemaType == null) return DataType.UNKNOWN.tag();
        return switch (schemaType.toUpperCase()) {
            case "NUMBER"  -> DataType.DECIMAL.tag();
            case "INTEGER" -> DataType.LONG.tag();
            case "STRING"  -> DataType.STRING.tag();
            case "BOOLEAN" -> DataType.BOOLEAN.tag();
            case "ARRAY"   -> DataType.LIST.tag();
            default         -> DataType.UNKNOWN.tag();
        };
    }
}
```

> 确认 `DataType` 有 `DECIMAL/LONG/STRING/BOOLEAN/LIST/UNKNOWN` 且 `tag()` 返回枚举名。若 `LIST` 标签不同(如 `ARRAY`),以 `DataType` 实际 tag 为准并同步测试。

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-config-svc -am test -Dtest='PayloadDataTypeMapperTest'`,预期 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PayloadDataTypeMapper.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PayloadDataTypeMapperTest.java
git commit -m "feat(config): payloadSchema type → DataType 映射工具"
```

---

## Task 7: AstDataTypeResolver 给 PAYLOAD 节点注入 payload dataType

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolverPayloadTest.java`

设计:`resolve(...)` 增加一个入参 `Map<String,String> payloadTypeMap`(payload 字段名 → DataType.tag());`resolveCondition` 按 `valueRef` 选 dataType 源——PAYLOAD 用 payloadTypeMap、METRIC 用现有 dataTypeMap;重建 ConditionNode 时透传 `cond.valueRef()`(7 参构造器)。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AstDataTypeResolverPayloadTest {
    @Test
    void payloadNode_getsDataTypeFromPayloadMap_andKeepsPayloadRef() {
        ConditionNode src = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 1000), 0.0, null, ValueRef.PAYLOAD);
        AstNode out = AstDataTypeResolver.resolve(src, Map.of(), Map.of("amount", "DECIMAL"));
        ConditionNode r = (ConditionNode) out;
        assertEquals("DECIMAL", r.dataType());
        assertEquals(ValueRef.PAYLOAD, r.valueRef());
    }

    @Test
    void metricNode_unaffectedByPayloadMap() {
        ConditionNode src = new ConditionNode("GT", "user.risk.score", null,
                Map.of("threshold", 80), 0.0, null, ValueRef.METRIC);
        AstNode out = AstDataTypeResolver.resolve(src, Map.of("user.risk.score", "LONG"), Map.of());
        ConditionNode r = (ConditionNode) out;
        assertEquals("LONG", r.dataType());
        assertEquals(ValueRef.METRIC, r.valueRef());
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-config-svc -am test -Dtest='AstDataTypeResolverPayloadTest'`,预期编译失败(resolve 签名不符)。

- [ ] **Step 3: 实现**

`resolve(AstNode, Map)` 改为 `resolve(AstNode root, Map<String,String> dataTypeMap, Map<String,String> payloadTypeMap)`,并把内部递归方法同步多传 `payloadTypeMap`。`resolveCondition` 改为:

```java
    private static ConditionNode resolveCondition(ConditionNode cond,
                                                  Map<String, String> dataTypeMap,
                                                  Map<String, String> payloadTypeMap) {
        String dataType;
        if (cond.valueRef() == com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD) {
            // payload 引用：dataType 来自 payloadSchema 映射；查不到则 null（走 Default 策略）
            dataType = payloadTypeMap.get(cond.metricCode());
        } else {
            dataType = dataTypeMap.get(cond.metricCode());
            if (dataType != null) {
                Set<String> allowed = ALLOWED.get(cond.conditionType());
                if (allowed != null && !allowed.contains(dataType)) {
                    throw new IllegalArgumentException(
                            "算子 " + cond.conditionType() + " 不支持 dataType=" + dataType
                            + "（metric=" + cond.metricCode() + "）");
                }
            }
        }
        return new ConditionNode(cond.conditionType(), cond.metricCode(),
                cond.displayLabel(), cond.params(), cond.weight(), dataType, cond.valueRef());
    }
```

> 把 `resolve` 入口与所有递归分支(含 `resolveConditionList`、DecisionTable 列处理)的方法签名都加上 `payloadTypeMap` 透传。DecisionTable 的 `Column` 不支持 payload(本轮 YAGNI),其重建保持原逻辑(只用 dataTypeMap)。

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-config-svc -am test -Dtest='AstDataTypeResolverPayloadTest,AstDataTypeResolverTest'`,预期全 PASS(旧用例若因签名变更需补一个 `Map.of()` 第三参,同步改)。

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolver.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/AstDataTypeResolverPayloadTest.java
git commit -m "feat(config): AstDataTypeResolver 给 PAYLOAD 节点从 payloadSchema 注入 dataType"
```

---

## Task 8: PublishService 接线 payload 校验 + dataType 注入

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PayloadFieldCollector.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PayloadFieldCollectorTest.java`

设计:新增 `PayloadFieldCollector.collect(AstNode)` 收集 valueRef=PAYLOAD 节点的字段名(仿 MetricDependencyCollector 遍历);`PublishService.publish` 在 metric 校验段之后:① 校验每个 payload 字段在 `scene.getPayloadSchema()` 的 name 集合,否则抛 `IllegalArgumentException`;② 构造 `payloadTypeMap`(字段名→`PayloadDataTypeMapper.toDataTypeTag`),传入 `AstDataTypeResolver.resolve(ast, dataTypeMap, payloadTypeMap)`。

- [ ] **Step 1: 写失败测试(collector)**

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PayloadFieldCollectorTest {
    @Test
    void collectsOnlyPayloadRefFields() {
        AstNode ast = new AndNode(java.util.List.of(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0, null, ValueRef.PAYLOAD),
                new ConditionNode("GTE", "user.risk.score", null, Map.of("threshold", 80), 0.0, null, ValueRef.METRIC)
        ), null, null);
        assertThat(PayloadFieldCollector.collect(ast)).containsExactly("amount");
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-config-svc -am test -Dtest='PayloadFieldCollectorTest'`,预期编译失败。

- [ ] **Step 3: 实现 PayloadFieldCollector**(仿 MetricDependencyCollector 结构,收集 PAYLOAD 节点 metricCode)

```java
package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 静态扫描 AST，收集 valueRef=PAYLOAD 叶子节点引用的 payload 字段名（去重保序）。 */
final class PayloadFieldCollector {
    private PayloadFieldCollector() {}

    static List<String> collect(AstNode node) {
        Set<String> acc = new LinkedHashSet<>();
        walk(node, acc);
        return new ArrayList<>(acc);
    }

    private static void walk(AstNode node, Set<String> acc) {
        switch (node) {
            case AndNode and -> and.children().forEach(c -> walk(c, acc));
            case OrNode or   -> or.children().forEach(c -> walk(c, acc));
            case NotNode not -> walk(not.child(), acc);
            case XorNode xor -> xor.children().forEach(c -> walk(c, acc));
            case IfNode ifn  -> {
                walk(ifn.condition(), acc);
                walk(ifn.thenBranch(), acc);
                if (ifn.elseBranch() != null) walk(ifn.elseBranch(), acc);
            }
            case ConditionNode cond -> {
                if (cond.valueRef() == ValueRef.PAYLOAD && cond.metricCode() != null) acc.add(cond.metricCode());
            }
            case ScorecardRootNode sc -> sc.conditions().forEach(c -> {
                if (c.valueRef() == ValueRef.PAYLOAD && c.metricCode() != null) acc.add(c.metricCode());
            });
            case DecisionLeafNode ignored -> {}
            case DecisionTableNode dt -> { /* 列不支持 payload（本轮 YAGNI） */ }
        }
    }
}
```

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-config-svc -am test -Dtest='PayloadFieldCollectorTest'`,预期 PASS。

- [ ] **Step 5: 接线 PublishService**(在 4.5 metric 校验段后、`AstDataTypeResolver.resolve` 调用处)

把原 `resolvedAst = AstDataTypeResolver.resolve(ast, dataTypeMap);`(原仅 metric 路径,在 `if (!metricCodes.isEmpty())` 块内)调整为:在收集 metricCodes 之后、统一做 payload 校验 + resolve。在 `publish` 方法 step 4 区域加入:

```java
        // 4.7 payload 引用校验：valueRef=PAYLOAD 字段必须在 scene.payloadSchema 声明
        List<String> payloadFields = PayloadFieldCollector.collect(ast);
        Map<String, String> payloadTypeMap = new HashMap<>();
        if (!payloadFields.isEmpty()) {
            java.util.List<com.sstlfsj.rule.config.api.dto.PayloadFieldSpec> schema =
                    scene.getPayloadSchema() != null ? scene.getPayloadSchema() : java.util.List.of();
            Map<String, String> schemaTypeByName = new HashMap<>();
            for (var f : schema) schemaTypeByName.put(f.name(), f.type());
            for (String field : payloadFields) {
                if (!schemaTypeByName.containsKey(field)) {
                    throw new IllegalArgumentException(
                            "规则引用的 payload 字段未在 scene.payloadSchema 声明: " + field);
                }
                payloadTypeMap.put(field, PayloadDataTypeMapper.toDataTypeTag(schemaTypeByName.get(field)));
            }
        }
```

并把 dataType 注入调用统一为(覆盖原 metric-only 调用,移出或合并 `if (!metricCodes.isEmpty())`,保证 payload-only 规则也走 resolve):

```java
        resolvedAst = AstDataTypeResolver.resolve(ast, dataTypeMap, payloadTypeMap);
```

> `dataTypeMap` 在无 metric 依赖时为空 Map(原代码 metric 块内构造,需提升作用域到方法级初始化 `Map<String,String> dataTypeMap = new HashMap<>();`,metric 块内填充)。确保 `resolvedAst` 默认 = `ast`,有 payload 或 metric 任一即 resolve。

- [ ] **Step 6: 写 PublishService 集成测试**(payload 字段不在 schema → 拒绝)

在现有 PublishService 测试类(若有 `PublishServiceTest`)追加;否则新建 `rule-config-svc/src/test/java/.../publish/PublishServicePayloadTest.java`,用 Mockito mock mapper,构造一个引用未声明 payload 字段的草稿,断言 `publish` 抛 `IllegalArgumentException` 且 message 含字段名。(参照仓内现有 PublishService 测试的 mock 装配方式编写。)

- [ ] **Step 7: 跑测试 + 提交**

```bash
$MVN -pl rule-config-svc -am test -Dtest='PayloadFieldCollectorTest,PublishServicePayloadTest,AstDataTypeResolverPayloadTest'
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PayloadFieldCollector.java rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PayloadFieldCollectorTest.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServicePayloadTest.java
git commit -m "feat(config): 发布期校验 payload 字段须在 payloadSchema 声明 + 注入 payload dataType"
```

---

## Task 9: SDK Condition DSL 加 payload 工厂

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/Condition.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/ConditionPayloadTest.java`

设计:新增私有 `leaf` 重载带 `ValueRef`;新增一组 `payloadGt/payloadGte/payloadLt/payloadLte/payloadEq/payloadNeq/payloadIn/payloadBetween`,生成 valueRef=PAYLOAD 的 ConditionNode。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionPayloadTest {
    @Test
    void payloadGt_buildsPayloadRefNode() {
        ConditionNode n = (ConditionNode) Condition.payloadGt("amount", 1000).toAst();
        assertEquals(ValueRef.PAYLOAD, n.valueRef());
        assertEquals("GT", n.conditionType());
        assertEquals("amount", n.metricCode());
        assertEquals(1000, n.params().get("threshold"));
    }

    @Test
    void gt_staysMetricRef() {
        ConditionNode n = (ConditionNode) Condition.gt("user.risk.score", 80).toAst();
        assertEquals(ValueRef.METRIC, n.valueRef());
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-sdk -am test -Dtest='ConditionPayloadTest'`,预期编译失败(payloadGt 不存在)。

- [ ] **Step 3: 实现 — 加 payload 工厂 + leaf 重载**

新增带 valueRef 的私有 leaf,并让现有 `leaf` 委托(默认 METRIC):

```java
    private static Condition leaf(String conditionType, String metric, Map<String, Object> params) {
        return leaf(conditionType, metric, params, com.sstlfsj.rule.kernel.api.model.ValueRef.METRIC);
    }

    private static Condition leaf(String conditionType, String metric, Map<String, Object> params,
                                  com.sstlfsj.rule.kernel.api.model.ValueRef valueRef) {
        return new Condition(new ConditionNode(conditionType, metric, null, params, 0.0, null, valueRef));
    }
```

新增 payload 工厂(与 metric 版对称,放在现有工厂方法附近):

```java
    public static Condition payloadGt(String field, Object threshold) {
        return leaf("GT", field, Map.of("threshold", threshold), com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
    }
    public static Condition payloadGte(String field, Object threshold) {
        return leaf("GTE", field, Map.of("threshold", threshold), com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
    }
    public static Condition payloadLt(String field, Object threshold) {
        return leaf("LT", field, Map.of("threshold", threshold), com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
    }
    public static Condition payloadLte(String field, Object threshold) {
        return leaf("LTE", field, Map.of("threshold", threshold), com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
    }
    public static Condition payloadEq(String field, Object value) {
        return leaf("EQ", field, Map.of("value", value), com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
    }
    public static Condition payloadNeq(String field, Object value) {
        return leaf("NEQ", field, Map.of("value", value), com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
    }
    public static Condition payloadIn(String field, Object... values) {
        return leaf("IN", field, Map.of("values", java.util.Arrays.asList(values)), com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
    }
    public static Condition payloadBetween(String field, Object min, Object max) {
        return leaf("BETWEEN", field, Map.of("min", min, "max", max), com.sstlfsj.rule.kernel.api.model.ValueRef.PAYLOAD);
    }
```

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-sdk -am test -Dtest='ConditionPayloadTest'`,预期 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/Condition.java rule-sdk/src/test/java/com/sstlfsj/rule/sdk/ConditionPayloadTest.java
git commit -m "feat(sdk): Condition 加 payloadGt/payloadEq 等 payload 引用工厂"
```

---

## Task 10: 端到端评估测试(payload 引用命中/未命中)

**Files:**
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/PayloadReferenceEvalTest.java`

验证从 AST(payload 引用 + metric 混合)经 EvalContextAssembler + executor 的整链路。参照仓内现有 InterpretedExecutor 测试的装配方式(构造 RuleVersionSnapshot/EvalContext → 执行)。

- [ ] **Step 1: 写测试**(payload.amount>1000 AND metric user.risk.score>=80)

```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PayloadReferenceEvalTest {

    private EvalContext ctx(Map<String,Object> payload, Map<String,Object> provided) {
        EvalContextAssembler a = new EvalContextAssembler(List.of(), List.of());
        RuleEvent e = new RuleEvent("1", "demo.login", "login", "u1", "evt1",
                Instant.now(), payload, provided);
        return a.assemble(e, List.of(), Instant.now());
    }

    @Test
    void payloadGt_hits_whenPayloadValueAboveThreshold() {
        ConditionNode node = new ConditionNode("GT", "amount", null,
                Map.of("threshold", 1000), 0.0, "DECIMAL", ValueRef.PAYLOAD);
        // 复用内置 GT 算子；payload 值已注入 metrics
        var ev = com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators.defaults().get("GT");
        assertTrue(ev.evaluate(node, ctx(Map.of("amount", 5000), Map.of())));
        assertFalse(ev.evaluate(node, ctx(Map.of("amount", 500), Map.of())));
    }
}
```

> 注:`KernelEvaluators.defaults()` 返回的算子注册表取法以仓内实际 API 为准;若是 `Map<String,ConditionEvaluator>` 则 `.get("GT")`,签名不符时按实际调整。

- [ ] **Step 2: 跑测试** — `$MVN -pl rule-kernel test -Dtest='PayloadReferenceEvalTest'`,预期 PASS(payload 值经 assemble 注入,GT 算子用 node.dataType=DECIMAL 比较)。

- [ ] **Step 3: 提交**

```bash
git add rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/PayloadReferenceEvalTest.java
git commit -m "test(kernel): payload 引用端到端求值(注入 + GT 算子命中)"
```

---

## Task 11: 修全仓 `new ConditionNode(` 全字段调用点

**Files:**
- Modify: 由 grep 结果确定(主代码 + 测试)

- [ ] **Step 1: grep 全字段调用点**

Run: `grep -rn 'new ConditionNode(' --include='*.java' . | grep -v '/target/'`
逐个检查:用 5 参/6 参便利构造器的**不用改**(已默认 METRIC);用 7 参旧 canonical(无 valueRef)的需补 `ValueRef.METRIC` 或对应值。AstDataTypeResolver 已在 Task 7 改。

- [ ] **Step 2: 逐点修复**(每个调用点补 `ValueRef.METRIC` 末参,除非语义是 payload)。

- [ ] **Step 3: 全量编译** — `$MVN -q -pl rule-kernel,rule-config-svc,rule-eval-svc,rule-sdk -am test-compile`,预期无 "constructor ConditionNode" 报错。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "fix: 补全 new ConditionNode 调用点的 valueRef 参数(默认 METRIC)"
```

---

## Task 12: 迁移 demo 数据 + examples 案例(amount 由 metric 改 payload)

**Files:**
- Modify: `docs/examples/risk-control/high-risk-login/scene.json`(确认 amount 在 payloadSchema)、`rules/high-risk-login.json`(amount 节点加 `"valueRef":"PAYLOAD"`)、`metrics/metrics.json`(删 amount)、`README.md`(更新 payload vs metric 判据)
- Runtime data:本地库 `metric_definition` 删 amount(经 API 或确认 demo 重建)

- [ ] **Step 1: 改 examples 规则 AST** — `rules/high-risk-login.json` 里 `amount` 的 ConditionNode 加 `"valueRef": "PAYLOAD"`;`user.risk.score` 节点不变(默认 METRIC)。

- [ ] **Step 2: 删 examples metrics 的 amount** — `metrics/metrics.json` 移除 amount 项,只留 `user.risk.score`。

- [ ] **Step 3: 更新 examples README** — 把"payload 字段也必须注册成 metric"的注意点改为"payload 字段用 `valueRef=PAYLOAD` 直接引用,只有受治理指标才注册 metric",附配置判据。

- [ ] **Step 4: 提交**

```bash
git add docs/examples/risk-control/high-risk-login/
git commit -m "docs(examples): high-risk-login 的 amount 改用 payload 直接引用"
```

---

## Task 13: 文档更新(契约 + 概念 + 表达式)

**Files:**
- Modify: `docs/10-api-contract.md`、`docs/03-rule-expression.md`、`docs/01-concepts.md`

- [ ] **Step 1:** `03-rule-expression.md` 增补 payload 引用 AST 写法(`valueRef: PAYLOAD`)与内置算子兼容说明。
- [ ] **Step 2:** `10-api-contract.md` §4.1 规则创建请求体 conditionAst 标注 `valueRef` 字段;§七 errorCode 补"payload 字段未在 payloadSchema 声明 → UNRESOLVED_VARIABLE"。
- [ ] **Step 3:** `01-concepts.md` metric/payload 区分补"指标身份"判据。
- [ ] **Step 4: 跨文档自洽性扫描** — 跑 `doc-consistency-review` skill 检查 03/10/01 改动一致。
- [ ] **Step 5: 提交**

```bash
git add docs/10-api-contract.md docs/03-rule-expression.md docs/01-concepts.md
git commit -m "docs: payload 直接引用契约/概念/表达式更新"
```

---

## Task 14: 全量回归 + 收尾

- [ ] **Step 1: 全量 clean test** — 用 mvn-env 设置环境后 `$MVN clean test`,预期 BUILD SUCCESS。
- [ ] **Step 2:** 若任一模块失败,定位修复后重跑(不得 skip)。
- [ ] **Step 3:** (可选)启动 rule-app,按 examples high-risk-login 的 curl 剧本验证 payload 引用命中、查 node_trace.value_source=PAYLOAD。
- [ ] **Step 4: 调用 `rule-engine-reviewer` agent** 审"代码 ↔ 文档对齐"。

---

## Self-Review(写计划后自查)

- **Spec 覆盖**:ValueRef(T1)、ConditionNode 字段(T2)、ValueSource.PAYLOAD(T3)、装配注入(T4)、collector 跳过(T5)、type 映射(T6)、dataType 注入(T7)、发布校验(T8)、SDK DSL(T9)、端到端(T10)、调用点(T11)、数据迁移(T12)、文档(T13)、回归(T14)。spec 各节均有对应任务。
- **类型一致**:`ValueRef`(枚举)、`ConditionNode` 7 参 canonical、`AstDataTypeResolver.resolve(ast, dataTypeMap, payloadTypeMap)`、`PayloadDataTypeMapper.toDataTypeTag`、`PayloadFieldCollector.collect`、`Condition.payloadGt` —— 跨任务签名一致。
- **待实现时核对的现状假设**(已在步骤内标注):`RuleEvent` 构造参数顺序、`KernelEvaluators.defaults()` 返回类型、`DataType.LIST.tag()` 实际值、现有 `PublishServiceTest` mock 装配方式、`dataTypeMap` 作用域提升。
