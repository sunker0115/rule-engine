# Easy Rules 风格注解规则(嵌入式 SDK)— 设计

> 状态:已实现 · 日期:2026-06-12 · 关联决策:D61(草案,见文末)
> 前置:D40(SDK `@RuleDef` 注解模式)、D60(纯决策化)、D34(嵌入式本地模式)、D49(RuleEvent)、D25(metric 预拉)

## 1. 背景与目标

现状 SDK 注解模式(D40)写规则要实现 `InlineRuleSpec` 接口、在 `condition()` 里用 `Condition` DSL 链式拼条件。目标是提供一套更接近 Easy Rules 的"写一个 POJO、方法即条件"的开发体验:

```java
@RuleDef(code = "even-number", sceneCode = "demo",
         decisions = @DecisionBinding(code = "EVEN", priority = 1))
public class EvenNumberRule {
    @Condition
    public boolean isEven(@Fact("number") Integer number) {
        return number % 2 == 0;
    }
}
```

并让"命中后做什么"(Easy Rules 的 `@Action`)以**贴合本引擎架构**的方式落地。

### 非目标

- 不在引擎内重新引入动作执行子系统(D60 已纯决策化,本设计严格遵守:引擎只出决策,动作在消费方)。
- 不支持把黑盒 `@Condition` 方法翻译成可内省 AST(做不到,见 §6 取舍)。
- 不面向远程评估服务形态;**仅嵌入式 SDK(同进程)**。

## 2. 关键决策(已对齐)

| # | 决策 | 选择 |
|---|------|------|
| 形态 | 部署形态 | 嵌入式 SDK,同进程;不碰 eval-svc / D60 |
| 模型 | 与 scene/decision 关系 | 现有模型加糖:POJO 声明 `@RuleDef` + `@DecisionBinding` |
| 条件 | `@Condition` 表达 | 单个布尔方法,包成不透明 `ConditionEvaluator`;多重逻辑写在方法体内(数据已注入,任意 Java) |
| 动作 | "命中后做什么"的表现形式 | **按 decision code 解耦**,不挂在规则上。甲:`DecisionFiredEvent` + `@EventListener`;乙:`@OnDecision` + `@Fact` 注入。两者都给 |
| 触发 | 多规则命中时动作跟谁 | 跟 `EvalResult.hitDecisions()`(评估策略决定),适配器不写死策略 |
| 异常 | 动作执行抛异常 | 吞 + `log.error` + 继续跑其余(条件侧异常另算,见 §5) |
| Fact | `@Fact` 来源 | payload + 元数据(eventId/tenantId/subjectId/occurredAt/决策码等);metric 走独立 `@Metric` |
| Metric | metric 注入与声明 | `@Metric("code")`(`version() default 1`,SDK 不填),声明=使用,驱动预拉 |

## 3. 架构与模块落点

原则:**`rule-sdk` 保持纯 Java(无 Spring),Spring 人体工学放 starter**,沿用现有模块边界。

```
rule-sdk(纯 Java,提供积木)
├─ 注解:@Condition、@Fact、@Metric、@OnDecision   ← @RuleDef/@DecisionBinding 复用 kernel 现有
├─ DecisionFiredEvent(record)                      ← 决策命中事件,普通值对象
├─ FactResolver                                    ← @Fact/@Metric 注入解析
├─ AnnotatedRuleScanner                            ← 扫 @Condition → 合成 ConditionEvaluator + 建快照 + 声明 metric 依赖
└─ DecisionDispatcher                              ← 吃 EvalOutcome,按 hitDecisions 产出 DecisionFiredEvent 流

rule-sdk-spring-boot-starter(Spring 装配)
├─ 收集 @RuleDef 规则 bean → AnnotatedRuleScanner → 注册进 RuleEngineClient(addEvaluator + ruleSource)
├─ 收集 @OnDecision 处理器 bean → 注册进 DecisionDispatcher(写法乙)
└─ 把 DecisionFiredEvent 桥到 ApplicationEventPublisher(写法甲,@EventListener 生效)
```

分层后职责:
- **规则侧**(`@RuleDef` + `@Condition` + `@Fact`/`@Metric`):只算决策(引擎本职)。条件被包成不透明算子,照常进 `SceneRuleIndex`,享受 scene 匹配 / trigger / priority / pre-gate / metric 预拉。
- **动作侧**(决策订阅,甲/乙):按 decision code 解耦,与具体规则无关(N 规则 → 1 决策 → 1 处理器,不重复)。

## 4. 组件设计

### 4.1 注解(rule-sdk,纯 Java)

```java
@Target(METHOD)    @Retention(RUNTIME) public @interface Condition {}
@Target(PARAMETER) @Retention(RUNTIME) public @interface Fact   { String value(); }
@Target(PARAMETER) @Retention(RUNTIME) public @interface Metric { String value(); int version() default 1; }
@Target(METHOD)    @Retention(RUNTIME) public @interface OnDecision { String[] value(); }
```
规则元数据继续用 kernel 现有的 `@RuleDef` + `@DecisionBinding`,不新造。

### 4.2 条件包装:`AnnotatedRuleScanner`

输入规则 POJO 实例,输出喂给 `RuleEngineClient.Builder` 的两样东西:

- **合成算子**:每条规则派生唯一 `conditionType = "__anno:" + tenant:scene:code`,`@Condition` 方法包成:
  ```java
  boolean evaluate(ConditionNode node, EvalContext ctx) {
      Object[] args = factResolver.resolve(method.getParameters(), ctx, /*decision*/ null);
      return (boolean) method.invoke(ruleBean, args);
  }
  ```
- **快照**:`RuleVersionSnapshot`,用 `RuleVersionSnapshot.builder()`,`conditionAst = Condition.of(conditionType, Map.of()).toAst()`;scene/code/version/trigger/decisions 从 `@RuleDef` 读(复用 `AnnotationRuleSource` 同款逻辑)。
- **metric 依赖声明**:扫 `@Condition` 参数,见 `@Metric` 即对快照构造器 `snapshotBuilder.addMetricDependency(code, version)` → 引擎据 `metricDependencies` 预拉(`EvalContextAssembler` 取候选快照 `metricDependencies` 并集为取数范围)。

starter 装配(注意是 `RuleEngineClient.Builder`,与上面的快照构造器不同):`clientBuilder.addEvaluator(condType, evaluator)` + `clientBuilder.ruleSource(new DslRuleSource(snapshots))`。

### 4.3 `FactResolver`(条件与动作共用)

```java
Object resolve(Parameter p, EvalContext ctx, Decision decision) {
    if (p.isAnnotationPresent(Metric.class)) {
        MetricValue mv = ctx.getMetric(p.getAnnotation(Metric.class).value());
        return mv == null ? null : coerce(mv, p.getType());
    }
    String name = p.getAnnotation(Fact.class).value();
    if (ctx.event().payload().containsKey(name)) return coerce(ctx.event().payload().get(name), p.getType());
    return metadata(name, ctx.event(), decision);  // eventId/tenantId/subjectId/occurredAt/decisionCode/priority/category
}
```
- `@Fact`:payload → 元数据(无 metric 回退)。
- `@Metric`:只取 metric;落空注入 `null`(Easy Rules fact 本可缺,方法自行判空)。
- `coerce` 负责类型转换;类型不符抛清晰异常。

### 4.4 动作侧:`DecisionDispatcher` + `DecisionFiredEvent`

```java
record DecisionFiredEvent(String decisionCode, int priority, String category,
                          RuleEvent event, EvalContext context) {
    public boolean decision(String code) { return decisionCode.equals(code); }
}
```

`DecisionDispatcher` 吃一次评估的 `EvalOutcome(result, context)`,**按 `result.hitDecisions()` 顺序**(策略已排好)为每个命中决策产出一个 `DecisionFiredEvent`,交给已注册 sink。两个 sink 并行都跑:
- **甲**:starter 注入 sink = `applicationEventPublisher::publishEvent` → 业务 `@EventListener` 收。顺序 `@Order`,异步 `@Async`。
- **乙**:`OnDecisionInvoker` sink,按 decisionCode 查 `@OnDecision` 方法表,`FactResolver` 注入 `@Fact`(此刻 `decision` 非 null,元数据可注入决策码/priority/category)后反射调用。

### 4.5 评估入口

适配器提供 `AnnotatedRuleEngine.fire(event)`(starter 包一层):内部走 `match()` + `evaluateWithContext` 拿 `EvalOutcome`,返回 `EvalResult` 给调用方,再交 `DecisionDispatcher` 派发。如此动作侧拿得到 `EvalContext`(metric 注入前提)—— 现有 `EvalResultListener.onResult(event, result)` 不带 context,故不复用它做动作分发。

### 4.6 数据流

`fire(event)` → 引擎算出 `EvalOutcome` → `@Condition`(经合成算子,注入 payload/metric)命中 → `hitDecisions` → `DecisionDispatcher` 逐决策发 `DecisionFiredEvent` → 甲(`@EventListener`)+ 乙(`@OnDecision` 注入 `@Fact`)各自处理。

## 5. 错误处理

| 出错点 | 处理 | 理由 |
|---|---|---|
| `@Condition` 方法抛异常 | 不吞,按引擎现有算子异常语义(`EvalResult.error(errorCode)` + trace),该规则不命中 | 条件求值是引擎职责,失败=结果不可信,不能假装没命中 |
| 动作侧 sink / `@OnDecision` 抛异常 | 吞 + `log.error`(决策码+处理器) + 继续下一个 | 副作用互不连坐 |
| `@Fact`/`@Metric` 类型不匹配 | 装配期能查的启动期快速失败;运行期按出错点归属上面两行 | 配置类错误越早暴露越好 |
| `@Fact`/`@Metric` 取值落空 | 注入 `null`,不报错 | fact 可缺,方法自行判空 |

## 6. 边界情况

- **无 `@Condition` / 多个 `@Condition`**:启动期校验,抛配置异常("恰好一个 @Condition")。多条件写在单方法体内;要可内省的原生组合走 `condition()` DSL。
- **`@RuleDef` 缺失**:静默跳过(同现有 `AnnotationRuleSource`)。
- **`conditionType` 撞名**:键含 `tenant:scene:code` 本就唯一;同坐标重复注册 → 启动期抛错。
- **`@OnDecision("X")` 但无规则产出 X**:启动期 `log.warn`(疑似笔误),不阻断。
- **决策命中但无处理器**:正常 no-op(决策可只记录、无动作)。
- **同一 decision 由多条规则命中**:`hitDecisions` 出现多次 → 处理器按命中次数触发多次;去重业务侧自理。
- **metric 未声明**:`@Metric` 漏标 → 不预拉 → 注入 null。`@Metric` 标注即声明,从机制上避免"用而不声明"。
- **`@Metric` 一个注解、两种角色(看标注位置)**:
  - 标在 `@Condition` 参数:**声明依赖(驱动预拉)+ 取值**。
  - 标在 `@OnDecision` 参数:**仅取值**(从评估已建好的 `EvalContext` 按名查;查不到给 `null`),**不驱动预拉**。
  - 依据:一次评估一个 `EvalContext`,且 `@Condition` 必在 `@OnDecision` 之前跑——条件侧已把该拉的 metric 拉好,动作侧同 context 直接复用即可,无需二次取数。动作要条件未拉的额外数据,自己在处理器内取(进程内业务代码,本可调 service/DAO)。**不**为动作驱动预拉(否则动作的 metric 需求要塞进所有产出该决策的规则快照,把动作关注点焊回规则,破坏解耦)。
- **非 Spring 用户**:直接 `new DecisionDispatcher(sink)` + 自定义 sink 拿 `DecisionFiredEvent`;`@OnDecision`/`@EventListener` 是 starter 特性,纯 SDK 不强依赖。

## 7. 取舍(固有代价)

- **条件不透明**:`@Condition` 是黑盒 Java 方法,引擎只见一个布尔叶子 → 丧失 AST 校验 / 前端可视化 / 服务端下发管理。可接受(嵌入式代码规则);要这些能力就用 DSL / 服务端规则。两条腿并存,不互相替代。
- **metric 必须显式声明**:黑盒方法体引擎看不见,无法自动发现 metric 引用;`@Metric` 声明是预拉的前提,这一步代码替代不了。

## 8. 测试策略

遵循项目测试纪律(`mvn-env` 设环境,`$MVN -pl <module> -am test`,跨模块带 `-am`,收尾 `clean test`):

- **单元(rule-sdk)**:
  - `FactResolver`:`@Fact` payload 命中 / 回退元数据 / 落空 null;`@Metric` 命中 / 落空 null;类型转换 / 类型不符报错。
  - `AnnotatedRuleScanner`:`@Condition` → 算子+快照正确;`@Metric` → `addMetricDependency` 正确;缺/多 `@Condition` 报错;`@RuleDef` 缺失跳过;conditionType 撞名报错。
  - `DecisionDispatcher`:按 hitDecisions 顺序发事件;多 sink 都跑;一个 sink 抛异常不连坐;空 hitDecisions 不发。
- **集成(starter / samples)**:偶数例子做样例规则 —— `@Condition isEven(@Fact number)` + 甲 `@EventListener` + 乙 `@OnDecision`,经 `RuleEngineClient` 端到端跑,断言两 sink 都触发、`@Fact`/`@Metric` 注入正确、动作异常被吞。补一个带 `@Metric` 的规则验证预拉→注入闭环。
- **功能 e2e**:嵌入式纯进程内、无 DB schema / 落库链路改动,**不涉及** CLAUDE.md "配置→运行→落库"功能测试纪律,集成测试绿即可。

## 9. 决策日志条目草案(D61)

> 待评审通过后追加到 `docs/00-decisions.md` 汇总表。

**D61 SDK Easy Rules 风格注解规则(`@Condition`/`@Fact`/`@Metric` + 决策事件) | A**

SDK 注解模式(D40)加糖:规则 POJO 用 `@Condition` 单布尔方法表达条件(`@Fact` 注入 payload/元数据、`@Metric` 注入并声明 metric 依赖,`version() default 1`),`AnnotatedRuleScanner` 扫描后包成不透明 `ConditionEvaluator` + 建快照 + `addMetricDependency`,经现有 `SceneRuleIndex`/`EvalEngine` 评估。"命中后做什么"**按 decision code 解耦、不挂规则**:甲 `DecisionFiredEvent` + `@EventListener`、乙 `@OnDecision` + `@Fact` 注入,两者由 `DecisionDispatcher` 按 `hitDecisions` 驱动(跟评估策略,不写死);动作异常吞+记日志续跑。**严格遵守 D60**:引擎不执行动作,动作全在消费方(SDK 进程内),本决策只在 SDK/starter 层加开发体验,不回退纯决策化。**取舍**:`@Condition` 黑盒化丧失 AST 内省/可视化/服务端下发(要这些走 DSL,两条腿并存);metric 必须 `@Metric` 显式声明才预拉。`rule-sdk` 保持纯 Java,Spring 装配(bean 收集 / 事件桥接)全在 starter。设计见 `specs/2026-06-12-annotation-rule-easyrules-style-design.md`。
