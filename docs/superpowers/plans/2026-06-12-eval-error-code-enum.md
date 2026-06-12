# EvalErrorCode 枚举统一(Fase 0 前置重构)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `EvalErrorCode` 从「String 常量类」改造成真 enum,统一所有评估错误码(含 SDK 散落字面量),`errorCode` 字段在 kernel 模型层用 enum、仅在 DB 持久层 `.name()` 转 String。

**Architecture:** 纯重构、零行为变更——序列化/落库的字符串值保持不变(enum name == 旧字面量)。`EvalErrorCode` 作单一真相源;`EvalResult`/`NodeTrace`/`ConditionOutcome` 的 `errorCode` 字段类型 `String → EvalErrorCode`;持久层实体(session/node_trace)仍为 String,在写库前 `.name()`(null 安全)。SDK 两个注解执行器的 6 个字符串字面量收进枚举。

**Tech Stack:** Java 25 (record/enum/sealed)、Maven 多模块、MyBatis-Plus、JUnit5 + AssertJ、`$MVN`(由 `mvn-env` skill 设置)。

> **⚠ 执行期修正(2026-06-12):** 实现中发现 errorCode **不是封闭集**——`MetricValue.errorCode`(String)由 metric provider(`MetricSourceHandler` SPI)填,可带 `METRIC_SOURCE_EVAL_ERROR` 等开放码,经 `ConditionEvaluation` 穿到 `ConditionOutcome`/`EvalResult.errorCode`。因此**不把字段改成 enum**(封闭化会导致 `valueOf` 崩或吞码):
> - `EvalErrorCode` 改 enum 作**单一真相源**(纳入 `METRIC_SOURCE_EVAL_ERROR` + SCRIPT_* + SDK 6 码);
> - `EvalResult/NodeTrace/ConditionOutcome.errorCode` 字段**保持 String**;各错误工厂**加 `EvalErrorCode` 重载**(内部 `.name()`),保留 `String` 工厂给 provider 开放码穿透;
> - **Task 2/3/4 改为"加 enum 重载、不改字段类型";Task 6(持久层 .name())作废**(字段仍 String,无需收口);Task 5 产出点绑定 enum 重载;Task 8 grep 照旧。
> - **执行单元划分**:Unit A = kernel(Task 1-5);Unit C = SDK 字面量收编(Task 7),含 `AnnotatedDecideExecutor`(4)/ `AnnotatedScoreExecutor`(3)/ **`AnnotatedMetricScanner`(`METRIC_SOURCE_EVAL_ERROR`,1,code-review 补发现)** → 全改 `EvalErrorCode.X` enum 重载;Unit D = Task 8 全量兜底。
> 以本 banner 为准,下方 Task 2-6 原文(字段改 enum)已被覆盖。

**前置依赖:** 这是 EXPRESSION_SCRIPT 功能(`docs/superpowers/specs/2026-06-12-expression-script-rule-design.md` §7 Fase 0)的前置重构;本计划独立可交付(全测试绿、无行为变更)。脚本功能本身在后续 plan(`2026-06-12-expression-script-rule.md`,待写)。

**这是更大 spec 的第 1 个 plan。完整 plan 序列:**
1. **(本 plan)** EvalErrorCode 枚举统一 — 前置重构,独立交付
2. kernel:`ExpressionEngine`/`CompiledExpression` SPI + `ScriptSource` + `ScriptExecutor` + `RuleVersionSnapshot.script` + `NodeType.SCRIPT` + SCRIPT_* 错误码(用 fake engine 测派发)
3. `rule-kernel-expression-cel` 新模块:`CelExpressionEngine`(编译/类型检查/变量抽取/求值 + Caffeine 预编译缓存)
4. eval-svc 装配 + config-svc 发布校验 + `rule_version.script_source` 列 + 端到端
5. SDK opt-in 执行 + API 契约

---

## 关键事实(实现者必读)

- `mvn-env` skill 先跑,用 `$MVN`。单模块测试 `$MVN -pl <module> -am test`(跨模块必带 `-am`);一轮结束用 `$MVN clean test` 全量兜底(只有 clean 强制重编所有 test 类)。
- 不得用 `-DskipTests` / `--no-verify` 绕过失败。
- **本重构零行为变更**:每个 enum 值的 `name()` 必须等于它替换掉的旧字符串。安全网是既有测试全绿,不是新写测试。
- 现有 `EvalErrorCode` 是 `final class` + `public static final String`(7 个值):`rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalErrorCode.java`。
- SDK 散落 6 个字面量(`rule-sdk/.../source/AnnotatedDecideExecutor.java` 与 `AnnotatedScoreExecutor.java`):`ANNO_DECIDE_UNREGISTERED` / `DECIDE_EVAL_ERROR` / `INVALID_DECISION_CODE` / `ANNO_DECIDE_NO_HIT` / `ANNO_SCORE_UNREGISTERED` / `SCORE_EVAL_ERROR`。
- 持久层 `errorCode` 为 `String` 的实体:`EvaluationSession`(`rule-eval-svc/.../domain/EvaluationSession.java:35`)、`DryRunSession`(同包:34)、`NodeTraceEntity`(`rule-observability/.../domain/NodeTraceEntity.java:32`)、`DryRunNodeTraceEntity`(:33)。写入点:`AuditPersister:127`、`DryRunPersister:53`、`TraceWriterDbImpl:120`、`DryRunTraceWriterDbImpl:120`。

---

## Task 1: `EvalErrorCode` 改 enum,纳入全部错误码

**Files:**
- Modify (整体重写): `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalErrorCode.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/model/EvalErrorCodeTest.java`

- [ ] **Step 1: 写失败测试——枚举值齐全且 name 不变**

```java
package com.sstlfsj.rule.kernel.model;

import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvalErrorCodeTest {

    @Test
    void allCodesPresentWithStableNames() {
        // 旧 kernel 常量(name 必须逐字不变,保证落库/序列化字符串零漂移)
        assertThat(EvalErrorCode.METRIC_FETCH_FAIL.name()).isEqualTo("METRIC_FETCH_FAIL");
        assertThat(EvalErrorCode.NO_EVALUATOR.name()).isEqualTo("NO_EVALUATOR");
        assertThat(EvalErrorCode.CONDITION_EVAL_ERROR.name()).isEqualTo("CONDITION_EVAL_ERROR");
        assertThat(EvalErrorCode.SCORECARD_AST_TYPE_MISMATCH.name()).isEqualTo("SCORECARD_AST_TYPE_MISMATCH");
        assertThat(EvalErrorCode.DECISION_TREE_AST_TYPE_MISMATCH.name()).isEqualTo("DECISION_TREE_AST_TYPE_MISMATCH");
        assertThat(EvalErrorCode.DECISION_TREE_UNEXPECTED_NODE.name()).isEqualTo("DECISION_TREE_UNEXPECTED_NODE");
        assertThat(EvalErrorCode.DECISION_TABLE_AST_TYPE_MISMATCH.name()).isEqualTo("DECISION_TABLE_AST_TYPE_MISMATCH");
        // 从 SDK 字面量收编而来
        assertThat(EvalErrorCode.ANNO_DECIDE_UNREGISTERED.name()).isEqualTo("ANNO_DECIDE_UNREGISTERED");
        assertThat(EvalErrorCode.ANNO_DECIDE_NO_HIT.name()).isEqualTo("ANNO_DECIDE_NO_HIT");
        assertThat(EvalErrorCode.ANNO_SCORE_UNREGISTERED.name()).isEqualTo("ANNO_SCORE_UNREGISTERED");
        assertThat(EvalErrorCode.DECIDE_EVAL_ERROR.name()).isEqualTo("DECIDE_EVAL_ERROR");
        assertThat(EvalErrorCode.SCORE_EVAL_ERROR.name()).isEqualTo("SCORE_EVAL_ERROR");
        assertThat(EvalErrorCode.INVALID_DECISION_CODE.name()).isEqualTo("INVALID_DECISION_CODE");
        // 预置脚本功能码(本 plan 仅声明,Task/plan 后续使用)
        assertThat(EvalErrorCode.SCRIPT_SOURCE_MISSING.name()).isEqualTo("SCRIPT_SOURCE_MISSING");
        assertThat(EvalErrorCode.SCRIPT_NO_ENGINE.name()).isEqualTo("SCRIPT_NO_ENGINE");
        assertThat(EvalErrorCode.SCRIPT_EVAL_ERROR.name()).isEqualTo("SCRIPT_EVAL_ERROR");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-kernel -am test -Dtest=EvalErrorCodeTest`
Expected: 编译失败(`EvalErrorCode` 还是 class,无 `.name()` 之类;且新值未声明)。

- [ ] **Step 3: 把 `EvalErrorCode` 重写为 enum**

```java
package com.sstlfsj.rule.kernel.api.model;

/**
 * 评估错误码的单一来源（落库 error_code 列 + API 响应契约）。
 * 各执行器/取数链路统一引用此枚举，避免散落的字符串字面量漂移。
 * 契约边界（DB 实体 / API DTO）以 {@link #name()} 转 String，字符串值与历史一致。
 */
public enum EvalErrorCode {
    /** 指标取数失败。 */
    METRIC_FETCH_FAIL,
    /** 未注册对应 conditionType 的算子。 */
    NO_EVALUATOR,
    /** 条件求值抛异常的兜底错误码。 */
    CONDITION_EVAL_ERROR,
    /** 评分卡 AST 类型不匹配。 */
    SCORECARD_AST_TYPE_MISMATCH,
    /** 决策树 AST 类型不匹配。 */
    DECISION_TREE_AST_TYPE_MISMATCH,
    /** 决策树遍历到非预期节点类型。 */
    DECISION_TREE_UNEXPECTED_NODE,
    /** 决策表 AST 类型不匹配。 */
    DECISION_TABLE_AST_TYPE_MISMATCH,
    /** @Decide 合成执行器:快照坐标未注册到调用表。 */
    ANNO_DECIDE_UNREGISTERED,
    /** @Decide 合成执行器:返回的码全部非法、无有效命中。 */
    ANNO_DECIDE_NO_HIT,
    /** @Score 合成执行器:快照坐标未注册到调用表。 */
    ANNO_SCORE_UNREGISTERED,
    /** @Decide 方法体抛异常。 */
    DECIDE_EVAL_ERROR,
    /** @Score 方法体抛异常。 */
    SCORE_EVAL_ERROR,
    /** 返回/命中的决策码不在 decisionBindings 中。 */
    INVALID_DECISION_CODE,
    /** EXPRESSION_SCRIPT:kind 为脚本但 snapshot.script() 为 null。 */
    SCRIPT_SOURCE_MISSING,
    /** EXPRESSION_SCRIPT:script.lang 无对应已注册 ExpressionEngine。 */
    SCRIPT_NO_ENGINE,
    /** EXPRESSION_SCRIPT:运行期求值抛错。 */
    SCRIPT_EVAL_ERROR
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `$MVN -pl rule-kernel -am test -Dtest=EvalErrorCodeTest`
Expected: PASS。(注:本模块其它类此刻可能编译失败——下个 Task 修;若 `-Dtest` 仍因同模块编译失败而跑不起来,先做 Task 2-5 再回头跑全量。)

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalErrorCode.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/model/EvalErrorCodeTest.java
git commit -m "refactor(kernel): EvalErrorCode 改 enum,纳入 SDK 散落错误码 + 预置 SCRIPT_* 码"
```

---

## Task 2: `EvalResult.errorCode` 字段 String→EvalErrorCode

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalResult.java`

> 说明:`EvalResult` 已被各执行器以 `EvalResult.error(EvalErrorCode.X)` 调用(旧 `X` 是 String 常量,现是 enum 值)——把字段与工厂入参从 String 改 enum 后,这些调用点**无需改动**即可编译。

- [ ] **Step 1: 改字段类型 + 工厂入参**

把第 11 行 `String errorCode,` 改为:

```java
        EvalErrorCode errorCode,
```

把两个 `error` 工厂签名从 `String errorCode` 改为 `EvalErrorCode errorCode`:

```java
    /**
     * 不命中 + 错误码，无 trace（AST 类型不符等早退场景）。
     *
     * @param errorCode 错误码
     * @return 错误结果
     */
    public static EvalResult error(EvalErrorCode errorCode) {
        return new EvalResult(false, null, List.of(), List.of(), errorCode, null, null, null);
    }

    /**
     * 不命中 + 错误码，携带已收集 trace（条件求值出错中止）。
     *
     * @param errorCode 错误码
     * @param nodeTrace 已收集的 NodeTrace 列表
     * @return 错误结果
     */
    public static EvalResult error(EvalErrorCode errorCode, List<NodeTrace> nodeTrace) {
        return new EvalResult(false, null, List.of(), nodeTrace, errorCode, null, null, null);
    }
```

(`EvalErrorCode` 与 `EvalResult` 同包 `com.sstlfsj.rule.kernel.api.model`,无需 import。)

- [ ] **Step 2: 不单独跑测试**(此改动会引发跨文件编译级联,Task 3-7 一并完成后统一验证)。继续 Task 3。

---

## Task 3: `NodeTrace.errorCode` 字段 String→EvalErrorCode

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeTrace.java`

- [ ] **Step 1: 改 record 字段类型**

把第 18 行 `String errorCode,` 改为:

```java
        EvalErrorCode errorCode,
```

- [ ] **Step 2: 改带 errorCode 的 container 工厂入参**

把第 45-48 行的工厂签名 `String errorCode` 改为 `EvalErrorCode errorCode`:

```java
    /** 容器节点 + 错误码（取数失败中止时，容器仍记录 errorCode 与已收集子节点）。 */
    public static NodeTrace container(NodeType type, Boolean result, EvalErrorCode errorCode,
                                      List<NodeTrace> children, Long ruleVersionId) {
        return new NodeTrace(type.tag(), null, null, result, null, null, errorCode, children, ruleVersionId, null, 0L, null, null);
    }
```

> 其它两个 `container` 工厂(无 errorCode 参,传 `null`)不变——`null` 对 `EvalErrorCode` 字段合法。
> 直接 `new NodeTrace(...)` 全参构造的调用点(执行器内)传的是 `outcome.errorCode()` 或 `node...errorCode`,其类型在 Task 4/5 一并变成 `EvalErrorCode`,故对齐后即编译通过。

- [ ] **Step 3: 继续 Task 4**(暂不跑测试)。

---

## Task 4: `ConditionOutcome.errorCode` 字段 String→EvalErrorCode

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ConditionOutcome.java`

- [ ] **Step 1: 改 record 字段 + 两个 error 工厂入参**

`ConditionOutcome` 与 `EvalErrorCode` 不同包,需 import。在文件顶部 `package` 行后加:

```java
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
```

把 record 头 `String errorCode` 改为 `EvalErrorCode errorCode`:

```java
record ConditionOutcome(Status status, EvalErrorCode errorCode, Object resolvedValue, String valueSource) {
```

把两个 `error` 工厂的 `String errorCode` 改为 `EvalErrorCode errorCode`:

```java
    /** 不可判定（无叶子值，如容器层 NO_EVALUATOR）。 */
    static ConditionOutcome error(EvalErrorCode errorCode) {
        return new ConditionOutcome(Status.ERROR, errorCode, null, null);
    }

    /** 叶子取数失败，携带来源。 */
    static ConditionOutcome error(EvalErrorCode errorCode, Object resolvedValue, String valueSource) {
        return new ConditionOutcome(Status.ERROR, errorCode, resolvedValue, valueSource);
    }
```

- [ ] **Step 2: 继续 Task 5**(暂不跑测试)。

---

## Task 5: 修 kernel 内 errorCode 消费/产出点(编译级联收口)

**Files:**
- Inspect/Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ConditionEvaluation.java`
- Inspect/Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/InterpretedExecutor.java`
- Inspect/Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTreeExecutor.java`
- Inspect/Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/DecisionTableExecutor.java`
- Inspect/Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/ScorecardExecutor.java`

> 这些文件已用 `EvalErrorCode.X`(现在是 enum)+ `outcome.errorCode()`/`cond.errorCode()`(现在是 enum)+ `NodeTrace` 全参构造的 errorCode 位。Task 2-4 之后类型已自洽,**绝大多数无需改动**。本 Task 是「编译驱动」收口:编译,只修编译器报错处。

- [ ] **Step 1: 编译 kernel,列出报错点**

Run: `$MVN -pl rule-kernel -am test-compile`
Expected: 若有报错,集中在「把 `EvalErrorCode` 赋给残留的 `String` 局部变量」或反向。逐个按下述规则修:
- 残留 `String errorCode` 局部/字段 → 改 `EvalErrorCode errorCode`。
- 任何把 enum 拼进字符串或与字符串字面量比较的(如 `"NO_EVALUATOR".equals(x)`)→ 改成 `EvalErrorCode.NO_EVALUATOR == x`。
- `EvalResult.error("XXX")` 形式的字符串字面量(若有)→ `EvalResult.error(EvalErrorCode.XXX)`。

- [ ] **Step 2: 修 kernel 测试里的字符串断言**

Run: `$MVN -pl rule-kernel -am test-compile` 后再 `grep -rn '"NO_EVALUATOR"\|"METRIC_FETCH_FAIL"\|"CONDITION_EVAL_ERROR"\|"SCORECARD_AST_TYPE_MISMATCH"\|"DECISION_TREE_AST_TYPE_MISMATCH"\|"DECISION_TREE_UNEXPECTED_NODE"\|"DECISION_TABLE_AST_TYPE_MISMATCH"' rule-kernel/src/test`

对每个命中:测试若断言 `result.errorCode()`(现在是 `EvalErrorCode`)等于字符串,改为枚举比较。例:

```java
// 旧
assertThat(result.errorCode()).isEqualTo("DECISION_TREE_AST_TYPE_MISMATCH");
// 新
assertThat(result.errorCode()).isEqualTo(EvalErrorCode.DECISION_TREE_AST_TYPE_MISMATCH);
```

(测试类按需 `import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;`)

- [ ] **Step 3: 跑 kernel 全量测试确认绿**

Run: `$MVN -pl rule-kernel -am test`
Expected: BUILD SUCCESS,所有既有执行器/trace 测试通过(零行为变更)。

- [ ] **Step 4: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalResult.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeTrace.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/
git add rule-kernel/src/test
git commit -m "refactor(kernel): errorCode 字段 String→EvalErrorCode(EvalResult/NodeTrace/ConditionOutcome + 执行器收口)"
```

---

## Task 6: 持久层边界 `.name()` 收口(eval-svc + observability)

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditPersister.java:127`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DryRunPersister.java:50,53`
- Modify: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImpl.java:120`
- Modify: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImpl.java:120`

> DB 实体(`EvaluationSession`/`DryRunSession`/`NodeTraceEntity`/`DryRunNodeTraceEntity`)的 `errorCode` 字段**保持 String**(契约边界)。这些写入点从 `r.errorCode()`/`trace.errorCode()`(现在是 `EvalErrorCode`)取值,改为 null 安全 `.name()`。

- [ ] **Step 1: 编译 eval-svc + observability,定位报错**

Run: `$MVN -pl rule-eval-svc -am test-compile` 然后 `$MVN -pl rule-observability -am test-compile`
Expected: 报错在 `setErrorCode(EvalErrorCode)` 实参类型不符(实体 setter 收 String)。

- [ ] **Step 2: AuditPersister(`EvaluationSession`)**

把 `AuditPersister.java:122,127` 一带的:

```java
        s.setStatus(r.errorCode() != null ? SessionStatus.ERROR : ...);   // 状态判断不变
        s.setErrorCode(r.errorCode());
```

改为(状态判断用 `!= null` 不变;落库取 name):

```java
        s.setErrorCode(r.errorCode() == null ? null : r.errorCode().name());
```

- [ ] **Step 3: DryRunPersister(`DryRunSession`)**

把 `DryRunPersister.java:53`:

```java
        s.setErrorCode(r.errorCode());
```

改为:

```java
        s.setErrorCode(r.errorCode() == null ? null : r.errorCode().name());
```

(第 50 行 `r.errorCode() != null` 的状态判断不变。)

- [ ] **Step 4: TraceWriterDbImpl / DryRunTraceWriterDbImpl(NodeTrace 实体)**

两个文件第 120 行均为:

```java
            entity.setErrorCode(trace.errorCode());
```

各改为:

```java
            entity.setErrorCode(trace.errorCode() == null ? null : trace.errorCode().name());
```

- [ ] **Step 5: 跑两模块测试确认绿**

Run: `$MVN -pl rule-eval-svc -am test` 然后 `$MVN -pl rule-observability -am test`
Expected: BUILD SUCCESS;落库的 `error_code` 字符串值与重构前一致。

- [ ] **Step 6: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/AuditPersister.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/async/DryRunPersister.java \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImpl.java \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImpl.java
git commit -m "refactor(persistence): errorCode 落库边界 .name() 收口(session/node_trace 实体保持 String)"
```

---

## Task 7: SDK 注解执行器字面量收编进 EvalErrorCode

**Files:**
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideExecutor.java`
- Modify: `rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedScoreExecutor.java`
- Test: `rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedExecutorErrorCodeTest.java`

- [ ] **Step 1: 写失败测试——非法决策码走枚举错误码**

```java
package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotatedExecutorErrorCodeTest {

    static String decideReturningBadCode() { return "NOT_BOUND"; }

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene", "EVT", "u1", "e1",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void decideBadCodeYieldsInvalidDecisionCodeEnum() throws Exception {
        var method = AnnotatedExecutorErrorCodeTest.class.getDeclaredMethod("decideReturningBadCode");
        var inv = new AnnotatedDecideExecutor.Invocation(null, method, (params, c, x) -> new Object[0]);
        var executor = new AnnotatedDecideExecutor(Map.of("k", inv));
        // 快照 conditionAst 用 ConditionNode 携带坐标键 "k";decisionBindings 不含 NOT_BOUND
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "scene", "t1",
                new ConditionNode("k", null, null, Map.of(), 0.0),
                List.of(), List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 10)), List.of(), "__anno_decide");

        EvalResult r = executor.execute(snap, ctx());

        assertThat(r.errorCode()).isEqualTo(EvalErrorCode.INVALID_DECISION_CODE);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedExecutorErrorCodeTest`
Expected: FAIL/编译错误——`r.errorCode()` 现在是 `EvalErrorCode`,而执行器仍存字符串字面量(类型不符或值不等)。

- [ ] **Step 3: `AnnotatedDecideExecutor` 字面量 → 枚举**

在 import 区加 `import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;`。`errorCode` 局部变量类型 `String → EvalErrorCode`,并替换 4 处字面量:

- 第 39 行:`return EvalResult.error("ANNO_DECIDE_UNREGISTERED");` → `return EvalResult.error(EvalErrorCode.ANNO_DECIDE_UNREGISTERED);`
- 第 47 行:`return EvalResult.error("DECIDE_EVAL_ERROR");` → `return EvalResult.error(EvalErrorCode.DECIDE_EVAL_ERROR);`
- 第 52 行:`String errorCode = null;` → `EvalErrorCode errorCode = null;`
- 第 55 行:`errorCode = "INVALID_DECISION_CODE";` → `errorCode = EvalErrorCode.INVALID_DECISION_CODE;`
- 第 60 行:`return EvalResult.error(errorCode == null ? "ANNO_DECIDE_NO_HIT" : errorCode);` → `return EvalResult.error(errorCode == null ? EvalErrorCode.ANNO_DECIDE_NO_HIT : errorCode);`

- [ ] **Step 4: `AnnotatedScoreExecutor` 字面量 → 枚举**

在 import 区加 `import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;`。替换 2 处:

- 第 36 行:`return EvalResult.error("ANNO_SCORE_UNREGISTERED");` → `return EvalResult.error(EvalErrorCode.ANNO_SCORE_UNREGISTERED);`
- 第 45 行:`return EvalResult.error("SCORE_EVAL_ERROR");` → `return EvalResult.error(EvalErrorCode.SCORE_EVAL_ERROR);`
- 第 57 行的全参构造:`new EvalResult(false, null, List.of(), List.of(), "INVALID_DECISION_CODE", score, null, null)` → 把第 5 实参 `"INVALID_DECISION_CODE"` 改为 `EvalErrorCode.INVALID_DECISION_CODE`。

- [ ] **Step 5: 跑测试确认通过**

Run: `$MVN -pl rule-sdk -am test -Dtest=AnnotatedExecutorErrorCodeTest`
Expected: PASS。

- [ ] **Step 6: 修 SDK 既有测试里对这些错误码的字符串断言**

Run: `grep -rn '"INVALID_DECISION_CODE"\|"DECIDE_EVAL_ERROR"\|"SCORE_EVAL_ERROR"\|"ANNO_DECIDE_UNREGISTERED"\|"ANNO_DECIDE_NO_HIT"\|"ANNO_SCORE_UNREGISTERED"' rule-sdk/src/test`
对每个命中:`assertThat(...errorCode()).isEqualTo("INVALID_DECISION_CODE")` → `...isEqualTo(EvalErrorCode.INVALID_DECISION_CODE)`(按需 import)。

- [ ] **Step 7: 跑 SDK 全量测试确认绿**

Run: `$MVN -pl rule-sdk -am test`
Expected: BUILD SUCCESS。

- [ ] **Step 8: 提交**

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedDecideExecutor.java \
        rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotatedScoreExecutor.java \
        rule-sdk/src/test/java/com/sstlfsj/rule/sdk/source/AnnotatedExecutorErrorCodeTest.java \
        rule-sdk/src/test
git commit -m "refactor(sdk): 注解执行器错误码字面量收编进 EvalErrorCode 枚举"
```

---

## Task 8: 全量兜底 + 残留字面量扫描

**Files:** 无(验证 Task)

- [ ] **Step 1: 全仓扫描残留错误码字符串字面量**

Run:
```bash
grep -rn '"METRIC_FETCH_FAIL"\|"NO_EVALUATOR"\|"CONDITION_EVAL_ERROR"\|"SCORECARD_AST_TYPE_MISMATCH"\|"DECISION_TREE_AST_TYPE_MISMATCH"\|"DECISION_TREE_UNEXPECTED_NODE"\|"DECISION_TABLE_AST_TYPE_MISMATCH"\|"ANNO_DECIDE_UNREGISTERED"\|"ANNO_DECIDE_NO_HIT"\|"ANNO_SCORE_UNREGISTERED"\|"DECIDE_EVAL_ERROR"\|"SCORE_EVAL_ERROR"\|"INVALID_DECISION_CODE"' --include=*.java rule-kernel rule-sdk rule-eval-svc rule-config-svc rule-observability rule-app
```
Expected: 仅 `EvalErrorCodeTest`(断言 name 字符串)命中;其余 src/main 与 src/test 应无裸字面量(持久层 `.name()` 不算字面量)。若有遗漏点,按 Task 5/7 规则改成枚举。

- [ ] **Step 2: 全量 clean test 兜底**

Run: `$MVN clean test`
Expected: BUILD SUCCESS(全模块)。只有 `clean` 才强制重编所有 test 类,捕捉增量编译漏掉的过期 test。

- [ ] **Step 3: 最终提交(若 Step 1 有遗漏修复)**

```bash
git add -A
git commit -m "refactor: EvalErrorCode 枚举统一收尾,全仓无裸错误码字面量"
```

---

## Self-Review(已执行)

- **Spec 覆盖**:对应 spec §7「前置重构(Fase 0)」全部要点——EvalErrorCode 改 enum(Task 1)、字段 String→EvalErrorCode(Task 2-4)、契约边界 `.name()`(Task 6)、SDK 字面量收编(Task 7)、SCRIPT_* 预置(Task 1)。✅
- **占位扫描**:无 TBD/TODO;每步含确切文件、行号、代码、命令、预期。✅
- **类型一致**:`errorCode` 字段 = `EvalErrorCode`(kernel 模型层),DB 实体仍 `String`、写库前 `.name()`;`EvalResult.error(EvalErrorCode)` / `NodeTrace.container(..., EvalErrorCode, ...)` / `ConditionOutcome.error(EvalErrorCode)` 三处签名一致。✅
- **一个已知顺序约束**:Task 2-4 单独不可编译(跨文件级联),故 Task 2/3 不跑测试,在 Task 5 统一编译收口——已在步骤内显式说明。
