# Tasks: cross-scene-rule-ref

## T1. DDL + 实体

- V1_41：rule_definition DROP scene_id，ADD scene_code
- RuleDefinition.java：sceneId Long → sceneCode String
- RuleDefinition.draft() 工厂：参数改 sceneCode

## T2. RuleDefinitionMapper 重构

- 新增 findByTenantAndCode(tenantId, code)
- findBySceneAndCode → findByTenantAndCode（删 sceneId 参数）
- findByTenantAndSceneIds → findByTenantAndSceneCode

## T3. PublishService 核心改动

- createDraft：去掉 scene.getId() 翻译，直接用 sceneCode
- freezeReferencedRule：查询改 + 快照 sceneCode 修正
- code 唯一性校验改为 tenant 级

## T4. 其余 config-svc 改动

- ConfigServiceImpl：去掉 sceneCode→sceneId 翻译
- RuleImportService：查重改 tenant 级
- RuleAnalysisServiceImpl / MetadataServiceImpl：查询改 scene_code
- RuleExportService：rd.getSceneCode() 替代 sceneById 查询

## T5. eval-svc SQL JOIN 改写

- RuleVersionReadMapper：3 处 JOIN 改写

## T6. 反向血缘

- RuleLineageService 接口 + RuleLineageServiceImpl
- GET /admin/v1/rules/{code}/referencedBy

## T7. rule-api

- RuleBundleController：?sceneId= → ?sceneCode=
- 新增 referencedBy endpoint

## T8. 前端

- CenterPanel：flowSceneRules tenant 全量
- FlowNodeInspectorDrawer：RuleRef 下拉 + sceneCode 分组
- FlowCanvasEditor：新建叶子规则 scene 弹选

## T9. 全量测试 + 验证

- PublishService 跨 scene + 快照 sceneCode 正确性单测
- eval-svc SQL JOIN 正确性测试
- 反向血缘单测
- 全量 clean test
