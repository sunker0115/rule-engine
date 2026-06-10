# rule-engine — 通用规则引擎产品

本仓库是**通用规则引擎产品**的设计与实现库,从 `cpt/integrate-service` 的 `docs/rule-engine/` 抽离独立。

## 目录划分

| 目录 | 职责 | 修改原则 |
|---|---|---|
| `docs/` | 产品设计:`00-decisions` / `01-concepts` / ... / `10-api-contract` + `examples/` | 改前用 `doc-consistency-review` skill 扫文档自洽性 |
| `rule-*/`（Maven 多模块） | 代码实现。核心:`rule-kernel`(纯 Java SPI+模型+求值内核,无 Spring,GraalVM native 硬约束)、`rule-config-svc`(配置写:scene/rule/metric/binding CRUD + 发布)、`rule-eval-svc`(评估+取数+action 派发+异步落库)、`rule-api`(HTTP `/admin·api·sdk/v1`)、`rule-app`(装配 + Modulith 边界 + 启动);辅助:`rule-observability`/`rule-audit-svc`/`rule-job-svc`/`rule-job-xxl`/`rule-sdk(-spring-boot-starter)`/`rule-mybatis-native`/`rule-benchmark` | 改动审查派 `rule-engine-reviewer` agent |

## 专用 review agent(只读)

- **改 `docs/**` 或 `rule-*/` 代码** → 显式调用 `rule-engine-reviewer` 审"代码 ↔ 文档对齐"。该 agent 仅在显式调用时启用,不要主动触发。

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
   - **DB 列用 VARCHAR，不用 MySQL `ENUM` 类型**（ENUM 加值要 ALTER + 与 app 双重定义；VARCHAR 后允许值校验上移 app，单一真相源在 enum，见 V1_11）。
   - **实体字段用 Java enum**（枚举名 == varchar 值，MyBatis-Plus 默认按 name 转换）；**出 VO/DTO/API 契约边界 `.name()` 转 String**（对外契约保持 String 不变，代码内是 enum）。

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
- **跨模块改动必须带 `-am`**：否则用 `~/.m2` 里的旧 jar，出 `NoSuchMethodError` / 编译假象。一轮改动最终用全量 `$MVN clean test`（无 `-pl`）兜底——只有 `clean` 才强制重编译所有 test 类，增量编译会漏掉过期 test。
- 有测试失败不得用 `-DskipTests` 绕过，必须修复后再提交。

## 功能测试纪律(集成测试通过后)

单测 / 集成测试绿之后,**涉及配置→运行→落库链路或 DB schema 的改动**,还要起真实服务走一遍 API 端到端功能测试(单测 mock 不掉的"真落库 / 真持久化快照 / 真副作用"问题在这里暴露)。通用流程:

1. **起服务**:用打包产物运行(多模块项目打可执行包后直接跑,**别用 reactor 内的 run 目标**——会跑到聚合工程报"找不到启动入口");启动时确认依赖迁移 / 初始化执行完成、服务就绪后再调。
2. **盘现状**:查数据存储(迁移版本 + 各表数据量)+ 经接口列已有配置,定位缺什么。
3. **补配置(API)**:缺的实体经接口按**依赖顺序**建——被引用的资源须先存在 / 达到可用状态,否则发布 / 提交期校验会拒。
4. **写后核对真落库**:每一步写操作后查持久层,确认应落库的派生数据 / 快照 / 冻结值**真写进去了**,不是占位 / 默认值。
5. **核对端到端链路 + 副作用**:走运行时入口验证输出正确;异步 / best-effort 副作用也要查持久层确认落库(失败 / 跳过态也算正确落库)。
6. **DB 字段落库审计**:对有数据的表逐表查恒空字段,分类——**遗漏(bug,必修)** / 不用了 / 设计如此(开关默认关等) / 数据使然(测试数据没覆盖该分支)。本轮改动点要**专门验证**(新增列是否真落库、新校验是否真拦截、新审计字段是否真捕获)。
7. **清理测试数据**:验证完删掉本次为测试新建 / 改动的数据,把环境恢复到干净基线(参考样例的预期状态),不留脏数据污染后续。

参考端到端剧本见 [docs/examples/](./docs/examples/) 下的样例(可直接复制的 curl 脚本 + 预期结果 + 清理)。

## 文档纪律

- `00-decisions.md` 是单一决策日志,新决策追加,不改历史条目状态以外的内容。
- 跨文档改动(如同时动 `01-concepts` 和 `02-runtime`)前先跑 `doc-consistency-review` skill。
- `docs/examples/` 是规约示例,改示例需同步对应章节。
