# EXPRESSION_SCRIPT kernel SPI + ScriptExecutor Implementation Plan(Plan 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现。步骤用 checkbox(`- [ ]`)跟踪。

**Goal:** 在 `rule-kernel` 落地 EXPRESSION_SCRIPT 的引擎无关骨架:`ExpressionEngine`/`CompiledExpression` SPI、脚本载体 `ScriptSource`、`ScriptExecutor`(按返回类型派发 + 扁平 SCRIPT trace)、`RuleVersionSnapshot.script` 字段、`NodeType.SCRIPT`。**不含具体引擎**(CEL 是 Plan 3),用 fake `ExpressionEngine` 测派发逻辑。

**Architecture:** `ScriptExecutor implements RuleVersionExecutor`,持注入的 `Map<String,ExpressionEngine>`(按 `ScriptSource.lang` 路由),注册形态对标 D42 的 `DecisionTreeExecutor`。脚本**不进 `AstNode`**:`conditionAst=null`,脚本挂在新字段 `RuleVersionSnapshot.script`。返回类型派发复用 D64:`Boolean`→ruleHit(引擎按 binding 裁决)、`String`→决策码(in-executor 解析,对标 `AnnotatedDecideExecutor`)、`Number`→`EvalResult.score`(score-only,**score→决策分档留 Plan 4**)。

**Tech Stack:** Java 25(record/enum/sealed)、Maven 多模块、JUnit5 + AssertJ、`$MVN`(mvn-env skill,JDK 25)。

**前置依赖:** Plan 0(EvalErrorCode 枚举,已含 `SCRIPT_SOURCE_MISSING`/`SCRIPT_NO_ENGINE`/`SCRIPT_EVAL_ERROR`)已完成(commit `b0dc96c`/`3476fe9`)。设计见 `docs/superpowers/specs/2026-06-12-expression-script-rule-design.md` §5.1/§5.2/§5.4/§5.5。

**plan 序列定位:** 第 2 个 plan。前置 Plan 0 ✅;本 plan 产出 kernel 骨架(fake engine 可测);后续 Plan 3(`rule-expression-cel` CEL 实现 + Caffeine 缓存)/ Plan 4(eval-svc 装配 + config-svc 发布校验 + `script_source` 列 + score 分档 carrier + 端到端)/ Plan 5(SDK opt-in + API)。

---

## 关键事实(实现者必读)

- `mvn-env` skill 先跑(JDK 25),用 `$MVN`。测试:`$MVN -pl rule-kernel -am test`。不得 `-DskipTests` 绕过。注释用中文,public/SPI 写 Javadoc。
- `RuleVersionExecutor` SPI:`EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx)`(`rule-kernel/.../api/spi/executor/RuleVersionExecutor.java`)。
- `EvalResult` record 8 分量:`(boolean ruleHit, Decision finalDecision, List<Decision> hitDecisions, List<NodeTrace> nodeTrace, String errorCode, Double score, String category, String decision)`。有 `EvalResult.error(EvalErrorCode)`、`error(EvalErrorCode, List<NodeTrace>)`、`miss()`、`hit()` 工厂。**errorCode 字段是 String**(Plan 0 决策);用 enum 重载产出。
- `EvalContext`:`tenantId()` / `event()`(RuleEvent)/ `subject()`(Subject,**可为 null**)/ `metrics()`(`Map<String,MetricValue>`)/ `now()`(Instant)。
- `MetricValue` record:`value()`(Object)/ `dataType()` / `valueSource()` / `errorCode()`。
- `Subject` record:`subjectId()` / `subjectType()` / `attributes()`(`Map<String,Object>`)。
- `RuleEvent` record:`payload()`(`Map<String,Object>`)等。
- `RuleVersionSnapshot.decisionBindings()` → `List<DecisionBinding(decisionCode,name,priority)>`。
- `Decision` 构造:`new Decision(code, name, priority, fromRuleVersionId, code/*category*/...)`——精确用 `new Decision(b.decisionCode(), b.name(), b.priority(), snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null)`(7 参,对标 `AnnotatedDecideExecutor.java:56`)。
- `NodeType` enum(tag 字符串):现有 AND/OR/NOT/XOR/IF/CONDITION/DECISION_LEAF/DECISION_TABLE_ROW/SCORECARD_ROOT。
- `TraceScope.COLLECT`(`ScopedValue<Boolean>`,`.orElse(true)`)——trace 收集守卫,关闭即零分配(对齐现有执行器)。
- **EvalEngine 决策裁决**:executor 返回非空 `hitDecisions` 则引擎用之(D64"executor 自选决策");返回 `ruleHit=true` 但空 `hitDecisions`(如 AST_BOOLEAN)则引擎按 `decisionBindings` 裁决。故 **Boolean 路径返回 `ruleHit` 不带 decision**(对标 `InterpretedExecutor.java:67`),**String 路径返回 hitDecisions**(对标 `AnnotatedDecideExecutor`)。

---

## Task 1: `ExpressionLang` 枚举 + `ScriptSource` 载体

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ExpressionLang.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ScriptSource.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/model/ScriptSourceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.model;

import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptSourceTest {

    @Test
    void blankLangDefaultsToCel() {
        ScriptSource s = new ScriptSource("payload.amount > 10", null);
        assertThat(s.lang()).isEqualTo(ExpressionLang.CEL.tag());
        assertThat(ExpressionLang.CEL.tag()).isEqualTo("CEL");
    }

    @Test
    void explicitLangKept() {
        assertThat(new ScriptSource("x > 1", "AVIATOR").lang()).isEqualTo("AVIATOR");
    }

    @Test
    void blankSourceRejected() {
        assertThatThrownBy(() -> new ScriptSource("  ", "CEL"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=ScriptSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(类不存在)。

- [ ] **Step 3: 实现 `ExpressionLang`**

```java
package com.sstlfsj.rule.kernel.api.model;

/** 表达式引擎语言标识(== ScriptSource.lang / 路由到对应 ExpressionEngine 的 key)。开放可扩展(Aviator/Lua 为 opt-in 插件)。 */
public enum ExpressionLang {
    /** Google CEL:受限表达式语言,盒内默认引擎。 */
    CEL;

    /** 序列化/路由用的字符串标签(== 枚举名)。 */
    public String tag() {
        return name();
    }
}
```

- [ ] **Step 4: 实现 `ScriptSource`**

```java
package com.sstlfsj.rule.kernel.api.model;

/**
 * EXPRESSION_SCRIPT 规则的脚本载体;与 AST 平级、不实现 AstNode。
 * source 为表达式源码,lang 标识引擎(默认 CEL)。
 */
public record ScriptSource(String source, String lang) {
    public ScriptSource {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("script source 不能为空");
        }
        lang = (lang == null || lang.isBlank()) ? ExpressionLang.CEL.tag() : lang;
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=ScriptSourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ExpressionLang.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ScriptSource.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/model/ScriptSourceTest.java
git commit -m "feat(kernel): ScriptSource 脚本载体 + ExpressionLang(EXPRESSION_SCRIPT 骨架)"
```

---

## Task 2: `ExpressionEngine` / `CompiledExpression` SPI + 编译异常

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/expression/CompiledExpression.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/expression/ExpressionEngine.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/expression/ExpressionCompileException.java`

> 纯接口/异常,无逻辑,无需 TDD 失败测试;由 Task 6 的 ScriptExecutor 测试通过 fake 实现间接覆盖。

- [ ] **Step 1: `CompiledExpression`**

```java
package com.sstlfsj.rule.kernel.api.spi.expression;

import java.util.Set;

/** 编译后的表达式产物(引擎实现持有引擎私有句柄);referencedVariables 供发布期依赖抽取与校验。 */
public interface CompiledExpression {
    /**
     * 表达式引用的变量点路径集合(如 "metrics.txn_cnt_1d" / "payload.amount")。
     * @return 引用变量集合;无法静态枚举时返回空集
     */
    Set<String> referencedVariables();
}
```

- [ ] **Step 2: `ExpressionCompileException`**

```java
package com.sstlfsj.rule.kernel.api.spi.expression;

/** 表达式编译/类型检查失败(语法错、未知变量、类型不符等);发布期校验捕获并拒绝。 */
public class ExpressionCompileException extends RuntimeException {
    public ExpressionCompileException(String message) {
        super(message);
    }

    public ExpressionCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: `ExpressionEngine`**

```java
package com.sstlfsj.rule.kernel.api.spi.expression;

import java.util.Map;

/**
 * 受限表达式引擎 SPI:编译为可缓存的 CompiledExpression,按只读变量绑定求值。
 * 实现须线程安全(单例共享);实现内部应按源码内容哈希缓存编译产物。
 */
public interface ExpressionEngine {
    /**
     * 引擎标识,与 {@link com.sstlfsj.rule.kernel.api.model.ScriptSource#lang()} 路由匹配(如 "CEL")。
     * @return 引擎语言标签
     */
    String lang();

    /**
     * 编译源码(含语法/类型检查);失败抛 {@link ExpressionCompileException}。
     * @param source 表达式源码
     * @return 编译产物(实现可缓存)
     */
    CompiledExpression compile(String source);

    /**
     * 对编译产物按只读变量绑定求值。
     * @param compiled 编译产物
     * @param bindings 顶层变量绑定(键如 "metrics"/"payload"/"subject"/"now")
     * @return Boolean / String / Number 之一,或 null(不命中)
     */
    Object evaluate(CompiledExpression compiled, Map<String, Object> bindings);
}
```

- [ ] **Step 4: 编译确认**

Run: `$MVN -pl rule-kernel -am test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/expression/
git commit -m "feat(kernel): ExpressionEngine/CompiledExpression SPI + ExpressionCompileException"
```

---

## Task 3: `NodeType.SCRIPT`

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeType.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/model/NodeTypeScriptTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.model;

import com.sstlfsj.rule.kernel.api.model.NodeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NodeTypeScriptTest {
    @Test
    void scriptTagIsStable() {
        assertThat(NodeType.SCRIPT.tag()).isEqualTo("ScriptNode");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=NodeTypeScriptTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`NodeType.SCRIPT` 不存在)。

- [ ] **Step 3: 加枚举值**

在 `NodeType` 枚举值列表末尾(`SCORECARD_ROOT("ScorecardRoot")` 后)加:

```java
    SCORECARD_ROOT("ScorecardRoot"),
    /** EXPRESSION_SCRIPT 规则的扁平单节点 trace 类型。 */
    SCRIPT("ScriptNode");
```

(注意:把原 `SCORECARD_ROOT("ScorecardRoot");` 的分号移到 `SCRIPT(...)` 后。)

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=NodeTypeScriptTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeType.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/model/NodeTypeScriptTest.java
git commit -m "feat(kernel): NodeType.SCRIPT(脚本扁平 trace 节点类型)"
```

---

## Task 4: `RuleVersionSnapshot.script` 字段(向后兼容)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/model/RuleVersionSnapshotScriptTest.java`

> 关键:加 `script` 为**第 13 个**分量,并保留**旧 12 参构造**(script=null)+ 旧 8 参构造,使所有既有 `new RuleVersionSnapshot(...)` 调用点(PublishService/SnapshotAssembler/测试)**零改动**。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.model;

import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionSnapshotScriptTest {

    @Test
    void scriptDefaultsNullForAstKinds() {
        RuleVersionSnapshot s = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.AST_BOOLEAN.tag());
        assertThat(s.script()).isNull();
    }

    @Test
    void builderCarriesScript() {
        ScriptSource src = new ScriptSource("payload.amount > 10 ? 'REVIEW' : 'PASS'", "CEL");
        RuleVersionSnapshot s = RuleVersionSnapshot.builder()
                .sceneCode("scene").kind(RuleKind.EXPRESSION_SCRIPT.tag())
                .script(src)
                .build();
        assertThat(s.script()).isEqualTo(src);
        assertThat(s.conditionAst()).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=RuleVersionSnapshotScriptTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`script()` / `.script(...)` 不存在)。

- [ ] **Step 3: 加 `script` 分量 + 向后兼容构造**

3a. 在 record 头部 `payloadDependencies` 分量后**加第 13 个分量**(注意把 `payloadDependencies` 后的 `)` 前加逗号):

```java
        /** AST 引用的 payload 字段依赖，发布期从 scene.payloadSchema 冻结。 */
        List<PayloadDependency> payloadDependencies,
        /** EXPRESSION_SCRIPT 规则的脚本载体;其它 kind 为 null。 */
        ScriptSource script
) {
```

3b. import:文件顶部加 `import com.sstlfsj.rule.kernel.api.model.ScriptSource;` —— **不需要**(同包 `com.sstlfsj.rule.kernel.api.model`)。确认 ScriptSource 同包即可。

3c. 紧凑构造器(`public RuleVersionSnapshot {...}`)无需为 script 加归一化(null 合法),保持不变。

3d. **保留旧 12 参签名作为便利构造**(委托 script=null),加在紧凑构造器后:

```java
    /**
     * 兼容旧 12 参调用点(无 script,默认 null)。
     *
     * @param ruleVersionId       规则版本 id
     * @param sceneCode           场景编码
     * @param tenantId            租户 id
     * @param conditionAst        条件 AST 根节点
     * @param preGates            Pre-Gate 配置列表
     * @param decisionBindings    Decision 绑定列表
     * @param triggerEventTypes   监听事件类型列表
     * @param kind                规则类型
     * @param code                逻辑规则编码
     * @param version             版本号
     * @param metricDependencies  metric 依赖
     * @param payloadDependencies payload 依赖
     */
    public RuleVersionSnapshot(Long ruleVersionId, String sceneCode, String tenantId, AstNode conditionAst,
                               List<PreGateConfig> preGates, List<DecisionBinding> decisionBindings,
                               List<String> triggerEventTypes, String kind, String code, long version,
                               List<MetricDependency> metricDependencies, List<PayloadDependency> payloadDependencies) {
        this(ruleVersionId, sceneCode, tenantId, conditionAst, preGates, decisionBindings,
                triggerEventTypes, kind, code, version, metricDependencies, payloadDependencies, null);
    }
```

3e. 旧 8 参便利构造当前体为 `this(..., kind, null, 0L, List.of(), List.of());`(12 参)——它现在会绑定到上面新增的 12 参便利构造,**无需改动**。

3f. Builder:加字段 + setter + build() 传参。
- 字段区加:`private ScriptSource script;`
- setter(加在 `conditionAst` setter 附近):
```java
        /** EXPRESSION_SCRIPT 脚本载体。 */
        public Builder script(ScriptSource v) { this.script = v; return this; }
```
- `build()` 改为 13 参(末尾加 `script`):
```java
        public RuleVersionSnapshot build() {
            return new RuleVersionSnapshot(ruleVersionId, sceneCode, tenantId, conditionAst,
                    preGates, decisionBindings, triggerEventTypes, kind, code, version,
                    metricDependencies, payloadDependencies, script);
        }
```

- [ ] **Step 4: 跑测试确认通过 + kernel 全量绿(验证旧调用点未破)**

Run: `$MVN -pl rule-kernel -am test`
Expected: BUILD SUCCESS(新测试通过 + 既有快照相关测试因 12 参/8 参便利构造保留而不受影响)。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/model/RuleVersionSnapshotScriptTest.java
git commit -m "feat(kernel): RuleVersionSnapshot.script 字段(向后兼容旧构造,EXPRESSION_SCRIPT 载体)"
```

---

## Task 5: `ScriptBindings` 变量绑定面工具

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScriptBindings.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScriptBindingsTest.java`

> 从 `EvalContext` 构造只读 binding map:顶层键 `metrics`/`payload`/`subject`/`now`,供引擎按 `metrics.<code>` 等点路径访问。subject 可为 null。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptBindings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptBindingsTest {

    @SuppressWarnings("unchecked")
    @Test
    void buildsNamespacedReadonlyMap() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        RuleEvent event = new RuleEvent("t1", "scene", "TXN", "u1", "e1", now,
                Map.of("amount", 12000), Map.of(), EventSource.HTTP);
        Subject subject = new Subject("u1", SubjectType.USER, Map.of("level", "VIP"));
        Map<String, MetricValue> metrics = Map.of(
                "txn_cnt_1d", new MetricValue(53L, "LONG", "FETCHED"));
        EvalContext ctx = new EvalContext("t1", event, subject, metrics, now);

        Map<String, Object> b = ScriptBindings.from(ctx);

        assertThat((Map<String, Object>) b.get("metrics")).containsEntry("txn_cnt_1d", 53L);
        assertThat((Map<String, Object>) b.get("payload")).containsEntry("amount", 12000);
        assertThat((Map<String, Object>) b.get("subject")).containsEntry("level", "VIP");
        assertThat(b.get("now")).isEqualTo(now);
    }

    @Test
    void nullSubjectYieldsEmptySubjectMap() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        RuleEvent event = new RuleEvent("t1", "scene", "TXN", "u1", "e1", now,
                Map.of(), Map.of(), EventSource.HTTP);
        EvalContext ctx = new EvalContext("t1", event, null, Map.of(), now);

        Map<String, Object> b = ScriptBindings.from(ctx);

        assertThat(b.get("subject")).isEqualTo(Map.of());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=ScriptBindingsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`ScriptBindings` 不存在)。

- [ ] **Step 3: 实现 `ScriptBindings`**

```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.Subject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 EvalContext 投影成脚本引擎用的只读变量绑定面:
 * metrics.&lt;code&gt; / payload.&lt;field&gt; / subject.&lt;attr&gt; / now。
 * ScriptExecutor 与(将来)方案 B 共用,引擎无关。
 */
public final class ScriptBindings {

    private ScriptBindings() {}

    /**
     * 构造顶层命名空间绑定 map(metrics/payload/subject/now)。
     *
     * @param ctx 评估上下文(subject 可为 null)
     * @return 不可变绑定 map
     */
    public static Map<String, Object> from(EvalContext ctx) {
        // 取数失败时 MetricValue.value() 为 null,子 map 须容忍 null value(Map.copyOf/Map.of 会拒 null)
        Map<String, Object> metrics = HashMap.newHashMap(ctx.metrics().size());
        for (Map.Entry<String, MetricValue> e : ctx.metrics().entrySet()) {
            metrics.put(e.getKey(), e.getValue().value());
        }
        Subject subject = ctx.subject();
        Map<String, Object> subjectAttrs = subject == null ? Map.of() : subject.attributes();
        // 顶层 4 个 value 均非 null(metrics 子 map 非 null、payload 归一化非 null、now 必填),故 Map.of 可用;
        // 仅 metrics 子 map 内部允许 null value,用 unmodifiableMap(HashMap) 而非 Map.copyOf
        return Map.of(
                "metrics", Collections.unmodifiableMap(metrics),
                "payload", ctx.event().payload(),
                "subject", subjectAttrs,
                "now", ctx.now());
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=ScriptBindingsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScriptBindings.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScriptBindingsTest.java
git commit -m "feat(kernel): ScriptBindings(EvalContext→脚本变量绑定面)"
```

---

## Task 6: `ScriptExecutor`(核心派发 + 扁平 SCRIPT trace)

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScriptExecutor.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScriptExecutorTest.java`

> 派发语义(§5.4):`script()==null`→`SCRIPT_SOURCE_MISSING`;无 engine→`SCRIPT_NO_ENGINE`;求值抛错→`SCRIPT_EVAL_ERROR`;`Boolean`→ruleHit(空 hitDecisions,引擎裁决 binding);`String`→决策码(∈bindings 则 hitDecisions,∉则 `INVALID_DECISION_CODE`);`Number`→`EvalResult.score`(score-only,**决策分档留 Plan 4**);`null`→miss。trace:collect 时单节点 SCRIPT(result + 输出值),否则空。

- [ ] **Step 1: 写失败测试(fake engine 覆盖全部派发分支)**

```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptExecutorTest {

    // fake engine:compile 返回固定产物,evaluate 返回预设值(或抛错)
    private static final class FakeEngine implements ExpressionEngine {
        private final Object result;
        private final boolean throwOnEval;
        FakeEngine(Object result) { this(result, false); }
        FakeEngine(Object result, boolean throwOnEval) { this.result = result; this.throwOnEval = throwOnEval; }
        public String lang() { return "CEL"; }
        public CompiledExpression compile(String source) { return Set::of; }
        public Object evaluate(CompiledExpression c, Map<String, Object> b) {
            if (throwOnEval) throw new RuntimeException("boom");
            return result;
        }
    }

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene", "TXN", "u1", "e1", Instant.now(),
                Map.of(), Map.of(), EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private RuleVersionSnapshot scriptSnapshot(String lang, String... bindingCodes) {
        List<RuleVersionSnapshot.DecisionBinding> bindings = java.util.Arrays.stream(bindingCodes)
                .map(c -> new RuleVersionSnapshot.DecisionBinding(c, 10)).toList();
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("scene").tenantId("t1")
                .kind(RuleKind.EXPRESSION_SCRIPT.tag())
                .script(new ScriptSource("expr", lang))
                .code("R1").version(1L)
                .build()
                .toBuilderBindings(bindings); // 见下:无 toBuilder,改用直接构造
    }

    private ScriptExecutor executor(ExpressionEngine engine) {
        return new ScriptExecutor(Map.of(engine.lang(), engine));
    }

    @Test
    void booleanTrueHitsWithoutDecision() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 10)),
                List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L, List.of(), List.of(),
                new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(Boolean.TRUE)).execute(snap, ctx());
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.hitDecisions()).isEmpty();      // 引擎裁决 binding(对标 AST_BOOLEAN)
        assertThat(r.finalDecision()).isNull();
    }

    @Test
    void booleanFalseMisses() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(Boolean.FALSE)).execute(snap, ctx());
        assertThat(r.ruleHit()).isFalse();
    }

    @Test
    void stringReturnsBoundDecision() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(new RuleVersionSnapshot.DecisionBinding("REVIEW", "审核", 10)),
                List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L, List.of(), List.of(),
                new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine("REVIEW")).execute(snap, ctx());
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");
        assertThat(r.hitDecisions()).hasSize(1);
    }

    @Test
    void stringNotInBindingsYieldsInvalidDecisionCode() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 10)),
                List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L, List.of(), List.of(),
                new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine("NOT_BOUND")).execute(snap, ctx());
        assertThat(r.ruleHit()).isFalse();
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.INVALID_DECISION_CODE.name());
    }

    @Test
    void numberSetsScoreOnly() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(72.5)).execute(snap, ctx());
        assertThat(r.score()).isEqualTo(72.5);
        assertThat(r.ruleHit()).isFalse();           // score-only;决策分档留 Plan 4
        assertThat(r.finalDecision()).isNull();
    }

    @Test
    void nullResultMisses() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(null)).execute(snap, ctx());
        assertThat(r.ruleHit()).isFalse();
    }

    @Test
    void nullScriptYieldsSourceMissing() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), null);
        EvalResult r = executor(new FakeEngine(Boolean.TRUE)).execute(snap, ctx());
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.SCRIPT_SOURCE_MISSING.name());
    }

    @Test
    void unknownLangYieldsNoEngine() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "LUA"));
        EvalResult r = executor(new FakeEngine(Boolean.TRUE)).execute(snap, ctx()); // engine.lang()=CEL,无 LUA
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.SCRIPT_NO_ENGINE.name());
    }

    @Test
    void evalThrowYieldsScriptEvalError() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.EXPRESSION_SCRIPT.tag(), "R1", 1L,
                List.of(), List.of(), new ScriptSource("expr", "CEL"));
        EvalResult r = executor(new FakeEngine(null, true)).execute(snap, ctx());
        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.SCRIPT_EVAL_ERROR.name());
    }
}
```

> 注:上面 `scriptSnapshot(...)` 辅助方法里写了一个不存在的 `toBuilderBindings`——**删掉该辅助方法**,各测试已直接用 13 参构造,不需要它。实现者写测试时不要保留 `scriptSnapshot`。

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=ScriptExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`ScriptExecutor` 不存在)。

- [ ] **Step 3: 实现 `ScriptExecutor`**

```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;

import java.util.List;
import java.util.Map;

/**
 * EXPRESSION_SCRIPT executor:把脚本源码交给按 lang 路由的 {@link ExpressionEngine} 求值,
 * 按返回值运行时类型派发(复用 D64):Boolean→ruleHit、String→决策码、Number→score。
 * 脚本不是 AST,trace 为单节点扁平 SCRIPT(记输出值),受 {@link TraceScope#COLLECT} 守卫。
 */
public class ScriptExecutor implements RuleVersionExecutor {

    private final Map<String, ExpressionEngine> engines;

    /**
     * @param engines lang 到 ExpressionEngine 的映射(空 map = 未 opt-in 任何引擎)
     */
    public ScriptExecutor(Map<String, ExpressionEngine> engines) {
        this.engines = Map.copyOf(engines);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        ScriptSource script = snapshot.script();
        if (script == null) {
            return EvalResult.error(EvalErrorCode.SCRIPT_SOURCE_MISSING);
        }
        ExpressionEngine engine = engines.get(script.lang());
        if (engine == null) {
            return EvalResult.error(EvalErrorCode.SCRIPT_NO_ENGINE);
        }

        Object result;
        try {
            CompiledExpression compiled = engine.compile(script.source());
            result = engine.evaluate(compiled, ScriptBindings.from(ctx));
        } catch (Exception e) {
            return EvalResult.error(EvalErrorCode.SCRIPT_EVAL_ERROR, scriptTrace(false, "ERROR", snapshot));
        }

        return switch (result) {
            case null -> EvalResult.miss(scriptTrace(false, null, snapshot));
            case Boolean b -> new EvalResult(b, null, List.of(), scriptTrace(b, b, snapshot),
                    null, null, null, null);
            case String code -> dispatchDecision(code, snapshot);
            case Number n -> new EvalResult(false, null, List.of(), scriptTrace(false, n, snapshot),
                    null, n.doubleValue(), null, null);
            default -> EvalResult.error(EvalErrorCode.SCRIPT_EVAL_ERROR, scriptTrace(false, "TYPE", snapshot));
        };
    }

    /** String 返回:决策码须 ∈ decisionBindings,否则 INVALID_DECISION_CODE。 */
    private EvalResult dispatchDecision(String code, RuleVersionSnapshot snapshot) {
        RuleVersionSnapshot.DecisionBinding binding = snapshot.decisionBindings().stream()
                .filter(b -> b.decisionCode().equals(code))
                .findFirst()
                .orElse(null);
        if (binding == null) {
            return EvalResult.error(EvalErrorCode.INVALID_DECISION_CODE, scriptTrace(false, code, snapshot));
        }
        Decision d = new Decision(binding.decisionCode(), binding.name(), binding.priority(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null);
        return new EvalResult(true, d, List.of(d), scriptTrace(true, code, snapshot),
                null, null, d.category(), d.code());
    }

    /** 单节点扁平 SCRIPT trace(actualValue=脚本输出);非收集模式返回空列表(零分配契约)。 */
    private static List<NodeTrace> scriptTrace(boolean result, Object output, RuleVersionSnapshot snapshot) {
        if (!TraceScope.COLLECT.orElse(true)) {
            return List.of();
        }
        return List.of(new NodeTrace(
                NodeType.SCRIPT.tag(), null, null, result, output, null, null, List.of(),
                snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null, null));
    }
}
```

> 说明:`NodeTrace` 只有唯一一个 13 参 canonical 构造器(第 7 位 `String errorCode`),`EvalErrorCode` 变体是静态工厂 `container(...)` 而非构造器重载,故第 7 位直接传 `null` 无歧义(无需强转)。`actualValue` 承载脚本输出值,满足 §5.5"记输出值";绑定变量明细与源码哈希的富化 trace 留待 Plan 4(届时随真实引擎/绑定补 NodeTrace 字段或子节点)。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=ScriptExecutorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS(9 个用例)。

- [ ] **Step 5: kernel 全量绿**

Run: `$MVN -pl rule-kernel -am test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScriptExecutor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScriptExecutorTest.java
git commit -m "feat(kernel): ScriptExecutor(EXPRESSION_SCRIPT 派发+扁平 trace,fake engine 测)"
```

---

## Self-Review(已执行)

**1. Spec 覆盖**:对应 spec §5.1(ScriptSource 非 AstNode + snapshot.script)Task 1/4;§5.2(ExpressionEngine/CompiledExpression SPI)Task 2;§5.3(变量绑定面)Task 5;§5.4(ScriptExecutor 派发)Task 6;§5.5(扁平 SCRIPT trace)Task 6 + NodeType.SCRIPT Task 3。**已知缺口**:§5.4 的 Number→决策分档,本 plan 仅做 score-only,分档 carrier 设计 + 实现明确**留 Plan 4**(已在 Goal/Task 6 标注)。

**2. 占位扫描**:无 TBD/TODO。两处"陷阱注释"(Task 6 测试里误写的 `scriptSnapshot/toBuilderBindings` 提示删除、`(String) null` 重载选择说明)已显式说明,非占位。

**3. 类型一致**:`ScriptExecutor(Map<String,ExpressionEngine>)` 构造与 Task 6 测试一致;`RuleVersionSnapshot` 13 参构造(末尾 `ScriptSource script`)与 Task 4 定义、Task 6 测试一致;`EvalResult.error(EvalErrorCode)` / `error(EvalErrorCode, List)` 用 Plan 0 已建的 enum 重载;`errorCode()` 断言比 `EvalErrorCode.X.name()`(字段是 String,Plan 0 决策)。

**4. 歧义**:Boolean 路径明确"返回 ruleHit 空 hitDecisions,引擎裁决 binding"(对标 InterpretedExecutor),String 路径"in-executor 解析 hitDecisions"(对标 AnnotatedDecideExecutor)——两条与 EvalEngine.resolveRuleDecisions 既有契约一致,已在关键事实区写明。

**一个执行顺序提示**:Task 4 改 record 会触发 `RuleVersionSnapshot` 全量重编;因保留 12/8 参便利构造,既有调用点(PublishService/SnapshotAssembler/各测试)零改动,Task 4 Step 4 全量 kernel 测试即验证此点。
