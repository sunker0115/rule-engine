# Easy Rules 注解易用性与启动防呆 — 设计

> 状态:已实现 · 日期:2026-06-12 · 关联决策:D63(草案,见文末) · 前置:D61(Easy Rules 风格注解规则)
> 范围拆分:本 spec 只收**不动引擎语义**的局部增强(A/B/C/D/G/H);注解表达非 boolean 规则与多决策见姊妹 spec `2026-06-12-annotation-nonboolean-rule-design.md`(E/F)。

## 1. 背景与动机

D61 落地后,嵌入式 SDK 已能用 `@RuleDef`+`@Condition`+`@Fact`/`@Metric` 写规则、`@OnDecision`/`@EventListener` 接动作。实际使用暴露出一组**易用性与防呆**缺口,均不触碰引擎执行语义,可在 `rule-sdk`/`rule-sdk-spring-boot-starter` 局部收口:

- **样板冗余**:每个注入参数都要写 `@Fact("amount") Integer amount`,字段名重复一遍。
- **静默坑**:`@Fact` 取不到只注入 `null`,必填字段拼错 key 不报错、规则悄悄不命中(`FactResolver.metadata` 末尾 `return null`)。
- **诊断滞后**:参数漏标 `@Fact`/`@Metric` 在**求值期**才抛 `IllegalStateException`(`FactResolver.resolveOne`),不是启动期。
- **死方法**:`OnDecisionInvoker.hasHandlerFor` 定义了但全仓无调用方,本意的 orphan 诊断没接上。
- **线程语义不透明**:`@OnDecision` 经 `DecisionDispatcher` 在 `RuleEngineClient.evaluate()` 调用栈内**同步**执行(RuleEngineClient.java:140-141),即动作占用评估线程,用户易误以为异步;且无 async 旋钮。

## 2. 范围

| 项 | 标题 | 触及 |
|---|---|---|
| **A** | `@Fact`/`@Metric` 的 `value()` 可选,缺省回退参数名 | `Fact`/`Metric` 注解、`FactResolver`、`AnnotatedRuleScanner` |
| **B** | `@Fact` 增加 `required()` + `defaultValue()` | `Fact` 注解、`FactResolver`、类型转换 |
| **C** | `@Fact` 支持嵌套路径 `order.amount` | `FactResolver` |
| **D** | 参数漏标/坐标校验上移到扫描期(fail-fast) | `AnnotatedRuleScanner`、`OnDecisionInvoker` |
| **G** | orphan `@OnDecision` 启动期 warn(盘活 `hasHandlerFor`) | `RuleEngineClientAutoConfiguration` |
| **H** | `@OnDecision` 线程语义:Javadoc 讲清 + 加 `async()` 开关 | `OnDecision` 注解、`OnDecisionInvoker`、AutoConfiguration |
| 附 | 类型转换扩展(支撑 B 的 `defaultValue` 解析) | `FactResolver.coerce` |

### 非目标

- **不动引擎**:`EvalEngine`/各 `RuleVersionExecutor`/`resolveRuleDecisions` 一律不改(那是 Spec 2)。
- **不支持非 boolean 注解规则**(`@Score`/`@Decide`、多决策)—— Spec 2。
- **不引入异步 transport**:H 的 `async()` 仅指"在独立线程池跑处理器",不引 MQ。

## 3. 逐项设计

### A. `value()` 可选 + 参数名回退

前置已满足:Spring Boot parent 默认开 `-parameters`(构建日志 `javac [debug parameters release 25]`),`Parameter#getName()` 返回真实参数名而非 `arg0`。

- `Fact.value()` 改为 `String value() default "";`
- `Metric.value()` 同理 `default ""`(`version()` 不变,default 1)。
- **取名规则(集中到一处)**:新增 `FactResolver.factName(Parameter p)` / `metricName(Parameter p)`:注解 `value()` 非空用之,空则回退 `p.getName()`。
- `FactResolver.resolveOne` 与 `AnnotatedRuleScanner` 声明 metric 依赖(`b.addMetricDependency(name, version)`)都改用该统一取名,保证"依赖声明用的名"与"注入取的名"永远一致。

效果:`@Fact Integer amount` ≡ `@Fact("amount") Integer amount`;`@Metric Integer total` ≡ `@Metric("total")`。

### B. `@Fact` 增加 `required()` + `defaultValue()`

```java
public @interface Fact {
    String value() default "";
    /** 取不到(payload + 元数据皆无)时是否报错;默认 false=注入 null/默认值。 */
    boolean required() default false;
    /** 取不到时的回退字面量;非空时按参数类型解析注入(优先级低于实际取值,高于 null)。 */
    String defaultValue() default "";
}
```

解析顺序(`FactResolver.resolveOne` 的 `@Fact` 分支):payload(含嵌套,见 C)→ 元数据 → `defaultValue`(非空则按类型解析)→ `required` ? 抛 `MissingFactException` : `null`。

- **`MissingFactException`**:新建 `com.sstlfsj.rule.sdk` 下的运行时异常,带参数名 + 规则/处理器上下文。
- **条件侧**:`@Condition` 参数 required 缺失 → 异常冒泡到 `AnnotatedRuleScanner.wrap` 的 catch,按既有"算子异常=降级不命中 + errorCode"语义处理(不破坏评估),errorCode 记 `MISSING_REQUIRED_FACT`。
- **动作侧**:`@OnDecision` 参数 required 缺失 → `OnDecisionInvoker.accept` 的 catch 吞 + error 日志(与现有处理器异常隔离一致)。
- `defaultValue` 是字面量 String,按目标参数类型解析(见"类型转换扩展")。

### C. `@Fact` 嵌套路径

payload 为嵌套结构时,`@Fact("order.amount")` 按 `.` 逐级下钻 `Map`:

- `FactResolver`:取 payload 值时,若 `name` 含 `.`,逐段 `((Map)cur).get(seg)`,中途非 Map 或缺键 → 落空(进入 B 的 default/required 流程)。
- 仅作用于 payload;元数据键(eventId/decisionCode 等)是平铺保留字,不走嵌套。
- 与 A 的参数名回退**互斥**:参数名不可能含 `.`,故回退名永远是平铺单段;嵌套必须显式写 `value()`。

### D. 扫描期参数校验(fail-fast)

把"参数必须标 `@Fact` 或 `@Metric`"的校验从求值期上移到启动期:

- **新增** `FactResolver.validate(Parameter[] params)`:逐参数确认标了 `@Fact` 或 `@Metric`,否则抛 `IllegalStateException`(带方法 + 参数名)。
- `AnnotatedRuleScanner.scan`:对每个 `@Condition` 方法的参数调 `validate`(在 `wrap` 前),启动即暴露漏标。
- `OnDecisionInvoker` 构造:对每个 `@OnDecision` 方法的参数调 `validate`,启动即暴露。
- `FactResolver.resolveOne` 里原求值期的 throw 保留为兜底(理论不再触发,但防御 validate 漏网)。

### G. orphan `@OnDecision` 启动 warn

盘活 `hasHandlerFor`,在装配完成处做一次交叉核对:

- `RuleEngineClientAutoConfiguration.ruleEngineClient`:扫描所有注解规则(`@RuleDef`+`@Condition`)的 `@DecisionBinding.code()` 汇成"已产出决策码集合";遍历 `OnDecisionInvoker` 登记的订阅码,凡不在该集合中的,`log.warn("@OnDecision 订阅的决策码 {} 没有任何规则产出,疑似拼写错误", code)`。
- 仅 warn 不 fail(消费方可能订阅服务端轮询规则的决策码,SDK 本地不可见)。
- 需给 `OnDecisionInvoker` 加 `Set<String> subscribedCodes()`(返回 `byCode.keySet()` 不可变视图)供核对。

### H. `@OnDecision` 线程语义 + `async()` 开关

**澄清(文档)**:`@OnDecision` 处理器经 `DecisionDispatcher` 在 `RuleEngineClient.evaluate()` 同步调用栈内执行,**占用评估线程**;慢处理器阻塞评估返回。这是默认行为,写进 `@OnDecision` 与 `DecisionDispatcher` Javadoc。

**开关**:

```java
public @interface OnDecision {
    String[] value();
    String fromRuleCode() default "";
    /** true=处理器在独立线程池异步执行,不阻塞评估;默认 false=评估线程同步执行。 */
    boolean async() default false;
}
```

- `OnDecisionInvoker` 持有一个可选 `Executor`(由 AutoConfiguration 注入,缺省用一个有界 `ThreadPoolExecutor`,或复用既有 `fetchExecutor` 语义的独立池——**spec 决定:新建一个命名 `onDecisionExecutor`,daemon 线程、有界队列、拒绝策略 CallerRuns**)。
- `accept` 内:`async` 的 Handler 提交到该 executor;非 async 同步执行。`async` 的异常隔离在提交的任务内 try/catch(同样吞 + error 日志)。
- 甲(`@EventListener`)不变:消费方仍可自行 `@Async`/`@Order`。

### 附:类型转换扩展(支撑 B 的 defaultValue)

`FactResolver.coerce` 现仅覆盖 `Number→{Integer,Long,Double,BigDecimal}`。为支持 `defaultValue`(字面量 String → 参数类型)与更宽的 payload 容错,扩展:

- `String → {Integer,Long,Double,BigDecimal,Boolean,String}`:按目标类型 parse;parse 失败抛 `IllegalArgumentException`(带值 + 目标类型)。
- `String → enum`:`Enum.valueOf`。
- 其余保持原样透传。
- 仅在 `defaultValue` 解析与目标类型不匹配的实际取值上触发;类型已匹配直接返回(短路不变)。

## 4. 影响面 / 文件清单

**rule-sdk**
- `annotation/Fact.java` — value 可选 + required + defaultValue
- `annotation/Metric.java` — value 可选
- `FactResolver.java` — 统一取名、嵌套路径、required/default、validate、coerce 扩展
- `MissingFactException.java`(新)
- `source/AnnotatedRuleScanner.java` — 统一取名声明依赖 + 调 validate

**rule-sdk-spring-boot-starter**
- `annotation`... (注:`OnDecision` 在 rule-sdk,故 H 的 async 属性改 rule-sdk 的 `annotation/OnDecision.java`)
- `OnDecisionInvoker.java` — validate、subscribedCodes、async executor 分支
- `RuleEngineClientAutoConfiguration.java` — orphan warn 交叉核对、注入 onDecisionExecutor

**rule-samples**(可选,演示新糖)
- `annotation/LargeTradeRule.java` — 用 `@Fact` 无值参数名回退 / 某参数 `required`/`async` 示意(择一两处,不堆砌)

## 5. 验证

每项配单测(TDD,英文方法名):
- A:`@Fact`/`@Metric` 无 value 时按参数名注入 + metric 依赖名一致。
- B:required 缺失抛 `MissingFactException`;defaultValue 命中且类型正确解析;实际值优先于 default。
- C:嵌套 `order.amount` 取到;中途断链落 default/null。
- D:漏标参数的规则/处理器在 `scan`/构造期即抛,带参数名。
- G:订阅无产出决策码 → 启动日志含 warn(用 `OutputCaptureExtension` 或断言 `subscribedCodes` 交叉集)。
- H:`async=true` 的处理器不在评估线程执行(断言线程名≠调用线程),且异常隔离;Javadoc 走查。
- coerce:String→各类型 + enum 正/反例。
- 收尾全量 `$MVN clean test`。

## 6. 决策日志条目草案(D63)

> 待评审通过后追加到 `docs/00-decisions.md`。

**D63 Easy Rules 注解易用性与启动防呆 | A**

在 D61 注解规则基础上补一组**不动引擎**的局部增强:`@Fact`/`@Metric` 的 `value()` 可选(回退参数名,依赖 `-parameters`);`@Fact` 加 `required()`+`defaultValue()` 堵住"取不到静默 null"(缺失抛 `MissingFactException`,条件侧降级不命中+errorCode、动作侧吞+日志);`@Fact` 支持嵌套路径 `a.b`;参数漏标 `@Fact`/`@Metric` 的校验从求值期上移到 `AnnotatedRuleScanner`/`OnDecisionInvoker` 启动期 fail-fast;盘活 `hasHandlerFor` 做 orphan `@OnDecision` 启动 warn(订阅了无规则产出的决策码);`@OnDecision` 明确"同步占用评估线程"语义并加 `async()` 开关(独立有界线程池,CallerRuns 兜底)。`FactResolver.coerce` 扩 String→{数值/Boolean/enum} 支撑 defaultValue。**非目标**:非 boolean 注解规则与多决策见 D64。设计见 `specs/2026-06-12-annotation-ergonomics-safety-design.md`。
