# 注解表达非 boolean 规则与多决策 — 设计

> 状态:设计待评审 · 日期:2026-06-12 · 关联决策:D64(草案,见文末) · 前置:D61(Easy Rules 风格注解规则)
> 范围拆分:本 spec 收**触碰引擎 executor/kind 选择**的扩展(E 非 boolean 规则、F 多决策);易用性与防呆见姊妹 spec `2026-06-12-annotation-ergonomics-safety-design.md`(A/B/C/D/G/H)。

## 1. 背景与动机

D61 的注解规则只能表达 **boolean** 判定:`@Condition` 返回 `boolean`,合成为不透明 `ConditionEvaluator`,快照 `kind=AST_BOOLEAN`,命中后由 `EvalEngine.resolveRuleDecisions` 按最高优先级 `@DecisionBinding` **取单个**决策。

且嵌入式 SDK 现状更受限:`RuleEngineClient` 只注册了 `Map.of(RuleKind.AST_BOOLEAN.tag(), executor)`(RuleEngineClient.java:81),scorecard/tree/table 执行器**根本没进 SDK**。因此当前注解风格无法表达:

- **E 非 boolean 判定**:规则需"算出一个分"(SCORECARD)或"在 Java 里直接算出命中哪个决策码"(类 DECISION_TREE/TABLE 的分支逻辑)。
- **F 多决策**:一条规则命中后**同时发多个决策**(如 `REVIEW`+`NOTIFY`),或按子条件分流到不同决策。

引擎侧 `resolveRuleDecisions` 已有"executor 自选决策(`r.hitDecisions()` 非空)就用它"的口子(EvalEngine.java:228),这给了**不改 kernel 执行器、只在 SDK 加合成 executor** 的实现路径。

## 2. 范围

| 项 | 标题 |
|---|---|
| **E** | 注解表达非 boolean 规则:`@Decide`(返回决策码)+ `@Score`(返回分,带 `@ScoreBand` 分档) |
| **F** | 多决策:`@Decide` 支持返回 `List<String>` / `String[]` 一次发多个决策 |

一条规则的"判定原语"**恰好三选一**:`@Condition`(D61,boolean)/ `@Decide`(本 spec)/ `@Score`(本 spec)。

### 非目标

- **不改 kernel 既有执行器**(`InterpretedExecutor`/`ScorecardExecutor`/`DecisionTree/TableExecutor`)与 `resolveRuleDecisions`。新能力靠 **SDK 侧合成 executor** 实现,复用引擎已有的"executor 自选决策"口子。
- **不把合成 kind 塞进 `RuleKind` 枚举**:`RuleVersionSnapshot.kind` 是 String,合成规则用 SDK 本地 String tag(`__anno_decide` / `__anno_score`),只注册进 SDK 的 executors map,不污染 kernel 枚举与持久化契约。
- **boolean 规则发全部 binding**(而非最高优先级单个)不在本 spec 默认实现:它需改 kernel `resolveRuleDecisions`,作为可选子项登记(见 §3.F)。F 的默认实现走 `@Decide` 多返回,纯 SDK。

## 3. 设计

### E.1 `@Decide` —— Java 直接产出决策码

```java
/** 标在规则 POJO 的方法上,返回命中的 decision code(null/空=不命中);返回值须是 @RuleDef.decisions 声明的码之一。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Decide {}
```

- 方法签名:`String decide(@Fact.../@Metric... )` 返回单个决策码;或(F)`List<String>`/`String[]` 返回多个。参数注入复用 `FactResolver`,与 `@Condition` 完全一致。
- 用户在方法体里写任意分支逻辑(if/switch/查表),直接 `return "REVIEW"` —— 这把 DECISION_TREE/TABLE 的"算出决策"语义收进一个 Java 方法,无需 AST。

**合成执行器 `AnnotatedDecideExecutor`(新,rule-sdk)** implements `RuleVersionExecutor`:
- 持有注册表 `Map<String, Invocation>`(key=合成 conditionType/规则坐标,value=bean+method+factResolver),与 `@Condition` 的 evaluators map 同款机制。
- `execute(snapshot, ctx)`:按快照坐标取 `Invocation` → `factResolver.resolve` 注入 → 反射调用 → 收集返回的码集合:
  - 空集 → `EvalResult.miss()`(带 trace 视 collect)。
  - 非空 → 每个码查 `snapshot.decisionBindings()` 拿 name/priority/category,构造 `Decision(code, name, priority, ruleVersionId, snap.code(), snap.version(), category)`,塞进 `hitDecisions`;`ruleHit=true`,`finalDecision`=其中最高优先级。
  - 返回的码**不在** bindings 中 → errorCode `INVALID_DECISION_CODE`,该码丢弃(或整条按算子异常降级——**spec 决定:丢弃该码 + 记 errorCode,其余有效码照发**)。
- 方法抛错 → 同 `@Condition` 语义:降级不命中 + errorCode `DECIDE_EVAL_ERROR`。

快照:`kind = "__anno_decide"`,`conditionAst = null`(该 executor 不读 AST),`decisionBindings` 仍由 `@RuleDef.decisions` 提供(用于校验返回码 + 补 name/priority)。

### E.2 `@Score` + `@ScoreBand` —— 评分规则

```java
/** 标在规则方法上,返回 double 评分;经方法上的 @ScoreBand 分档映射到决策,并写入 EvalResult.score。 */
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
public @interface Score {}

/** 评分分档:score >= min 时归入 decision;同一方法多个,取满足条件中 min 最大的一档。 */
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
@Repeatable(ScoreBands.class)
public @interface ScoreBand {
    double min();
    String decision();
}
@Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @Documented
public @interface ScoreBands { ScoreBand[] value(); }
```

- 方法签名:`double score(@Fact.../@Metric...)`。
- **合成执行器 `AnnotatedScoreExecutor`(新,rule-sdk)**:`execute` 调方法得 `score`,在 `@ScoreBand` 中选 `min ≤ score` 且 `min` 最大的一档 → 该 `decision`;无档命中 → miss。命中时构造 `Decision`(从 bindings 补 name/priority/category),`EvalResult.score = score`,`category` 取 binding 的。
- 快照:`kind = "__anno_score"`,`conditionAst = null`,`@ScoreBand.decision` 须 ⊆ `@DecisionBinding` 码(扫描期校验)。

### F. 多决策

- **默认实现(纯 SDK)**:`@Decide` 返回 `List<String>`/`String[]`,`AnnotatedDecideExecutor` 把每个有效码各产一个 `Decision` 进 `hitDecisions` → 引擎 `resolveRuleDecisions` 原样透传(`r.hitDecisions()` 非空分支),`finalDecision` 取最高优先级,其余进 `hitDecisions`。动作侧每个决策各派一次 `DecisionFiredEvent`(已有行为)。
- **可选子项(需 kernel 改动,登记不默认做)**:让 `@Condition` boolean 规则一次发**全部** `@DecisionBinding`(而非最高优先级单个)。这要改 `EvalEngine.resolveRuleDecisions` 的 fallback 分支(boolean 命中时返回全部 bindings 的 Decision),或在 `@RuleDef` 加 `emitAllBindings` 开关并下传快照。**因触碰 kernel,留作 D64 的下游、本 spec 不实现**;有需求者用 `@Decide` 返回多码替代。

### 集成点(SDK 装配)

1. **`RuleEngineClient` 构造(RuleEngineClient.java:79-82)**:executors map 从单 `AST_BOOLEAN` 扩为多 kind:
   ```
   Map.of(
     RuleKind.AST_BOOLEAN.tag(), interpretedExecutor,
     "__anno_decide", annotatedDecideExecutor,
     "__anno_score",  annotatedScoreExecutor)
   ```
   两个合成 executor 的注册表由 builder 收集(同 `extraEvaluators` 模式,新增 `decideInvocations`/`scoreInvocations`)。
2. **`AnnotatedRuleScanner.scan`** 升级:一个 `@RuleDef` POJO 按其判定原语分流——
   - 有 `@Condition` → 现有逻辑(kind=AST_BOOLEAN + evaluator)。
   - 有 `@Decide` → kind=`__anno_decide`,登记进 decide 注册表。
   - 有 `@Score` → kind=`__anno_score`,登记进 score 注册表,带 `@ScoreBand` 表。
   - **校验**:三者**恰好一个**,否则 `IllegalStateException`(0 个=缺判定原语,>1=冲突);`@Decide`/`@ScoreBand` 引用的决策码 ⊆ `@DecisionBinding`。
   - `ScanResult` 扩展为携带三类注册产物(evaluators + decideInvocations + scoreInvocations + snapshots)。
3. **`RuleEngineClientAutoConfiguration`**:收集 `@RuleDef` bean 时,识别 `@Condition`/`@Decide`/`@Score` 任一方法即纳入注解规则集;把 scanner 产出的三类注册表分别灌进 builder。

### 与 Spec 1 的接口契约

- `FactResolver`(Spec 1 增强:参数名回退/required/default/嵌套/coerce)对 `@Decide`/`@Score` 参数**同样适用**——它们的参数注入与 `@Condition` 共用 `FactResolver.resolve`。两 spec 在 `FactResolver` 上无冲突:Spec 1 改注入语义,Spec 2 只新增调用方。
- 实施顺序建议:**Spec 1 先行**(FactResolver 增强 + 扫描期校验框架就位),Spec 2 在其上加判定原语;但二者可独立编译、独立验证,无硬依赖(Spec 2 不依赖 Spec 1 的新属性即可工作,只是共享 `FactResolver`)。

## 4. 影响面 / 文件清单

**rule-sdk**
- `annotation/Decide.java`、`annotation/Score.java`、`annotation/ScoreBand.java`(+`ScoreBands` 容器)(新)
- `source/AnnotatedDecideExecutor.java`、`source/AnnotatedScoreExecutor.java`(新,implements `RuleVersionExecutor`)
- `source/AnnotatedRuleScanner.java` — 三原语分流 + 校验 + `ScanResult` 扩展
- `RuleEngineClient.java` — builder 收集 decide/score 注册表;构造期 executors map 扩多 kind
- 合成 kind 常量集中处(如 `AnnotatedRuleScanner` 内 `static final String KIND_DECIDE/KIND_SCORE`)

**rule-sdk-spring-boot-starter**
- `RuleEngineClientAutoConfiguration.java` — bean 识别纳入 `@Decide`/`@Score`;灌注册表

**rule-samples**(演示)
- `annotation/` 下加一个 `@Decide` 样例(如多分支风控分流)与一个 `@Score`+`@ScoreBand` 样例(信用分→PASS/REVIEW/REJECT),各配端到端 IT。

## 5. 风险与验证

- **风险**:合成 executor 走引擎"executor 自选决策"口子,须确保 `conditionAst=null` 不被其它链路(trace/snapshot 持久化)误用——SDK 本地评估不持久化快照,风险局限;`EvalEngine.selectExecutor` 按 kind 命中合成 executor(命中则不回退 AST_BOOLEAN),需测 kind 路由正确。
- **验证**(TDD,英文方法名):
  - `@Decide` 单码命中/不命中/无效码丢弃+errorCode/方法抛错降级。
  - `@Decide` 多码 → `hitDecisions` 全含,`finalDecision` 最高优先级。
  - `@Score` 分档:边界值(score==min)、多档取最大 min、无档 miss、`EvalResult.score` 写入。
  - scanner 校验:0/多判定原语抛错;`@Decide`/`@ScoreBand` 引用未声明决策码抛错。
  - 端到端(starter `ApplicationContextRunner`):`@Decide`/`@Score` 规则装配后 `evaluate` 产出正确决策 + 动作派发。
  - 收尾全量 `$MVN clean test`(跨模块 `-am`)。

## 6. 决策日志条目草案(D64)

> 待评审通过后追加到 `docs/00-decisions.md`。

**D64 注解表达非 boolean 规则与多决策 | A**

在 D61 boolean 注解规则之外,补两类判定原语,**不改 kernel 执行器**、靠 SDK 合成 `RuleVersionExecutor` 复用引擎"executor 自选决策"口子(EvalEngine.java:228):`@Decide` 方法在 Java 里直接返回决策码(`String` 单码或 `List<String>`/`String[]` 多码=F 多决策),`@Score`+`@ScoreBand` 返回评分并按分档映射决策、写入 `EvalResult.score`。合成规则用 SDK 本地 String kind tag(`__anno_decide`/`__anno_score`),只注册进 SDK executors map(RuleEngineClient.java:81 从单 AST_BOOLEAN 扩多 kind),**不污染 `RuleKind` 枚举**。一条规则的判定原语 `@Condition`/`@Decide`/`@Score` 恰好三选一,返回/分档的决策码须 ⊆ `@DecisionBinding`(扫描期校验)。`FactResolver` 参数注入三者共用。**下游(本次不做)**:`@Condition` boolean 规则一次发全部 binding 需改 kernel `resolveRuleDecisions`,有需求再议;当前用 `@Decide` 多返回替代。设计见 `specs/2026-06-12-annotation-nonboolean-rule-design.md`。
```
