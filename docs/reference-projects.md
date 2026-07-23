# 参考项目与业界对标台账

> **文档定位**：把本项目（rule-engine）对外部项目的**结构对照**与**业界对标**收成单一活入口。以往这些记录散落在 `00-decisions.md` 决策条目、`08-evolution.md` 锚点、`specs/` 调研快照里，读者拼不出全貌；本台账做**汇总 + 链接**，不搬走历史（历史专档/决策条目不动）。
>
> **性质**：活文档，调研快照的汇总台账，**不是执行计划**。可吸收项一律回写 `08-evolution.md` 锚点 / `00-decisions.md` 决策，本台账只记"对照 + 吸收结论"。
>
> **追加规范**：每调研一个外部项目，在「三、吸收状态总览 > 分项目」追加一节（照「四、追加新项目模板」），并在「一、汇总速览」补一行；同步维护「三、吸收状态总览 > 总表」。只在决策里点到、未逐模块拆的业界项目进「二、业界对标速览」。

---

## 一、汇总速览

| 项目 | 类型 | 一句话结论 | 主要落点 | 详见 |
|---|---|---|---|---|
| **trae_projects** | Java 风险决策引擎（Trae AI 协作，SDK/框架） | 业务完整性/可靠性/规范本项目远优；引擎核心实现有代码参考价值 | XOR 节点、脚本对象池、类型化比较策略工厂 | [§3.2.1](#321-trae_projects) |
| **skyhackvip/risk_engine（天网）** | Java 决策引擎（开源） | 二维决策矩阵是**录入便利**、非引擎能力；本项目 `DECISION_TABLE` 表达力已覆盖 | 决策矩阵录入 demo + 顺带修 `BETWEEN` bug | [§3.2.2](#322-skyhackviprisk_engine天网) |
| **GoRules ZEN Engine** | Rust 决策引擎（JDM 决策图 + 表达式内核） | JDM 决策图=本项目 `DECISION_FLOW` 直接对标；表达式 intellisense 是编辑期体验缺口的参照 | D75 `DECISION_FLOW`；补全/诊断分级方案 | [§3.2.3](#323-gorules-zen-engine) |
| **gengine（bilibili）** | Go 嵌入式规则引擎（AST DSL + 执行内核） | 基因不同宗（命令式可副作用 DSL）；可吸收面小，唯一候选=并行求值 | 候选记入 `08-evolution.md` §2.29（不实现） | [§3.2.4](#324-genginebilibili) |
| **RuleGo**（rulego.dev） | Go 组件编排规则引擎（规则链，类 node-red） | 类别不同（编排+动作 vs 纯决策）；D60 边界反例佐证 | 分层已定 D60 / D75 / Flowable | [§3.2.5](#325-rulego) |
| **Drools**（Apache KIE） | Java RETE 推理 + CEP + DMN 一体化 | 刻意非 RETE（不可变快照 + 无状态）；Verifier/决策表已吸收 | §2.26 B31 静态分析 | [§3.2.6](#326-droolsapache-kie) |
| **Kogito**（Java，Apache KIE） | Drools + jBPM + DMN 决策流程捆绑、cloud-native | 决策+编排捆绑反例，佐证 D60/D75 分层 | D60 / D75 | [§3.2.7](#327-kogitoapache-kie) |
| **Evrete**（Java） | 轻量 RETE + JSR-94 + 注解 Java 规则 | 刻意非 RETE（轻量立面对立面）；D61 已有 easyrules 注解 | D6/D17/D61 | [§3.2.8](#328-evrete) |
| **OpenL Tablets**（Java，LGPL） | Excel 规则 → JVM 字节码 → REST API | Excel 业务用户 authoring 参照；留 D74 待触发 | D74 | [§3.2.9](#329-openl-tabletslgpl) |
| **ice**（Go+Java+Python，本地 `../ice`，Apache 2.0） | 树形编排规则引擎：Relation 节点 + Leaf 带副作用 + Roam 上下文 + 多语言 SDK + 零依赖文件存储 | 执行型有副作用(D60 拒)；多语言 SDK 非本项目方向；节点复用/并行已有等价物 | [§3.2.10](#3210-ice) |

---

## 二、业界对标速览（决策级引用索引）

只在决策/演进里点到、未逐模块拆的业界项目，索引到具体落点。

| 对标对象 | 对标点 | 落点 | 结论 |
|---|---|---|---|
| **OPA**（Go）| 策略引擎出决策 + PEP 执行，决策/执行分层 | D60 | 吸收（定位对齐：引擎纯决策，编排外置） |
| **Camunda**（Java，DMN + BPMN）| DMN 出决策、BPMN 做编排，决策与编排分层 | D60（编排接 Flowable）；D75 判据 | 吸收（定位对齐） |
| **Camunda DMN**（Java，itemDefinition + typeRef）| 类型定义驱动校验 | D69 | 吸收（typed 契约 + 分层校验参照） |
| **Apache Calcite**（Java）| 算子表 + 操作数类型检查 | D69 | 吸收（`OperatorSpec` + 发布期类型检查参照） |
| **JSON Schema**（语言无关）| 声明式约束 | D69（typed 契约 + payload 约束） | 吸收 |
| **Drools Verifier**（Java，Apache KIE）| 规则集完备性/冲突校验 | §2.26（规则集静态分析，已落地 B31） | 吸收（命名/语义对齐 Verifier） |
| **Easy Rules**（Java）| `@Condition`/`@Fact` 注解规则 | D61（SDK 注解规则，仅嵌入式 SDK） | 吸收（加糖，严守 D60） |
| **urule**（Java）| FunctionLibrary（全局函数注册）/ ConstantLibrary | 08-evolution §四 | **已否决**（与闭合校验/禁副作用/metric 只读冲突） |
| **CEL / Aviator**（Go/Java）| 表达式引擎 / 高吞吐评估形态 | D20；D66（六引擎 EXPRESSION_SCRIPT） | 吸收（CEL/Aviator 为六引擎之二） |
| **ice**（Go+Java+Python，本地 `../ice`，Apache 2.0）| 树形编排规则引擎：Relation 节点（AND/ANY/ALL/NONE/TRUE + P_AND/P_ANY 并行变体）+ Leaf 节点（Flow/Result/None 带副作用执行）+ Roam 共享上下文 + 多语言 SDK + 零依赖文件存储 + Lane 流量隔离 | 执行型有副作用（Result 发券、None 日志/查询）→ D60 拒绝；零依赖文件存储→本项目 DB 化相反；多语言 SDK→本项目 Java-only；节点复用→D75 RuleRef 已有；并行→§2.29 已捕获 | **不吸收**（执行+副作用+D60 否定；其余各有更优方案） |
| **OpenFeature**（语言无关）| provider SPI + spec + conformance suite | D72（连接器标准化） | 吸收（声明式连接器 + conformance 套件） |
| **OTel**（语言无关）| exporter SPI + OTLP 信封 + semantic conventions | D72；§2.22（OTLP + LGTM 可观测性） | 吸收 |
| **Confluent Schema Registry / K8s**（语言无关 + Go）| 代理主键 + 反范式冗余自然键 | rule identity（code + version） | 吸收（保留代理 PK + 冗余自然键） |
| **Apache Ranger**（Java）| 声明在字段定义、读时按策略遮蔽 | D71（Trace PII 读时脱敏） | 吸收 |
| **FICO / Sapiens**（闭源商用）| 规则绩效 / 有效性度量 | §2.27（决策效果闭环，演进位） | 记录待触发 |
| **Grule**（Go，Hyperjump，本地 `../grule-rule-engine`）| Drools 式 GRL DSL + salience + when/then + 可变 facts + 推理 | 同 gengine（§3.2.4） | **不吸收**（命令式 + 副作用 + 可变 facts，D60/D16 拒） |
| **Gval**（Go，本地 `../gval`）| 可组合表达式语言 + parse-once 复用 | D66 `ExpressionEngine` SPI | **不吸收**（Go 库、本项目 Java；思路 D66 已具备） |
| **CEL-Go**（Go，Google，本地 `../cel-go`）| CEL 语言的 Go 宿主实现 | D66（本项目用 CEL 的 Java 实现 dev.cel） | **已覆盖**（CEL 已是六引擎之一；Go 宿主不用） |

---

## 三、吸收状态总览

三桶定义：**已吸收**=已落地或思路已进实现；**不需要**=否决 / 已有更优抽象，不吸收；**待定**=候选，触发条件未满足、未立项。

### 3.1 总表（全项目 × 三桶）

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
| 已吸收 | ZEN L1 | 表达式编辑器变量补全（**六引擎通用**） | `expressionCompletions.ts` + ScriptEditor + ExpressionInput（Flow Switch/Transform），零后端 |
| 已吸收 | FICO / Sapiens（效果·一半） | 业务结局标签回灌 | §2.27 B32 `decision_outcome` |
| 已吸收 | ZEN L2 | CEL 实时类型诊断（CEL 专属） | `ExpressionValidationService` + `POST /admin/v1/expressions/validate` + ScriptEditor/ExpressionInput debounced lint（弱类型引擎 no-op 自动通过） |
| 不需要 | trae | 6 级 Context / 内部事件总线 / 自研验证框架 / 引擎耦合 JPA / `DecisionCode` 硬编码 / Rule 层组合策略 | 分别被 不可变 POJO / Modulith / Spring Validation / kernel 零 Spring / Decision 业务配置 / AST 组合 顶替 |
| 不需要 | trae R4 | 自研有状态 Flow 编排 | 架构已定：同步图归 D75 `DECISION_FLOW`，有状态编排接 Flowable（D60/D75），不自研 |
| 不需要 | gengine | 命令式副作用 DSL / DataContext 任意函数 / 规则池 / salience·StopTag / DAG 执行模型 | 分别被 D60 / urule 否决 / 快照+预编译 / hit policy / D75 覆盖 |
| 不需要 | urule | FunctionLibrary + ConstantLibrary（全局函数注册） | 08-evolution §四已否决（冲突闭合校验/禁副作用/metric 只读） |
| 不需要 | ZEN | QuickJS Function 节点 / LSP / 自然语言转表达式 | D60 纯决策不跟 / 投产比低 / 中文准确度风险 |
| 不需要 | 天网 | matrix 作为独立引擎 kind | 录入糖非引擎能力，不进 kind |
| 不需要 | RuleGo | 组件化动作编排 / 动作节点 / endpoint 集成 | 类别不同（编排+动作 vs D60 纯决策） |
| 不需要 | Grule | Drools 式 GRL / 可变 facts / 推理 | 同 gengine（命令式 + 副作用，D60/D16 拒） |
| 不需要 | Gval | Go 可组合表达式库 | 本项目 Java；D66 六引擎 + 编译缓存已具备 |
| 不需要 | CEL-Go | CEL 的 Go 宿主实现 | CEL 已是六引擎之一（cel-java），Go 宿主不用 |
| 不需要 | Drools/Kogito | RETE 推理 + DMN/CEP 决策流程捆绑 | 刻意非 RETE（不可变快照+无状态）；捆绑反例佐证 D60/D75 分层 |
| 不需要 | Evrete | 轻量 RETE + JSR-94 注解规则 | 刻意非 RETE；D61 已有 easyrules 注解 |
| 不需要 | OpenL Tablets | Excel 编译 JVM 字节码 authoring | 业务用户 Excel 录入模型留 D74 待触发；LGPL |
| 不需要 | ice | 树形编排+Leaf 副作用执行+多语言 SDK+零依赖文件存储 | 执行型有副作用(D60 拒)；多语言 SDK/文件存储非本项目方向；节点复用(D75 RuleRef)/并行(§2.29)已有等价物 |
| 待定 | gengine | 场景内独立规则并行求值 | §2.29（待实测吞吐瓶颈） |
| 待定 | trae R5 | Decorator 三级缓存键（条件去重） | §2.13 alpha 节点共享，待实现 |
| 待定 | Drools | guided rule template（参数化模板） | D74 暂缓，记录待触发 |
| 待定 | FICO / Sapiens | 按规则聚合 precision/recall/漂移 | §2.27 后续（标签位已就绪，聚合未做） |

### 3.2 分项目（细对比·融合版）

每项目一张"维度表 + 差异概要 + 三桶细表"。已吸收/不需要/待定 逐条含"细节 / 为什么这样归"。

#### 3.2.1 trae_projects

> 详细快照见 [`specs/archive/2026-06-04-trae-reference-design.md`](./superpowers/specs/archive/2026-06-04-trae-reference-design.md)（本节为摘要）。

| 维度 | trae_projects | rule-engine |
|---|---|---|
| 定位 | SDK / 框架，偏底层引擎抽象 | 产品，完整业务平台 |
| 技术栈 | Java 21 / Spring Boot 4 / JPA / Groovy JSR-223 / Caffeine | Java 25 / Spring Boot 4 / Modulith / MyBatis-Plus / Caffeine |
| 完成度 | 代码有实现但多处未完成、部分模块缺源码 | 决策驱动，核心链路已实装 |

核心能力：`flow/`（FlowEngine + JsonFlowParser，7 种节点）、`rule/`（CompositeRule AND/OR/NOT/XOR/Sequence/Parallel/FirstMatch + ScriptCondition + 7 种类型化比较策略）、`context/`（6 级作用域 + 增量快照 + 30+ 事件）、`cache/`（装饰器缓存 + commons-pool2 对象池）。本项目在业务完整性、可靠性语义（不可变快照/审计/灰度）、工程规范上远优。

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | R3 类型化比较策略工厂 | 按 dataType 路由，避免 if-else 大杂烩、各类型可独立测 | B19 `ComparisonStrategyFactory`（Long/Double/Decimal/String/Boolean/Date/DateTime） |
| 已吸收 | R1 XOR 逻辑节点 | "有且仅有一个满足"，遍历不短路计数 `==1` | `XorNode` sealed AST + Interpreted/Tracing executor + AstCompiler（§2.21） |
| 已吸收 | R2 脚本执行器池化 | Groovy 秒级初始化且非线程安全，trae 用 commons-pool2；本项目吸收"编译产物复用"思路 | D66 六引擎 `compile` Caffeine 缓存（未引 commons-pool2，编译缓存等价） |
| 不需要 | 6 级作用域 Context | v1 EvalContext 是一次性不可变 POJO，无流程挂起语义 | 顶替：不可变 POJO |
| 不需要 | 内部事件总线 EventManager | 重复造轮子 | 顶替：Spring Modulith `ApplicationEventPublisher` |
| 不需要 | 自研验证框架 | 无需自造 | 顶替：Spring Validation（JSR-303） |
| 不需要 | 引擎核心耦合 JPA 实体 | 违 kernel 零 Spring / 零 DB 硬约束（09-skeleton §五） | — |
| 不需要 | `DecisionCode` 硬编码枚举 | 本项目 Decision 是业务配置，灵活性更高 | — |
| 不需要 | Rule 实体层组合策略 | 本项目组合语义在 AST 表达，职责更清晰 | — |
| 不需要 | R4 自研有状态 Flow | 同步图已由 D75 覆盖；有状态编排 D60/D75 已定接 Flowable | — |
| 待定 | R5 Decorator 三级缓存键 | `ConditionEvaluationKey=(conditionId, ctxHash)` 与"同 ctx 条件只算一次"一致 | §2.13 alpha 节点共享 |

#### 3.2.2 skyhackvip/risk_engine（天网）

> 对照结论见 [`specs/archive/2026-06-16-decision-matrix-demo-and-between-fix-design.md`](./superpowers/specs/archive/2026-06-16-decision-matrix-demo-and-between-fix-design.md)。

独立 `matrix`（交叉决策表）节点：X 轴分箱 × Y 轴分箱 → 输出格。本项目 `DecisionTableNode(columns, rows)`（FIRST_HIT，N 列）表达力已覆盖且更通用——二维矩阵 = 2 列 + m×n 行的特例。差异只在录入体感，引擎 0 改动。对照过程中顺带发现 `BETWEEN`/`NOT_BETWEEN` 列因 `buildParams` 缺分支而永不命中的潜伏 bug。

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | 二维决策矩阵录入 | matrix 是 DecisionTable 特例；丢弃式 demo 证明矩阵视图 → 展开成现有 JSON | `docs/examples/decision-matrix-mockup.html` |
| 已吸收 | BETWEEN bug 修复 | `buildParams` 缺 BETWEEN/NOT_BETWEEN 分支 → 决策表该类列永不命中 | `DecisionTableExecutor.buildParams` |
| 不需要 | matrix 作为独立引擎 kind | 录入糖非引擎能力，不进 kind | — |

#### 3.2.3 GoRules ZEN Engine

> JDM 对标见 [`00-decisions.md`](./00-decisions.md) D75；表达式 intellisense 对标见 [`specs/2026-07-20-expression-intellisense-analysis.md`](./superpowers/specs/2026-07-20-expression-intellisense-analysis.md)。

JDM（决策图 = Input→节点→Output 的 DAG，叶子原子、图只编排）+ 表达式内核 `intellisense/`（补全/诊断/类型推断/nl）。JDM 与 D75 `DECISION_FLOW` 同宗——同步无状态决策图。本项目更严：typed sealed 节点 + 发布期冻结被引规则快照 + 环/死节点检测；`Transform` 只允许既有 6 引擎、禁任意副作用脚本（ZEN 有 QuickJS，D60 不跟）。表达式智能上 L1 变量补全 + L2 CEL 实时类型诊断**均已实现**——`referencedVariables` / `typeCheck` 经 `expressionCompletions.ts` + `ExpressionValidationService` 已喂给前端编辑器（仅 L3 LSP/自然语言仍待定）。

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | JDM 决策图 | DAG 编排、图只编排叶子原子；本项目更严（typed + 发布期冻结 + 静态分析） | D75 `DECISION_FLOW`（第 6 种 kind） |
| 不需要 | QuickJS Function 节点 | D60 纯决策化，`Transform` 只允许既有 6 引擎、禁任意副作用脚本 | — |
| 不需要 | LSP（Level 3） | 多引擎各自实现 LSP 投入产出比低 | — |
| 不需要 | 自然语言转表达式 | 中文运营场景 + 准确度风险 | — |
| 已吸收 | 变量补全（Level 1，**六引擎通用**） | CodeMirror `completionSource`：顶层变量 + `metrics./payload./subject.` 字段 + dataType 提示；Script 编辑器 + Flow Switch/Transform 三处统一 | `expressionCompletions.ts` + ScriptEditor + ExpressionInput，零后端 |
| 已吸收 | CEL 实时类型诊断（Level 2，CEL 专属） | keystroke 级红波浪，复用 CEL `typeCheck` + 编译缓存（弱类型引擎 typeCheck 为 no-op） | `ExpressionValidationService` + controller + ScriptEditor/ExpressionInput debounced lint |

#### 3.2.4 gengine（bilibili）

> 本轮调研（2026-07-23）。仓库：`github.com/bilibili/gengine`（同级目录 `../gengine`）。

| 维度 | gengine | rule-engine |
|---|---|---|
| 定位 | Go 嵌入式规则引擎（进程内库） | 服务化决策平台（配置/评估/审计/灰度） |
| 技术栈 | Go / ANTLR4 生成 AST | Java 25 / Spring Boot 4 / Modulith |
| 边界 | 无服务端 / 无持久化 / 无版本 / 无审计 / 无取数 SPI | 全具备 |

命令式 DSL（`rule "名" salience N begin … end`，支持 if/else/赋值/for/return，规则体直接改注入 struct/调 Go 方法）+ DataContext（struct/函数注入热替换）+ 极丰富执行模型（Sort/Concurrent/MixModel/DAG/ExecuteSelectedRules）+ StopTag + 规则池 GenginePool。基因不同宗：命令式 + 副作用 vs 本项目 D60 纯决策 + 08-evolution §四已否决 urule FunctionLibrary。

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 不需要 | 命令式副作用 DSL | 规则体直接改状态 / 调任意方法 | D60 纯决策、禁副作用 |
| 不需要 | DataContext 注入任意函数 | 等价 urule FunctionLibrary（全局函数注册） | 08-evolution §四已否决 |
| 不需要 | 规则池 GenginePool | 扛 Go build AST 成本 | D6/D17 不可变快照 + D67 预编译 + Caffeine 已有等价物 |
| 不需要 | salience / StopTag | 优先级 + 提前退出 | priority + hit policy（FIRST_HIT/HIGHEST_PRIORITY），声明式更适配 |
| 不需要 | DAG 执行模型 `[][]string` | 分层调度、层内并行 | D75 typed 节点 + 发布期冻结更结构化 |
| 待定 | 场景内独立规则并行求值 | Concurrent / DAG 层内并行；含多重 EXTERNAL_HTTP/SCRIPT 场景或有吞吐收益 | §2.29（待实测瓶颈触发） |

#### 3.2.5 RuleGo（rulego.dev）

> 本轮调研（2026-07-23）。仓库：`github.com/rulego/rulego`（同级目录 `../rulego`，rulego.dev）。

| 维度 | RuleGo | rule-engine |
|---|---|---|
| 定位 | Go 组件编排规则引擎（规则链/流编排，类 node-red，偏 IoT/边缘） | 服务化纯决策平台 |
| 形态 | 嵌入式 + 独立部署双模；JSON 配置、可动态编排 | 服务化，评估出 Decision |
| 边界 | 编排 + **动作执行一体**（发邮件/gRPC/HTTP/DB/MQTT 等副作用节点） | D60 引擎纯决策、不执行动作 |

核心能力：组件化（一切业务逻辑=组件）+ 规则链 DAG + 子链嵌套 + AOP + Endpoint 多协议集成 + Go plugin + 协程池/对象池 + 上下文隔离。类别不同：RuleGo 是编排+动作一体（node-red 血统），其动作组件正是 D60 推出引擎的部分。可吸收面小，是 D60 边界的又一佐证。

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 不需要 | 动作组件 / endpoint 动作 | 发邮件/HTTP/DB/MQTT 等副作用节点 | D60 归消费方 / Flowable |
| 不需要 | 组件化规则链流编排 | 同步图已由 D75 覆盖（且只编排决策、不含动作）；有状态接 Flowable | D75 / Flowable |
| 不需要 | 规则链热替换 / AOP / 子链嵌套 | 热更=D17 Watcher+快照、复用=RuleRef 已有；AOP 无意义（纯函数无副作用可织入） | — |

#### 3.2.6 Drools（Apache KIE，本地同级目录 `../incubator-kie-drools`）

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | 规则集完备性/冲突校验 | Drools Verifier 思路已吸收进 B31 静态分析（命名/语义对齐）；决策表 + guided template 入 D42/D74 | §2.26 B31 |
| 不需要 | RETE 前向/后向推理引擎 | 本项目刻意非 RETE：不可变快照 (D6) + 倒排索引 (D17) + 每事件无状态求值 | — |
| 不需要 | CEP 复杂事件处理 | 事件流处理留 Flink/CEP 扩展，不内嵌引擎 | §2.24 |
| 不需要 | DRL 命令式规则 DSL | 同 gengine·grule：命令式 + 副作用，D60 纯决策拒绝 | — |
| 不需要 | KIE 生态捆绑（决策+流程+规则）| 本品分层：纯决策 (D60) → 同步图 (D75) → 有状态编排 (Flowable) | — |

#### 3.2.7 Kogito（Apache KIE，Drools 同生态）

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 不需要 | Drools + jBPM + DMN 捆绑 | 本品已拆：纯决策 / 同步图 / Flowable；Kogito 是反面 | D60/D75 |
| 不需要 | cloud-native（Quarkus） | 本项目 Spring Boot 4 / GraalVM 已拆除 (D62)；Quarkus 生态不对齐 | — |

#### 3.2.8 Evrete（本地同级目录 `../evrete`）

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 不需要 | RETE 算法 | 刻意非 RETE（同 Drools 理由） | — |
| 不需要 | JSR-94 合规 | JSR-94 已停更（2004 年最终版），现代规则引擎不再以此为目标 | — |
| 不需要 | 注解 Java 规则（`@RuleSet`/`@Where`/`@Action`） | D61 已有 easyrules 风格注解规则（`@Condition`/`@Fact`），同层不重复引入 | — |

#### 3.2.9 OpenL Tablets（本地同级目录 `../openl-tablets`，LGPL）

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 不需要 | Excel → JVM 字节码编译链 | 本项目规则体是 typed JSON（AST/Script/FlowGraph），不走 Excel 路径；编译思路 D67 已覆盖 | — |
| 不需要 | 业务用户 Excel 维护规则 | 这正是 D74（参数化模板）暂缓等场景——当前是技术作者配规则 | D74 暂缓 |
| 不需要 | MCP AI 集成、自动 REST 暴露 | 附加功能，非核心对照点 | — |

#### 3.2.10 ice（本地同级目录 `../ice`，Apache 2.0）

> Go server（Web UI + 规则存储/发布）+ Java/Go/Python 多语言嵌入式 SDK。zero-dependency（文件存储，无 DB/MQ）。

树形规则引擎：Relation 节点（AND/ANY/ALL/NONE/TRUE + **P_AND/P_ANY/P_ALL/P_NONE/P_TRUE 并行变体**）+ Leaf 节点（Flow=条件检查/Result=业务操作如发券/None=副作用如写日志查询）+ **Roam** 共享 context（线程安全 ConcurrentHashMap 多语言 + deep key + `@uid` 动态引用）+ 节点跨树复用 + Lane 流量隔离(A/B/灰度)。

**类别不同**：ice 是**执行+副作用引擎**（Result/None 节点直接执行操作）。本项目 D60 纯决策化——决策与执行严格分层。Roam 的 `@uid` 动态引用有灵巧性，但等价于本项目 EvalContext bindings。多语言 SDK(Java/Go/Python)和零依赖文件存储和本项目 Java-only + DB-backed 方向相反。

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 不需要 | Leaf 副作用执行（Result/None） | 发券/写日志/查询等操作在规则节点内直接执行 | D60 纯决策不执行副作用 |
| 不需要 | 多语言 SDK（Java/Go/Python） | 本项目 Java-only，无 polyglot 需求 | — |
| 不需要 | 零依赖文件存储 | 本项目 DB-backed（MySQL + MyBatis + Flyway）不可变版本管理；文件存储不能满足审计/血缘/多租户需求 | — |
| 不需要 | 并行关系节点（P_AND 等） | 已在 gengine §2.29 捕获；ice 的并行是节点内 child 并行，非场景级规则并行 | — |
| 不需要 | Lane 流量隔离 | 本项目已有 rollout/pre-gate 灰度发布 + A/B bucket | — |
| 不需要 | 节点跨树复用 | D75 DECISION_FLOW RuleRef 已有等价能力（发布期冻结被引规则快照） | — |

---

## 四、追加新项目模板

调研一个新外部项目时，复制以下骨架到「三、吸收状态总览 > 分项目」追加一节，并在「一、汇总速览」和「三 > 总表」补一行；纯决策级引用则只在「二、业界对标速览」补一行。

```markdown
### 3.2.x <项目名>

> 调研日期 / 仓库或来源链接。（若已有专档快照，链回并标注"本节为摘要"。）

| 维度 | <项目> | rule-engine |
|---|---|---|
| 定位 | … | … |
| 技术栈 | … | … |
| 边界 | … | … |

核心能力 + 差异描述（1–2 段融合叙述）…

| 桶 | 点 | 细节 / 为什么 | 落点 |
|---|---|---|---|
| 已吸收 | … | … | … |
| 不需要 | … | … | … |
| 待定 | … | … | … |
```
