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

## 测试纪律

- 每个 Task 提交前必须运行该模块的所有测试，全部通过才能 commit。
- 使用 `mvn-env` skill 设置环境后执行：`$MVN -pl <module> -am test`
- 有测试失败不得用 `-DskipTests` 绕过，必须修复后再提交。

## 文档纪律

- `00-decisions.md` 是单一决策日志,新决策追加,不改历史条目状态以外的内容。
- 跨文档改动(如同时动 `01-concepts` 和 `02-runtime`)前先跑 `doc-consistency-review` skill。
- `docs/examples/` 是规约示例,改示例需同步对应章节。
