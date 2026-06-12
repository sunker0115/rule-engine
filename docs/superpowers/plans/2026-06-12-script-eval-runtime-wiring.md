# EXPRESSION_SCRIPT 持久化 + 运行期装配 Implementation Plan(Plan 4a)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐任务实现。步骤用 checkbox(`- [ ]`)跟踪。

**Goal:** 打通 EXPRESSION_SCRIPT 的"**存储 → 装配快照 → 运行期求值**"路径:`rule_version.script_source` 列 + `RuleVersionRow`/`SnapshotAssembler` 带 script + eval-svc 注册 `CelExpressionEngine`+`ScriptExecutor`。让"DB 里一条脚本规则行能被索引加载并由 CEL 引擎求值出决策"。

**Architecture:** 不碰写路径(config-svc create/publish 是 Plan 4b)。本 plan 是**读/运行侧**:① DB 加 `script_source` 列(可空);② kernel 装配链路(`RuleVersionRow` 加 `scriptSourceJson`、`SnapshotAssembler` 反序列化进 `RuleVersionSnapshot.script`,conditionAst 为 null 时跳过 AST 反序列化);③ eval-svc 依赖 `rule-kernel-expression-cel`,`EvalAutoConfiguration` 建 `CelExpressionEngine`+`ScriptExecutor` bean 并注册进 kind→executor map。

**Tech Stack:** Java 25、Maven、MyBatis-Plus、Flyway(`V1_NN__*.sql`)、`dev.cel`(经 expression-cel 模块)、JUnit5 + AssertJ、`$MVN`(mvn-env,JDK 25)。

**前置依赖:** Plan 2(`ScriptSource`/`ScriptExecutor`/`RuleVersionSnapshot.script`,commit `3a24814`…`59c6e49`)、Plan 3(`CelExpressionEngine`,commit `3df836a`…`f570acd`)已完成。

**plan 序列定位:** 第 4 个 plan(4a)。后续 Plan 4b(config-svc 写路径:`resolveAndValidate` EXPRESSION_SCRIPT 分支 + lifecycle + controller/DTO)/ Plan 4c(发布期 typed 校验:`typeCheck` SPI + CEL StructType)/ Plan 5(SDK opt-in + API)。

---

## 关键事实(实现者必读)

- `mvn-env` skill(JDK 25),用 `$MVN`。跨模块改动带 `-am`,最终 `$MVN clean test` 兜底。中文注释,public/SPI 写 Javadoc。不得 `-DskipTests`。
- **迁移号**:现有最高 `V1_27`,新迁移用 **`V1_28`**。迁移目录:`rule-config-svc/src/main/resources/db/migration/`。
- `RuleVersionSnapshot` 已有 13 参 canonical 构造(末参 `ScriptSource script`)+ 旧 12 参/8 参便利构造(Plan 2)。`ScriptSource(source, lang)` 在包 `com.sstlfsj.rule.kernel.api.model`。
- 装配链路:`RuleVersionReadMapper`(eval-svc,@Select 把列 alias 进 `RuleVersionRow`)→ `SnapshotAssembler.assemble`(kernel,反序列化 JSON 字段 → `RuleVersionSnapshot`)。`RuleVersionRow` 的 `kind` 取自 `rd.kind`(rule_definition.kind)。
- `AstJsonCodec`(kernel)持 ObjectMapper,已有 `deserializeAst`/`deserializePreGates` 等;`ScriptSource` 是普通 record,可直接 `mapper.readValue(json, ScriptSource.class)`。
- eval-svc `EvalAutoConfiguration.evalEngine`(已存在)用 `Map.of(RuleKind.AST_BOOLEAN.tag(), ..., DECISION_TABLE.tag(), decisionTableExecutor)` 注册 kind→executor。`ScriptExecutor`(kernel)构造 `new ScriptExecutor(Map<String, ExpressionEngine>)`。`CelExpressionEngine`(expression-cel)无参构造。
- **本 plan 不做**:config-svc 写路径(Plan 4b)、发布期 typed 校验(Plan 4c)、编译缓存预热(优化,延后;首次 eval 惰性编译+缓存即正确)。
- **测试边界**:自动化测试到 `SnapshotAssembler` 单测(script 行 → snapshot.script 正确);真·端到端(真 DB 建脚本规则→评估)是 Plan 4b 给写 API 后的**功能测试阶段**手动剧本(CLAUDE.md 功能测试纪律)。

---

## Task 1: `V1_28` 迁移——`rule_version.script_source` 列

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_28__rule_version_script_source.sql`

- [ ] **Step 1: 写迁移**

```sql
-- EXPRESSION_SCRIPT 规则的脚本载体(ScriptSource JSON: {source, lang})；其它 kind 为 NULL。
-- 与 condition_ast 互斥:脚本规则 condition_ast=NULL、script_source 非空。
ALTER TABLE rule_version
    ADD COLUMN script_source JSON NULL COMMENT 'EXPRESSION_SCRIPT 脚本载体 {source,lang}，其它 kind 为 NULL';
```

- [ ] **Step 2: 验证迁移可应用(全量构建会跑 Flyway)**

Run: `$MVN -pl rule-config-svc -am test-compile`
Expected: BUILD SUCCESS(迁移文件被打包;实际 apply 在集成测试/启动时)。

- [ ] **Step 3: 提交**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_28__rule_version_script_source.sql
git commit -m "feat(db): V1_28 rule_version.script_source 列(EXPRESSION_SCRIPT 载体)"
```

---

## Task 2: `AstJsonCodec` 加 `deserializeScriptSource`

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/AstJsonCodec.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/codec/AstJsonCodecScriptTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.codec;

import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.internal.codec.AstJsonCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AstJsonCodecScriptTest {

    private final AstJsonCodec codec = new AstJsonCodec();

    @Test
    void deserializesScriptSource() throws Exception {
        ScriptSource s = codec.deserializeScriptSource("{\"source\":\"payload.amount > 10\",\"lang\":\"CEL\"}");
        assertThat(s.source()).isEqualTo("payload.amount > 10");
        assertThat(s.lang()).isEqualTo("CEL");
    }

    @Test
    void nullOrBlankReturnsNull() throws Exception {
        assertThat(codec.deserializeScriptSource(null)).isNull();
        assertThat(codec.deserializeScriptSource("  ")).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=AstJsonCodecScriptTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败(`deserializeScriptSource` 不存在)。

- [ ] **Step 3: 加方法**

在 `AstJsonCodec` 内(如 `deserializePayloadDependencies` 后)加:

```java
    /**
     * 将 JSON 字符串反序列化为 ScriptSource(rule_version.script_source);null/空白返回 null(非脚本规则)。
     *
     * @param json 脚本载体 JSON,形如 {"source":"...","lang":"CEL"};可为 null
     * @return ScriptSource,或 null
     */
    public ScriptSource deserializeScriptSource(String json) throws JacksonException {
        if (json == null || json.isBlank()) return null;
        return mapper.readValue(json, ScriptSource.class);
    }
```

文件顶部 import 加 `import com.sstlfsj.rule.kernel.api.model.ScriptSource;`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=AstJsonCodecScriptTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/AstJsonCodec.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/codec/AstJsonCodecScriptTest.java
git commit -m "feat(kernel): AstJsonCodec.deserializeScriptSource(脚本载体反序列化)"
```

---

## Task 3: `RuleVersionRow` 加 `scriptSourceJson`(向后兼容)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/RuleVersionRow.java`

> 加第 14 个分量 `scriptSourceJson`,并**保留旧 13 参与 9 参便利构造**(scriptSourceJson=null),使既有调用点(测试、SnapshotAssembler 旧路径)零改动。

- [ ] **Step 1: 加分量 + 向后兼容构造**

1a. record 头部 `version` 分量后加第 14 个分量(`version` 后加逗号):

```java
        /** rule_version.version 版本号。 */
        long version,
        /** rule_version.script_source JSON(ScriptSource {source,lang});非脚本规则为 null。 */
        String scriptSourceJson
) {
```

1b. 现有 9 参便利构造的 `this(...)` 委托末尾已是 `null, null, null, 0L`(12 参)——它会绑定到下面新增的 13 参便利构造,**无需改**。

1c. **新增 13 参便利构造**(旧 canonical 签名,委托 scriptSourceJson=null),加在 9 参便利构造之后:

```java
    /**
     * 兼容旧 13 参调用点(无 scriptSourceJson,默认 null)。
     *
     * @param ruleVersionId         规则版本 id
     * @param sceneCode             场景编码
     * @param tenantId              租户 id
     * @param conditionAstJson      条件 AST JSON
     * @param preGatesJson          Pre-Gate JSON
     * @param decisionBindingsJson  Decision 绑定 JSON
     * @param triggerEventTypesJson 触发事件类型 JSON
     * @param kind                  规则类型
     * @param decisionStrategy      场景执行策略
     * @param metricDependenciesJson metric 依赖 JSON
     * @param payloadDependenciesJson payload 依赖 JSON
     * @param code                  逻辑编码
     * @param version               版本号
     */
    public RuleVersionRow(Long ruleVersionId, String sceneCode, Long tenantId,
                          String conditionAstJson, String preGatesJson, String decisionBindingsJson,
                          String triggerEventTypesJson, String kind, String decisionStrategy,
                          String metricDependenciesJson, String payloadDependenciesJson,
                          String code, long version) {
        this(ruleVersionId, sceneCode, tenantId, conditionAstJson, preGatesJson, decisionBindingsJson,
                triggerEventTypesJson, kind, decisionStrategy, metricDependenciesJson, payloadDependenciesJson,
                code, version, null);
    }
```

> 注:9 参便利构造体当前是 `this(..., null, null, null, 0L)`(委托到旧 13 参 canonical)。加第 14 分量后它委托到上面新增的 13 参便利构造(参数个数 13),仍成立。确认编译通过即可。

- [ ] **Step 2: 编译 kernel**

Run: `$MVN -pl rule-kernel -am test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/RuleVersionRow.java
git commit -m "feat(kernel): RuleVersionRow.scriptSourceJson(向后兼容旧构造)"
```

---

## Task 4: `SnapshotAssembler` 带 script + conditionAst null 守卫

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssembler.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/codec/SnapshotAssemblerScriptTest.java`

> 脚本行 `conditionAstJson=null` → conditionAst=null(不反序列化);`scriptSourceJson` → `snapshot.script`。用 13 参 canonical 构造。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.codec;

import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.codec.RuleVersionRow;
import com.sstlfsj.rule.kernel.internal.codec.SnapshotAssembler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotAssemblerScriptTest {

    private final SnapshotAssembler assembler = new SnapshotAssembler();

    @Test
    void assemblesScriptRowWithNullConditionAst() {
        // 脚本行:condition_ast 为 null,script_source 非空,kind=EXPRESSION_SCRIPT
        RuleVersionRow row = new RuleVersionRow(
                1L, "scene", 100L,
                null,                       // conditionAstJson
                "[]", "[]", "[]",
                RuleKind.EXPRESSION_SCRIPT.tag(), "HIGHEST_PRIORITY",
                "[]", "[]", "R1", 1L,
                "{\"source\":\"payload.amount > 10000 ? 'REVIEW' : 'PASS'\",\"lang\":\"CEL\"}");

        RuleVersionSnapshot snap = assembler.assembleAll(java.util.List.of(row)).get(0);

        assertThat(snap.conditionAst()).isNull();
        assertThat(snap.script()).isNotNull();
        assertThat(snap.script().source()).isEqualTo("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        assertThat(snap.script().lang()).isEqualTo("CEL");
        assertThat(snap.kind()).isEqualTo(RuleKind.EXPRESSION_SCRIPT.tag());
    }

    @Test
    void astRowHasNullScript() {
        RuleVersionRow row = new RuleVersionRow(
                2L, "scene", 100L,
                "{\"type\":\"AndNode\",\"children\":[]}",
                "[]", "[]", "[]", RuleKind.AST_BOOLEAN.tag(), "HIGHEST_PRIORITY",
                "[]", "[]", "R2", 1L, null);

        RuleVersionSnapshot snap = assembler.assembleAll(java.util.List.of(row)).get(0);

        assertThat(snap.script()).isNull();
        assertThat(snap.conditionAst()).isNotNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=SnapshotAssemblerScriptTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 失败——当前 `assemble` 对 null conditionAstJson 调 `deserializeAst(null)` 抛错,且不带 script。

- [ ] **Step 3: 改 `assemble`**

把 `assemble` 方法体改为(conditionAst null 守卫 + script 反序列化 + 13 参构造):

```java
    public RuleVersionSnapshot assemble(RuleVersionRow row) throws JacksonException {
        // 脚本规则 condition_ast 为 null:跳过 AST 反序列化(脚本载体走 script 字段)
        AstNode conditionAst = (row.conditionAstJson() == null || row.conditionAstJson().isBlank())
                ? null : codec.deserializeAst(row.conditionAstJson());
        List<RuleVersionSnapshot.PreGateConfig> preGates =
                codec.deserializePreGates(row.preGatesJson());
        List<RuleVersionSnapshot.DecisionBinding> decisionBindings =
                codec.deserializeDecisionBindings(row.decisionBindingsJson());
        List<String> triggerEventTypes = codec.deserializeStringList(
                row.triggerEventTypesJson() == null ? "[]" : row.triggerEventTypesJson());
        List<MetricDependency> metricDependencies = codec.deserializeMetricDependencies(
                row.metricDependenciesJson() == null ? "[]" : row.metricDependenciesJson());
        List<PayloadDependency> payloadDependencies = codec.deserializePayloadDependencies(
                row.payloadDependenciesJson() == null ? "[]" : row.payloadDependenciesJson());
        ScriptSource script = codec.deserializeScriptSource(row.scriptSourceJson());

        return new RuleVersionSnapshot(
                row.ruleVersionId(),
                row.sceneCode(),
                String.valueOf(row.tenantId()),
                conditionAst,
                preGates,
                decisionBindings,
                triggerEventTypes,
                row.kind() != null ? row.kind() : RuleKind.AST_BOOLEAN.tag(),
                row.code(),
                row.version(),
                metricDependencies,
                payloadDependencies,
                script
        );
    }
```

文件顶部 import 加 `import com.sstlfsj.rule.kernel.api.model.ScriptSource;`。

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=SnapshotAssemblerScriptTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: kernel 全量绿(验证既有装配测试不破)**

Run: `$MVN -pl rule-kernel -am test`
Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssembler.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/codec/SnapshotAssemblerScriptTest.java
git commit -m "feat(kernel): SnapshotAssembler 带 script + conditionAst null 守卫"
```

---

## Task 5: `RuleVersionReadMapper` SQL 选 `script_source`

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java`

> 3 个返回 `RuleVersionRow` 的 @Select(`loadAllActive`/`loadActiveByScene`/`loadById`)各加一列 `rv.script_source AS scriptSourceJson`。MyBatis record 构造按列名映射,新分量须有对应列,否则构造失败。

- [ ] **Step 1: 三个 SELECT 各加列**

在每个 @Select 的列清单里,把 `rv.version AS version` 行后加(注意保持逗号正确):

```sql
              rv.version         AS version,
              rv.script_source   AS scriptSourceJson
```

(三处 @Select 都改;`latestVersionIdByRule` 返回 Long 不动。)

- [ ] **Step 2: 编译 eval-svc**

Run: `$MVN -pl rule-eval-svc -am test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java
git commit -m "feat(eval): RuleVersionReadMapper 选 script_source 列"
```

---

## Task 6: eval-svc 注册 `CelExpressionEngine` + `ScriptExecutor`

**Files:**
- Modify: `rule-eval-svc/pom.xml`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`

- [ ] **Step 1: eval-svc 依赖 expression-cel 模块**

`rule-eval-svc/pom.xml` 的 `<dependencies>` 加(版本由根 dependencyManagement 管理,参照同 pom 内 `rule-kernel` 依赖写法):

```xml
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel-expression-cel</artifactId>
        </dependency>
```

> 先核对 `rule-eval-svc/pom.xml` 内部模块依赖是否省 version(由根 dependencyManagement 管理);若需 version 则加 `<version>${revision}</version>`。若根 dependencyManagement 未声明该新模块,在根 pom 的 `<dependencyManagement>` 补一条(参照其它 rule-* 模块)。

- [ ] **Step 2: 建 bean + 注册 kind**

在 `EvalAutoConfiguration` 加两个 @Bean(参照既有 `scorecardExecutor()`/`decisionTreeExecutor()` 写法):

```java
    /**
     * 注册 CEL 表达式引擎(EXPRESSION_SCRIPT 默认引擎,dyn env + 预编译缓存)。
     *
     * @return CelExpressionEngine 实例
     */
    @Bean
    public com.sstlfsj.rule.kernel.expression.cel.CelExpressionEngine celExpressionEngine() {
        return new com.sstlfsj.rule.kernel.expression.cel.CelExpressionEngine();
    }

    /**
     * 注册 ScriptExecutor,供 kind=EXPRESSION_SCRIPT 的规则版本评估使用。
     * 按 lang 路由引擎;盒内默认仅 CEL。
     *
     * @param celExpressionEngine CEL 引擎
     * @return ScriptExecutor 实例
     */
    @Bean
    public com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor scriptExecutor(
            com.sstlfsj.rule.kernel.expression.cel.CelExpressionEngine celExpressionEngine) {
        return new com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor(
                java.util.Map.of(celExpressionEngine.lang(), celExpressionEngine));
    }
```

把 `evalEngine(...)` bean 方法签名加形参 `ScriptExecutor scriptExecutor`,并在构造 executors map 处加一项 `RuleKind.EXPRESSION_SCRIPT.tag()`:

```java
    @Bean
    public EvalEngine evalEngine(
            SceneRuleIndex sceneRuleIndex,
            EvalContextAssembler evalContextAssembler,
            @Autowired(required = false) List<PreGate> preGates,
            RuleVersionExecutor ruleVersionExecutor,
            ScorecardExecutor scorecardExecutor,
            DecisionTreeExecutor decisionTreeExecutor,
            DecisionTableExecutor decisionTableExecutor,
            com.sstlfsj.rule.kernel.internal.evaluator.ScriptExecutor scriptExecutor,
            com.sstlfsj.rule.eval.internal.TraceProperties traceProperties) {
        Map<String, PreGate> gateMap = new HashMap<>();
        if (preGates != null) {
            preGates.forEach(g -> gateMap.put(g.gateType(), g));
        }
        return new EvalEngine(sceneRuleIndex, evalContextAssembler, gateMap,
                Map.of(RuleKind.AST_BOOLEAN.tag(),    ruleVersionExecutor,
                       RuleKind.SCORECARD.tag(),      scorecardExecutor,
                       RuleKind.DECISION_TREE.tag(),  decisionTreeExecutor,
                       RuleKind.DECISION_TABLE.tag(), decisionTableExecutor,
                       RuleKind.EXPRESSION_SCRIPT.tag(), scriptExecutor),
                traceProperties.isEnabled());
    }
```

- [ ] **Step 3: 编译 + eval-svc 全量测试**

Run: `$MVN -pl rule-eval-svc -am test`
Expected: BUILD SUCCESS(既有 eval 测试不破;新增脚本 kind 注册不影响其它 kind)。

- [ ] **Step 4: 提交**

```bash
git add rule-eval-svc/pom.xml \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
git commit -m "feat(eval): 注册 CelExpressionEngine + ScriptExecutor(EXPRESSION_SCRIPT 运行期装配)"
```

> 若 Step 1 需在根 pom dependencyManagement 补条目,把根 `pom.xml` 一并 `git add`。

---

## Task 7: 全量兜底 + 装配链路验证

**Files:** 无(验证)

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test`
Expected: BUILD SUCCESS(全模块;`SnapshotAssemblerScriptTest`/`AstJsonCodecScriptTest` 绿;既有 eval/config 测试不破)。

- [ ] **Step 2: 未越界核查**

Run:
```bash
git diff --name-only <Plan4a 起点 SHA>..HEAD | grep -v '^docs/' | sort
```
Expected: 仅 `rule-config-svc/.../db/migration/V1_28*`、`rule-kernel/.../codec/*`、`rule-eval-svc/.../RuleVersionReadMapper.java`、`rule-eval-svc/pom.xml`、`rule-eval-svc/.../EvalAutoConfiguration.java`(+ 可能根 pom)。**不应碰** config-svc 的 `PublishService`/controller(那是 Plan 4b)。

> **真·端到端**(真 DB 建脚本规则 → 评估出决策)留待 Plan 4b 给出写 API 后,按 CLAUDE.md 功能测试纪律起真实服务走 curl 剧本验证。本 plan 的自动化覆盖到装配单测(`SnapshotAssemblerScriptTest`)+ 全量编译/既有集成测试绿。

---

## Self-Review(已执行)

**1. Spec 覆盖**:对应 spec §6(数据契约:`script_source` 列、`conditionAst=null`、snapshot 带 script)Task 1/3/4;§4 模块落点(eval-svc 装配 CelExpressionEngine+ScriptExecutor)Task 6。**明确不在本 plan**:config-svc 写路径(§5.7 发布校验 → Plan 4b)、typed 校验(Plan 4c)、缓存预热(优化,延后)。

**2. 占位扫描**:无 TBD。Task 6 Step 1 的"核对 pom version 是否 parent 管理 / 根 dependencyManagement 是否需补"是对既有 pom 约定的核对指引(给了两种情形的具体处理),非占位。Task 7 的 `<Plan4a 起点 SHA>` 是执行时填入的实际起点 commit。

**3. 类型一致**:`RuleVersionRow` 14 分量(末 `scriptSourceJson`)+ 13/9 参便利构造;`SnapshotAssembler` 用 `RuleVersionSnapshot` 13 参 canonical(末 `script`,Plan 2 已建);`ScriptExecutor(Map<String,ExpressionEngine>)`、`CelExpressionEngine()` 无参(Plan 2/3 已建)签名一致;mapper alias `scriptSourceJson` 对应 record 分量名(MyBatis 构造映射)。

**4. 一个执行顺序约束**:Task 3(加 RuleVersionRow 分量)与 Task 5(mapper 加列)必须配套——加了分量但 mapper 没选对应列,MyBatis 构造会失败。Task 4 的单测用全 14 参构造不依赖 mapper,可独立绿;mapper 列在 Task 6 后由 eval-svc 全量测试(若有 DB 集成测试)或 Task 7 clean test 验证。
