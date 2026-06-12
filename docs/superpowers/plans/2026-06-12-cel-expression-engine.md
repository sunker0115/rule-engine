# rule-kernel-expression-cel:CEL 运行期引擎 Implementation Plan(Plan 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现。步骤用 checkbox(`- [ ]`)跟踪。

**Goal:** 新建 Maven 模块 `rule-kernel-expression-cel`,用 Google `dev.cel` 实现 `ExpressionEngine`(Plan 2 的 kernel SPI):**dyn env** 下 compile + evaluate + 抽取引用变量 + Caffeine 预编译缓存。**仅运行期求值引擎**,不含发布期类型检查(那是 Plan 4)。

**Architecture:** 运行期与发布期分离(对标 k8s:CRD 注册=发布期做 typed 类型检查,admission=运行期求值)。本模块是**运行期引擎**:env 用 dyn(`metrics`/`payload`/`subject` = `map(string, dyn)`、`now` = `timestamp`),scene 无关、全局一份。运行期规则已过发布校验,无需再 typed 检查;dyn 对 Java `Map` 求值是 dev.cel 原生支持,无 StructType↔Map 运行期解析风险。compile 按源码内容缓存(Caffeine,scene 无关 key=source)。**per-scene typed 类型检查(`StructType` from dataType)在 Plan 4 config-svc 发布校验做,只 check 不 eval。**

**Tech Stack:** Java 25、Maven、`dev.cel:cel`(Google 官方 CEL-Java)、Caffeine(项目已用)、JUnit5 + AssertJ、`$MVN`(mvn-env,JDK 25)。

**前置依赖:** Plan 2(`ExpressionEngine`/`CompiledExpression` SPI + `ScriptSource` + `ScriptExecutor`,commit `3a24814`…`59c6e49`)已完成。Plan 3 只实现 SPI,不碰 kernel/eval-svc/config-svc(装配是 Plan 4)。设计见 spec §5.2/§5.3/§5.6。

**plan 序列定位:** 第 3 个 plan。后续 Plan 4(eval-svc 装配 + config-svc 发布期 typed 校验 + `script_source` 列 + score 分档 carrier + 端到端)/ Plan 5(SDK opt-in + API)。

---

## 关键事实 / 待验证项(实现者必读)

- `mvn-env` skill 先跑(JDK 25),用 `$MVN`。新模块测试 `$MVN -pl rule-kernel-expression-cel -am test`。不得 `-DskipTests`。中文注释,public 写 Javadoc。
- **依赖版本(Maven Central 核实钉死)**:`dev.cel:cel:0.13.0`(聚合 jar,含 compiler/runtime/common,Central 当前最新稳定版,metadata `<latest>0.13.0</latest>`)。protobuf 4.33.5 与 Spring Boot 4 同主版本线、**无冲突**,proto3 兜底不需要。仅 `sun.misc.Unsafe` 弃用 WARNING(非错误)。Caffeine/junit/assertj 由 Spring Boot BOM 管理(pom 省 version),只 `dev.cel:cel` 显式写版本。parent version 用 `${revision}`(与 `rule-kernel` 一致)。
- **dev.cel 核心 API**(基于官方文档,**按钉死版本核实方法名**——0.x 有 API 漂移):
  - 编译器:`CelCompilerFactory.standardCelCompilerBuilder().addVar(name, CelType)...build()` → `CelCompiler`;`compiler.compile(expr)` → `CelValidationResult`;`.hasError()` / `.getErrorString()` / `.getAst()`(`getAst()` 在有错时抛 `CelValidationException`)。
  - 运行时:`CelRuntimeFactory.standardCelRuntimeBuilder().build()` → `CelRuntime`;`runtime.createProgram(ast)` → `CelRuntime.Program`;`program.eval(Map<String,?>)` → `Object`(抛 `CelEvaluationException`)。
  - 类型:`SimpleType.{BOOL,INT,UINT,DOUBLE,STRING,TIMESTAMP,DYN}`、`MapType.create(k,v)`、`ListType.create(v)`。
  - 引用变量抽取:`CelNavigableAst.fromAst(ast)` → `.getRoot().allNodes()`(`Stream<CelNavigableExpr>`),按 `CelExpr.ExprKind.Kind.SELECT` 过滤(operand 为 IDENT 且 name ∈ {metrics,payload,subject})。**此 API 版本敏感,Task 3 测试验证**。
- Plan 2 的 SPI(已存在):`ExpressionEngine`(`String lang()` / `CompiledExpression compile(String)` / `Object evaluate(CompiledExpression, Map<String,Object>)`)、`CompiledExpression`(`Set<String> referencedVariables()`)、`ExpressionCompileException`,包 `com.sstlfsj.rule.kernel.api.spi.expression`。
- 绑定面顶层键(`ScriptBindings.from`,Plan 2 已实现):`metrics` / `payload` / `subject`(均 `Map<String,Object>`)/ `now`(Instant)。**eval 传入的 map 即此结构**;`now` 是 `java.time.Instant`,dev.cel TIMESTAMP 接受 `Instant`(核实;否则 Task 4 转 `java.time.Instant`→CEL 时间)。

---

## Task 1: 新建模块 `rule-kernel-expression-cel` + dev.cel 冒烟验证

**Files:**
- Create: `rule-kernel-expression-cel/pom.xml`
- Modify: `pom.xml`(根 reactor,`<modules>` 加新模块)
- Test: `rule-kernel-expression-cel/src/test/java/com/sstlfsj/rule/kernel/expression/cel/CelSmokeTest.java`

- [ ] **Step 1: 根 pom 注册模块 + 新模块 pom**

根 `pom.xml` 的 `<modules>` 块加一行 `<module>rule-kernel-expression-cel</module>`(放在 `rule-kernel` 之后)。

新建 `rule-kernel-expression-cel/pom.xml`(参照 `rule-kernel/pom.xml` 的 parent/groupId/version 写法,依赖 rule-kernel + dev.cel + caffeine + 测试):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>${revision}</version>
    </parent>
    <artifactId>rule-kernel-expression-cel</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Unit G 核实:0.13.0 是 Central 当前最新稳定聚合包;protobuf 4.33.5 无冲突 -->
        <dependency>
            <groupId>dev.cel</groupId>
            <artifactId>cel</artifactId>
            <version>0.13.0</version>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

> 先核对 `rule-kernel/pom.xml` 的实际 parent 坐标与 caffeine/junit/assertj 是否由 parent dependencyManagement 管理版本(若是则此处省 version,如上 caffeine/junit/assertj 不写 version)。dev.cel 版本 parent 未管理,需显式写并核实。

- [ ] **Step 2: 写冒烟测试(验证 dev.cel 在 classpath + dyn env + Map 求值)**

```java
package com.sstlfsj.rule.kernel.expression.cel;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CelSmokeTest {

    @Test
    void dynEnvCompilesAndEvaluatesAgainstJavaMap() throws Exception {
        CelCompiler compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("metrics", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("payload", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .build();
        CelAbstractSyntaxTree ast = compiler.compile(
                "metrics.txn_cnt_1d > 50 && payload.amount > 10000 ? 'REVIEW' : 'PASS'").getAst();

        CelRuntime runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
        Object out = runtime.createProgram(ast).eval(Map.of(
                "metrics", Map.of("txn_cnt_1d", 53L),
                "payload", Map.of("amount", 12000L)));

        assertThat(out).isEqualTo("REVIEW");
    }
}
```

- [ ] **Step 3: 跑冒烟测试**

Run: `$MVN -pl rule-kernel-expression-cel -am test -Dtest=CelSmokeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。**若编译/依赖解析失败**:核实 dev.cel 坐标/版本(见关键事实的迁移与 proto3 备注),调整 pom 后重试。**若 API 方法名不符**(如 `getAst()`/`standardCelCompilerBuilder()`),按钉死版本的实际 API 修正本测试与后续任务的调用——这是本 plan 的"地基验证",务必在此关把 dev.cel 真实 API 对齐。

- [ ] **Step 4: 提交**

```bash
git add pom.xml rule-kernel-expression-cel/pom.xml \
        rule-kernel-expression-cel/src/test/java/com/sstlfsj/rule/kernel/expression/cel/CelSmokeTest.java
git commit -m "feat(expression-cel): 新建 rule-kernel-expression-cel 模块 + dev.cel 冒烟验证"
```

---

## Task 2: `CelCompiledExpression`(包装 AST + 引用变量)

**Files:**
- Create: `rule-kernel-expression-cel/src/main/java/com/sstlfsj/rule/kernel/expression/cel/CelCompiledExpression.java`

> 实现 Plan 2 的 `CompiledExpression` SPI,持有 dev.cel 的 `CelAbstractSyntaxTree` 与预抽取的引用变量集。无独立测试,由 Task 5 间接覆盖。

- [ ] **Step 1: 实现**

```java
package com.sstlfsj.rule.kernel.expression.cel;

import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import dev.cel.common.CelAbstractSyntaxTree;

import java.util.Set;

/** dev.cel 编译产物:持有 checked AST(供运行期 plan)与发布期依赖抽取用的引用变量集。 */
public final class CelCompiledExpression implements CompiledExpression {

    private final CelAbstractSyntaxTree ast;
    private final Set<String> referencedVariables;

    /**
     * @param ast                 dev.cel 编译后的 AST
     * @param referencedVariables 引用的变量点路径(如 "metrics.txn_cnt_1d"),发布期冻依赖用
     */
    public CelCompiledExpression(CelAbstractSyntaxTree ast, Set<String> referencedVariables) {
        this.ast = ast;
        this.referencedVariables = Set.copyOf(referencedVariables);
    }

    /** @return dev.cel checked AST(供 CelExpressionEngine.evaluate 创建 Program) */
    public CelAbstractSyntaxTree ast() {
        return ast;
    }

    @Override
    public Set<String> referencedVariables() {
        return referencedVariables;
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `$MVN -pl rule-kernel-expression-cel -am test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add rule-kernel-expression-cel/src/main/java/com/sstlfsj/rule/kernel/expression/cel/CelCompiledExpression.java
git commit -m "feat(expression-cel): CelCompiledExpression(包装 AST + 引用变量)"
```

---

## Task 3: 引用变量抽取 `CelReferencedVariables`

**Files:**
- Create: `rule-kernel-expression-cel/src/main/java/com/sstlfsj/rule/kernel/expression/cel/CelReferencedVariables.java`
- Test: `rule-kernel-expression-cel/src/test/java/com/sstlfsj/rule/kernel/expression/cel/CelReferencedVariablesTest.java`

> 从 checked AST 抽取形如 `metrics.<x>` / `payload.<x>` / `subject.<x>` 的引用点路径(发布期冻 metricDependencies/payloadDependencies 用)。**CelNavigableAst API 版本敏感**,本 Task 测试即为验证关。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.expression.cel;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CelReferencedVariablesTest {

    private CelAbstractSyntaxTree compile(String expr) throws Exception {
        CelCompiler c = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("metrics", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("payload", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("subject", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("now", SimpleType.TIMESTAMP)
                .build();
        return c.compile(expr).getAst();
    }

    @Test
    void extractsNamespacedSelects() throws Exception {
        Set<String> vars = CelReferencedVariables.from(
                compile("metrics.txn_cnt_1d > 50 && payload.amount > 10000 ? 'R' : 'P'"));
        assertThat(vars).contains("metrics.txn_cnt_1d", "payload.amount");
    }

    @Test
    void ignoresNonNamespacedAndNow() throws Exception {
        Set<String> vars = CelReferencedVariables.from(compile("now > timestamp('2020-01-01T00:00:00Z')"));
        assertThat(vars).isEmpty();   // now 不是 metrics/payload/subject 命名空间,不计入依赖
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel-expression-cel -am test -Dtest=CelReferencedVariablesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`CelReferencedVariables` 不存在)。

- [ ] **Step 3: 实现(按钉死版本核实 CelNavigableAst API)**

```java
package com.sstlfsj.rule.kernel.expression.cel;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;

import java.util.LinkedHashSet;
import java.util.Set;

/** 从 dev.cel checked AST 抽取 metrics/payload/subject 命名空间下的字段选择(点路径),供发布期冻依赖。 */
public final class CelReferencedVariables {

    private static final Set<String> NAMESPACES = Set.of("metrics", "payload", "subject");

    private CelReferencedVariables() {}

    /**
     * 抽取形如 "metrics.x" / "payload.x" / "subject.x" 的引用点路径。
     *
     * @param ast dev.cel 编译后的 AST
     * @return 引用点路径集合(命名空间外的标识/选择忽略)
     */
    public static Set<String> from(CelAbstractSyntaxTree ast) {
        Set<String> out = new LinkedHashSet<>();
        CelNavigableAst.fromAst(ast).getRoot().allNodes()
                .map(node -> node.expr())
                .filter(expr -> expr.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT)
                .forEach(expr -> {
                    CelExpr.CelSelect select = expr.select();
                    CelExpr operand = select.operand();
                    // 仅收 <namespace>.<field>:operand 是命名空间 IDENT
                    if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT
                            && NAMESPACES.contains(operand.ident().name())) {
                        out.add(operand.ident().name() + "." + select.field());
                    }
                });
        return out;
    }
}
```

> 若钉死版本的 `CelNavigableAst`/`CelExpr` 访问器名不同(如 `getExpr()`/`getKind()`/`getSelect()`),按实际 API 调整;语义不变:遍历所有节点、取 SELECT、operand 为 NAMESPACES 内 IDENT 时收 `ns.field`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel-expression-cel -am test -Dtest=CelReferencedVariablesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel-expression-cel/src/main/java/com/sstlfsj/rule/kernel/expression/cel/CelReferencedVariables.java \
        rule-kernel-expression-cel/src/test/java/com/sstlfsj/rule/kernel/expression/cel/CelReferencedVariablesTest.java
git commit -m "feat(expression-cel): CelReferencedVariables(从 AST 抽 metrics/payload/subject 引用)"
```

---

## Task 4: `CelExpressionEngine`(SPI 实现 + Caffeine 预编译缓存)

**Files:**
- Create: `rule-kernel-expression-cel/src/main/java/com/sstlfsj/rule/kernel/expression/cel/CelExpressionEngine.java`
- Test: `rule-kernel-expression-cel/src/test/java/com/sstlfsj/rule/kernel/expression/cel/CelExpressionEngineTest.java`

> dyn env、单例线程安全、按 source 内容缓存编译产物。`lang()` 返回 `ExpressionLang.CEL.tag()`。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.expression.cel;

import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CelExpressionEngineTest {

    private final CelExpressionEngine engine = new CelExpressionEngine();

    private Map<String, Object> bindings(Map<String, Object> metrics, Map<String, Object> payload) {
        Map<String, Object> b = new HashMap<>();
        b.put("metrics", metrics);
        b.put("payload", payload);
        b.put("subject", Map.of());
        b.put("now", Instant.parse("2026-06-01T00:00:00Z"));
        return b;
    }

    @Test
    void langIsCel() {
        assertThat(engine.lang()).isEqualTo(ExpressionLang.CEL.tag());
    }

    @Test
    void evaluatesStringDecision() {
        CompiledExpression c = engine.compile("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        Object out = engine.evaluate(c, bindings(Map.of(), Map.of("amount", 12000L)));
        assertThat(out).isEqualTo("REVIEW");
    }

    @Test
    void evaluatesBooleanAndNumber() {
        assertThat(engine.evaluate(engine.compile("metrics.cnt > 50"),
                bindings(Map.of("cnt", 53L), Map.of()))).isEqualTo(true);
        assertThat(engine.evaluate(engine.compile("metrics.score + 0.5"),
                bindings(Map.of("score", 10.0), Map.of()))).isEqualTo(10.5);
    }

    @Test
    void compileCachesBySource() {
        CompiledExpression a = engine.compile("payload.x > 1");
        CompiledExpression b = engine.compile("payload.x > 1");
        assertThat(a).isSameAs(b);   // 同源命中缓存,同一实例
    }

    @Test
    void exposesReferencedVariables() {
        CompiledExpression c = engine.compile("metrics.txn_cnt_1d > 50 && payload.amount > 0");
        assertThat(c.referencedVariables()).contains("metrics.txn_cnt_1d", "payload.amount");
    }

    @Test
    void syntaxErrorThrowsCompileException() {
        assertThatThrownBy(() -> engine.compile("metrics.x >>> "))
                .isInstanceOf(ExpressionCompileException.class);
    }

    @Test
    void ioAndReflectionNotExpressible() {
        // safe-by-design:CEL 无文件/反射/类加载内建,此类标识符编译期即不可解析 → 拒
        assertThatThrownBy(() -> engine.compile("java.lang.Runtime.getRuntime()"))
                .isInstanceOf(ExpressionCompileException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel-expression-cel -am test -Dtest=CelExpressionEngineTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`CelExpressionEngine` 不存在)。

- [ ] **Step 3: 实现**

```java
package com.sstlfsj.rule.kernel.expression.cel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

import java.util.Map;

/**
 * dev.cel 实现的运行期表达式引擎(EXPRESSION_SCRIPT 默认引擎)。
 * dyn env(metrics/payload/subject = map(string,dyn)、now = timestamp),scene 无关、线程安全单例。
 * 按源码内容缓存编译产物(Caffeine);类型检查在发布期(config-svc)另做,本引擎只 compile+eval。
 */
public final class CelExpressionEngine implements ExpressionEngine {

    private final CelCompiler compiler;
    private final CelRuntime runtime;
    private final Cache<String, CelCompiledExpression> cache;

    /** 默认缓存上限 10_000(脚本规则数量级远小于此)。 */
    public CelExpressionEngine() {
        this(10_000);
    }

    /**
     * @param maxCachedExpressions 预编译缓存上限
     */
    public CelExpressionEngine(long maxCachedExpressions) {
        this.compiler = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar("metrics", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("payload", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("subject", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addVar("now", SimpleType.TIMESTAMP)
                .build();
        this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder().build();
        this.cache = Caffeine.newBuilder().maximumSize(maxCachedExpressions).build();
    }

    @Override
    public String lang() {
        return ExpressionLang.CEL.tag();
    }

    @Override
    public CompiledExpression compile(String source) {
        // 内容寻址缓存:同源脚本(跨规则/版本)共享一份编译产物
        return cache.get(source, this::doCompile);
    }

    private CelCompiledExpression doCompile(String source) {
        CelValidationResult result = compiler.compile(source);
        if (result.hasError()) {
            throw new ExpressionCompileException("CEL 编译失败: " + result.getErrorString());
        }
        try {
            CelAbstractSyntaxTree ast = result.getAst();
            return new CelCompiledExpression(ast, CelReferencedVariables.from(ast));
        } catch (CelValidationException e) {
            throw new ExpressionCompileException("CEL 编译失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Object evaluate(CompiledExpression compiled, Map<String, Object> bindings) {
        CelCompiledExpression cel = (CelCompiledExpression) compiled;
        try {
            // 运行期对 dyn env 求值;ScriptExecutor 捕获异常转 SCRIPT_EVAL_ERROR
            return runtime.createProgram(cel.ast()).eval(adaptBindings(bindings));
        } catch (CelEvaluationException e) {
            throw new ExpressionEvaluateException("CEL 求值失败: " + e.getMessage(), e);
        }
    }
}
```

> 注:`evaluate` 抛 **`ExpressionEvaluateException`**(kernel SPI 包内,与 `ExpressionCompileException` 对称的 typed 异常,code-review I1 补;extends RuntimeException),由上游 `ScriptExecutor` 的 `catch (Exception)` 兜成 `SCRIPT_EVAL_ERROR`(Plan 2 已实现)。
> `now` 绑定值是 `Instant`——**Unit H 实测:standardCelRuntime 默认 TIMESTAMP 运行期是 protobuf `Timestamp`,不直接吃 `Instant`**(native java.time 需 opt-in `CelOptions`,非默认)。故 evaluate 入口加 `adaptBindings`:把 `now` 的 `Instant` 转 `com.google.protobuf.Timestamp`(`setSeconds(epochSecond).setNanos(nano)`)、`new HashMap<>(bindings)` 拷贝避免污染不可变入参、其余键透传。protobuf 由 dev.cel 传递依赖提供(版本锁定,故意不在模块 pom 显式声明)。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel-expression-cel -am test -Dtest=CelExpressionEngineTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS(7 用例)。若 `ioAndReflectionNotExpressible` 行为与预期不符(CEL 对未声明标识符的报错形式),按实际:未声明变量/函数 → 编译错 → `ExpressionCompileException`,断言成立即可。

- [ ] **Step 5: 模块全量绿**

Run: `$MVN -pl rule-kernel-expression-cel -am test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交**

```bash
git add rule-kernel-expression-cel/src/main/java/com/sstlfsj/rule/kernel/expression/cel/CelExpressionEngine.java \
        rule-kernel-expression-cel/src/test/java/com/sstlfsj/rule/kernel/expression/cel/CelExpressionEngineTest.java
git commit -m "feat(expression-cel): CelExpressionEngine(dyn env + Caffeine 预编译缓存,实现 ExpressionEngine SPI)"
```

---

## Task 5: 全量兜底

**Files:** 无(验证)

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: BUILD SUCCESS(新增 rule-kernel-expression-cel 模块在 reactor 内全绿;其它模块不受影响——本 plan 未碰它们)。

- [ ] **Step 2: 确认未越界**

Run: `git diff --name-only a20816b..HEAD | grep -v '^rule-kernel-expression-cel/' | grep -v '^docs/' | grep -E '^rule-' || echo "仅 expression-cel + docs 改动(根 pom 除外)"`
Expected: 仅根 `pom.xml`(加模块)+ `rule-kernel-expression-cel/**`;不应碰 kernel/eval-svc/config-svc/sdk 源码。

---

## Self-Review(已执行)

**1. Spec 覆盖**:§5.2(ExpressionEngine 实现)Task 4;§5.3(绑定面——消费 Plan 2 的 ScriptBindings 结构,顶层 metrics/payload/subject/now)Task 1/4;§5.6(预编译缓存,内容寻址 key=source)Task 4。**明确不在本 plan**:§5.7 发布期 typed 类型检查(→ Plan 4,typed env 在那)、score 分档(→ Plan 4)、eval-svc 装配(→ Plan 4)。

**2. 占位扫描**:无 TBD。三处"按钉死版本核实 API"是对**外部快速迭代库**(dev.cel 0.x)的诚实标注,非占位——每处都给了基于官方文档的具体代码 + 兜底测试(Task 1 冒烟、Task 3/4 测试)来捕捉 API 偏差。

**3. 类型一致**:`CelExpressionEngine implements ExpressionEngine`(lang/compile/evaluate)与 Plan 2 SPI 一致;`CelCompiledExpression implements CompiledExpression`(referencedVariables)一致;`compile` 返回 `CompiledExpression`、`evaluate` 收 `CompiledExpression` 强转 `CelCompiledExpression`——与 Plan 2 ScriptExecutor 调用契约一致。

**4. 风险与去风险**:最大未知是 dev.cel 0.x 的精确 API(方法名/CelNavigableAst/Instant↔timestamp)。Task 1 冒烟测试作为"地基验证关"前置,API 不符在此即暴露、统一对齐后再往下;Task 3/4 测试再次锁定 referencedVariables 与求值行为。protobuf 冲突/坐标迁移在关键事实区给了 0.9.0-proto3 与坐标核实的明确指引。
