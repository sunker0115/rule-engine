# Spec: rule_definition 去 scene_id + DECISION_FLOW 跨 Scene RuleRef

## ADDED Requirements

### Requirement: rule_definition 以 scene_code 关联 Scene

`rule_definition` 表 **SHALL** 以 `scene_code VARCHAR(64)` 字段关联 Scene，不再存储 `scene_id`。规则实体的 `sceneCode` 字段直接为业务标识，消除内部 `sceneId` 翻译层。

#### Scenario: 创建规则时 scene_code 直接存储

- **GIVEN** Scene `demo.login` 存在，tenantId=1
- **WHEN** 创建规则 `check-amount`，sceneCode=`demo.login`
- **THEN** `rule_definition.scene_code = 'demo.login'`，不存储任何 scene 数值 id
- **AND** `uk_tenant_code` 约束生效，同 tenant 下 code 唯一

### Requirement: ruleCode 在 tenant 内唯一

创建规则草稿时，ruleCode **SHALL** 在同 tenant 内唯一（不区分 Scene）。

#### Scenario: 同 tenant 重复 code 被拒绝

- **GIVEN** tenantId=1 下 Scene A 已有规则 `check-amount`
- **WHEN** 在 Scene B 创建 code 也为 `check-amount` 的规则
- **THEN** 创建失败，报错 `规则编码已存在`

### Requirement: RuleRefNode 可引用同 tenant 任意 Scene 的规则

DECISION_FLOW 规则的 RuleRefNode.ruleCode **SHALL** 能引用同 tenant 下任意 Scene 的已发布规则。发布期按 `(tenantId, ruleCode)` 查找，若无 ACTIVE 版本则拒绝发布。被引规则快照的 `sceneCode` **SHALL** 反映被引规则自己所属的 Scene，不是 flow 的 Scene。

#### Scenario: 跨 Scene 引用发布成功，快照 sceneCode 正确

- **GIVEN** tenant T 下：Scene A（含规则 `base-check` ACTIVE，scene_code=`risk.base`）和 Scene B（含 DECISION_FLOW `risk-flow`，scene_code=`risk.transfer`）
- **WHEN** `risk-flow` 的 RuleRefNode.ruleCode = `base-check`，发布 `risk-flow`
- **THEN** 发布成功，`FlowBody.referencedSnapshots["base-check"].sceneCode = "risk.base"`（不是 `"risk.transfer"`）

#### Scenario: 被引规则无 ACTIVE 版本则拒绝

- **GIVEN** `base-check` 存在但无 ACTIVE 版本
- **WHEN** 发布引用它的 DECISION_FLOW
- **THEN** 发布失败，报错 `DECISION_FLOW 引用的规则无 ACTIVE 版本`

#### Scenario: 跨 Scene 引用评估命中

- **GIVEN** Scene B 的 `risk-flow` 已发布（引用了 Scene A 的 `base-check`，条件 payload.amount > 100 命中 BLOCK）
- **WHEN** 评估事件 `{sceneCode: "risk.transfer", payload: {amount: 200}}`
- **THEN** FlowExecutor 从冻结快照执行 `base-check`，命中，flow 按图继续产出正确 finalDecision

### Requirement: 反向血缘查询

`GET /admin/v1/rules/{code}/referencedBy?tenantId=` **SHALL** 返回该 tenant 下所有 ACTIVE DECISION_FLOW 规则中引用了此 code 的规则列表。

#### Scenario: 查询引用了某规则的 flow

- **GIVEN** `base-check` 被 3 个 DECISION_FLOW 引用（跨 2 个 Scene）
- **WHEN** `GET /admin/v1/rules/base-check/referencedBy?tenantId=1`
- **THEN** 返回 3 条记录，含 ruleCode / sceneCode / ruleDefinitionId

#### Scenario: 无引用时返回空列表

- **GIVEN** `check-amount` 未被任何 DECISION_FLOW 引用
- **WHEN** 查询 referencedBy
- **THEN** 返回 `[]`

### Requirement: 对外 API 不暴露 scene 代理键

面向外部的 API **SHALL NOT** 接受或返回 `sceneId`（数值型内部代理键）。所有涉及 Scene 的外部参数统一使用 `sceneCode`（业务字符串标识）。

#### Scenario: Bundle 导出改用 sceneCode

- **GIVEN** 调用 `GET /admin/v1/rules/export?tenantId=1&sceneCode=demo.login`
- **WHEN** 请求正常
- **THEN** 导出成功，不再接受 `?sceneId=Long` 参数
