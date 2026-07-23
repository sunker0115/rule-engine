# Tasks: cross-scene-rule-ref

## T1. RuleDefinitionMapper + PublishService 核心改动

- 新增 `findByTenantAndCode`
- `freezeReferencedRule` 查询 + sceneCode 修正
- `createDraft` code 唯一性校验改为 tenant 级

## T2. RuleImportService + RuleBundleController

- 导入查重 tenant 级
- `?sceneId=` → `?sceneCode=`

## T3. 反向血缘 RuleLineageService

- 新增接口 + 实现
- 新增 API endpoint `GET /admin/v1/rules/{code}/referencedBy`

## T4. 前端

- CenterPanel flowSceneRules → tenant 全量
- FlowNodeInspectorDrawer RuleRef 下拉 + sceneCode 分组
- FlowCanvasEditor 新建叶子规则 scene 弹选

## T5. 测试 + 全量验证

- PublishService 跨 scene RuleRef 单测
- 反向血缘单测
- 全量 clean test
