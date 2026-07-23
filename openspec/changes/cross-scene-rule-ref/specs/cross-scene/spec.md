# Spec: DECISION_FLOW 跨 Scene RuleRef

## ADDED Requirements

### Requirement: RuleRefNode 可引用同 tenant 任意 Scene 的规则

DECISION_FLOW 规则的 RuleRefNode.ruleCode **SHALL** 能引用同 tenant 下任意 Scene 的已发布规则，不限于 flow 所属 Scene。发布期按 `(tenantId, ruleCode)` 查找，若无 ACTIVE 版本则拒绝发布。

#### Scenario: 跨 Scene 引用发布成功

- **GIVEN** 租户 T 下有 Scene A（含规则 `base-check` ACTIVE）和 Scene B（含 DECISION_FLOW `risk-flow`）
- **WHEN** `risk-flow` 的 RuleRefNode.ruleCode = `base-check`，发布 `risk-flow`
- **THEN** 发布成功，`FlowBody.referencedSnapshots` 含 `base-check` 的完整快照
- **AND** 快照的 sceneCode = Scene A 的 code（被引规则归属 scene），不是 Scene B

#### Scenario: 被引规则无 ACTIVE 版本则拒绝

- **GIVEN** `base-check` 存在但状态为 DRAFT（无 ACTIVE 版本）
- **WHEN** 发布引用它的 DECISION_FLOW
- **THEN** 发布失败，报错 `DECISION_FLOW 引用的规则无 ACTIVE 版本`

#### Scenario: 被引规则 code 在 tenant 内不存在则拒绝

- **GIVEN** `unknown-rule` 在 tenant T 下不存在
- **WHEN** 发布引用 `unknown-rule` 的 DECISION_FLOW
- **THEN** 发布失败，报错 `DECISION_FLOW 引用的规则不存在`

### Requirement: ruleCode 在 tenant 内唯一

创建规则草稿时，ruleCode **SHALL** 在同 tenant 内唯一（不区分 Scene）。同一 tenant 下两个不同 Scene 不得出现相同 code。

#### Scenario: 同 tenant 重复 code 被拒绝

- **GIVEN** tenant T 下 Scene A 已有规则 `check-amount`
- **WHEN** 在 Scene B 创建 code 也为 `check-amount` 的规则
- **THEN** 创建失败，报错 `规则编码已存在`

### Requirement: 反向血缘查询

`GET /admin/v1/rules/{code}/referencedBy?tenantId=` **SHALL** 返回该 tenant 下所有 ACTIVE DECISION_FLOW 规则中引用了此 code 的规则列表（ruleCode + sceneCode + ruleDefinitionId）。

#### Scenario: 查询引用了某规则的 flow

- **GIVEN** `base-check` 被 3 个 DECISION_FLOW 引用（跨 2 个 Scene）
- **WHEN** `GET /admin/v1/rules/base-check/referencedBy?tenantId=1`
- **THEN** 返回 3 条记录，含 ruleCode / sceneCode / ruleDefinitionId

#### Scenario: 无引用时返回空列表

- **GIVEN** `check-amount` 未被任何 DECISION_FLOW 引用
- **WHEN** 查询 referencedBy
- **THEN** 返回空列表 `[]`

### Requirement: 评估链路不感知跨 Scene 变更

跨 Scene 引用的 DECISION_FLOW 评估时 **SHALL** 与同 Scene 引用行为完全一致——FlowExecutor 从冻结快照执行，不经倒排索引路由。

#### Scenario: 跨 Scene 引用评估命中

- **GIVEN** Scene B 的 `risk-flow` 引用了 Scene A 的 `base-check`（payload.amount > 100 → 命中）
- **WHEN** 发起评估，传入 `payload.amount = 200`
- **THEN** `base-check` RuleRef 命中，flow 按图继续，最终 finalDecision 正确
