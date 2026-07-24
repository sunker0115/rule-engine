# D74 参数化规则模板(重设计)— 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重写参数化规则模板:JsonPointer 统一寻址覆盖全部 6 kind + params 冻结常量命名空间消除 Script/Flow 异类,bodySkeleton 恒为合法 body、slot+binding 显式 sidecar、TemplateBinder SPI 单点分派

**Architecture:** 自底向上——kernel 加 params 命名空间(唯一 kernel/eval 触点)→ config-svc 建 DTO/SPI/JsonPointerBinder → 重写 Service/实体/迁移 → rule-api 改 Controller/DTO(删 export 端点)→ 前端 types+editor 重写。每 Task 收尾同步更新测试,同 Task 同 commit(遵项目"测试与实现同 commit"纪律)

**Tech Stack:** Java 25 / Spring Boot 4 Modulith / MyBatis-Plus / Jackson3 (tools.jackson) / React+TS 前端 / MySQL 8.0

**首版代码处置(就地重写,不整体回退):** 全部未提交。KEEP:`RuleVersion`/`PublishService.createDraft` 重载/`AuditTargetType`/`AuditSnapshot`/`RuleTemplateStatus`/`RuleTemplateMapper`/菜单/路由/i18n/feature flag;REWRITE:ServiceImpl/实体/DTO/Controller/迁移/前端 types+editor;DELETE:反向导出(ExportFromRuleRequest+前端导出入口);NEW:SlotBinding/SlotTarget/TemplateBinder/JsonPointerBinder/params 字段

## Global Constraints

- 功能开关默认关闭(`rule.template.enabled=false`+前端 `FEATURES.templates=false`),所有新 bean 带 `@ConditionalOnProperty`
- 封闭取值用 enum(kernel `DataType`),不用魔法字符串;`dataType` 复用 kernel `DataType`(非 `SlotDataType`)
- DTO/实体 JSON 列 typed(Jackson3TypeHandler),不手写 ObjectMapper 序列化、不传 JSON String
- 副作用走事件:模板 create/update/publish/disable 走 `OperationAuditedEvent`
- 日志 SLF4J,记录用 Lombok(构造/Builder 视参数量),多参走 Builder
- 测试与实现同 Task 同 commit,提交前 `mvn -pl <module> -am test` 绿,最终 `mvn clean test` 兜底
- status/state 用 enum(如 `RuleTemplateStatus`),禁止魔法字符串
- DB 列用 VARCHAR 不用 MySQL ENUM;加列/表迁移带 `COLLATE=utf8mb4_unicode_ci`

---

### Task 1: ScriptSource 加 params 字段(kernel 模型)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ScriptSource.java`
- New: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ScriptSourceTest.java`

**Interfaces:**
- Produces: `ScriptSource(String source, String lang, Map<String,Object> params)` + 向后兼容 `ScriptSource(String source, String lang)`(默认空 params)

**Context:** 脚本参数化需 `params` 冻结常量命名空间。`ScriptSource` 现为 `record(String source, String lang)`,加 `Map<String,Object> params`。保留旧 2 参构造器默认空 params,全仓 ~20 个 `new ScriptSource(s, l)` 调用点零改动。compact 构造器 null→Map.of() 兜底,Jackson3 反序列化缺键自动安全。

- [ ] **Step 1: 写测试**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ScriptSourceTest.java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptSourceTest {

    @Test
    void twoArgConstructor_defaultsParamsToEmptyMap() {
        ScriptSource s = new ScriptSource("metrics.amount > 100", "CEL");
        assertThat(s.params()).isEmpty();
    }

    @Test
    void threeArgConstructor_preservesParams() {
        Map<String, Object> params = Map.of("threshold", 100);
        ScriptSource s = new ScriptSource("expr", "CEL", params);
        assertThat(s.params()).containsEntry("threshold", 100);
    }

    @Test
    void paramsAreImmutable() {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("k", "v");
        ScriptSource s = new ScriptSource("expr", "CEL", p);
        p.put("extra", "x");
        assertThat(s.params()).hasSize(1);
        assertThrows(UnsupportedOperationException.class, () -> s.params().put("k2", "v2"));
    }

    @Test
    void nullParams_defaultsToEmptyMap() {
        ScriptSource s = new ScriptSource("expr", "CEL", null);
        assertThat(s.params()).isEmpty();
    }

    @Test
    void recordEquality_includesParams() {
        ScriptSource a = new ScriptSource("e", "CEL", Map.of("x", 1));
        ScriptSource b = new ScriptSource("e", "CEL", Map.of("x", 1));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void recordEquality_paramsDiff_notEqual() {
        ScriptSource a = new ScriptSource("e", "CEL", Map.of("x", 1));
        ScriptSource b = new ScriptSource("e", "CEL", Map.of("x", 2));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void blankSource_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScriptSource("  ", "CEL"));
    }

    @Test
    void nullLang_defaultsToCEL() {
        ScriptSource s = new ScriptSource("expr", null);
        assertThat(s.lang()).isEqualTo(ExpressionLang.CEL.tag());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-kernel test -Dtest=ScriptSourceTest
```
因 `ScriptSource` 无 3 参构造器,编译失败。

- [ ] **Step 3: 改写 ScriptSource**

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ScriptSource.java
package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/**
 * EXPRESSION_SCRIPT 规则的脚本载体;与 AST 平级、不实现 AstNode。
 * source 为表达式源码,lang 标识引擎(默认 CEL),params 为冻结常量命名空间(求值期并入 binding 的顶层 {@code params} key)。
 */
public record ScriptSource(String source, String lang, Map<String, Object> params) {

    public ScriptSource {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("script source 不能为空");
        }
        lang = (lang == null || lang.isBlank()) ? ExpressionLang.CEL.tag() : lang;
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 向后兼容:无冻结常量的脚本(默认空 params)。 */
    public ScriptSource(String source, String lang) {
        this(source, lang, Map.of());
    }
}
```

- [ ] **Step 4: 跑测试验证通过**

```bash
$MVN -pl rule-kernel test -Dtest=ScriptSourceTest
```
预期:PASS。再跑模块全量确认无编译断裂:`$MVN -pl rule-kernel test`(含 ScriptExecutorTest 等用旧 2 参构造器的测试,应全绿)。

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ScriptSource.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/ScriptSourceTest.java
git commit -m "feat(kernel): ScriptSource 加 params 冻结常量字段,向后兼容旧 2 参构造器"
```

---

### Task 2: FlowGraph 加 params 字段(kernel 模型)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/flow/FlowGraph.java`
- New: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/flow/FlowGraphTest.java`(不存在则建;存在则补 params 增量测试)

**Interfaces:**
- Produces: `FlowGraph(List<FlowNode> nodes, List<FlowEdge> edges, String inputNodeId, Map<String,Object> params)` + 向后兼容旧 3 参构造器

- [ ] **Step 1: 写测试**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/flow/FlowGraphTest.java
package com.sstlfsj.rule.kernel.api.model.flow;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FlowGraphTest {

    @Test
    void threeArgConstructor_defaultsParamsToEmptyMap() {
        FlowGraph graph = new FlowGraph(
                List.of(new OutputNode("out", "PASS")), List.of(), "out");
        assertThat(graph.params()).isEmpty();
    }

    @Test
    void fourArgConstructor_preservesParams() {
        Map<String, Object> params = Map.of("threshold", 100);
        FlowGraph graph = new FlowGraph(
                List.of(new OutputNode("out", "PASS")), List.of(), "out", params);
        assertThat(graph.params()).containsEntry("threshold", 100);
    }

    @Test
    void paramsAreImmutable() {
        java.util.Map<String, Object> mutable = new java.util.HashMap<>();
        mutable.put("k", "v");
        FlowGraph graph = new FlowGraph(List.of(), List.of(), "in", mutable);
        mutable.put("extra", "x");
        assertThat(graph.params()).hasSize(1);
    }

    @Test
    void nullParams_defaultsToEmpty() {
        FlowGraph graph = new FlowGraph(List.of(), List.of(), "in", null);
        assertThat(graph.params()).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败(编译错误)**

```bash
$MVN -pl rule-kernel test -Dtest=FlowGraphTest
```

- [ ] **Step 3: 改写 FlowGraph**

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/flow/FlowGraph.java
package com.sstlfsj.rule.kernel.api.model.flow;

import java.util.List;
import java.util.Map;

/**
 * 决策图:节点 + 有向边 + 入口 + 冻结常量。
 * 图只做编排,叶子逻辑由 {@link RuleRefNode} 引用的独立规则承载。
 *
 * @param nodes       全部节点
 * @param edges       有向边(Switch 出边带 caseKey)
 * @param inputNodeId 入口节点 id
 * @param params      冻结常量命名空间(求值期并入 binding 的 {@code params} key),缺省空 map
 */
public record FlowGraph(
        List<FlowNode> nodes,
        List<FlowEdge> edges,
        String inputNodeId,
        Map<String, Object> params) {

    public FlowGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** 向后兼容:无冻结常量的 flow 图。 */
    public FlowGraph(List<FlowNode> nodes, List<FlowEdge> edges, String inputNodeId) {
        this(nodes, edges, inputNodeId, Map.of());
    }
}
```

- [ ] **Step 4: 跑测试 + 模块全量**

```bash
$MVN -pl rule-kernel test -Dtest=FlowGraphTest  # → PASS
$MVN -pl rule-kernel test                        # → 全量 PASS
```

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/flow/FlowGraph.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/flow/FlowGraphTest.java
git commit -m "feat(kernel): FlowGraph 加 params 冻结常量字段,向后兼容旧 3 参构造器"
```

---

### Task 3: ScriptExecutor 并入 params binding

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScriptExecutor.java`
- Modify: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScriptExecutorTest.java`

**Interfaces:**
- Consumes: `ScriptSource.params()`(Task 1)
- Produces: evaluate 上下文含 `params` 顶层 key

- [ ] **Step 1: 补测试(给现有 ScriptExecutorTest 加 params 场景)**

在 `ScriptExecutorTest.java` 中找到 FakeEngine 定义,确认它保留所有 binding key。然后加:

```java
// 追加到 ScriptExecutorTest 类内
@Test
void params_injectedIntoBinding() {
    // 初始化 executor: FakeEngine 捕获 binding,校验含 params key
    AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
    FakeEngine engine = new FakeEngine("FKE") {
        @Override
        public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
            captured.set(bindings);
            return true;
        }
    };
    ScriptExecutor exec = new ScriptExecutor(Map.of("FKE", engine));
    ScriptSource src = new ScriptSource("params.threshold > 0", "FKE", Map.of("threshold", 100));
    RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
            .ruleVersionId(1L).sceneCode("s1").tenantId("t1")
            .script(src).kind("EXPRESSION_SCRIPT").build();
    exec.execute(snap, EvalContexts.minimal());
    assertThat(captured.get()).containsKey("params");
    assertThat((Map<?, ?>) captured.get().get("params")).containsEntry("threshold", 100);
}

@Test
void params_emptyMapWhenNoParams() {
    AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
    FakeEngine engine = new FakeEngine("FKE") {
        @Override
        public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
            captured.set(bindings);
            return true;
        }
    };
    ScriptExecutor exec = new ScriptExecutor(Map.of("FKE", engine));
    ScriptSource src = new ScriptSource("expr", "FKE"); // 旧 2 参,空 params
    RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
            .ruleVersionId(1L).sceneCode("s1").tenantId("t1")
            .script(src).kind("EXPRESSION_SCRIPT").build();
    exec.execute(snap, EvalContexts.minimal());
    assertThat((Map<?, ?>) captured.get().get("params")).isEmpty();
}
```

- [ ] **Step 2: 跑测试确认失败(assert 找不到 params key)**

```bash
$MVN -pl rule-kernel test -Dtest=ScriptExecutorTest
```

- [ ] **Step 3: 改 ScriptExecutor.execute**

```java
// ScriptExecutor.java execute 方法第 51–54 行,把:
//   result = engine.evaluate(compiled, ScriptBindings.from(ctx));
// 改为:
Map<String, Object> bindings = new HashMap<>(ScriptBindings.from(ctx));
bindings.put("params", script.params());
result = engine.evaluate(compiled, bindings);
```

需加 import `java.util.HashMap`(已存在)。

- [ ] **Step 4: 跑测试验证**

```bash
$MVN -pl rule-kernel test -Dtest=ScriptExecutorTest  # → PASS
```

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScriptExecutor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/ScriptExecutorTest.java
git commit -m "feat(kernel): ScriptExecutor 求值时将 params 并入引擎 binding 顶层 key"
```

---

### Task 4: FlowExecutor 并入 params binding

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/FlowExecutor.java`
- Modify: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/FlowExecutorTest.java`

**Interfaces:**
- Consumes: `FlowGraph.params()`(Task 2)
- Produces: `evalExpr` 绑定含 `params` key

- [ ] **Step 1: 补测试**

在 `FlowExecutorTest` 中已有的 flow 图构造里加 params:

```java
// 追加到 FlowExecutorTest
@Test
void paramsFromFlowGraph_injectedIntoBinding() {
    // 在 SwitchNode expression 中引用 params.threshold,验证求值成功
    FlowGraph graph = new FlowGraph(
            List.of(
                    new RuleRefNode("ref1", "rule-a"),
                    new OutputNode("out", "PASS")
            ),
            List.of(new FlowEdge("ref1", "out", null)),
            "ref1",
            Map.of("threshold", 50)  // params
    );
    // ... 建 snapshot + executor,验证 RuleRef 表达式能读到 params.threshold
    // 最小化:验证 FlowBody 的 graph.params() 有值
    FlowBody body = new FlowBody(graph, Map.of());
    assertThat(body.flowGraph().params()).containsEntry("threshold", 50);
}

@Test
void switchExpression_readsParams() {
    SwitchNode sw = new SwitchNode("sw1", "CEL", "params.threshold > 50");
    FlowGraph graph = new FlowGraph(
            List.of(sw, new OutputNode("out1", "LOW"), new OutputNode("out2", "HIGH")),
            List.of(new FlowEdge("sw1", "out1", "true"), new FlowEdge("sw1", "out2", "false")),
            "sw1",
            Map.of("threshold", 75)
    );
    // params.threshold=75 > 50 → true → out1("LOW")
    // 用 TestEngine + FAKE 规则验证...
    // (此处需结合现有 fake executor 基础设施;最小化:建 snapshot 走 FlowExecutor)
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
$MVN -pl rule-kernel test -Dtest=FlowExecutorTest
```

- [ ] **Step 3: 改 FlowExecutor.evalExpr**

在 `FlowExecutor.Walker.evalExpr` 方法(约第 156 行),把 `graph.params()` 并入 binding:

```java
// FlowExecutor.Walker.evalExpr 方法内,bindings 构造处(约第 159 行)加一行:
bindings.put("params", graph.params());
```

`graph` 是 Walker 构造函数传入的 `FlowGraph` 字段,直接可用。

- [ ] **Step 4: 跑测试验证**

```bash
$MVN -pl rule-kernel test -Dtest=FlowExecutorTest  # → PASS
$MVN -pl rule-kernel test                           # → 模块全量 PASS
```

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/FlowExecutor.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/FlowExecutorTest.java
git commit -m "feat(kernel): FlowExecutor 求值时将 flowGraph.params 并入引擎 binding"
```

---

### Task 5: CelExpressionEngine 注册 params 命名空间

**Files:**
- Modify: `rule-expression/rule-expression-cel/src/main/java/com/sstlfsj/rule/expression/cel/CelExpressionEngine.java`
- Modify: `rule-expression/rule-expression-cel/src/test/java/com/sstlfsj/rule/expression/cel/CelExpressionEngineTest.java`(若存在则补;若不存在则建最小测试)

**Interfaces:**
- Consumes: `ScriptSource.params`(Task 1,运行期通过 binding 传入)
- Produces: CEL 编译器接受 `params.x` 表达式

**Context:** CEL 是唯一强类型引擎,未声明命名空间的变量引用会被 compile/typeCheck 拒。弱引擎(Aviator/JEXL/等)**零改动**(动态类型自动透明)。

- [ ] **Step 1: 检查/建测试**

```bash
# 先找已有测试
find rule-expression/rule-expression-cel/src/test -name '*CelExpressionEngine*' -o -name '*Cel*Test*' | head -5
```

若已有 `CelExpressionEngineTest`,补 params 场景;若无,建最小化测试:

```java
// rule-expression-cel/src/test/java/.../CelExpressionEngineTest.java
@Test
void compileAndEvaluate_paramsVariable() {
    CelExpressionEngine engine = new CelExpressionEngine();
    CompiledExpression ce = engine.compile("params.threshold > 50");
    Object result = engine.evaluate(ce,
            Map.of("params", Map.of("threshold", 75),
                   "metrics", Map.of(), "payload", Map.of(), "subject", Map.of(),
                   "now", java.time.Instant.now()));
    assertThat(result).isEqualTo(true);
}
```

- [ ] **Step 2: 跑测试确认 CEL compile 报 unknown variable "params"**

```bash
$MVN -pl rule-expression/rule-expression-cel test -Dtest=CelExpressionEngineTest
```

- [ ] **Step 3: 三处改动**

**3a.** 运行期 compiler 声明(构造函数第 50 行 `.build()` 前加):

```java
.addVar("params", MapType.create(SimpleType.STRING, SimpleType.DYN))
```

**3b.** `typeCheck` 方法声明(第 101–106 行,`builder` 链 `.build()` 前加):

```java
builder.addVar("params", MapType.create(SimpleType.STRING, SimpleType.DYN));
```

**3c.** `adaptBindings` 数值规整数组(第 152 行)把 `"params"` 加进去:

```java
for (String ns : new String[]{"metrics", "payload", "subject", "params"}) {
```

- [ ] **Step 4: 跑测试验证**

```bash
$MVN -pl rule-expression/rule-expression-cel test  # → PASS
```

- [ ] **Step 5: Commit**

```bash
git add rule-expression/rule-expression-cel/
git commit -m "feat(cel): CelExpressionEngine compile/typeCheck/adaptBindings 注册 params 命名空间"
```

---

### Task 6: Config-svc DTO —— SlotBinding / SlotTarget / JsonPointerTarget + TemplateSlot 重写

**Files:**
- New: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotBinding.java`
- New: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotTarget.java`
- New: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/JsonPointerTarget.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotConstraint.java`(已是 record,签名不变——保留)
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/TemplateSlot.java`(dataType 改 `DataType`,删 `defaultValue`)
- New: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/TemplateSlotTest.java`

**Interfaces:**
- Produces: `SlotBinding(String slotKey, SlotTarget target)`, `SlotTarget` sealed permits `JsonPointerTarget`, `JsonPointerTarget(String jsonPointer)`, `TemplateSlot(String key, String label, DataType dataType, boolean required, @Nullable SlotConstraint constraint)`

**Context:** `dataType` 从 String 改 kernel `DataType` enum;删 `defaultValue`(默认值 = skeleton 绑定位置的值);`SlotTarget` sealed + 单 permit,保留多态外壳(Jackson `@JsonTypeInfo`+`@JsonSubTypes`)。

- [ ] **Step 1: 写新 DTO + 测试,覆盖改写后的 TemplateSlot**

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotTarget.java
package com.sstlfsj.rule.config.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** slot 绑定目标——sealed 多态,Jackson 按 type 判别。 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JsonPointerTarget.class, name = "JsonPointerTarget")
})
public sealed interface SlotTarget permits JsonPointerTarget {
}
```

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/JsonPointerTarget.java
package com.sstlfsj.rule.config.api.dto;

/**
 * JSON Pointer 寻址 body skeleton 内具体位置。
 * @param jsonPointer RFC 6901 JsonPointer 字符串(如 /conditionAst/children/0/params/threshold)
 */
public record JsonPointerTarget(String jsonPointer) implements SlotTarget {
}
```

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotBinding.java
package com.sstlfsj.rule.config.api.dto;

/**
 * slot→body 位置的显式绑定(sidecar,非 token)。
 * @param slotKey 对应 TemplateSlot.key
 * @param target  绑定的 body 位置
 */
public record SlotBinding(String slotKey, SlotTarget target) {
}
```

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/TemplateSlot.java(改写)
package com.sstlfsj.rule.config.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sstlfsj.rule.kernel.api.model.DataType;

/**
 * 模板 Slot 参数定义。
 * 无 defaultValue 字段——默认值 = bodySkeleton 在该 slot 对应 binding 位置的当前值。
 * {@link #required} = true 表示实例化时必须提供值,skeleton 值仅保证可预览。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateSlot(
        String key,
        String label,
        DataType dataType,
        boolean required,
        SlotConstraint constraint
) {
    public TemplateSlot {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("slot key 不能为空");
        if (dataType == null) throw new IllegalArgumentException("slot dataType 不能为空");
        if (dataType == DataType.UNKNOWN) throw new IllegalArgumentException("slot dataType 不能为 UNKNOWN");
    }
}
```

```java
// rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/TemplateSlotTest.java
package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.DataType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateSlotTest {
    @Test
    void blankKey_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("  ", "label", DataType.LONG, true, null));
    }

    @Test
    void nullDataType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("k", "label", null, true, null));
    }

    @Test
    void unknownDataType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("k", "label", DataType.UNKNOWN, false, null));
    }

    @Test
    void noDefaultValue_field_notPresent() {
        // 验证 TemplateSlot record 无 defaultValue 字段
        var fields = TemplateSlot.class.getRecordComponents();
        for (var f : fields) {
            assertThat(f.getName()).isNotEqualTo("defaultValue");
        }
    }

    @Test
    void validSlot_roundTrips() {
        TemplateSlot s = new TemplateSlot("threshold", "阈值", DataType.LONG, true,
                new SlotConstraint(java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, null));
        assertThat(s.key()).isEqualTo("threshold");
        assertThat(s.dataType()).isEqualTo(DataType.LONG);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
$MVN -pl rule-config-svc compile
```
预期:编译通过(SlotConstraint 不变,SlotBinding/SlotTarget 无编译依赖)。

- [ ] **Step 3: 跑测试**

```bash
$MVN -pl rule-config-svc test -Dtest=TemplateSlotTest  # → PASS
```

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotBinding.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotTarget.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/JsonPointerTarget.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/TemplateSlot.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/TemplateSlotTest.java
git commit -m "feat(config): 新增 SlotBinding/SlotTarget/JsonPointerTarget DTO,TemplateSlot dataType 改 DataType/去 defaultValue"
```

---

### Task 7: TemplateBinder SPI + JsonPointerBinder 实现

**Files:**
- New: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/template/TemplateBinder.java`
- New: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/template/JsonPointerBinder.java`
- New: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/template/JsonPointerBinderTest.java`

**Interfaces:**
- Produces: `TemplateBinder`(supports/validate/bind),`JsonPointerBinder implements TemplateBinder`
- Consumes: DTO(Task 6),kernel `RuleBody`/`AstBody`/`ScriptBody`/`FlowBody`,Jackson `JsonPointer`

**Context:** `JsonPointerBinder` 是 v1 唯一实现,覆盖全部 6 种 kind(Ast/Script/Flow body 都 JSON 可寻址)。`supports = body instanceof AstBody || ScriptBody || FlowBody`。`bind` = ObjectMapper body→JsonNode tree → 按 bindings 逐 pointer 替换 → tree→RuleBody。`validate` = pointer 可解析到已存在节点 + slot↔binding 1:1 + body 专属守卫(ScriptBody 仅 `/script/params/*`;FlowBody 拒 `/referencedSnapshots`)。

- [ ] **Step 1: 写 TemplateBinder SPI**

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/template/TemplateBinder.java
package com.sstlfsj.rule.config.internal.template;

import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;

import java.util.List;
import java.util.Map;

/**
 * 模板绑定器 SPI:将 instance 填值按 binding 声明写入 body skeleton。
 * 实现按 {@link #supports(RuleBody)} 判别 body 变体,Spring 注入 {@code List<TemplateBinder>} 分派。
 */
public interface TemplateBinder {
    /** 是否处理该 body 变体。 */
    boolean supports(RuleBody body);

    /**
     * 校验 bindings 与 skeleton 的一致性:每个 target 在 skeleton 中可解析到已存在节点、
     * slot→binding 1:1 双射、slot key 无重复、body 专属守卫(如 script 禁 /script/source);
     * 不符抛带错误码的 {@link IllegalArgumentException}。
     */
    void validate(RuleBody skeleton, List<SlotBinding> bindings, List<TemplateSlot> slots);

    /**
     * 按 bindings 把 values 填入 skeleton 对应位置,返回新 body(不改入参)。
     * @param values slotKey→value,已通过 required/type/constraint 校验
     */
    RuleBody bind(RuleBody skeleton, List<SlotBinding> bindings, Map<String, Object> values);
}
```

- [ ] **Step 2: 写 JsonPointerBinder 测试(覆盖核心 + 首版漏掉的深层场景)**

> **AST 节点真实签名(已核实,构造勿错):**
> - `AndNode(List<AstNode> children, String displayLabel, Double weight)` —— 三参
> - `ScorecardRootNode(List<ConditionNode> conditions, List<ScoreBand> bands)` —— 双参
> - `IfNode(AstNode condition, AstNode thenBranch, AstNode elseBranch)`
> - `DecisionLeafNode(String decisionCode, String category)` —— 双参
> - `DecisionTableNode(List<Column> columns, List<Row> rows)`;`Column(String metricCode, String operator, String dataType, ValueRef valueRef)`(**无 params**);`Row(List<Object> conditions, String decisionCode)` —— 比较值在 Row.conditions
> - `ConditionNode(String conditionType, String metricCode, String displayLabel, Map params, Double weight)` —— 5 参便捷构造(dataType=null/valueRef=METRIC)

```java
// rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/template/JsonPointerBinderTest.java
package com.sstlfsj.rule.config.internal.template;

import com.sstlfsj.rule.config.api.dto.*;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.model.flow.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class JsonPointerBinderTest {

    static JsonPointerBinder binder;

    @BeforeAll
    static void setUp() {
        binder = new JsonPointerBinder(new ObjectMapper());
    }

    @Test
    void supports_astBody()     { assertThat(binder.supports(new AstBody(null))).isTrue(); }
    @Test
    void supports_scriptBody()  { assertThat(binder.supports(new ScriptBody(
            new ScriptSource("e", "CEL")))).isTrue(); }
    @Test
    void supports_flowBody()    { assertThat(binder.supports(new FlowBody(
            new FlowGraph(List.of(), List.of(), "in"), Map.of()))).isTrue(); }

    // ---- AST: ConditionNode params deep binding ----
    @Test
    void bind_conditionNode_replacesThreshold() {
        AstBody skeleton = new AstBody(
                new AndNode(List.of(new ConditionNode("GT", "amount", "金额大于",
                        Map.of("threshold", 100), 0.0)), null, null));
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/0/params/threshold"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 200));
        AstBody ab = (AstBody) result;
        ConditionNode cn = (ConditionNode) ((AndNode) ab.conditionAst()).children().get(0);
        assertThat(cn.params().get("threshold")).isEqualTo(200);
    }

    // ---- ScorecardRootNode:叶子 condition weight(双参构造:conditions + bands) ----
    @Test
    void bind_scorecardCondition_weight() {
        ScorecardRootNode root = new ScorecardRootNode(
                List.of(new ConditionNode("GT", "score", ">60", Map.of("threshold", 60), 0.3)),
                List.of());
        AstBody skeleton = new AstBody(root);
        SlotBinding binding = new SlotBinding("w",
                new JsonPointerTarget("/conditionAst/conditions/0/weight"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("w", 0.5));
        AstBody ab = (AstBody) result;
        ScorecardRootNode sc = (ScorecardRootNode) ab.conditionAst();
        assertThat(sc.conditions().get(0).weight()).isEqualTo(0.5);
    }

    // ---- DecisionTableNode:比较值在 Row.conditions(首版漏掉的 DECISION_TABLE 深层) ----
    @Test
    void bind_decisionTableRowCell() {
        DecisionTableNode dt = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT", "LONG", ValueRef.METRIC)),
                List.of(new DecisionTableNode.Row(List.of(100), "PASS")));
        AstBody skeleton = new AstBody(dt);
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/rows/0/conditions/0"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 200));
        AstBody ab = (AstBody) result;
        DecisionTableNode resultDt = (DecisionTableNode) ab.conditionAst();
        assertThat(resultDt.rows().get(0).conditions().get(0)).isEqualTo(200);
    }

    // ---- IfNode 深层(首版漏掉的 DECISION_TREE) ----
    @Test
    void bind_ifNodeCondition_threshold() {
        IfNode ifn = new IfNode(
                new AndNode(List.of(new ConditionNode("GT", "amt", "",
                        Map.of("threshold", 50), 0.0)), null, null),
                new DecisionLeafNode("PASS", null), null);
        AstBody skeleton = new AstBody(ifn);
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/condition/children/0/params/threshold"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 75));
        AstBody ab = (AstBody) result;
        IfNode resultIf = (IfNode) ab.conditionAst();
        AndNode andNode = (AndNode) resultIf.condition();
        ConditionNode cn = (ConditionNode) andNode.children().get(0);
        assertThat(cn.params().get("threshold")).isEqualTo(75);
    }

    // ---- Script params ----
    @Test
    void bind_scriptParams() {
        ScriptSource src = new ScriptSource("params.t > 0", "CEL", Map.of("t", 1));
        ScriptBody skeleton = new ScriptBody(src);
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/script/params/t"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 99));
        ScriptBody sb = (ScriptBody) result;
        assertThat(sb.script().params().get("t")).isEqualTo(99);
    }

    // ---- 校验:pointer 可解析 ----
    @Test
    void validate_unresolvablePointer_throws() {
        AstBody skeleton = new AstBody(
                new AndNode(List.of(new ConditionNode("GT", "a", "", Map.of("t", 1), 0.0)), null, null));
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/999/params/x"));
        assertThatThrownBy(() -> binder.validate(skeleton, List.of(binding), List.of(
                new TemplateSlot("t", "", DataType.LONG, true, null))))
                .hasMessageContaining("TEMPLATE_BINDING_UNRESOLVABLE");
    }

    // ---- 校验:slot↔binding 不一一对应 ----
    @Test
    void validate_slotBindingMismatch_extraSlot_throws() {
        AstBody skeleton = new AstBody(
                new AndNode(List.of(new ConditionNode("GT", "a", "", Map.of("t", 1), 0.0)), null, null));
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/0/params/t"));
        assertThatThrownBy(() -> binder.validate(skeleton, List.of(binding), List.of(
                new TemplateSlot("t", "", DataType.LONG, true, null),
                new TemplateSlot("extra", "", DataType.STRING, false, null))))
                .hasMessageContaining("TEMPLATE_SLOT_BINDING_MISMATCH");
    }

    // ---- 校验:ScriptBody 拒 /script/source ----
    @Test
    void validate_scriptSourcePointer_rejected() {
        ScriptBody skeleton = new ScriptBody(new ScriptSource("expr", "CEL"));
        SlotBinding binding = new SlotBinding("s",
                new JsonPointerTarget("/script/source"));
        assertThatThrownBy(() -> binder.validate(skeleton, List.of(binding), List.of(
                new TemplateSlot("s", "", DataType.STRING, true, null))))
                .hasMessageContaining("TEMPLATE_TARGET_FORBIDDEN");
    }

    // ---- 校验:FlowBody 拒 /referencedSnapshots ----
    @Test
    void validate_flowRefSnapshots_rejected() {
        FlowBody skeleton = new FlowBody(
                new FlowGraph(List.of(new OutputNode("o", "PASS")), List.of(), "o"), Map.of());
        SlotBinding binding = new SlotBinding("r",
                new JsonPointerTarget("/referencedSnapshots/someRule"));
        assertThatThrownBy(() -> binder.validate(skeleton, List.of(binding), List.of(
                new TemplateSlot("r", "", DataType.STRING, true, null))))
                .hasMessageContaining("TEMPLATE_TARGET_FORBIDDEN");
    }

    @Test
    void bind_preservesSkeleton() {
        AstBody skeleton = new AstBody(
                new AndNode(List.of(new ConditionNode("GT", "a", "", Map.of("t", 100), 0.0)), null, null));
        SlotBinding binding = new SlotBinding("t",
                new JsonPointerTarget("/conditionAst/children/0/params/t"));
        RuleBody result = binder.bind(skeleton, List.of(binding), Map.of("t", 200));
        AstBody original = (AstBody) skeleton;
        ConditionNode originalCn = (ConditionNode) ((AndNode) original.conditionAst()).children().get(0);
        assertThat(originalCn.params().get("t")).isEqualTo(100);
        assertThat(result).isNotSameAs(skeleton);
    }
}
```

- [ ] **Step 3: 实现 JsonPointerBinder**

> **Jackson3 依赖(已核实项目用法):** `ObjectMapper`/`JsonNode`/`ObjectNode` 均在 `tools.jackson.databind[.node]`(项目 69 处),`JsonPointer` 在 `tools.jackson.core`;对象↔树用 `objectMapper.convertValue`(项目惯用,非 `valueToTree`)。
> **实现前先核** `tools.jackson.core.JsonPointer` 的 API:`compile(String)` / `head()`(去末段父指针) / `last().getMatchingProperty()`(末段属性名) —— 若 Jackson3 方法名不同,以实际 IDE/编译为准调整(遵"写测试前先读被测依赖行为"纪律)。

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/template/JsonPointerBinder.java
package com.sstlfsj.rule.config.internal.template;

import com.sstlfsj.rule.config.api.dto.*;
import com.sstlfsj.rule.kernel.api.model.*;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

/** 用 Jackson JsonPointer 寻址任意 body JSON 树,覆盖全部 Ast/Script/Flow 三种 body 变体。 */
public class JsonPointerBinder implements TemplateBinder {

    private final ObjectMapper objectMapper;

    public JsonPointerBinder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(RuleBody body) {
        return body instanceof AstBody || body instanceof ScriptBody || body instanceof FlowBody;
    }

    @Override
    public void validate(RuleBody skeleton, List<SlotBinding> bindings, List<TemplateSlot> slots) {
        // 1. body 专属守卫
        guardBodySpecific(skeleton, bindings);
        // 2. pointer 可解析到已存在节点
        JsonNode tree = objectMapper.convertValue(skeleton, JsonNode.class);
        Set<String> bindingSlots = new HashSet<>();
        for (SlotBinding b : bindings) {
            if (!bindingSlots.add(b.slotKey())) {
                throw new IllegalArgumentException("TEMPLATE_SLOT_BINDING_MISMATCH: slot key 重复: " + b.slotKey());
            }
            if (b.target() instanceof JsonPointerTarget jpt) {
                JsonNode target = tree.at(JsonPointer.compile(jpt.jsonPointer()));
                if (target.isMissingNode()) {
                    throw new IllegalArgumentException(
                            "TEMPLATE_BINDING_UNRESOLVABLE: pointer 解析失败: " + jpt.jsonPointer());
                }
            }
        }
        // 3. slot↔binding 1:1
        Set<String> slotKeys = new HashSet<>();
        if (slots != null) slots.forEach(s -> slotKeys.add(s.key()));
        if (!bindingSlots.equals(slotKeys)) {
            Set<String> missing = new HashSet<>(slotKeys);
            missing.removeAll(bindingSlots);
            Set<String> extra = new HashSet<>(bindingSlots);
            extra.removeAll(slotKeys);
            throw new IllegalArgumentException("TEMPLATE_SLOT_BINDING_MISMATCH"
                    + (missing.isEmpty() ? "" : "，缺少 binding: " + missing)
                    + (extra.isEmpty() ? "" : "，多余 binding: " + extra));
        }
    }

    @Override
    public RuleBody bind(RuleBody skeleton, List<SlotBinding> bindings, Map<String, Object> values) {
        JsonNode tree = objectMapper.convertValue(skeleton, JsonNode.class);
        for (SlotBinding b : bindings) {
            if (b.target() instanceof JsonPointerTarget jpt) {
                JsonPointer pointer = JsonPointer.compile(jpt.jsonPointer());
                JsonNode parentNode = tree.at(pointer.head());
                if (parentNode instanceof ObjectNode parent) {
                    Object value = values.get(b.slotKey());
                    JsonNode valueNode = objectMapper.convertValue(value, JsonNode.class);
                    parent.set(pointer.last().getMatchingProperty(), valueNode);
                }
            }
        }
        return objectMapper.convertValue(tree, RuleBody.class);
    }

    private void guardBodySpecific(RuleBody body, List<SlotBinding> bindings) {
        if (body instanceof ScriptBody) {
            for (SlotBinding b : bindings) {
                if (b.target() instanceof JsonPointerTarget jpt) {
                    String path = jpt.jsonPointer();
                    if (!(path.startsWith("/script/params/") && path.length() > "/script/params/".length())) {
                        throw new IllegalArgumentException(
                                "TEMPLATE_TARGET_FORBIDDEN: Script 仅允许 /script/params/*: " + path);
                    }
                }
            }
        }
        if (body instanceof FlowBody) {
            for (SlotBinding b : bindings) {
                if (b.target() instanceof JsonPointerTarget jpt) {
                    if (jpt.jsonPointer().startsWith("/referencedSnapshots")) {
                        throw new IllegalArgumentException(
                                "TEMPLATE_TARGET_FORBIDDEN: 不可指向 /referencedSnapshots: " + jpt.jsonPointer());
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 编译 + 跑测试**

```bash
$MVN -pl rule-config-svc test -Dtest=JsonPointerBinderTest
```
若 `Row.conditions` 数组元素替换后类型(Integer vs Long)导致断言微差,按实际 Jackson 反序列化类型调整断言(如 `isEqualTo(200)` vs `200L`)。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/template/ \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/template/
git commit -m "feat(config): TemplateBinder SPI + JsonPointerBinder——JsonPointer 统一覆盖全 6 kind"
```

---

### Task 8: RuleTemplate 实体重写 + V1_42 迁移改写

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleTemplate.java`
- Modify(Write 覆盖):`rule-config-svc/src/main/resources/db/migration/V1_42__rule_template.sql`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/RuleTemplateSnapshot.java`(加 bindings 字段以完整审计)
- Modify(若存在):`rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/domain/RuleTemplateTest.java`

**Interfaces:**
- Produces: `RuleTemplate` 实体含 `bodySkeleton`(RuleBody,原 `templateBody` 改名)、`slots`(List\<TemplateSlot\>)、`bindings`(List\<SlotBinding\>,新增)、`kind`(RuleKind);`RuleTemplateSnapshot` 加 `bindings` 字段

- [ ] **Step 1: 改写实体**

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleTemplate.java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** rule_template 表实体。 */
@Getter
@Setter
@TableName(value = "rule_template", autoResultMap = true)
public class RuleTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long tenantId;
    private String name;
    private String description;
    private RuleKind kind;
    /** body 骨架:合法 body,可覆盖位置填默认值,无 token。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private RuleBody bodySkeleton;
    /** Slot 定义列表。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<TemplateSlot> slots;
    /** slot→body 位置的显式绑定(sidecar,非 token)。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<SlotBinding> bindings;
    private Integer version;
    private RuleTemplateStatus status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
```

- [ ] **Step 2: 改写迁移**

```sql
-- rule-config-svc/src/main/resources/db/migration/V1_42__rule_template.sql
CREATE TABLE rule_template (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    code          VARCHAR(128) NOT NULL,
    name          VARCHAR(256) NOT NULL,
    description   VARCHAR(1024),
    kind          VARCHAR(32)  NOT NULL,
    body_skeleton JSON         NOT NULL COMMENT '合法 body 骨架,默认值就位,无 token',
    slots         JSON         NOT NULL COMMENT 'TemplateSlot[] 参数 schema',
    bindings      JSON         NOT NULL COMMENT 'SlotBinding[],slot→body 位置显式绑定',
    version       INT          NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    created_by    VARCHAR(64),
    created_at    DATETIME,
    updated_by    VARCHAR(64),
    updated_at    DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_id, code)
) COLLATE = utf8mb4_unicode_ci;

ALTER TABLE rule_version
    ADD COLUMN template_id      BIGINT NULL COMMENT '实例化来源模板 ID(手建规则为 null)',
    ADD COLUMN template_version INT    NULL COMMENT '实例化时模板版本号';
```

- [ ] **Step 3: 改写 RuleTemplateSnapshot(加 bindings)**

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/RuleTemplateSnapshot.java
package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import lombok.Builder;

import java.util.List;

@Builder
public record RuleTemplateSnapshot(
        Long id, String code, String name, String status, int version,
        RuleBody bodySkeleton, List<TemplateSlot> slots, List<SlotBinding> bindings)
        implements AuditSnapshot {
}
```

- [ ] **Step 4: 更新 toSnapshot(Service 之后在 Task 10 改)**

先编译确认实体/Mapper/Snapshot 可编译:

```bash
$MVN -pl rule-config-svc compile
```
预期:Mapper(`RuleTemplateMapper`)引用的 `RuleTemplate` getter 名从 `getTemplateBody()`→`getBodySkeleton()`,Mapper 内 `LambdaQueryWrapper` 引用须匹配。需确认 Mapper 里**无**对 `templateBody` 字段的引用(当前 Mapper 只有 tenantId/code/status 查询,无字段名引用,安全)。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleTemplate.java \
        rule-config-svc/src/main/resources/db/migration/V1_42__rule_template.sql \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/RuleTemplateSnapshot.java
git commit -m "feat(config): RuleTemplate 实体重写——templateBody→bodySkeleton,加 bindings 字段;V1_42 迁移改写为单表一次成型"
```

---

### Task 9: RuleTemplateService 接口更新

**Files:**
- Modify(Write 覆盖):`rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/RuleTemplateService.java`

**Interfaces:**
- Consumes: DTO(Task 6),实体(Task 8)
- Produces: `RuleTemplateService`(create/update/publish/disable/list/get/instantiate——7 方法,删 exportFromRule)

- [ ] **Step 1: 改写接口**

```java
// rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/RuleTemplateService.java
package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.*;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.kernel.api.model.RuleBody;

import java.util.List;
import java.util.Map;

/** 规则模板管理（v2:binder SPI 重设计,JsonPointer 统一寻址覆盖全 6 kind）。 */
public interface RuleTemplateService {

    /** 创建 DRAFT 模板。bodySkeleton 为合法 body(默认值已就位,无 token)。 */
    Long create(Long tenantId, String code, String name, String kind,
                String description, RuleBody bodySkeleton,
                List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId);

    /** 更新 DRAFT 模板(仅 DRAFT 可编辑)。 */
    void update(Long tenantId, String code, String name, String kind,
                String description, RuleBody bodySkeleton,
                List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId);

    /** 发布模板 DRAFT→PUBLISHED(发布前经 binder.validate 校验)。 */
    void publish(Long tenantId, String code, String actorId);

    /** 禁用模板 PUBLISHED→DISABLED。 */
    void disable(Long tenantId, String code, String actorId);

    /** 按租户 + 状态列出模板。 */
    List<RuleTemplate> list(Long tenantId, String status);

    /** 查单个模板。 */
    RuleTemplate get(Long tenantId, String code);

    /** 实例化:从 PUBLISHED 模板生成 DRAFT RuleVersion。 */
    DraftCreatedResult instantiate(Long tenantId, String templateCode,
                                   String ruleCode, String ruleName,
                                   String sceneCode, List<String> triggerEventTypes,
                                   Map<String, Object> slotValues, String actorId);
}
```

- [ ] **Step 2: 编译**

```bash
$MVN -pl rule-config-svc compile
```
预期:编译失败——`RuleTemplateServiceImpl` 仍引用旧接口签名 + 含 `exportFromRule`。这是预期的,Task 10 重写 Impl 即可修复。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/RuleTemplateService.java
git commit -m "feat(config): RuleTemplateService 接口重写——bodySkeleton 替代 templateBody、加 bindings、删 exportFromRule"
```

---

### Task 10: RuleTemplateServiceImpl 重写(核心)

**Files:**
- Modify(Write 覆盖):`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/RuleTemplateServiceImpl.java`
- Modify(若存在):`rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/RuleTemplateServiceImplTest.java`

**Interfaces:**
- Consumes: `TemplateBinder` SPI + `JsonPointerBinder`(Task 7),`RuleTemplateMapper`(KEEP),`RuleTemplate`/`RuleTemplateStatus`(Task 8),`PublishService.createDraft(templateId,templateVersion)`(KEEP),`ObjectMapper`(KEEP),`OperationAuditedEvent`

**Context:** 这是本轮最大的单文件改动。首版 ~380 行全量替换为 ~300 行新实现。**删除**:`collectSlotKeys`/`replaceSlotsInMap`/`validateSlotsConsistent`/`setJsonPathValue`/`deepCloneAndReplace`/`deepClone`/`exportFromRule`/`AST_KINDS` 常量。**新增**:`List<TemplateBinder>` 注入 + `pick` + `coerceValue` + `validateSlotConstraints`;`create`/`update`/`publish`/`instantiate` 全部走 binder dispatch。

- [ ] **Step 1: 写 ServiceImpl 测试(mock binder + mock mapper)**

```java
// rule-config-svc/src/test/java/.../internal/service/RuleTemplateServiceImplTest.java
// 核心场景:
// 1. create: binder.validate 被调用 + 审计事件发布
// 2. create: 无 matching binder → TEMPLATE_KIND_UNSUPPORTED
// 3. publish: binder.validate 被调用 + status→PUBLISHED
// 4. instantiate: binder.bind 被调用 → createDraft 被调用(含 templateId/version)
// 5. instantiate: 模板非 PUBLISHED → 拒
// 6. update: binder.validate 被调用 + version+1
// 7. disable: PUBLISHED→DISABLED,非 PUBLISHED→拒

@ExtendWith(MockitoExtension.class)
class RuleTemplateServiceImplTest {

    @Mock RuleTemplateMapper mapper;
    @Mock PublishService publishService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    List<TemplateBinder> binders;  // 在 setUp 中注入包含 JsonPointerBinder 的列表

    @InjectMocks RuleTemplateServiceImpl service;

    @Test
    void create_noMatchingBinder_throws() { ... }
    @Test
    void create_publishesAuditEvent() { ... }
    @Test
    void instantiate_bindsAndCreatesDraft() { ... }
    @Test
    void instantiate_notPublished_throws() { ... }
    // ... 等
}
```

- [ ] **Step 2: 写 ServiceImpl 完整实现**

核心实现(~300 行):

```java
// RuleTemplateServiceImpl.java
package com.sstlfsj.rule.config.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.dto.*;
import com.sstlfsj.rule.config.api.service.RuleTemplateService;
import com.sstlfsj.rule.config.internal.domain.*;
import com.sstlfsj.rule.config.internal.event.*;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.RuleTemplateMapper;
import com.sstlfsj.rule.config.internal.template.TemplateBinder;
import com.sstlfsj.rule.kernel.api.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
@ConditionalOnProperty(name = "rule.template.enabled", havingValue = "true")
public class RuleTemplateServiceImpl implements RuleTemplateService {

    private static final Logger log = LoggerFactory.getLogger(RuleTemplateServiceImpl.class);

    private final RuleTemplateMapper templateMapper;
    private final PublishService publishService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final List<TemplateBinder> binders;

    public RuleTemplateServiceImpl(RuleTemplateMapper templateMapper,
                                   PublishService publishService,
                                   ApplicationEventPublisher eventPublisher,
                                   ObjectMapper objectMapper,
                                   List<TemplateBinder> binders) {
        this.templateMapper = templateMapper;
        this.publishService = publishService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.binders = binders;
    }

    @Override
    @Transactional
    public Long create(Long tenantId, String code, String name, String kind,
                       String description, RuleBody bodySkeleton,
                       List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId) {
        RuleKind rk = validateKind(kind);
        TemplateBinder binder = pickBinder(bodySkeleton);
        binder.validate(bodySkeleton, bindings, slots);

        RuleTemplate tmpl = new RuleTemplate();
        tmpl.setCode(code);
        tmpl.setTenantId(tenantId);
        tmpl.setName(name);
        tmpl.setDescription(description);
        tmpl.setKind(rk);
        tmpl.setBodySkeleton(bodySkeleton);
        tmpl.setSlots(slots);
        tmpl.setBindings(bindings);
        tmpl.setVersion(1);
        tmpl.setStatus(RuleTemplateStatus.DRAFT);
        tmpl.setCreatedBy(actorId);
        tmpl.setCreatedAt(LocalDateTime.now());
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.insert(tmpl);

        var snapshot = toSnapshot(tmpl);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.CREATE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                null, snapshot, LocalDateTime.now()));
        return tmpl.getId();
    }

    @Override
    @Transactional
    public void update(Long tenantId, String code, String name, String kind,
                       String description, RuleBody bodySkeleton,
                       List<TemplateSlot> slots, List<SlotBinding> bindings, String actorId) {
        RuleTemplate tmpl = requireDraft(tenantId, code);
        RuleKind rk = validateKind(kind);
        TemplateBinder binder = pickBinder(bodySkeleton);
        binder.validate(bodySkeleton, bindings, slots);

        var before = toSnapshot(tmpl);
        tmpl.setName(name);
        tmpl.setDescription(description);
        tmpl.setKind(rk);
        tmpl.setBodySkeleton(bodySkeleton);
        tmpl.setSlots(slots);
        tmpl.setBindings(bindings);
        tmpl.setVersion(tmpl.getVersion() + 1);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(tmpl);

        var after = toSnapshot(tmpl);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.UPDATE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void publish(Long tenantId, String code, String actorId) {
        RuleTemplate tmpl = requireDraft(tenantId, code);
        TemplateBinder binder = pickBinder(tmpl.getBodySkeleton());
        binder.validate(tmpl.getBodySkeleton(), tmpl.getBindings(), tmpl.getSlots());

        var before = toSnapshot(tmpl);
        tmpl.setStatus(RuleTemplateStatus.PUBLISHED);
        tmpl.setVersion(tmpl.getVersion() + 1);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(tmpl);

        var after = toSnapshot(tmpl);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.PUBLISH,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void disable(Long tenantId, String code, String actorId) {
        RuleTemplate tmpl = requireTemplate(tenantId, code);
        if (tmpl.getStatus() != RuleTemplateStatus.PUBLISHED) {
            throw new IllegalArgumentException("仅 PUBLISHED 状态的模板可禁用");
        }
        var before = toSnapshot(tmpl);
        tmpl.setStatus(RuleTemplateStatus.DISABLED);
        tmpl.setUpdatedBy(actorId);
        tmpl.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(tmpl);

        var after = toSnapshot(tmpl);
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actorId, ActorType.USER, AuditAction.DISABLE,
                AuditTargetType.RULE_TEMPLATE, tmpl.getId().toString(),
                before, after, LocalDateTime.now()));
    }

    @Override
    public List<RuleTemplate> list(Long tenantId, String status) {
        if (status != null && !status.isBlank()) {
            return templateMapper.findByTenantId(tenantId, RuleTemplateStatus.valueOf(status));
        }
        return templateMapper.findByTenantId(tenantId);
    }

    @Override
    public RuleTemplate get(Long tenantId, String code) {
        return requireTemplate(tenantId, code);
    }

    @Override
    @Transactional
    public DraftCreatedResult instantiate(Long tenantId, String templateCode,
                                          String ruleCode, String ruleName,
                                          String sceneCode, List<String> triggerEventTypes,
                                          Map<String, Object> slotValues, String actorId) {
        RuleTemplate tmpl = templateMapper.findPublishedByCode(tenantId, templateCode);
        if (tmpl == null) {
            throw new IllegalArgumentException("模板不存在或未发布: " + templateCode);
        }
        TemplateBinder binder = pickBinder(tmpl.getBodySkeleton());
        validateSlotValues(tmpl.getSlots(), slotValues);
        Map<String, Object> coerced = coerceValues(tmpl.getSlots(), slotValues);
        RuleBody bound = binder.bind(tmpl.getBodySkeleton(), tmpl.getBindings(), coerced);

        RuleContent content = new RuleContent(ruleName, tmpl.getKind().tag(), bound,
                List.of(), List.of(),
                triggerEventTypes != null ? triggerEventTypes : List.of());
        return publishService.createDraft(tenantId, sceneCode, ruleCode, content, actorId,
                tmpl.getId(), tmpl.getVersion());
    }

    // ---------- 内部 ----------

    private TemplateBinder pickBinder(RuleBody body) {
        for (TemplateBinder b : binders) {
            if (b.supports(body)) return b;
        }
        throw new IllegalArgumentException("TEMPLATE_KIND_UNSUPPORTED: 无 binder 支持该 body 类型: " + body.getClass().getSimpleName());
    }

    private RuleKind validateKind(String kind) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("模板 kind 不可为空");
        return RuleKind.valueOf(kind);
    }

    private RuleTemplate requireTemplate(Long tenantId, String code) {
        RuleTemplate tmpl = templateMapper.findByTenantAndCode(tenantId, code);
        if (tmpl == null) throw new IllegalArgumentException("模板不存在: code=" + code);
        return tmpl;
    }

    private RuleTemplate requireDraft(Long tenantId, String code) {
        RuleTemplate tmpl = requireTemplate(tenantId, code);
        if (tmpl.getStatus() != RuleTemplateStatus.DRAFT) {
            throw new IllegalArgumentException("仅 DRAFT 状态的模板可编辑/发布");
        }
        return tmpl;
    }

    private RuleTemplateSnapshot toSnapshot(RuleTemplate tmpl) {
        return RuleTemplateSnapshot.builder()
                .id(tmpl.getId()).code(tmpl.getCode())
                .name(tmpl.getName()).status(tmpl.getStatus().name())
                .version(tmpl.getVersion())
                .bodySkeleton(tmpl.getBodySkeleton())
                .slots(tmpl.getSlots())
                .bindings(tmpl.getBindings())
                .build();
    }

    /** 校验 值类型与约束:required 齐全 + 按 DataType 强转 + SlotConstraint */
    private void validateSlotValues(List<TemplateSlot> slots, Map<String, Object> slotValues) {
        Map<String, TemplateSlot> byKey = new HashMap<>();
        if (slots != null) slots.forEach(s -> byKey.put(s.key(), s));
        for (TemplateSlot def : slots != null ? slots : List.<TemplateSlot>of()) {
            if (def.required() && !slotValues.containsKey(def.key())) {
                throw new IllegalArgumentException("缺少必填 slot: " + def.key());
            }
        }
        for (var entry : slotValues.entrySet()) {
            TemplateSlot def = byKey.get(entry.getKey());
            if (def == null) {
                throw new IllegalArgumentException("slotValues 包含未声明的 slot: " + entry.getKey());
            }
            // 强转校验 + SlotConstraint 校验(补首版缺口:min/max/enumValues)
            Object coerced = coerceValue(entry.getValue(), def.dataType());
            validateConstraint(def, coerced);
        }
    }

    /** 校验 SlotConstraint:数值 min/max、标量/LIST 元素 enumValues 成员。 */
    private void validateConstraint(TemplateSlot def, Object coerced) {
        SlotConstraint c = def.constraint();
        if (c == null || coerced == null) return;
        if (coerced instanceof Number n) {
            double d = n.doubleValue();
            if (c.min() != null && d < c.min().doubleValue()) {
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + def.key() + " 小于 min " + c.min());
            }
            if (c.max() != null && d > c.max().doubleValue()) {
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + def.key() + " 大于 max " + c.max());
            }
        }
        if (c.enumValues() != null && !c.enumValues().isEmpty()) {
            if (coerced instanceof List<?> list) {
                for (Object el : list) {
                    if (!c.enumValues().contains(String.valueOf(el))) {
                        throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + def.key() + " 元素不在 enumValues: " + el);
                    }
                }
            } else if (!c.enumValues().contains(String.valueOf(coerced))) {
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: " + def.key() + " 不在 enumValues: " + coerced);
            }
        }
    }

    private Map<String, Object> coerceValues(List<TemplateSlot> slots, Map<String, Object> raw) {
        Map<String, TemplateSlot> byKey = new HashMap<>();
        if (slots != null) slots.forEach(s -> byKey.put(s.key(), s));
        Map<String, Object> out = new HashMap<>(raw);
        for (var entry : out.entrySet()) {
            TemplateSlot def = byKey.get(entry.getKey());
            if (def != null) {
                out.put(entry.getKey(), coerceValue(entry.getValue(), def.dataType()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object coerceValue(Object value, DataType dt) {
        if (value == null) return null;
        return switch (dt) {
            case LONG -> {
                if (value instanceof Number n) yield n.longValue();
                if (value instanceof String s) { try { yield Long.parseLong(s); } catch (NumberFormatException e) { throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: 无法转为 LONG: " + value); } }
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: LONG 类型需要数值或字符串: " + value);
            }
            case DOUBLE -> {
                if (value instanceof Number n) yield n.doubleValue();
                if (value instanceof String s) { try { yield Double.parseDouble(s); } catch (NumberFormatException e) { throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: 无法转为 DOUBLE: " + value); } }
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: DOUBLE 类型需要数值或字符串: " + value);
            }
            case DECIMAL -> {
                if (value instanceof BigDecimal bd) yield bd;
                if (value instanceof String s) { try { yield new BigDecimal(s); } catch (NumberFormatException e) { throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: 无法转为 DECIMAL: " + value); } }
                if (value instanceof Number n) yield BigDecimal.valueOf(n.doubleValue());
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: DECIMAL 类型需要数值或字符串: " + value);
            }
            case STRING       -> String.valueOf(value);
            case BOOLEAN      -> {
                if (value instanceof Boolean b) yield b;
                if (value instanceof String s) yield Boolean.parseBoolean(s);
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: BOOLEAN 类型需要布尔值: " + value);
            }
            case DATE, DATETIME -> {
                if (value instanceof String s) {
                    try { java.time.LocalDate.parse(s); } catch (Exception e) {
                        try { java.time.Instant.parse(s); } catch (Exception e2) {
                            throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: 无法解析日期: " + s);
                        }
                    }
                    yield s;
                }
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: DATE/DATETIME 需要 ISO 字符串: " + value);
            }
            case LIST -> {
                if (value instanceof List<?> l) yield l;
                throw new IllegalArgumentException("TEMPLATE_SLOT_VALUE_INVALID: LIST 类型需要数组: " + value);
            }
            default -> value;
        };
    }
}
```

- [ ] **Step 3: 编译 + 跑测试**

```bash
$MVN -pl rule-config-svc test -Dtest=RuleTemplateServiceImplTest  # → PASS
$MVN -pl rule-config-svc test                                     # → 模块全量
```
若 `RuleTemplateSnapshot` 字段变更导致既有测试编译错误(如旧测试用了旧构造器),就地补 `bodySkeleton`/`slots`/`bindings` 参数或改 Builder。

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/RuleTemplateServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/RuleTemplateServiceImplTest.java
git commit -m "feat(config): RuleTemplateServiceImpl 重写——binder SPI 分派、删 token 逻辑、DataType 强转+constraint 校验"
```

---

### Task 11: rule-api Controller + DTO 重写

**Files:**
- Modify(Write 覆盖):`rule-api/src/main/java/com/sstlfsj/rule/web/admin/RuleTemplateController.java`
- Modify(Write 覆盖):`rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/CreateTemplateRequest.java`
- Modify(Write 覆盖):`rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/UpdateTemplateRequest.java`
- Delete:`rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/ExportFromRuleRequest.java`
- Keep(无需改):`rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/InstantiateRequest.java`(已核实:字段 tenantId/ruleCode/ruleName/sceneCode/triggerEventTypes/slotValues 全对得上,零改动)

**Interfaces:**
- Consumes: `RuleTemplateService`(Task 9)

**Context(已核实首版真实结构,勿臆造):** Controller 路径 `@RequestMapping("/admin/v1/rule-templates")`(复数连字符);`create`/`update` 从**请求体**读 `tenantId`(`req.tenantId()`),`publish`/`disable`/`list`/`get` 从 `X-Tenant-Id` **header** 读;`instantiate` 从 `InstantiateRequest.tenantId` 读。首版 DTO 用 `Object templateBody`,本轮改 typed `RuleBody bodySkeleton` + 加 `bindings`;`create`/`update` DTO **必须保留 tenantId**。首版 Controller **无** `@ConditionalOnProperty`,本轮补上(feature flag)。

- [ ] **Step 1: 改写请求 DTO(templateBody→bodySkeleton typed,加 bindings,保留 tenantId)**

```java
// CreateTemplateRequest.java
package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateTemplateRequest(
        @NotNull Long tenantId,
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String kind,
        String description,
        RuleBody bodySkeleton,
        List<TemplateSlot> slots,
        List<SlotBinding> bindings
) {}
```

```java
// UpdateTemplateRequest.java
package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateTemplateRequest(
        @NotNull Long tenantId,
        String name,
        String kind,
        String description,
        RuleBody bodySkeleton,
        List<TemplateSlot> slots,
        List<SlotBinding> bindings
) {}
```

- [ ] **Step 2: 改写 Controller(路径/header 沿用首版,templateBody→bodySkeleton+bindings,删 export,加 flag)**

```java
// RuleTemplateController.java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.service.RuleTemplateService;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.web.admin.dto.CreateTemplateRequest;
import com.sstlfsj.rule.web.admin.dto.InstantiateRequest;
import com.sstlfsj.rule.web.admin.dto.UpdateTemplateRequest;
import com.sstlfsj.rule.web.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 规则模板管理入口(D74 authoring 便利层,重设计;默认关闭)。 */
@RestController
@RequestMapping("/admin/v1/rule-templates")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rule.template.enabled", havingValue = "true")
public class RuleTemplateController {

    private final RuleTemplateService templateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> create(@Valid @RequestBody CreateTemplateRequest req,
                                    @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(templateService.create(
                req.tenantId(), req.code(), req.name(), req.kind(),
                req.description(), req.bodySkeleton(), req.slots(), req.bindings(), actorId));
    }

    @PutMapping("/{code}")
    public ApiResponse<Void> update(@PathVariable String code,
                                    @Valid @RequestBody UpdateTemplateRequest req,
                                    @RequestHeader("X-Actor-Id") String actorId) {
        templateService.update(req.tenantId(), code, req.name(), req.kind(),
                req.description(), req.bodySkeleton(), req.slots(), req.bindings(), actorId);
        return ApiResponse.ok((Void) null);
    }

    @PostMapping("/{code}/publish")
    public ApiResponse<Void> publish(@PathVariable String code,
                                     @RequestHeader("X-Tenant-Id") Long tenantId,
                                     @RequestHeader("X-Actor-Id") String actorId) {
        templateService.publish(tenantId, code, actorId);
        return ApiResponse.ok((Void) null);
    }

    @PostMapping("/{code}/disable")
    public ApiResponse<Void> disable(@PathVariable String code,
                                     @RequestHeader("X-Tenant-Id") Long tenantId,
                                     @RequestHeader("X-Actor-Id") String actorId) {
        templateService.disable(tenantId, code, actorId);
        return ApiResponse.ok((Void) null);
    }

    @GetMapping
    public ApiResponse<List<RuleTemplate>> list(@RequestHeader("X-Tenant-Id") Long tenantId,
                                                @RequestParam(required = false) String status) {
        return ApiResponse.ok(templateService.list(tenantId, status));
    }

    @GetMapping("/{code}")
    public ApiResponse<RuleTemplate> get(@PathVariable String code,
                                         @RequestHeader("X-Tenant-Id") Long tenantId) {
        return ApiResponse.ok(templateService.get(tenantId, code));
    }

    @PostMapping("/{code}/instantiate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DraftCreatedResult> instantiate(@PathVariable String code,
                                                        @Valid @RequestBody InstantiateRequest req,
                                                        @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(templateService.instantiate(
                req.tenantId(), code, req.ruleCode(), req.ruleName(), req.sceneCode(),
                req.triggerEventTypes(), req.slotValues(), actorId));
    }
    // 删: exportFromRule 端点 + ExportFromRuleRequest import
}
```

- [ ] **Step 3: 删 `ExportFromRuleRequest.java`**

```bash
rm rule-api/src/main/java/com/sstlfsj/rule/web/admin/dto/ExportFromRuleRequest.java
```

- [ ] **Step 4: 编译 + 跑测试**

```bash
$MVN -pl rule-api -am test  # → PASS(带 -am 拉 config-svc 新签名)
```
若首版有 `RuleTemplateControllerTest` 引用旧签名(templateBody/export),同步改为 bodySkeleton+bindings、删 export 用例;补开关测试:`@SpringBootTest(properties="rule.template.enabled=false")` 时 `POST /admin/v1/rule-templates` → 404。

- [ ] **Step 5: Commit**

```bash
git add rule-api/
git commit -m "feat(api): RuleTemplateController/DTO 重写——bodySkeleton+SlotBinding,删 exportFromRule 端点,加 feature flag"
```

---

### Task 12: 前端 types + 模板编辑器重写

**Files:**
- Modify(Write 覆盖):`frontend/src/types/template.ts`
- Modify(Write 覆盖):`frontend/src/pages/template-editor/`(整个目录)
- Modify(微调):`frontend/src/pages/template-instantiate/`(删 defaultValue 引用)
- Delete:反向导出相关页面/组件(若 `template-editor/` 内含导出入口,从该目录移除)
- KEEP:`frontend/src/pages/template-list/`、`frontend/src/api/template.ts`(API 端点 7 个不变)

**Context:** 前端改动最大处在编辑器——从"挖空塞 token"改为"在 skeleton 渲染视图上选位置 + 声明 binding + 编辑 slot schema"。功能默认关(`FEATURES.templates=false`),简化处理。

- [ ] **Step 1: 重写 types/template.ts**

```typescript
// frontend/src/types/template.ts
import type { DataType } from './metric';  // 复用 metric 的 DataType,或从 kernel 类型映射导入
import type { RuleBody } from './rule';

export type TemplateStatus = 'DRAFT' | 'PUBLISHED' | 'DISABLED';

export interface SlotConstraint {
  min?: number | null;
  max?: number | null;
  enumValues?: string[] | null;
}

export interface TemplateSlot {
  key: string;
  label: string;
  dataType: DataType;
  required: boolean;
  // 无 defaultValue——默认值 = skeleton 在 binding 位置的当前值
  constraint?: SlotConstraint | null;
}

// --- SlotTarget 多态 ---
export type SlotTarget = JsonPointerTarget;

export interface JsonPointerTarget {
  type: 'JsonPointerTarget';
  jsonPointer: string;          // e.g. /conditionAst/children/0/params/threshold
}

export interface SlotBinding {
  slotKey: string;
  target: SlotTarget;
}

export interface RuleTemplate {
  id: number;
  code: string;
  tenantId: number;
  name: string;
  description?: string;
  kind: string;
  bodySkeleton: RuleBody;       // was: templateBody
  slots: TemplateSlot[];
  bindings: SlotBinding[];      // 新增
  version: number;
  status: TemplateStatus;
  createdAt?: string;
}
```

- [ ] **Step 2: 重写模板编辑器(核心交互)**

新交互:复用 rule-editor 组件渲染 `bodySkeleton`(真实默认值读数),叠加 selection layer:

1. 加载 skeleton → rule-editor 渲染(所有值可见)
2. 用户点击 ConditionNode 的 param 值位 / script params 项 / flow node 字段 → 弹出 slot binding 面板
3. 面板:选择绑到哪个已有 slot(下拉),或「新建 slot」+ 编辑 slot schema(key/label/dataType/required/constraint)
4. 编辑器状态 = `{ bodySkeleton: RuleBody, slots: TemplateSlot[], bindings: SlotBinding[] }`
5. 提交时:保存 bodySkeleton(含当前默认值)+ slots + bindings 三件

交互简化(功能默认关,不追求打磨):直接复用现有 AstEditor/ScriptEditor/FlowEditor 展示 body,在 AST 的 ConditionCard 上、Script 的 params table 上、Flow 的 node editor 上加"选位置→绑 slot"入口。

- [ ] **Step 3: 微调实例化表单**

`template-instantiate`:删对 `slot.defaultValue` 的引用,实例化时默认值取 skeleton 渲染值或留空(required=true 时必填)。slot 列表字段名从 `defaultValue` 改为无此字段。

- [ ] **Step 4: 确保编译**

```bash
cd frontend && npx tsc --noEmit
```

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): 模板 types+editor 重写——SlotBinding/SlotTarget,编辑器从挖空改为选位置声明 binding"
```

---

### Task 13: 全量测试 + 端到端 + 文档善后

**Files:**
- Modify:`docs/00-decisions.md`(D74 条目更新)
- Modify:`docs/reference-projects.md`(Drools 行退回)
- 验证:所有模块测试全绿

- [ ] **Step 1: 文档善后**

`docs/00-decisions.md` 找到 D74 条目(若有),改为:

```
### D74(2026-07-24 重设计)
实验性预实现,默认关闭(rule.template.enabled=false)。
binder SPI 重设计:JsonPointer 统一寻址覆盖全 6 kind + params 冻结常量命名空间消除 Script/Flow 异类。
待真实业务场景出现后开启功能开关。
```

`docs/reference-projects.md` 找到 Drools 行→从"已吸收"退回,加注"D74 模板功能为实验性预实现,默认关闭,受 Drools 启发但定位为 authoring 便利层"。

- [ ] **Step 2: 模块级全量测试**

```bash
$MVN -pl rule-kernel test            # kernel:params+executor 改动
$MVN -pl rule-expression-cel test    # CEL:params 注册
$MVN -pl rule-config-svc -am test    # config-svc:DTO/SPI/Service/实体/迁移(带 -am 拉取上游依赖)
$MVN -pl rule-api test               # api:Controller/DTO
```

- [ ] **Step 3: 全量 clean test 兜底(关键,不可跳过)**

```bash
$MVN clean test
```
`clean` 强制重编译全部 test 类,kernel 加字段后增量编译可能漏过期 test,必须全量 clean。

- [ ] **Step 4: 起服务端到端功能测试(如 spec 测试策略所述)**

(代码改动完成、全量测试绿后)起真实服务 → 建 scene/metric/decision 依赖 → 建模板(create + publish)→ 实例化 → 查 `rule_version` 确认 body(替换后值/params)、`template_id`/`template_version` 真落库 → 走评估入口验证产物行为 → 清理。可复用 `docs/examples/` 下剧本。

- [ ] **Step 5: Commit**

```bash
git add docs/00-decisions.md docs/reference-projects.md
git commit -m "docs: D74 决策日志更新 + Drools 行退回实验性预实现"
```

---

## 依赖顺序总览

```
Task 1(ScriptSource) ──┐
                        ├── Task 3(ScriptExecutor)
Task 2(FlowGraph) ──────┤── Task 4(FlowExecutor) ──┐
                        │                          │
Task 5(CEL) ────────────┘                          │
                                                   │
                                              全量 test
Task 6(DTOs) ── Task 7(JsonPointerBinder) ─── Task 10(ServiceImpl)
                    │                              │
Task 8(实体+迁移) ──┤                              │
                    │                              │
Task 9(接口) ───────┘                              │
                                                   │
                                          Task 11(Controller) ── Task 12(前端) ── Task 13(文档)
```

Task 1-2 可并行;Task 3-5 可并行(各自依赖 Task 1/2);Task 6-9 可并行(依赖 Task 1/2 编译产物,不依赖运行时行为);Task 10 依赖 7+8+9 全完成;Task 11 依赖 10;Task 12 依赖 11 的 API 签名;Task 13 串行在后。
```
