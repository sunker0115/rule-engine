# 借鉴 ice:TIME_WINDOW 前置门 + 可解释 blocked + 单行 trace 日志

## Context

读开源规则引擎 ice(waitmoon/ice)后,确认 3 个设计点值得借鉴到本项目,且都能精确落在既有抽象上、零多余新抽象:

1. **① 规则生效时段**:ice 每个节点带时间窗(窗口外跳过)。本项目缺"规则到点自动生失效"——目前只能人工 publish/disable 或把时间塞进 AST。我们用**现有 PreGate 前置门框架**(`ROLLOUT` 灰度门的同款机制)落一个内置 `TIME_WINDOW` 门,而非新增 DB 列/改 Matcher(方案 A)。代价:改时段走发版(可接受,活动时段属规则定义一部分)。
2. **② 为什么没参与可解释**:ice 用 NONE 态区分"跳过"vs"判假"。本项目**无需新状态**——PreGate 拦截天然落 `evaluation_session.blocked_by` + `status=BLOCKED`,`blockedBy="TIME_WINDOW"` 即"不在生效时段"的可解释信号。基本零改动。
3. **③ 单行 trace 日志**:ice 的 `ProcessUtils` 把执行轨迹拍平成一行塞日志,便于线上 grep。本项目已有结构化 `NodeTrace` 树(落 `node_trace` 表),但无压缩单行日志视图。补一个纯格式化器 + 两入口 debug 日志。

## 关键设计决策

- **时段判断用引擎统一时刻 `now`,不用 `event.occurredAt()`**:`EvalEngine.evaluate0` 的 `now`(常规=`Instant.now()`,重放=历史 `evalNow`,asOf=显式时刻)是引擎权威时刻;用它保证重放/asOf 窗口判断可复现。禁止在门内调 `Instant.now()`。
- **params 用 epoch millis `Long`**(键 `fromEpochMilli`/`toEpochMilli`),typed 视图 record `TimeWindowParams` 解析,不裸读 Map。闭区间 `[from,to]`;单边可空;两者皆空 → fail-open 放行。
- **gateType 字符串字面量 `"TIME_WINDOW"`**(对齐 SPI 开放标识约定)。
- **③ 开关用 SLF4J `log.isDebugEnabled()`**;`RuleEvent` 不加 debug 字段。单行视图依赖 trace 已收集(eval-svc 默认开,SDK 默认关→空树无害)。

## 改动清单

### 阶段 1 — kernel 基座(改 record 签名,跨模块重编译)
- 改 `PreGateContext.java`:加 `Instant occurredAt`(唯一破坏性签名变更)。
- 改 `EvalEngine.java`:`applyPreGates` 加 `Instant now` 形参;调用处传 `now`;构造 `PreGateContext` 补 `occurredAt`。
- 新建 `rule-kernel/.../api/trace/NodeTraceFormatter.java`(③):纯 Java,`compact(List<NodeTrace>)` 递归拍平。
- 改 `PreGateContextTest` 构造桩;新建 `NodeTraceFormatterTest`。

### 阶段 2 — eval-svc / config-svc
- 新建 `rule-eval-svc/.../internal/pregate/TimeWindowPreGate.java`:`@Component`,自动收集,不改 AutoConfiguration。
- 新建 `rule-config-svc/.../internal/publish/TimeWindowParams.java`(仿 RolloutParams)。
- 改 `PublishService.validatePreGateParams`:放行 TIME_WINDOW 并校验 `from<=to`。
- 改 `RolloutPreGateTest` 构造桩;新建 `TimeWindowPreGateTest`;PublishService 校验测试。
- 改 `EvalServiceImpl`:补 logger + 单行 trace debug 日志(③)。

### 阶段 3 — SDK / 收尾
- 改 `RuleEngineClient.java`:补 logger + 单行 trace debug 日志(③)。
- ② 无独立代码改动(复用 blocked_by→BLOCKED 链路);可选加 TIME_WINDOW blockedBy 用例。

## ③ 单行 trace 格式规约
- 叶子:`<conditionType>:<metricCode>:<R>`,错误 `<conditionType>:<metricCode>:E:<errorCode>`,R∈{T,F}。
- 容器:`<nodeType>:<R>[<child>,...]`,R∈{T,F,-}。
- 每棵树前缀 `<ruleCode>v<ruleVersion>=`;多命中空格分隔;空树 `[]`。
- StringBuilder 递归。
- 示例:`PROMO_A v3=AND:T[GT:order_amount:T,IN:user_level:T]`

## 验证
1. `$MVN -pl rule-kernel,rule-eval-svc,rule-config-svc,rule-sdk -am test`。
2. 全量兜底 `$MVN clean test`(改了 PreGateContext record 跨模块)。
3. 端到端:起服务→配 TIME_WINDOW 规则发布→窗口外评估→查 `evaluation_session` status=BLOCKED/blocked_by=TIME_WINDOW 真落库→改窗口含当前再评估正常→debug 日志确认单行 trace→清理测试数据。
4. 发布期:`from>to` 的 TIME_WINDOW 发布应被拒。
