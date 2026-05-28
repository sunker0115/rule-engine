# 09 — 项目骨架与工程结构（占位草稿）

> **位置定位**：本文档承载 rule-engine 的**工程契约层**——Maven 模块拆分、包命名根、SPI 接口落点、依赖方向、配置分布。当前**占位**，仅章节就位，内部具体决策待定。
>
> **前置阅读**：[`README.md`](./README.md) §四 抽象表、[`00-decisions.md`](./00-decisions.md)、[`01-concepts.md`](./01-concepts.md) §三 名词全景
>
> **解决什么疑问**："新代码放哪个模块 / 哪个包？""业务方接入要依赖哪个 jar？""SPI 接口在哪个模块？""模块间依赖方向是什么？"
>
> **职责边界**——
> - ✅ 模块划分 / 包结构 / SPI 落点 / 依赖方向 / 配置文件分布
> - ❌ 不写决策权衡（→ 00-decisions）、不写一等概念字段表（→ 01-concepts）、不写 DDL（→ 05-storage）、不写运维参数默认值（→ 07-operability）、不写扩展实操步骤（→ 04-extension）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 Maven 模块拆分 | ⏳ 未展开（待定模块粒度与命名） |
| §三 包命名与包结构 | ⏳ 未展开（待定包根 + 按职责分包还是按类型分包） |
| §四 SPI 接口落点 | ⏳ 未展开（ConditionType / ActionType / MetricSource / RuleVersionWatcher / RuleVersionExecutor / Scheduler 在哪个模块） |
| §五 依赖方向与禁止环 | ⏳ 未展开 |
| §六 配置文件分布 | ⏳ 未展开（application.yml + 各模块 spring.factories / AutoConfiguration） |
| §七 测试组织 | ⏳ 未展开（单测 / 集成测 / 性能基线 / mock 策略） |
| §八 v1 不做的拆分 | ⏳ 未展开（v2 嵌入式 SDK 模式拆分回填，指向 [`08-evolution.md`](./08-evolution.md) §2.14） |

---

## 二、Maven 模块拆分

⏳ 未展开。

> 展开时落定：v1 阶段模块清单 + 每个模块一句话职责 + 部署形态（独立 Spring Boot 服务 / 可嵌入 jar）。注意与 D20 §3 输入闭合校验、D17 RuleVersionWatcher SPI、D21 TraceWriter 的模块归属对齐。

---

## 三、包命名与包结构

⏳ 未展开。

> 展开时落定：包命名根（如 `com.x.rule.*`）+ 包内组织原则（按职责分包：`core / matcher / evaluator / dispatcher / metric / action / condition / pregate / persistence / web / job / spi`，还是按类型分包：`controller / service / dao / dto`）+ 一等概念到包的映射表（Scene / Rule / RuleVersion / Condition / Action / Metric / Subject / Pre-Gate / RuleEvent / EvalResult 各自落在哪个包）。

---

## 四、SPI 接口落点

⏳ 未展开。

> 展开时落定：v1 已确定的 SPI 接口清单（来自 00-decisions / 01-concepts 关键边界）——
>
> - `ConditionType`（D12 / §3.6）
> - `ActionType` + `ActionHandler`（D16 / §3.7）
> - `MetricSource`（§3.9）
> - `RuleVersionWatcher`（D17 / §3.12）
> - `RuleVersionExecutor`（D20 §5）
> - `Scheduler`（D11）
> - `TraceWriter`（D21）
> - `Pre-Gate` 各类（§3.14）
>
> 落定每个 SPI 的：所在模块 + 所在包 + 是否对业务方公开（业务方依赖的"最薄面"） + 是否允许业务方实现替换。

---

## 五、依赖方向与禁止环

⏳ 未展开。

> 展开时落定：模块间允许的依赖箭头 + 禁止反向依赖的红线 + 工具校验（如 ArchUnit / Maven enforcer）是否引入。注意 SPI 模块应当是被依赖的"最底层"，禁止反向依赖业务实现。

---

## 六、配置文件分布

⏳ 未展开。

> 展开时落定：`application.yml` 主配置项归属（`engine.rule.*` 命名空间）+ 各模块是否提供 `spring.factories` / `AutoConfiguration` + 配置项目录（默认值见 [`07-operability.md`](./07-operability.md)，本节只列**结构**不列默认值）。

---

## 七、测试组织

⏳ 未展开。

> 展开时落定：
>
> - **测试目录结构**：每个模块的 `src/test/java` 组织约定（按功能 / 按类型）+ 集成测专门模块或专门目录
> - **单测策略**：核心评估器 / Matcher / Pre-Gate / Dispatcher 的单测最低覆盖率要求 + mock 策略（mock SPI 边界，不 mock 内部协作类）
> - **集成测策略**：Scene / Rule 端到端用例（含 dry-run + PUSH + PULL 三种模式）+ DB 用真实 schema（不 mock 数据库，避免 mock 与生产迁移行为分歧）
> - **性能基线**：评估 P99 / metric 预拉 P99 / Dispatcher 吞吐基线测试 + 回归门槛
> - **测试数据**：seed SQL / 公共 fixture / examples/ 目录的测试数据复用
> - **CI 集成**：测试在哪个阶段跑（pre-commit / PR / merge）+ 性能基线测试触发条件（避免每次 PR 全跑）

---

## 八、v1 不做的拆分

⏳ 未展开。

> 展开时归档：v1 阶段不拆但 v2 要拆的模块（如嵌入式 SDK 模式需要把 core 进一步切出无 Spring 依赖的纯逻辑包，详见 [`08-evolution.md`](./08-evolution.md) §2.14）+ v1 暂时合并的模块（如 job 是否独立 jar）。

---

## 九、维护原则

- 本文档只承载**工程结构契约**。具体类名 / 方法签名 / 实现代码不入。
- 模块边界 / 包结构变更必须回写本文档对应章节，且若变更影响业务方依赖（如 SPI 模块更名 / 拆分），同步在 [`README.md`](./README.md) §七 版本史登记。
- 新增 SPI 接口必须回填 §四 SPI 接口落点表。
- 新增测试维度（如契约测试 / 混沌测试）必须回填 §七 测试组织。
- v1 阶段任何"暂时合并"的模块在演进时拆分前，回写 §八 + [`08-evolution.md`](./08-evolution.md) 对应演进锚点。
