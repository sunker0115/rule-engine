# 参考项目与业界对标台账

> **文档定位**：把本项目（rule-engine）对外部项目的**结构对照**与**业界对标**收成单一活入口。以往这些记录散落在 `00-decisions.md` 决策条目、`08-evolution.md` 锚点、`specs/` 调研快照里，读者拼不出全貌；本台账做**汇总 + 链接**，不搬走历史（历史专档/决策条目不动）。
>
> **性质**：活文档，调研快照的汇总台账，**不是执行计划**。可吸收项一律回写 `08-evolution.md` 锚点 / `00-decisions.md` 决策，本台账只记"对照 + 吸收结论"。
>
> **对照维度（统一）**：定位 / 技术栈 / 核心能力 / 与本项目差异 / 吸收结论。
>
> **追加规范**：每调研一个外部项目，在「二、深度结构对照」追加一节（照「五、追加新项目模板」），并在「一、汇总速览」补一行；只在决策里点到、未逐模块拆的业界项目进「三、业界对标速览」；同步维护「四、吸收状态总览」的三桶归类。深度对照节是**调研快照**，不随对方代码更新。

---

## 一、汇总速览

| 项目 | 类型 | 一句话结论 | 主要落点 | 详见 |
|---|---|---|---|---|
| **trae_projects** | Java 风险决策引擎（Trae AI 协作，SDK/框架） | 业务完整性/可靠性/规范本项目远优；引擎核心实现（flow/script/context）有代码参考价值 | XOR 节点、脚本对象池、类型化比较策略工厂 | [§2.1](#21-trae_projects) |
| **skyhackvip/risk_engine（天网）** | Java 决策引擎（开源） | 二维决策矩阵是**录入便利**、非引擎能力；本项目 `DECISION_TABLE` 表达力已覆盖 | 决策矩阵录入 demo（引擎 0 改动）+ 顺带修 `BETWEEN` bug | [§2.2](#22-skyhackviprisk_engine天网) |
| **GoRules ZEN Engine** | Rust 决策引擎（JDM 决策图 + 表达式内核） | JDM 决策图=本项目 `DECISION_FLOW` 直接对标；其表达式 intellisense 是编辑期体验缺口的参照 | D75 `DECISION_FLOW`；表达式编辑器补全/诊断分级方案 | [§2.3](#23-gorules-zen-engine) |
| **gengine（bilibili）** | Go 嵌入式规则引擎（AST DSL + 执行内核） | 基因不同宗（命令式可副作用 DSL / 进程内库）；可吸收面小，唯一候选=场景内独立规则并行求值 | 候选记入 `08-evolution.md` §2.29（不实现） | [§2.4](#24-genginebilibili) |
| **RuleGo**（rulego.dev） | Go 组件编排规则引擎（规则链/流编排，类 node-red） | 类别不同（编排+动作 vs 纯决策）；是 D60「引擎不含动作/编排」边界的反例佐证，可吸收面小 | 分层已定 D60 / D75 / Flowable | [§2.5](#25-rulego) |

---

## 二、深度结构对照

### 2.1 trae_projects

> 详细快照见 [`specs/archive/2026-06-04-trae-reference-design.md`](./superpowers/specs/archive/2026-06-04-trae-reference-design.md)（本节为摘要）。

| 维度 | trae_projects | rule-engine |
|---|---|---|
| 定位 | SDK / 框架，偏底层引擎抽象 | 产品，完整业务平台 |
| 技术栈 | Java 21 / Spring Boot 4 / JPA / Groovy JSR-223 / Caffeine | Java 25 / Spring Boot 4 / Modulith / MyBatis-Plus / Caffeine |
| 完成度 | 代码有实现但多处未完成、部分模块缺源码 | 决策驱动，核心链路已实装 |

**核心能力**：`flow/`（FlowEngine + JsonFlowParser，7 种节点）、`rule/`（CompositeRule AND/OR/NOT/XOR/Sequence/Parallel/FirstMatch + ScriptCondition + 7 种类型化比较策略）、`context/`（6 级作用域 + 增量快照 + 30+ 事件）、`cache/`（装饰器缓存 + commons-pool2 对象池）。

**与本项目差异**：本项目在业务完整性、可靠性语义（不可变快照/审计/灰度）、工程规范上远优；trae 在引擎核心的具体实现上有代码参考价值。

**吸收结论**：

| 参考点 | 优先级 | 落点 |
|---|---|---|
| R1 XOR 逻辑节点（"恰好一个满足"） | 高·低成本 | `08-evolution.md` §2.21（AST 扩展期） |
| R2 脚本执行器 JSR-223 对象池（Groovy 秒级初始化 → commons-pool2 池化） | 中 | 已由 D66 六引擎装配吸收其思路（各引擎 `compile` 缓存） |
| R3 类型化比较策略工厂（按 dataType 路由，避免 if-else） | 中 | 已落地（`ComparisonStrategyFactory`，B19，kernel `internal/condition/strategy`） |
| R4 Flow 引擎（JSON 节点图 + Context 分层）| — | 同步图已由 D75 `DECISION_FLOW` 吸收（typed 节点 + 发布期冻结）；**有状态编排不自研**，D60/D75 已定接 Flowable |
| R5 Decorator 三级缓存键（Rule/Condition/Flow ExecutionKey） | 低·备忘 | 条件去重演进（§2.13 alpha 节点共享）参照 |

**不吸收**：6 级作用域 Context（v1 EvalContext 不可变 POJO 够用）、内部事件总线（用 Spring Modulith）、自研验证框架（用 Spring Validation）、引擎核心耦合 JPA 实体（kernel 零 Spring/DB）、`DecisionCode` 硬编码枚举（本项目 Decision 是业务配置）、Rule 实体层组合策略（本项目组合语义在 AST 表达）。

---

### 2.2 skyhackvip/risk_engine（天网）

> 对照结论见 [`specs/archive/2026-06-16-decision-matrix-demo-and-between-fix-design.md`](./superpowers/specs/archive/2026-06-16-decision-matrix-demo-and-between-fix-design.md)。

**核心能力**：独立的 `matrix`（交叉决策表）节点——X 轴分箱 × Y 轴分箱 → 输出格，专为稠密二维网格录入设计。

**与本项目差异**：本项目 `DecisionTableNode(columns, rows)`（FIRST_HIT，N 列）**表达力已覆盖且更通用**——二维矩阵 = 2 列 + m×n 行的特例。差异只在**录入体感**（稠密网格填表 vs 逐行枚举）。

**吸收结论**：**不新增引擎 kind**。矩阵是**录入糖**不是引擎能力——做一个丢弃式可视化 demo（矩阵视图 → 实时展开成现有 `DecisionTableNode` JSON）证明这一点即可。对照过程中顺带发现并修复真实潜伏 bug：决策表 `BETWEEN`/`NOT_BETWEEN` 列因 `buildParams` 缺分支而永不命中（`DecisionTableExecutor`）。

---

### 2.3 GoRules ZEN Engine

> JDM 对标见 [`00-decisions.md`](./00-decisions.md) D75；表达式 intellisense 对标见 [`specs/2026-07-20-expression-intellisense-analysis.md`](./superpowers/specs/2026-07-20-expression-intellisense-analysis.md)。

**核心能力**：① JDM（JSON Decision Model）——决策图 = Input→节点→Output 的 DAG，叶子是决策表、`Decision` 节点调子决策，图只编排、叶子是原子；② 表达式内核 `intellisense/`——补全（completion）、实时诊断（diagnostic）、类型推断（type_provider）、自然语言转表达式（nl）。

**与本项目差异**：JDM 与本项目 `DECISION_FLOW`（D75）同宗——都是同步无状态决策图、图只编排叶子复用。本项目更严：typed sealed 节点（`RuleRef`/`Switch`/`Transform`/`Output`）+ 发布期冻结被引规则 ACTIVE 快照 + metric 依赖并集 + 环/死节点静态检测；`Transform` 只允许既有 6 表达式引擎、禁任意副作用脚本（ZEN 有 QuickJS，本项目 D60 已纯决策化不跟）。表达式智能上本项目"引擎算得出但编辑期没用上"——`referencedVariables` / CEL `typeCheck` 已就位，缺的是喂给编辑器。

**吸收结论**：

| 对标点 | 落点 |
|---|---|
| JDM 决策图（DAG 编排） | 已落地 `DECISION_FLOW`（D75，第 6 种 rule kind） |
| 表达式补全（Level 1，纯前端零后端） | 建议做：Monaco CompletionItemProvider 喂 `inputManifest` + metric 列表 |
| CEL 实时类型诊断（Level 2，轻量 API） | 中低成本：`POST /admin/v1/expressions/validate` + 前端 debounced markers |
| LSP（Level 3）/ 自然语言转表达式 | 不跟（投入产出比低 + 中文准确度风险） |

---

### 2.4 gengine（bilibili）

> 本轮调研（2026-07-23）。仓库：`github.com/bilibili/gengine`（同级目录 `../gengine`）。

| 维度 | gengine | rule-engine |
|---|---|---|
| 定位 | Go 嵌入式规则引擎（进程内库） | 服务化决策平台（配置/评估/审计/灰度） |
| 技术栈 | Go / ANTLR4 生成 AST | Java 25 / Spring Boot 4 / Modulith |
| 边界 | 无服务端 / 无持久化 / 无版本 / 无审计 / 无取数 SPI | 全具备 |

**核心能力**：

- **命令式 DSL（可副作用）**：`rule "名" "描述" salience N begin … end`，支持 `if/else`、赋值、for、return；规则体内**直接改注入的结构体、调注入的 Go 方法**（`User.Age = User.GetNum(x)`）。
- **DataContext**：把 Go struct/函数注入成 DSL 可调 API，可热替换、热加载规则不停服。
- **执行模型极丰富**（其最大特色）：Sort（按 salience）/ Concurrent（全并行）/ MixModel（最高优先级先跑、其余并行）/ InverseMix / N-sort-M-concurrent 各种混合 / **DAG 模型**（`[][]string` 分层，层内并行、层间串行）/ ExecuteSelectedRules（按名跑子集）。
- **StopTag**：规则体内可写的协作式提前终止标志，短路后续低优先级规则。
- **规则池 GenginePool**：类比 MySQL 连接池，预建引擎实例扛高 QPS（因 Go 里 build AST 贵）。

**与本项目差异（基因不同宗）**：gengine 是 Drools 式命令式规则（规则可改状态、调任意函数、有副作用）；本项目 **D60 已纯决策化**（只出 Decision、禁副作用），**08-evolution §四已否决** urule 式 FunctionLibrary（任意函数注册）。二者从根上分道。

**吸收结论（逐条映射，可吸收面小）**：

| gengine 能力 | 本项目对应 | 结论 |
|---|---|---|
| 命令式 DSL + 副作用 | D60 纯决策化 | 已否决，不吸收 |
| DataContext 注入任意函数 | Metric SPI + payload（任意函数=urule FunctionLibrary） | 08-evolution §四已否决 |
| salience 优先级 / StopTag 提前退出 | rule priority + hit policy（FIRST_HIT / HIGHEST_PRIORITY） | 已有，声明式更适合配置化产品 |
| 规则池扛 build 成本 | D6/D17 不可变快照 + D67 预编译 + Caffeine | 已有等价物 |
| DAG 执行模型（`[][]string`） | D75 `DECISION_FLOW`（typed 节点 + 发布期冻结） | 已有更结构化版本 |
| **场景内多条独立规则并行求值**（Concurrent / DAG 层内并行） | 评估当前同步串行；metric 预拉已批量（D20），规则求值本身无并行 | **唯一候选** → 记入 `08-evolution.md` §2.29（不实现，待真实吞吐瓶颈触发） |

---

### 2.5 RuleGo

> 本轮调研（2026-07-23）。仓库：`github.com/rulego/rulego`（同级目录 `../rulego`，rulego.dev）。

| 维度 | RuleGo | rule-engine |
|---|---|---|
| 定位 | Go 组件编排规则引擎（规则链/流编排，类 node-red，偏 IoT/边缘/数据集成） | 服务化纯决策平台 |
| 形态 | 嵌入式 + 独立部署双模；规则链 = 组件 DAG（JSON 配置、可动态编排） | 服务化，评估出 Decision |
| 边界 | 编排 + **动作执行一体**（组件含 发邮件/gRPC/HTTP/DB/MQTT/脚本转换 等副作用节点） | D60 引擎纯决策、不执行动作 |

**核心能力**：组件化（一切业务逻辑=组件）+ 规则链（组件 DAG 编排、不重启动态替换/新增）+ 子规则链嵌套 + AOP（不改链给执行织入行为）+ Endpoint 多协议数据集成（HTTP/MQTT/TCP/Kafka/Schedule 入口）+ Go plugin 动态加载 + 协程池/对象池 + 上下文隔离。

**与本项目差异（类别不同）**：RuleGo 是**编排 + 动作执行一体**的引擎（node-red 血统），其价值主张（动作组件、发邮件/HTTP/DB 副作用、endpoint 集成）正是本项目 **D60 明确推出引擎**、交给消费方/流程引擎的部分。本项目分层：纯决策（D60）→ 同步决策图（D75 DECISION_FLOW）→ 有状态动作编排（接 Flowable）。

**吸收结论**：

| RuleGo 能力 | 本项目对应 | 结论 |
|---|---|---|
| 组件化规则链 DAG 编排 | D75 DECISION_FLOW（同步、纯决策、typed 节点 + 发布期冻结） | 同步图已覆盖，但本项目图只编排决策、不含动作节点 |
| 动作组件（发邮件/HTTP/DB/MQTT） | D60 推出引擎 | 不吸收（归消费方 / Flowable） |
| 规则链热替换 / 动态加载 | D17 RuleVersionWatcher 热更 + 不可变快照 | 已有等价物 |
| Endpoint 多协议入口 | Trigger Sources 适配（HTTP/MQ/JOB/SDK）+ D72 声明式连接器（取数侧） | 已有对应层 |
| AOP / 子链嵌套 | DECISION_FLOW RuleRef 复用；求值纯函数无 AOP | 部分覆盖；AOP 不需要（纯决策无副作用可织入） |

整体：**类别不同（编排+动作 vs 纯决策），架构已分层，可吸收面小**——是 D60「引擎不含动作/编排」边界的又一佐证。

---

## 三、业界对标速览（决策级引用索引）

只在决策/演进里点到、未逐模块拆的业界项目，索引到具体落点。

| 对标对象 | 对标点 | 落点 | 结论 |
|---|---|---|---|
| **OPA**（Open Policy Agent） | 策略引擎出决策 + PEP 执行，决策/执行分层 | D60 | 吸收（定位对齐：引擎纯决策，编排外置） |
| **Camunda**（DMN + BPMN） | DMN 出决策、BPMN 做编排，决策与编排分层 | D60（编排接 Flowable）；D75 判据 | 吸收（定位对齐） |
| **Camunda DMN**（itemDefinition + typeRef） | 类型定义驱动校验 | D69 | 吸收（typed 契约 + 分层校验参照） |
| **Apache Calcite**（SqlOperatorTable + OperandTypeChecker） | 算子表 + 操作数类型检查 | D69 | 吸收（`OperatorSpec` + 发布期类型检查参照） |
| **JSON Schema** | 声明式约束 | D69（typed 契约 + payload 约束） | 吸收 |
| **GoRules ZEN JDM** | 决策图 DAG（图只编排、叶子原子） | D75；见 [§2.3](#23-gorules-zen-engine) | 吸收（`DECISION_FLOW`） |
| **Drools** | guided rule template / decision table | D74（参数化模板，**暂缓不实现**） | 记录待触发 |
| **Drools Verifier** | 规则集完备性/冲突校验 | §2.26（规则集静态分析，已落地 B31） | 吸收（命名/语义对齐 Verifier） |
| **Easy Rules** | `@Condition`/`@Fact` 注解规则 | D61（SDK 注解规则，仅嵌入式 SDK） | 吸收（加糖，严守 D60） |
| **urule** | FunctionLibrary（全局函数注册）/ ConstantLibrary | 08-evolution §四 | **已否决**（与闭合校验/禁副作用/metric 只读冲突） |
| **CEL / Aviator / ice**（市场吞吐对比） | 表达式引擎 / 高吞吐评估形态 | D20；D66（六引擎 EXPRESSION_SCRIPT） | 吸收（CEL/Aviator 为六引擎之二；ice 作吞吐参照） |
| **OpenFeature** | provider SPI + spec + conformance suite | D72（连接器标准化） | 吸收（声明式连接器 + conformance 套件） |
| **OTel** | exporter SPI + OTLP 信封 + semantic conventions | D72；§2.22（OTLP + LGTM 可观测性） | 吸收 |
| **Confluent Schema Registry / K8s / Camunda** | 代理主键 + 反范式冗余自然键 | rule identity（code + version） | 吸收（保留代理 PK + 冗余自然键） |
| **DB 动态数据脱敏 / Apache Ranger** | 声明在字段定义、读时按策略遮蔽 | D71（Trace PII 读时脱敏） | 吸收 |
| **FICO / Sapiens** | 规则绩效 / 有效性度量 | §2.27（决策效果闭环，演进位） | 记录待触发 |
| **Grule**（Hyperjump，本地同级目录 `../grule-rule-engine`） | Drools 式 GRL DSL + salience + when/then + 可变 facts + 推理 | 同 gengine（§2.4）；Drools 已在本表 | **不吸收**（命令式 + 副作用 + 可变 facts，D60/D16 拒；Drools-in-Go 无新意） |
| **Gval**（本地同级目录 `../gval`） | Go 可组合表达式语言 + parse-once 复用 | D66 `ExpressionEngine` SPI（六引擎 + 编译缓存） | **不吸收**（Go 库、本项目 Java；可组合 + 编译复用思路 D66 已具备） |
| **CEL-Go**（Google CEL 官方 Go 实现，本地同级目录 `../cel-go`） | CEL 语言的 Go 宿主实现 | D66（本项目用 CEL 的 Java 实现 dev.cel） | **已覆盖**（CEL 已是六引擎之一；Go 宿主不用；conformance 可作参照） |

---

## 四、吸收状态总览

三桶定义：**已吸收**=已落地或思路已进实现；**不需要**=否决 / 已有更优抽象，不吸收；**待定**=候选，触发条件未满足、未立项。

### 4.1 总表（全项目 × 三桶）

| 状态 | 来源 | 点 | 落点 |
|---|---|---|---|
| 已吸收 | trae R3 | 类型化比较策略工厂 | B19 `ComparisonStrategyFactory` |
| 已吸收 | trae R1 | XOR 逻辑节点 | `XorNode`（§2.21） |
| 已吸收 | trae R2 | 脚本执行器池化 / 编译产物复用思路 | D66 六引擎 `compile` 缓存 |
| 已吸收 | 天网 | 二维决策矩阵录入 | decision-matrix demo + 修 `BETWEEN` bug |
| 已吸收 | ZEN | JDM 决策图（DAG 编排） | D75 `DECISION_FLOW` |
| 已吸收 | Drools Verifier | 规则集完备性 / 冲突校验 | §2.26 B31 静态分析 |
| 已吸收 | Easy Rules | `@Condition`/`@Fact` 注解规则 | D61 SDK 注解规则 |
| 已吸收 | OPA | 策略出决策 + PEP 执行分层 | D60 纯决策化 |
| 已吸收 | Camunda DMN+BPMN | 决策与编排分层 | D60（编排接 Flowable） |
| 已吸收 | Camunda typeRef / Calcite / JSON Schema | typed 契约 + 类型检查 | D69 分层校验 |
| 已吸收 | OpenFeature / OTel | provider SPI + conformance + OTLP | D72 声明式连接器 |
| 已吸收 | OTel | OTLP + LGTM | §2.22 可观测性 |
| 已吸收 | Confluent / K8s | 代理主键 + 冗余自然键 | rule identity（code+version） |
| 已吸收 | Apache Ranger | 字段声明 + 读时遮蔽 | D71 Trace PII 读时脱敏 |
| 已吸收 | CEL / Aviator | 表达式引擎 | D66 六引擎之二 |
| 已吸收 | FICO / Sapiens（效果·一半） | 业务结局标签回灌 | §2.27 B32 `decision_outcome` |
| 不需要 | trae | 6 级 Context / 内部事件总线 / 自研验证框架 / 引擎耦合 JPA / `DecisionCode` 硬编码 / Rule 层组合策略 | 分别被 不可变 POJO / Modulith / Spring Validation / kernel 零 Spring / Decision 业务配置 / AST 组合 顶替 |
| 不需要 | trae R4 | 自研有状态 Flow 编排 | 架构已定：同步图归 D75 `DECISION_FLOW`，有状态编排接 Flowable（D60/D75），不自研 |
| 不需要 | gengine | 命令式副作用 DSL / DataContext 任意函数 / 规则池 / salience·StopTag / DAG 执行模型 | 分别被 D60 / urule 否决 / 快照+预编译 / hit policy / D75 覆盖 |
| 不需要 | urule | FunctionLibrary + ConstantLibrary（全局函数注册） | 08-evolution §四已否决（冲突闭合校验/禁副作用/metric 只读） |
| 不需要 | ZEN | QuickJS Function 节点 / LSP / 自然语言转表达式 | D60 纯决策不跟 / 投产比低 / 中文准确度风险 |
| 不需要 | 天网 | matrix 作为独立引擎 kind | 录入糖非引擎能力，不进 kind |
| 不需要 | RuleGo | 组件化动作编排 / 动作节点 / endpoint 集成 | 类别不同（编排+动作 vs D60 纯决策）；同步图 D75 覆盖、有状态编排接 Flowable |
| 不需要 | Grule | Drools 式 GRL / 可变 facts / 推理 | 同 gengine（命令式 + 副作用，D60/D16 拒） |
| 不需要 | Gval | Go 可组合表达式库 | 本项目 Java；D66 六引擎 + 编译缓存已具备，不引入 |
| 不需要 | CEL-Go | CEL 的 Go 宿主实现 | CEL 已是六引擎之一（cel-java），Go 宿主不用 |
| 待定 | gengine | 场景内独立规则并行求值 | §2.29（待实测吞吐瓶颈） |
| 待定 | ZEN | 表达式编辑器变量补全（Level 1） | 纯前端零后端，未立项 |
| 待定 | ZEN | CEL 实时类型诊断（Level 2） | `POST /expressions/validate`，未立项 |
| 待定 | trae R5 | Decorator 三级缓存键（条件去重） | §2.13 alpha 节点共享，待实现 |
| 待定 | Drools | guided rule template（参数化模板） | D74 暂缓，记录待触发 |
| 待定 | FICO / Sapiens | 按规则聚合 precision/recall/漂移 | §2.27 后续（标签位已就绪，聚合未做） |

### 4.2 分项目（细对比）

每项目按三桶逐条列，含"细节 / 为什么这样归"。业界对标（§三）不逐项目单列，其状态见 4.1 总表。

#### 4.2.1 trae_projects

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | R3 类型化比较策略工厂 | 按 dataType 路由到独立策略类，避免 if-else 大杂烩、各类型可独立测 | B19 `ComparisonStrategyFactory`（kernel `internal/condition/strategy`，Long/Double/Decimal/String/Boolean/Date/DateTime） |
| 已吸收 | R1 XOR 逻辑节点 | "有且仅有一个满足"，遍历不短路计数 `==1`；免 `(A AND NOT B) OR (B AND NOT A)` 的可读性负担 | `XorNode` sealed AST + Interpreted/Tracing executor + AstCompiler + 5 单测（§2.21） |
| 已吸收 | R2 脚本执行器池化 | Groovy ScriptEngine 秒级初始化且非线程安全，trae 用 commons-pool2 池化；本项目吸收"编译产物复用"思路 | D66 六引擎 `compile` 结果 Caffeine 缓存（未引 commons-pool2，编译缓存等价） |
| 不需要 | 6 级作用域 Context | v1 EvalContext 是一次性不可变 POJO，无流程挂起语义，分层是复杂度负债 | 顶替：不可变 POJO |
| 不需要 | 内部事件总线 EventManager | 重复造轮子 | 顶替：Spring Modulith `ApplicationEventPublisher` |
| 不需要 | 自研验证框架 | 无需自造 | 顶替：Spring Validation（JSR-303） |
| 不需要 | 引擎核心耦合 JPA 实体 | 违 kernel 零 Spring / 零 DB 硬约束（09-skeleton §五） | — |
| 不需要 | `DecisionCode` 硬编码枚举 | 本项目 Decision 是业务配置，灵活性更高 | — |
| 不需要 | Rule 实体层组合策略（CompositeRule 7 种） | 本项目组合语义在 AST 表达，职责更清晰 | — |
| 不需要 | R4 自研有状态 Flow | 同步图已由 D75 覆盖；有状态编排 D60/D75 已定接 Flowable，不自研（trae Flow ~2000 行不引入） | — |
| 待定 | R5 Decorator 三级缓存键 | `ConditionEvaluationKey=(conditionId, ctxHash)` 与"同 ctx 内条件只算一次"思路一致 | §2.13 alpha 节点共享 |

#### 4.2.2 skyhackvip/risk_engine（天网）

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | 二维决策矩阵录入 | matrix = `DecisionTableNode`（2 列 + m×n 行）的特例；做丢弃式 demo 证明"矩阵视图 → 实时展开成现有 JSON"，引擎 0 改动 | `docs/examples/decision-matrix-mockup.html` |
| 已吸收 | （顺带）BETWEEN bug 修复 | 对照落地时发现 `buildParams` 缺 BETWEEN/NOT_BETWEEN 分支 → 决策表该类列永不命中；TDD 先红后绿 | `DecisionTableExecutor.buildParams` |
| 不需要 | matrix 作为独立引擎 kind | 表达力已被 `DECISION_TABLE` 覆盖，差异只在**录入体感**；录入糖不进引擎 | — |

#### 4.2.3 GoRules ZEN Engine

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | JDM 决策图 | DAG = Input→节点→Output，图只编排、叶子原子；本项目更严（typed sealed 节点 + 发布期冻结 + 环/死节点检测） | D75 `DECISION_FLOW`（第 6 种 kind） |
| 不需要 | QuickJS Function 节点 | 本项目 D60 已纯决策化，`Transform` 只允许既有 6 表达式引擎、禁任意副作用脚本 | — |
| 不需要 | LSP（Level 3） | 多引擎各自实现 LSP 投入产出比低 | — |
| 不需要 | 自然语言转表达式 | 中文运营场景 + 准确度风险 | — |
| 待定 | 变量补全（Level 1） | Monaco CompletionItemProvider 喂 `inputManifest` + metric 列表；`referencedVariables` 已就位、只是没喂前端 | 纯前端零后端，建议做 |
| 待定 | CEL 实时类型诊断（Level 2） | keystroke 级类型错误红波浪，复用 CEL `typeCheck` + 编译缓存（编译成本≈0） | `POST /admin/v1/expressions/validate` + 前端 debounced |

#### 4.2.4 gengine（bilibili）

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | （无） | 逐条映射后本项目均已有更优抽象或架构已否决 | — |
| 不需要 | 命令式副作用 DSL | 规则体直接改状态 / 调任意 Go 方法；本项目 D60 纯决策、禁副作用 | — |
| 不需要 | DataContext 注入任意函数 | 等价 urule FunctionLibrary（全局函数注册） | 08-evolution §四已否决 |
| 不需要 | 规则池 GenginePool | 为扛 Go build AST 成本；本项目有 D6/D17 不可变快照 + D67 预编译 + Caffeine | — |
| 不需要 | salience / StopTag | 优先级排序 + 协作式提前退出；本项目 priority + hit policy（FIRST_HIT/HIGHEST_PRIORITY），声明式更适配配置化产品 | — |
| 不需要 | DAG 执行模型 `[][]string` | 分层调度、层内并行；本项目 D75 typed 节点 + 发布期冻结更结构化 | — |
| 待定 | 场景内独立规则并行求值 | Concurrent / DAG 层内并行；本项目评估同步串行，含多重 `EXTERNAL_HTTP` 取数 / `EXPRESSION_SCRIPT` 重算的场景或有吞吐收益 | §2.29（待实测瓶颈触发） |

#### 4.2.5 RuleGo（rulego.dev）

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | （无） | 类别不同（编排+动作引擎）；逐条映射后本项目已有对应层或架构已推出 | — |
| 不需要 | 动作组件 / endpoint 动作 | 发邮件/HTTP/DB/MQTT 等副作用节点；本项目 D60 纯决策、不执行动作 | 归消费方 / Flowable |
| 不需要 | 组件化规则链流编排 | 同步图已由 D75 DECISION_FLOW 覆盖（且只编排决策、不含动作节点）；有状态编排接 Flowable | D75 / Flowable |
| 不需要 | 规则链热替换 / AOP / 子链嵌套 | 热更=D17 Watcher+快照、复用=RuleRef 已有；AOP 无意义（纯函数无副作用可织入） | — |

---

## 五、追加新项目模板

调研一个新外部项目时，复制以下骨架到「二、深度结构对照」，并在「一、汇总速览」补一行；纯决策级引用则只在「三」补一行。

```markdown
### 2.x <项目名>

> 调研日期 / 仓库或来源链接。（若已有专档快照，链回并标注"本节为摘要"。）

| 维度 | <项目> | rule-engine |
|---|---|---|
| 定位 | … | … |
| 技术栈 | … | … |
| 边界 | … | … |

**核心能力**：…

**与本项目差异**：…

**吸收结论**：逐条映射「对方能力 → 本项目对应 → 吸收/否决/已有」；可吸收项写明回写到哪条 D / §，不在本文档展开实现。
```
