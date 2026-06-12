# EXPRESSION_SCRIPT 表达式脚本规则 — 设计

> 状态:设计中 · 日期:2026-06-12 · 关联决策:D12(Rule.kind 多态预留)/ D42(决策树/表 + 把 EXPRESSION_SCRIPT 留 v1.5)/ D56(草稿即冻结快照)/ D64(返回类型派发决策/分契约)/ D45(统一取数 EvalContext)
> 范围:补完 `RuleKind` 五形态里最后一个未实装的 `EXPRESSION_SCRIPT`,定位为**服务端 config-driven**(运营在配置里写表达式、不发版热改)。

## 1. 背景与动机

`RuleKind` 当前五值:`AST_BOOLEAN`(D61)/ `SCORECARD`(D12)/ `DECISION_TREE`/ `DECISION_TABLE`(D42)均已实装;`EXPRESSION_SCRIPT` 自 D12 占位、D42 明确"留 v1.5",至今**只是枚举声明 + DB 迁移枚举值 + 一处发布测试引用**,classpath 上无任何脚本/表达式引擎,无执行器。

它要补的能力缺口:**整条规则就是一个表达式,运营在 DB 配置里写、热改不发版**——其余四个 kind 都在发布期冻结成 AST 快照(premise A / D56),没有"运营自由书写一段逻辑"的形态。

D42 当年搁置的两个顾虑——**沙箱安全 + 性能代价**——本设计用"受限表达式语言(safe-by-design)+ 预编译缓存"正面化解。

## 2. 关键设计决策(brainstorm 结论)

| # | 决策 | 取舍依据 |
|---|---|---|
| **定位** | **服务端 config-driven**;SDK 仅"执行"不"编写" | SDK 已有 `@Condition`/`@Decide`/`@Score` 写任意 Java(D61/D64),嵌入式再提供"写脚本"冗余;真空缺是配置侧热改。**编写在服务端,执行可发生在 eval-svc 或内嵌 SDK 的接入方进程**——后者是 embedded 模式定义本身,不矛盾。 |
| **引擎类别** | **受限表达式语言(非图灵完备、无 I/O/反射/类加载),不是全功能脚本** | 脚本来自配置 = 来源不完全可信 = RCE 风险。受限表达式**安全 by design**(对标 Google CEL / Camunda FEEL / OPA Rego);全功能脚本(Groovy/MVEL)靠沙箱事后封堵是长期负债(D42 因此搁置)。"安全 by design > 安全 by 配置"。 |
| **引擎可插拔** | `ExpressionEngine` SPI + **盒内只发一个 safe-by-design 默认 = CEL**;其它引擎(Aviator/Lua/...)做 opt-in 插件 | 对标 Easy Rules"核心 + 每语言一适配器",且复用本项目 `ConditionEvaluator`/`MetricSourceHandler`/`RuleVersionExecutor` 一律 SPI 的调性。但**不发语言菜单**:多引擎会把安全地板拉到最弱那个、每引擎重写校验/trace/变量抽取、治理碎片化(成熟平台 FEEL/Rego/CEL 都标准化单一语言)。危险引擎降级为部署方主动 opt-in(自负沙箱 + 放弃静态校验 + 接受扁平 trace)。 |
| **返回契约** | 复用 D64:`Boolean`→ruleHit、`String`→决策码、`Number`→score | 与注解规则同一套输出语义,`EvalResult` 已多态,引擎零改动。 |
| **trace** | 单节点扁平 `SCRIPT` trace(非节点级树) | 脚本不是 AST,记不了节点级 trace;诚实记 input(绑定变量+来源)/output,对标 OPA decision log。不动现有树形 trace 体系。 |
| **依赖冻结** | 发布期从编译后表达式**自动抽取**引用变量 → 冻 `metricDependencies`/`payloadDependencies` | 运营不手填依赖;对齐 D56 草稿冻结快照。 |

## 3. 范围

**做(本 spec)**
- `EXPRESSION_SCRIPT` kind 端到端:脚本载体、`ScriptExecutor`、`ExpressionEngine` SPI、CEL 实现、预编译缓存、发布校验、扁平 trace、SDK opt-in 执行、代码 builder 写脚本形态。

**留缝(不实装)**
- 方案 B:`expression.*` ConditionEvaluator(表达式作 AST_BOOLEAN 内的布尔叶子)——靠 `ExpressionEngine` SPI 抽好接口,本轮不建校验/测试路径。
- `@ExpressionRule` SDK 注解(复用 D64 合成扫描器机制,纯加扫描器,kernel 不动)。
- 其它引擎:Aviator / Lua(LuaJ,图灵完备 = 需沙箱,归 opt-in 插件层),靠 `ExpressionEngine` SPI 补。
- **`RuleLogic` 统一父类型(DMN `Expression` 模式)**:把 `RuleVersionSnapshot.conditionAst: AstNode` + `script: ScriptSource` 两个互斥可空字段收敛成单一 `logic: RuleLogic`(`sealed permits AstNode, ScriptSource`),消费方改穷尽 switch。模型更干净但本质是对既有 4 个 kind 的**跨模块统一重构**(blast radius 大)。**性能上与两字段路 0 差异**(派发本就走 kind→executor,RuleLogic switch 不在热环)——选不选只看模型干净度。**触发条件**:出现**第 3 个非 AST 树逻辑形态**(如 D12 列的决策流/决策集,大概率非 AST 树)使可空字段增至 3+,或两字段约定在实现中真咬人时,**才另开 spec 做**。本轮 2 形态属 borderline,先走两字段轻量路,不投机性重构(§2)。

**非目标**
- 不实现全功能脚本沙箱(Groovy/MVEL)。
- 不在 `RuleKind` 之外引入新 kind 枚举值(`EXPRESSION_SCRIPT` 已存在)。
- 不提供 SDK"本地编写脚本"的注解入口(见 §2 定位)。

## 4. 模块落点

守 **kernel 纯净(无 CEL 依赖)+ CEL 真 opt-in**:

| 模块 | 新增/改动 |
|---|---|
| `rule-kernel` | `ExpressionEngine` SPI、`CompiledExpression` 接口、`ScriptExecutor`(持注入 engine,**不依赖 CEL**)、脚本载体 `ScriptSource`(typed record,非 AstNode)、`RuleVersionSnapshot.script` 字段、`SCRIPT_*` 错误码、`NodeType.SCRIPT`、`INVALID_DECISION_CODE` 提升 |
| `rule-expression-cel`(**新模块**,带 CEL 依赖) | `CelExpressionEngine implements ExpressionEngine`(编译/类型检查/变量抽取/求值 + Caffeine 预编译缓存) |
| `rule-eval-svc` | `EvalAutoConfiguration` 建 `CelExpressionEngine` + `ScriptExecutor` bean,注册进 kind→executor map;索引热更时预热编译缓存 |
| `rule-config-svc` | `resolveAndValidate` 加 `EXPRESSION_SCRIPT` 分支(发布期编译 + 类型检查 + 决策码校验 + 依赖抽取冻结) |
| `rule-sdk(-spring-boot-starter)` | `RuleEngineClient.Builder` 始终注册 kernel `ScriptExecutor`;CEL **引擎**为 opt-in(可选依赖);引擎未注册时该规则优雅 `SCRIPT_NO_ENGINE`(不触发 AST_BOOLEAN 回退) |
| `rule-api` / `10-api-contract.md` | 补 EXPRESSION_SCRIPT 规则的请求/响应 schema(脚本源码字段 + kind) |

## 5. 详细设计

### 5.1 脚本载体 `ScriptSource`(与 AST 平级,非 AstNode)

脚本不进 `AstNode` sealed 体系。理由(brainstorm + 开源对标结论):

- **开源共识**:自由表达式是"决策逻辑种类"层的独立载体,与结构化表示**平级**,从不塞进条件节点树当叶子——Camunda DMN 的 `LiteralExpression` 是 `DecisionTable` 的**兄弟**(都是 `Expression` 子类型),Drools 的 consequence 是独立代码块,Easy Rules 的表达式是 rule 子类的头等字段。映射到我们:脚本坐在 `RuleKind` 层(= DMN `Expression` 子类型层),不进 `AstNode`(= 结构化种类的内部结构)。
- **不透明性**:现有 `ScorecardRootNode`/`IfNode`/`DecisionTableNode` 都是**可遍历的 ConditionNode 结构**(`MetricDependencyCollector`/`PayloadFieldCollector` 走进去摘依赖)。脚本是**不可遍历**的——依赖从 `engine.referencedVariables()` 出。把它塞进 sealed `AstNode` 会**强制** 4 处无 `default` 的穷尽 switch(`InterpretedExecutor`/`AstDataTypeResolver`/`MetricDependencyCollector`/`PayloadFieldCollector`)各补一个"假装处理实则 bypass"的 `case ScriptNode`。

载体定义(kernel,纯 typed record,不实现 `AstNode`):

```java
/** EXPRESSION_SCRIPT 规则的脚本载体;source 为表达式源码,lang 标识引擎(默认 CEL)。 */
public record ScriptSource(String source, String lang) {
    public ScriptSource {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("script source 不能为空");
        lang = (lang == null || lang.isBlank()) ? ExpressionLang.CEL.tag() : lang;
    }
}
```

- EXPRESSION_SCRIPT 规则:`RuleVersionSnapshot.conditionAst = null`,脚本挂在新增字段 `ScriptSource script`(nullable;其它 kind 为 null)。
- `lang` 为可插拔引擎标识(`CEL` 默认);其它引擎 opt-in 时复用此字段路由到对应 `ExpressionEngine`。
- `AstNode` sealed 体系**零改动**——4 处穷尽 switch 不动,脚本规则根本不进这些 walker。

### 5.2 `ExpressionEngine` SPI(kernel,纯 Java)

```java
/** 表达式引擎 SPI:编译为可缓存的 CompiledExpression,按变量绑定求值。实现须线程安全(单例共享)。 */
public interface ExpressionEngine {
    /** 引擎标识,与 ScriptSource.lang 路由匹配(如 "CEL")。 */
    String lang();
    /** 编译源码(含语法/类型检查);失败抛 ExpressionCompileException。实现内部按源码内容哈希缓存。 */
    CompiledExpression compile(String source);
    /** 对编译产物按只读变量绑定求值,返回 Boolean/String/Number 之一(或 null=不命中)。 */
    Object evaluate(CompiledExpression compiled, Map<String, Object> bindings);
}

/** 编译产物;referencedVariables 供发布期依赖抽取与校验(点路径,如 "metrics.txn_cnt_1d")。 */
public interface CompiledExpression {
    Set<String> referencedVariables();
}
```

- `ScriptExecutor`(kernel)只依赖此 SPI,不依赖任何具体引擎。
- `evaluate` 返回 `Object`,由 `ScriptExecutor` 按运行时类型派发(§5.4),与 D64 一致。

### 5.3 变量绑定面(server / SDK 统一)

从 `EvalContext`(D45 统一装配,两端一致)构造**只读** binding map,命名空间:

| 前缀 | 来源 | 例 |
|---|---|---|
| `metrics.<code>` | 取数管线结果 | `metrics.txn_cnt_1d` |
| `payload.<field>` | 事件 payload | `payload.amount` |
| `subject.<attr>` | SubjectLoader 加载的主体属性 | `subject.level` |
| `now` | 评估时刻 | `now` |

绑定面构造逻辑放 kernel 一处工具方法,`ScriptExecutor` 与(将来)B 方案共用。

### 5.4 `ScriptExecutor`(kernel)

`implements RuleVersionExecutor`,持注入的 `Map<String, ExpressionEngine>`(按 lang 路由),注册形态完全对标 D42 的 `DecisionTreeExecutor`:

```
execute(snapshot, ctx):
  1. snapshot.script() == null → EvalResult.error(SCRIPT_SOURCE_MISSING)
  2. engine = engines.get(script.lang());  engine==null → error(SCRIPT_NO_ENGINE)
  3. compiled = engine.compile(script.source())   // 命中预编译缓存,未命中编译
  4. bindings = buildBindings(ctx)        // §5.3
  5. result = engine.evaluate(compiled, bindings)   // 抛错 → error(SCRIPT_EVAL_ERROR)
  6. 按 result 运行时类型派发(复用 D64 语义):
       Boolean  → ruleHit=result;  命中则按最高优先级 @DecisionBinding 取单决策
       String   → 决策码;∈ decisionBindings → ruleHit + 该 Decision;∉ → error(INVALID_DECISION_CODE)
       Number   → score;按 snapshot 内 min-only 分档(D64)映射决策 + 写 EvalResult.score
       null     → EvalResult.miss()
  7. trace:collect 时挂单节点 SCRIPT trace(§5.5),否则 List.of()
```

派发与决策裁决**完全复用** D64 既有语义(决策码须 ∈ bindings、min-only 分档),不新造规则。

### 5.5 扁平 SCRIPT trace

脚本无节点结构,trace 记 **input + output** 而非节点树。新增 `NodeType.SCRIPT`,单个 `NodeTrace`:

- `nodeType = SCRIPT`,`satisfied = ruleHit`;
- 携带:绑定变量的**实际值与来源**(`metrics.*` 的 valueSource 复用取数三态)、脚本**源码哈希**(非全文,避免 trace 膨胀)、**输出值**;
- 受 `TraceScope.COLLECT` 守卫:关闭时零分配(与现有执行器一致,对齐 `2026-06-08-eval-trace-skip-design.md`)。

dry-run 同此:走带版本单快照分支(D56),输出脚本输入绑定 + 输出,不落 session。

### 5.6 预编译缓存

- **位置/key**:`CelExpressionEngine` 内 **Caffeine**,**以脚本源码内容哈希为 key**(项目已用 Caffeine:`DbMetricDefinitionResolver`/`CaffeineMetricCache`)。
- **编译时机 = 快照加载期预热,不在 eval 热路径**:对齐 AST"装配期反序列化一次"的做法——eval-svc 收 `RulePublishedEvent` 重载快照时 / SDK poll 到新 snapshot 时调 `engine.compile()` 预热(本就异步、离请求热路径)。
- **失效 = 内容寻址,无显式 invalidate**:改脚本 = 新版本(premise A)= 新源码 = 新 hash = 新条目;旧版本 supersede、索引重载后不再引用,旧条目靠 size/TTL 淘汰。
- **附带去重**:多规则/多版本同源脚本共享一份编译产物。
- **双端各持各的**:eval-svc `CelExpressionEngine` 单例 bean 一份缓存;SDK `RuleEngineClient` 一实例一份。
- config-svc 发布期 compile 仅为校验,与 eval-svc 运行时缓存**不跨进程共享**(正常,各自预热)。

### 5.7 发布期校验(config-svc `resolveAndValidate` 加 EXPRESSION_SCRIPT 分支)

草稿 create/edit/newVersion 时(D56 premise A)即跑:
1. **编译**:`engine.compile(source)` 失败(语法/类型) → 拒,返回编译错误位置。
2. **变量声明校验**:`compiled.referencedVariables()` 里的 `metrics.*` 须在 scene 可用且 metric ACTIVE;`payload.*` 须在 `scene.payloadSchema` 声明(对齐现有 AST 的 payload 依赖校验)。
3. **依赖冻结**:从引用变量抽取 → 写 `metricDependencies`(metric+version)/ `payloadDependencies`(name/dataType/required)进草稿快照。
4. **决策码静态校验**:CEL 能静态枚举返回的字符串字面量时,校验 ⊆ `decisionBindings`;无法静态枚举的(动态拼串)→ 运行期 `INVALID_DECISION_CODE` 兜底(同 D64 `@Decide` 策略)。

### 5.8 SDK opt-in 执行

**关键约束**:`EvalEngine:254-255` 对未知 kind 会**回退到默认 AST_BOOLEAN 执行器**。若 SDK 不注册 `ScriptExecutor`,EXPRESSION_SCRIPT snapshot 会被错误地丢给布尔解释器(且 `conditionAst=null`)。规避方式——**`ScriptExecutor` 是 kernel 类、无 CEL 依赖,SDK 始终注册它进 kind map**;真正 opt-in 的只是 **CEL 引擎**:

- `rule-sdk-spring-boot-starter` 对 `rule-expression-cel` 为**可选依赖**;
- `RuleEngineClient.Builder` 始终把 kernel `ScriptExecutor` 注册进本地 kind→executor map(对标 D64 多 kind 注册);`enableExpressionScript(ExpressionEngine)` / classpath 自动检测决定**往 `ScriptExecutor` 的 engines map 里注入哪个引擎**;
- **CEL 引擎未 opt-in 时**:`ScriptExecutor` 在(空 engines map)碰 EXPRESSION_SCRIPT 规则返回 `SCRIPT_NO_ENGINE`(graceful),**不触发 AST_BOOLEAN 回退、不连累 client 其它规则**。

### 5.9 代码 builder 写脚本形态(SDK 本地/测试)

```java
RuleVersionSnapshot rule = RuleVersionSnapshot.builder()
    .sceneCode("txn_risk").kind(RuleKind.EXPRESSION_SCRIPT.tag())
    .script(new ScriptSource("payload.amount > 10000 && metrics.txn_cnt_1d > 50 ? 'REVIEW' : 'PASS'", "CEL"))
    .addDecisionBinding("REVIEW", 10).addDecisionBinding("PASS", 100)
    .build();
```

用途:本地原型/单测脚本再推服务端。**不提供** `@ExpressionRule` 注解(§3 留缝)。

## 6. 数据 / 契约影响

- `rule_definition.kind` / `rule_version.kind`:`EXPRESSION_SCRIPT` 值已在(VARCHAR,D51);无 DDL。
- `rule_version.condition_ast`:EXPRESSION_SCRIPT 规则该列为 **null**(脚本不进 AST)。
- `rule_version.script_source`:**新增列**,typed JSON `{source, lang}`(`ScriptSource`,经 `Jackson3TypeHandler` + autoResultMap;greenfield 无历史迁移负担)。实体 `RuleVersion` 加 `ScriptSource scriptSource` 字段;`RuleVersionSnapshot` 加 `ScriptSource script` 字段(snapshot 装配链路一并带)。
- `metric_dependencies` / `payload_dependencies`:复用现列,发布期由**引擎变量抽取**填(非 AST 走查)。
- API 契约(`10-api-contract.md`):规则请求体 kind=EXPRESSION_SCRIPT 时携 `script: {"source":"...","lang":"CEL"}`(与 `conditionAst` 互斥);响应 trace 含 SCRIPT 节点形态。

## 7. 错误码(kernel `EvalErrorCode` 新增)

**前置重构(Fase 0):`EvalErrorCode` 改 enum 作单一真相源;errorCode 字段保持 String。**
- 现状:`EvalErrorCode` 是 `final class` + `String` 常量;`EvalResult.errorCode`/`NodeTrace.errorCode`/`ConditionOutcome.errorCode` 字段为 `String`;SDK 散落 6 个字面量(`ANNO_DECIDE_UNREGISTERED`/`ANNO_DECIDE_NO_HIT`/`ANNO_SCORE_UNREGISTERED`/`DECIDE_EVAL_ERROR`/`SCORE_EVAL_ERROR`/`INVALID_DECISION_CODE`,`AnnotatedScore/DecideExecutor`)未引用 `EvalErrorCode`。
- **关键:errorCode 不是封闭集**——`MetricValue.errorCode`(String)由 metric provider(`MetricSourceHandler` SPI)填,可带 `METRIC_SOURCE_EVAL_ERROR` 等**开放码**,经 `ConditionEvaluation` 穿到 `ConditionOutcome`/`EvalResult.errorCode`。故按项目 §数据类型规范「**开放可扩展用常量/String,不用 enum**」:**字段保持 String,不改 enum 类型**(避免把开放集当封闭集 → `valueOf` 崩 / 吞码)。
- 改造:`EvalErrorCode` 改 **enum** 作 kernel/SDK 错误码的**单一真相源**(纳入 `METRIC_SOURCE_EVAL_ERROR` + 下表 SCRIPT_* + SDK 6 码);各错误工厂(`EvalResult.error`/`NodeTrace.container`/`ConditionOutcome.error`/`MetricValue.error`)**加接收 `EvalErrorCode` 的重载**,内部存 `.name()`——kernel 产出点写 `error(EvalErrorCode.X)` 干净不撒 `.name()`;**保留 `String` 工厂**给 provider 开放码原样穿透。字段、持久层(session/node_trace 实体均 String)、`ConditionEvaluation` 语义**全不动**,零行为变更、零新崩溃面。
- 范围:kernel(EvalErrorCode + EvalResult/NodeTrace/ConditionOutcome/MetricValue 加重载 + 执行器产出点绑定 enum 重载)/ sdk(注解执行器 6 字面量 → `EvalErrorCode.X`)。**不动 eval-svc/observability 持久层**。
- **本脚本功能的新码直接加进这个 enum**(下表)。

新增枚举值:

| 码 | 含义 |
|---|---|
| `SCRIPT_SOURCE_MISSING` | kind=EXPRESSION_SCRIPT 但 snapshot.script() 为 null |
| `SCRIPT_NO_ENGINE` | `script.lang` 无对应已注册 `ExpressionEngine`(SDK 未 opt-in CEL 引擎即此态);ScriptExecutor 始终注册,故**不会**走 EvalEngine 的 AST_BOOLEAN 回退 |
| `SCRIPT_EVAL_ERROR` | 运行期求值抛错(类型不符/除零等) |
| `INVALID_DECISION_CODE` | 返回决策码 ∉ decisionBindings(由 SDK 字面量提升而来,ScriptExecutor 与注解执行器共用) |

(编译期错误在 config-svc 发布校验抛业务异常,不进 `EvalErrorCode`。)

## 8. 测试计划

- **kernel**:`ScriptExecutorTest`——Boolean/String/Number 三种返回派发;决策码 ∉ bindings(`INVALID_DECISION_CODE`);`script()` 为 null(`SCRIPT_SOURCE_MISSING`);无 engine(`SCRIPT_NO_ENGINE`);求值抛错(`SCRIPT_EVAL_ERROR`);trace collect on/off 零分配。用 fake `ExpressionEngine`(不依赖 CEL)覆盖派发逻辑。
- **rule-expression-cel**:`CelExpressionEngineTest`——编译/类型检查/变量抽取/求值;预编译缓存命中(同源去重)、内容变更换 key;**安全验证**:尝试 I/O/反射/类加载的表达式编译即拒(safe-by-design 断言)。
- **config-svc**:发布校验——编译失败拒、未声明变量拒、依赖正确冻结、决策码静态校验。
- **eval-svc**:端到端——配 EXPRESSION_SCRIPT 规则 → 评估出决策/分 + SCRIPT trace 落库;索引热更预热编译。
- **SDK**:opt-in CEL 引擎后执行;未注册引擎时该规则 `SCRIPT_NO_ENGINE` 且不影响其它规则(验证不走 AST_BOOLEAN 回退)。
- 跨模块改动带 `-am`,最终 `$MVN clean test` 兜底(CLAUDE.md 测试纪律)。

## 9. 决策日志条目(待追加 00-decisions,D66 草案)

> D66. `EXPRESSION_SCRIPT` 表达式脚本规则(补完 RuleKind 第五形态)。定位**服务端 config-driven**(SDK 仅执行不编写)。**受限表达式语言 safe-by-design**(非图灵完备,对标 CEL/FEEL/Rego),不做全功能脚本沙箱(化解 D42 搁置的沙箱安全顾虑)。`ExpressionEngine` SPI + 盒内单一默认 **CEL** 实现(`rule-expression-cel` 模块,kernel 不依赖 CEL),其它引擎(Aviator/Lua)降级 opt-in 插件。**脚本载体 `ScriptSource`(typed record,与 AST 平级、非 AstNode)**——开源对标(DMN `LiteralExpression` 是 `DecisionTable` 兄弟、Drools consequence 独立、Easy Rules 表达式头等字段)+ 脚本不可遍历(依赖从 `engine.referencedVariables()` 出),都指向不进 sealed `AstNode`;EXPRESSION_SCRIPT 规则 `conditionAst=null` + 新列 `rule_version.script_source`,`AstNode` 体系零改动。`ScriptExecutor` 注册进 kind→executor map(对标 D42),`EvalEngine` 零改动。返回类型派发复用 D64(Boolean/String/Number)。**预编译缓存**:源码内容哈希为 key 的 Caffeine,快照加载期预热、内容寻址天然失效。发布期编译 + 类型检查 + 变量抽取冻依赖 + 决策码校验。trace 为单节点扁平 `SCRIPT`(input/output,非节点树)。SDK 始终注册 kernel `ScriptExecutor`(避开 `EvalEngine` 对未知 kind 回退 AST_BOOLEAN 的陷阱),CEL 引擎才是 opt-in,未注册引擎该规则优雅 `SCRIPT_NO_ENGINE`。**前置重构**:`EvalErrorCode` 从 String 常量类改造成真 enum 作**单一真相源**(纳入 SDK 散落码 + `METRIC_SOURCE_EVAL_ERROR` + 新 `SCRIPT_*`);各错误工厂加 enum 重载(内部 `.name()`)。**errorCode 字段保持 String**——因 metric provider(`MetricSourceHandler` SPI)经 `MetricValue.errorCode` 可带开放码,errorCode 非封闭集,按 §数据类型规范开放集用 String;持久层/`ConditionEvaluation` 不动,零行为变更。留缝:方案 B(`expression.*` ConditionEvaluator)、`@ExpressionRule` 注解、其它引擎,均靠 `ExpressionEngine` SPI 加法补。
