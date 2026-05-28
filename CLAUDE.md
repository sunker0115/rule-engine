# rule-engine — 通用规则引擎产品

本仓库是**通用规则引擎产品**的设计与实现库,从 `cpt/integrate-service` 的 `docs/rule-engine/` 抽离独立。

## 目录划分

| 目录 | 职责 | 修改原则 |
|---|---|---|
| `docs/` | 产品设计:`00-decisions` / `01-concepts` / ... / `10-api-contract` + `examples/` | 改前用 `doc-consistency-review` skill 扫文档自洽性 |
| `src/`(待建) | 代码骨架,按 `docs/09-skeleton.md` 规划落地 | 改动审查派 `rule-engine-reviewer` agent |

## 专用 review agent(只读)

- **改 `docs/**` 或将来的 `src/**`** → 显式调用 `rule-engine-reviewer` 审"代码 ↔ 文档对齐"。该 agent 仅在显式调用时启用,不要主动触发。

## 文档纪律

- `00-decisions.md` 是单一决策日志,新决策追加,不改历史条目状态以外的内容。
- 跨文档改动(如同时动 `01-concepts` 和 `02-runtime`)前先跑 `doc-consistency-review` skill。
- `docs/examples/` 是规约示例,改示例需同步对应章节。
