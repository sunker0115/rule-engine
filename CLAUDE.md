# rule-engine — 通用规则引擎产品

本仓库是**通用规则引擎产品**的设计与实现库,从 `cpt/integrate-service` 的 `docs/rule-engine/` 抽离独立。

## 目录划分

| 目录 | 职责 | 修改原则 |
|---|---|---|
| `docs/` | 产品设计:`00-decisions` / `01-concepts` / ... / `10-api-contract` + `examples/` | 改前用 `doc-consistency-review` skill 扫文档自洽性 |
| `src/`(待建) | 代码骨架,按 `docs/09-skeleton.md` 规划落地 | 改动审查派 `rule-engine-reviewer` agent |

## 专用 review agent(只读)

- **改 `docs/**` 或将来的 `src/**`** → 显式调用 `rule-engine-reviewer` 审"代码 ↔ 文档对齐"。该 agent 仅在显式调用时启用,不要主动触发。

## 代码注释规范

覆盖全局 CLAUDE.md 的"默认不写注释"规则。

- **Javadoc**：所有 `public` 接口、SPI 接口方法、AutoConfiguration 类必须写 Javadoc。说明"做什么 + 参数含义 + 返回值语义"，一句话能说清楚的不要写多行。
- **实现注释**：方法体内有非显而易见的逻辑（算法选择、并发约束、业务规则引用）时加单行注释说明 why，不要描述 what（代码本身已经说明）。
- **record / 枚举**：类级别 Javadoc 说明用途，字段不需要单独注释（名字已自明）。
- **禁止**：TODO/FIXME 注释不得出现在提交代码中；不写"added for X"/"used by Y"类追溯性注释。

## 数据类型与边界规范（强制）

承载结构化数据的字段——DTO、service 接口入参/出参、API 请求体、领域实体的 JSON 列——**一律用已定义的具体类型**，**禁止 JSON String，禁止裸 `Object`，禁止用 `Map` 当万能容器**。

1. **优先具体类型**：能引用已定义的 record/类（如 `AstNode` / `List<DecisionBinding>` / `List<PreGateConfig>` / `RuleKind`）就用它，不要降级成 `Map`/`Object`/`String`。
2. **`Map<String, Object>` 仅在「确实无定义」时用**：即结构开放/异构、没有也不该有固定类型的场景（如 action `default_params` 依 actionType 而异）。这是唯一例外，不是默认选项。
3. **实体 JSON 列以 `RuleVersion` 为模板**：`@TableName(autoResultMap = true)` + 字段为 typed（`AstNode` / `List<...>`）+ `@TableField(typeHandler = Jackson3TypeHandler.class)`。JSON↔对象由 MyBatis TypeHandler 在持久层完成，**实体/service/controller 内一律不手写 ObjectMapper 序列化、不传 JSON String**。
4. **请求参数同样适用**：controller 收 typed 请求 DTO，直传 service 的 typed 入参；不得 typed→String→typed 来回。
5. **DTO ↔ service/实体 转换走 MapStruct**（`web/admin/convert/` 包），不在 controller/service 内联手写（字段极少的一次性映射可手写，见全局 §5）。
6. **封闭取值用 enum，不用魔法字符串**：状态 / 生命周期 / 类型判别等**取值有限且封闭**的字段（如 rule/scene/metric 的 status `DRAFT`/`ACTIVE`/`PUBLISHED`/`DISABLED`/`SUPERSEDED`、`actorType`、`kind` 等），一律定义 enum（或既有常量集），**禁止散落字符串字面量**。仅当集合**开放可扩展**（如 conditionType / actionType 走 SPI）时才用常量类而非 enum。

新增/改动相关代码时以本节为准；既有 String/Object/Map/魔法字符串违例在触及时顺手收敛。

## 副作用与事件解耦（强制）

一次操作里，**主业务写**之后的**非业务副作用**（审计日志、索引刷新、通知、缓存、派生数据）**一律走事件**，不在主业务方法里内联堆叠。事件按用途分三类，每类的（命名 / 归属包 / 发布 / 监听）**钉死一致，不得各搞一套、不得多入口**：

| 类别 | 用途 | 事件归属包 | 发布 | 监听 | 一致性 |
|---|---|---|---|---|---|
| **A 跨模块集成** | producer 提交后通知别的模块（config→eval 索引热更） | producer 的 `api/event`（公开契约） | `ApplicationEventPublisher` | `@ApplicationModuleListener`（Modulith，提交后异步） | 最终一致 |
| **B 同模块强一致副作用** | 须与主业务同事务原子（`audit_log` D14 红线） | 本模块 `internal/event` | `ApplicationEventPublisher` | `@TransactionalEventListener(BEFORE_COMMIT)` | 同事务 |
| **C 同模块异步 best-effort** | 旁路可丢、未来可换 MQ（评估执行审计 / trace / action 落库） | 与 persister 同包（`internal/async`） | `DomainEventPublisher` 缝（进程内/MQ 各一实现） | persister `@EventListener` + 队列 | 旁路可丢 |

**贯穿规则**：
- **命名统一**：所有领域事件 `<聚合><过去式>Event`（`SceneChangedEvent` / `RulePublishedEvent` / `ActionExecutedEvent`…），不混用无后缀的 `XxxRecorded`/`XxxExecuted`。
- **一个聚合 / 一类副作用 = 一个事件 + 一个集中监听器**：不开多入口、不每个 service 各发各的私有逻辑（如各处私有 `writeAudit` 收敛成单一审计事件 + 单一落库监听器）。
- C 类的 `DomainEventPublisher` 是**有意的 transport 缝**（为未来 MQ），与 A/B 的 `ApplicationEventPublisher` 是不同类别的不同 transport，**不是重复，不要为"统一"合并掉**。
- 目的：主业务方法只表达业务意图；副作用集中到监听器，便于增删副作用而不动业务代码。

## 测试纪律

- 每个 Task 提交前必须运行该模块的所有测试，全部通过才能 commit。
- 使用 `mvn-env` skill 设置环境后执行：`$MVN -pl <module> -am test`
- 有测试失败不得用 `-DskipTests` 绕过，必须修复后再提交。

## 文档纪律

- `00-decisions.md` 是单一决策日志,新决策追加,不改历史条目状态以外的内容。
- 跨文档改动(如同时动 `01-concepts` 和 `02-runtime`)前先跑 `doc-consistency-review` skill。
- `docs/examples/` 是规约示例,改示例需同步对应章节。
