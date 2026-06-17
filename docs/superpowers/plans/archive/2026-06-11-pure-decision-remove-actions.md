# 规则引擎纯决策化 — 移除动作子系统(D60)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把规则引擎收敛为纯决策引擎——彻底移除动作(Action)子系统(SPI / 注解 / 模型字段 / 派发 / 落库 / 配置 / DDL / 文档),保留决策输出口(`evaluate()` + SDK listener)与 PULL/PUSH 双模(PUSH 去派发)。

**Architecture:** 自顶向下删除(先删 action 的消费方,再删核心类型,使每步可编译):① eval-svc 派发 → ② config/api 动作配置 → ③ kernel 模型字段 + 内部构建 → ④ kernel SPI/注解/类型 → ⑤ DB 迁移 → ⑥ 文档(D60 取代 D16/D18/D27/D28/D57)→ ⑦ 全量验证。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / Flyway。greenfield 无兼容包袱。

**前置环境:** 跑 mvn 前用 `mvn-env` skill 设 `$MVN`(JDK 25)。跨模块改动带 `-am`;一轮收尾 `$MVN clean test` 兜底。注释中文、测试方法名英文。删除时**禁用** `-DskipTests`;有失败先修。

**贯穿原则**
- 每个 Task 删完 → 该模块(及下游)`-am test` 全绿才提交。
- 删字段/类型时,**grep 全仓引用**,一次清干净(含测试),不留半挂。
- 不新建任何替代机制(编排交流程引擎,YAGNI)。

**关键事实(已查实)**
- `EvalResult` 含 `List<ActionResult> actionResults`(第 11 字段)。
- `Decision` 含 `List<RuleVersionSnapshot.DecisionAction> actions`(末字段)。
- `RuleVersionSnapshot` 含嵌套 record `DecisionAction` 和 `DecisionBinding(decisionCode, name, priority, List<DecisionAction> actions)`。
- 构建 Decision 含 actions 的点:`DecisionTreeExecutor:158`、`DecisionTableExecutor:125`、`EvalEngine:237-238`(均 `..., b.actions())`);`DecisionTreeExecutor:76` 用 `branch.actionResults()` 拼 EvalResult。
- `EvalServiceImpl`:字段 `ActionCommandChannel actionDelivery` + `EvalActionDispatcher dispatcher`(行 33/35/47/52),import `DispatchActionsCommand`。
- `DecisionDefinition`(config)含 `@TableField List<DecisionAction> actions`(行 26-28)+ import。
- eval-svc 动作文件(整删):`internal/action/{ActionDispatchService,SendAlertHandler,SendAlertProperties,ActionExecutionPersister}.java`、`internal/dispatch/EvalActionDispatcher.java`、`internal/async/{ActionCommandChannel,ActionExecutedEvent,DispatchActionsCommand,InProcessAsyncCommandChannel}.java`、`internal/domain/ActionExecutionEntity.java`、`internal/repository/ActionExecutionMapper.java`(+ 对应 XML 若有)。
- kernel 动作类型(整删):`api/spi/action/ActionHandler.java`、`api/annotation/ActionType.java`、`api/model/ActionContext.java`、`api/model/ActionResult.java`。
- 最新迁移 `V1_27`(上一条 V1_26)。
- `AstJsonCodec` 已 disable `FAIL_ON_UNKNOWN_PROPERTIES`(对未知字段宽容)→ 存量 `rule_version.decision_bindings` JSON 里残留的 `actions` 字段反序列化时被忽略,安全;无需清库(Task 3 Step 0 验证)。

---

### Task 1: 删 eval-svc 动作派发子系统

**Files:**
- Delete: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/`(整目录 4 文件)、`internal/dispatch/EvalActionDispatcher.java`、`internal/async/ActionCommandChannel.java`、`internal/async/ActionExecutedEvent.java`、`internal/async/DispatchActionsCommand.java`、`internal/async/InProcessAsyncCommandChannel.java`、`internal/domain/ActionExecutionEntity.java`、`internal/repository/ActionExecutionMapper.java`(+ 同名 mapper XML 若存在)
- Modify: `internal/service/EvalServiceImpl.java`、`EvalAutoConfiguration.java`、`internal/retention/SessionRetentionCleaner.java` + `RetentionProperties.java`
- Delete tests: eval-svc 下 `*ActionDispatch*Test`、`*SendAlert*Test`、`ActionExecution*Test`、`DoEvaluateEmitsEventsTest` 中 action 断言、`InProcessAsyncCommandChannelTest` 等

- [ ] **Step 1: 先确认 `InProcessAsyncCommandChannel` 是否仅服务 action**

Run: `grep -rn "InProcessAsyncCommandChannel\|ActionCommandChannel\|DomainEventPublisher" rule-eval-svc/src/main/java | grep -v target`
预期:`ActionCommandChannel` 仅被 action 派发用 → 整删。若 `InProcessAsyncCommandChannel` 也承载 dry-run/审计(C 类 `DomainEventPublisher`)的旁路,则**只摘 action 分支,保留其余**,本步据实调整删除清单。

- [ ] **Step 2: 删整文件 + 改 EvalServiceImpl**

删上面列出的文件。`EvalServiceImpl` 去掉:import `ActionCommandChannel`/`DispatchActionsCommand`/`EvalActionDispatcher`;字段 `actionDelivery`/`dispatcher`;构造器里创建 dispatcher 的行(`this.dispatcher = new EvalActionDispatcher(...)`)与 `dispatcher.start()`;`doEvaluate` 内向 `actionDelivery` 投递 `DispatchActionsCommand` 的调用。PUSH 路径保留(仍 `doEvaluate(e, PUSH, ...)` 评估 + 落库),只是不再派发。
`EvalAutoConfiguration` 去掉 `ActionDispatchService`/`SendAlertHandler`/`SendAlertProperties`/`ActionCommandChannel` 等 Bean 装配。
`SessionRetentionCleaner` / `RetentionProperties` 去掉 `action_execution` 表清理项。

- [ ] **Step 3: 编译 + 修测试**

Run: `$MVN -pl rule-eval-svc -am test-compile`
按报错删/改引用了已删类的测试;`DoEvaluateEmitsEventsTest` 去掉 action 事件断言(保留评估/审计断言)。
Run: `$MVN -pl rule-eval-svc -am test` → 全绿。

- [ ] **Step 4: Commit**

```bash
git add rule-eval-svc/src
git commit -m "refactor(eval): 移除动作派发子系统(dispatcher/handler/落库),PUSH 仅评估不派发"
```

---

### Task 2: 删 config/api 动作配置

**Files:**
- Modify: `rule-config-svc/.../internal/domain/DecisionDefinition.java`、`api/service/DecisionService.java` + `internal/service/DecisionServiceImpl.java`、`api/service/MetadataService.java` + `internal/service/MetadataServiceImpl.java`、`api/dto/RuleBundle.java` + `RuleImportResult.java`、`internal/bundle/RuleExportService.java` + `RuleImportService.java`、`internal/publish/PublishService.java`、`rule-api/.../web/admin/DecisionController.java` + `MetadataController.java`

- [ ] **Step 1: DecisionDefinition 去 actions**

`DecisionDefinition.java` 删字段 `actions`(行 26-28)+ import `RuleVersionSnapshot.DecisionAction`。

- [ ] **Step 2: DecisionService / Controller 去 actions**

`DecisionService` 接口的 `DecisionRequest`/出参去 `actions`;`DecisionServiceImpl` 去读写 actions;`DecisionController` 的请求/响应 DTO 去 `actions`。grep 定位:`grep -rn "actions" rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/DecisionService.java rule-api/src/main/java/com/sstlfsj/rule/web/admin/DecisionController.java`。

- [ ] **Step 3: MetadataService 去 actionTypes**

`MetadataService.MetadataResponse` 删 `actionTypes` 列表 + 删 record `ActionTypeMeta`;`getSceneMetadata` 返回 `new MetadataResponse(conditionTypes, metricMetas)`(去中间 actionTypes 参数);`MetadataServiceImpl` 行 57 同步;`MetadataController` 响应同步。

- [ ] **Step 4: RuleBundle / import-export / PublishService 去 actions**

`RuleBundle` / `RuleImportResult` 去 actions 字段;`RuleExportService` / `RuleImportService` 去 actions 导入导出;`PublishService` 去掉把 `decision.actions` 冻结进快照 `DecisionBinding` 的逻辑(发布时 `addDecisionBinding` 不再带 actions)。

- [ ] **Step 5: 编译 + 测试 + Commit**

Run: `$MVN -pl rule-config-svc,rule-api -am test`(按报错修测试)→ 全绿。
```bash
git add rule-config-svc/src rule-api/src
git commit -m "refactor(config): 移除 decision/metadata/bundle 的 action 配置"
```

---

### Task 3: 删 kernel 模型 action 字段 + 内部构建

**Files:**
- Modify: `rule-kernel/.../api/model/Decision.java`、`EvalResult.java`、`RuleVersionSnapshot.java`、`internal/engine/EvalEngine.java`、`internal/evaluator/DecisionTreeExecutor.java`、`internal/evaluator/DecisionTableExecutor.java`(+ 任何 branch/leaf 内部类型携带 actions/actionResults 处)

- [ ] **Step 0: 验证存量 JSON 反序列化宽容**

Run: `grep -n "FAIL_ON_UNKNOWN_PROPERTIES" rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/AstJsonCodec.java`
预期:已 `disable(...)`(忽略未知字段)。则 `rule_version.decision_bindings` 旧 JSON 残留 `actions` 被忽略,无需清库。若未 disable,在本 Task 补 `.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`(仅 decision_bindings 反序列化需要)或清库重建——据实择一并在 commit message 注明。

- [ ] **Step 1: Decision 去 actions**

`Decision.java` 删末字段 `List<RuleVersionSnapshot.DecisionAction> actions`、紧凑构造里的 `actions = ...` 归一行、所有便利构造的 actions 传参。结果 record:
```java
public record Decision(
        String code, String name, int priority,
        Long fromRuleVersionId, String fromRuleCode, long fromRuleVersion,
        String category
) {}
```
(保留现有便利构造的"短"形态,去掉 actions 形参/转发。)

- [ ] **Step 2: EvalResult 去 actionResults**

`EvalResult.java` 删字段 `List<ActionResult> actionResults`(及紧凑构造归一、`import ActionResult`)。结果:
```java
public record EvalResult(
        boolean ruleHit, Decision finalDecision, List<Decision> hitDecisions,
        List<NodeTrace> nodeTrace, String errorCode, Double score,
        String category, String decision
) { ... }
```
所有静态工厂(`hit()`/`miss()`/`error()` 等)去掉 actionResults 实参。

- [ ] **Step 3: RuleVersionSnapshot 去 DecisionAction / DecisionBinding.actions**

删嵌套 record `DecisionAction`;`DecisionBinding` 去 `actions` 字段(结果 `DecisionBinding(String decisionCode, String name, int priority)`),去其紧凑构造的 actions 归一与"仅 (decisionCode, priority)"便利构造里的 actions。

- [ ] **Step 4: 修内部构建点**

`DecisionTreeExecutor:158` / `DecisionTableExecutor:125` / `EvalEngine:237-238`:`new Decision(..., b.actions())` → 去掉末尾 `b.actions()` 实参(用去 actions 后的构造)。`DecisionTreeExecutor:76`:EvalResult 构造去掉 `branch.actionResults()` 实参;若 branch/leaf 内部类型(IfBranch 等)带 `actions()`/`actionResults()`,一并删该字段。grep 兜底:`grep -rn "\.actions()\|actionResults\|DecisionAction\|ActionResult" rule-kernel/src/main/java | grep -v target`。

- [ ] **Step 5: 编译 + 测试 + Commit**

Run: `$MVN -pl rule-kernel -am test`(修 kernel 测试里构造 Decision/EvalResult/DecisionBinding 的 actions 实参)→ 全绿。
```bash
git add rule-kernel/src
git commit -m "refactor(kernel): Decision/EvalResult/DecisionBinding 移除 action 字段与内部构建"
```

---

### Task 4: 删 kernel action SPI / 注解 / 类型

**Files:**
- Delete: `rule-kernel/.../api/spi/action/ActionHandler.java`、`api/annotation/ActionType.java`、`api/model/ActionContext.java`、`api/model/ActionResult.java`(+ 空目录 `api/spi/action/` 删除)
- Delete tests: `ActionTypeTest.java`、`ActionContextTest`/`ActionResultTest` 若有

- [ ] **Step 1: 确认无残留引用**

Run: `grep -rn "ActionHandler\|@ActionType\|ActionContext\|ActionResult\|api.annotation.ActionType\|api.spi.action" rule-*/src --include=*.java | grep -v target`
预期:仅这 4 个文件自身 + 各自测试。若仍有业务引用,回到 Task 1-3 补删。

- [ ] **Step 2: 删文件 + 测试**

删上述 4 个主文件 + 对应测试。

- [ ] **Step 3: 编译 + 测试 + Commit**

Run: `$MVN -pl rule-kernel -am test` → 全绿。
```bash
git add rule-kernel/src
git commit -m "refactor(kernel): 删 ActionHandler/@ActionType/ActionContext/ActionResult"
```

---

### Task 5: DB 迁移 V1_27 — drop action_execution + decision_definition.actions

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_27__drop_action_subsystem.sql`

- [ ] **Step 1: 写迁移**

```sql
-- 纯决策化(D60):移除动作子系统的存储
DROP TABLE IF EXISTS action_execution;
ALTER TABLE decision_definition DROP COLUMN actions;
```

- [ ] **Step 2: 验证 Flyway 应用**

Run: `$MVN -pl rule-app -am test -Dtest=HighRiskLoginScenario -Dsurefire.failIfNoSpecifiedTests=false`(Testcontainers + Flyway 跑全链 V1_27)→ BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_27__drop_action_subsystem.sql
git commit -m "feat(db): V1_27 drop action_execution + decision_definition.actions"
```

---

### Task 6: 文档 — D60 + 清除 action 段落

**Files:**
- Modify: `docs/00-decisions.md`、`docs/04-extension.md`、`docs/01-concepts.md`、`docs/02-runtime.md`、`docs/05-storage.md`、`docs/10-api-contract.md`

- [ ] **Step 1: 00-decisions 追加 D60**

append D60「规则引擎纯决策化,移除动作子系统」:理由(对标 OPA/DMN,编排交流程引擎/Flowable)、范围(删 SPI/注解/模型/派发/落库/DDL)、**显式取代 D16 / D18 / D27 / D28 / D57**、保留(决策输出口 + PULL/PUSH,PUSH 去派发);汇总表加 D60 行。被取代条目正文不改(append-only)。

- [ ] **Step 2: 04-extension 删 §三(ActionType)**

删整节「加 ActionType」+ 文档状态表对应行;保留 §二(ConditionType)/§四(MetricSource)/§九(@RuleDef)。

- [ ] **Step 3: 其余文档清 action**

`01-concepts` / `02-runtime`:删 Action 派发、Decision.actions、链式触发(D16)相关段。`05-storage`:删 `action_execution` 表 DDL、`decision_definition.actions` 列。`10-api-contract`:评估响应去 `actionResults`、decision 配置去 actions、metadata 去 actionTypes。

- [ ] **Step 4: 跨文档自洽**

改前/后跑 `doc-consistency-review` skill 扫一遍这批改动。

- [ ] **Step 5: Commit**

```bash
git add docs/
git commit -m "docs: D60 规则引擎纯决策化(取代 D16/D18/D27/D28/D57)+ 清除 action 文档"
```

---

### Task 7: 全量兜底 + 端到端 + 审查

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test` → 全模块 BUILD SUCCESS。

- [ ] **Step 2: 残留扫描**

Run: `grep -rn "ActionHandler\|@ActionType\|ActionContext\|ActionResult\|actionResults\|decision.*\.actions\|action_execution\|SEND_ALERT\|SendAlert\|EvalActionDispatcher" rule-*/src/main/java rule-config-svc/src/main/resources | grep -v target`
预期:空(或仅无害的历史字符串)。

- [ ] **Step 3: 端到端**

起 rule-app(打包产物),PULL `/api/v1/rule/evaluate` 一条规则,确认响应 `data` **无 `actionResults`**、`finalDecision` **无 `actions`**,`node_trace` 落库正常;SDK `SdkLocalDemo` 实跑出决策。验完清理测试数据。

- [ ] **Step 4: rule-engine-reviewer 审查**

显式调用 `rule-engine-reviewer` agent,审 D60 与代码/各文档对齐、被取代决策标注一致。

---

## Self-Review

**Spec 覆盖:**
- §三 kernel(SPI/注解/模型/executor)→ Task 3 + Task 4 ✅
- §三 eval-svc(派发/落库/retention)→ Task 1 ✅
- §三 config/api(decision/metadata/bundle/publish)→ Task 2 ✅
- §三 DB(action_execution / decision_definition.actions)→ Task 5 ✅
- §三 文档(D60 + 清各文档)→ Task 6 ✅
- §二 保留(决策出口 / PULL-PUSH)→ Task 1 Step 2 明确保留 PUSH 评估、不删 EvalResultListener(本计划全程不碰 SDK 的 listener)✅
- §五 风险(JSON 宽容 / async channel 复用)→ Task 3 Step 0 + Task 1 Step 1 ✅
- §七 验收 → Task 7 ✅

**占位符扫描:** 模型 record 给了删后完整形态;删除文件给了精确清单;call-site 给了精确行号 + grep 兜底。Task 2/6 的部分 DTO 字段删除以 grep 定位(字段名 `actions`/`actionTypes` 明确),非 TODO。

**类型一致性:** `Decision`(去 actions,留 code/name/priority/from*/category)、`EvalResult`(去 actionResults)、`DecisionBinding`(去 actions)删后形态在 Task 3 一次定义,Task 4 删的 4 个类型在 Task 1-3 已无引用后才删——顺序自洽。

**诚实标注:** Task 1 的 `InProcessAsyncCommandChannel`、Task 3 Step 0 的 codec 宽容,均为"先验证再据实删/补"的实现期分支,已在步骤里写明判定方式,非遗留歧义。Task 3 Step 4 的 branch/leaf 内部类型(IfBranch 等)是否带 actions 用 grep 兜底——实现期按编译报错清。
