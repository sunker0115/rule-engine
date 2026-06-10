# 规则草稿/版本生命周期 — 设计

> 状态:设计待评审(2026-06-10,brainstorming 达成)。把规则的"草稿—版本—发布"生命周期补全并统一:草稿即完整合法冻结快照(只差激活),支持编辑/出新版本/回退/删草稿,并修掉 dry-run 副作用 bug。

## 一、背景与目标

当前规则生命周期是**一次性**的,且有一处 bug:

1. **无规则演进**:`createDraft` 对已存在规则码直接拒绝、`publish` 要求 `status==DRAFT`。一条规则**建草稿 → 发布(v1)→ 之后只能 `disable`**。**没有任何端点**能:编辑草稿、给已发布规则出新版本(v2)、回退。
2. **草稿是半成品**:`createDraft` 存 raw(未解析:dataType=null、metric/payload 依赖空、decision 未冻结),解析+校验全压在 `publish`。导致草稿不是可信快照——dry-run 草稿失真、发布前无法忠实预览。
3. **dry-run 副作用 bug**:`/api/v1/rule/dry-run` 不带 `ruleVersionId` 时,落到真评估的候选分支(不门控 `isDryRun`)→ 落真 `evaluation_session`+node_trace、**派发真 action**,违反 dry-run 无副作用语义。

**核心前提(已定)**:**草稿和正式走一样的逻辑,只是状态不同**。草稿在**创建/编辑时**就跑全套**解析 + 校验**,产出完整合法的冻结快照,与正式版只差 `status`/是否激活。

**目标**:把生命周期补全为 createDraft / editDraft / newVersion / rollback / publish / delete,并以"草稿即完整快照"为基石,顺带根除 dry-run 副作用 bug。

## 二、版本模型(钉死)

| 操作 | 版本号 | 状态 | 行为 |
|---|---|---|---|
| 建新规则草稿 `createDraft` | v1 | DRAFT | 建时 `resolveAndValidate` |
| **编辑草稿 `editDraft`** | **不变** | DRAFT | **原地更新**草稿版本内容,重跑 `resolveAndValidate`;只能编辑 DRAFT 版本 |
| **出新版本 `newVersion`** | v_n+1 | DRAFT | 给已发布规则建新草稿版本;可选「从旧版本 X 克隆内容」(= 回退);建时 `resolveAndValidate` |
| 发布 `publish` | **不变** | DRAFT→ACTIVE | **只激活** + supersede 旧 ACTIVE + 发 `SceneChangedEvent` 索引热更;**不增版本、不重解析、不重校验** |
| 回退 `rollback` | v_n+1 | DRAFT→(可发布) | = `newVersion(fromVersionId=旧好版本)`,内容克隆旧版本、按当前世界重解析 → 再 `publish` 激活(supersede 坏版本) |
| 删草稿 `delete` | — | — | 见 §六 |

**不变量**:
- 版本号**只在「新建版本」时 +1**(createDraft v1、newVersion/rollback v_n+1);**editDraft 原地、publish 激活,都不增版本**。
- **一条规则同时最多挂一条 DRAFT**(待发布的那条);editDraft 改的就是它。已发布则同时有 ACTIVE(线上)+ 至多一条 DRAFT(待发布)。
- **已发布版本(ACTIVE/SUPERSEDED)不可变**(D6/D19):不能编辑、不能删;要改只能出新版本。

## 三、核心:`resolveAndValidate` 抽取(前提 A 落地)

把现 `PublishService.publish` 里的**解析+校验**段抽成可复用方法 `resolveAndValidate(scene, rule, 草稿输入) → 已冻结的 rule_version 字段`,内容(沿用现有逻辑,顺序不变):
- kind 合法性 + 结构校验(SCORECARD 根/权重、DECISION_TREE/TABLE 结构);
- `triggerEventTypes ⊆ scene.eventTypes`、preGate ROLLOUT 参数校验;
- `MetricDependencyCollector` 收集 → 查 ACTIVE metric(无 ACTIVE 则拒)→ 冻结 metricVersion + dataType;`MetricSafetyValidator`;
- `PayloadFieldCollector` 收集 → 校验字段在 `scene.payloadSchema` 声明 → 冻结 `payloadDependencies(name,dataType,required)` + dataType;
- decision 绑定冻结(从 `decision_definition` 取 name/priority/actions 冻进 `decision_bindings`;decisionCode 不存在则拒);
- `AstDataTypeResolver.resolve(ast, dataTypeMap, payloadTypeMap)` → resolvedAst。

**调用点**:`createDraft` / `editDraft` / `newVersion`(三者建/改草稿时都跑)。**`publish` 不再调用它**。

**`publish` 退化为激活**:加载该规则最新 DRAFT 版本(已是冻结快照)→ `status=ACTIVE`、supersede 旧 ACTIVE、`rule_definition.status=PUBLISHED`、发 `SceneChangedEvent`。仅校验激活前置(存在 DRAFT 版本等),**不重解析、不重校验**。

## 四、dry-run 重设计

`POST /api/v1/rule/dry-run` 改为 `ruleId` / `ruleVersionId` **二选一必传**:
- 传 `ruleVersionId` → 试跑该精确版本;
- 传 `ruleId`(不带版本)→ 取该规则**最新版本(最高版本号,含 DRAFT)**试跑(因前提 A,草稿已是完整冻结快照 → **忠实**);
- 都不传 → **400**(`MISSING_DRYRUN_TARGET: 必须指定 ruleId 或 ruleVersionId`);
- 解析为版本 id 的查询:`SELECT rv.id ... WHERE rd.tenant_id AND rd.id=ruleId ORDER BY rv.version DESC LIMIT 1`(eval-svc 侧 mapper)。

**bug 根除**:dry-run 永远先解析出一个版本 id → `doEvaluate` 走「带版本」分支(loadById 单快照 + dry-run persister)→ **结构上不再触达候选分支**那条有副作用的路径。候选分支保持原样(仅真评估 `isDryRun=false` 走)。

## 五、出新版本 / 回退

- **出新版本 `newVersion(tenantId, ruleId, 草稿输入)`**:要求规则当前**无未发布 DRAFT**(一条规则同时一条 DRAFT);新建 v_max+1 的 DRAFT 版本,跑 `resolveAndValidate`。`rule_definition.status` 视情况(已 PUBLISHED 的保持 PUBLISHED,新增一条 DRAFT 版本挂其下)。
- **回退 `rollback(tenantId, ruleId, fromVersionId)`**:`newVersion` 的特例——草稿**内容克隆自 fromVersionId**(其 conditionAst/decisionBindings/preGates/triggerEventTypes),按**当前** scene/metric/decision 状态重跑 `resolveAndValidate`(回退到旧**逻辑**、解析对齐当前世界,符合 D19)。产出 v_max+1 DRAFT;**激活仍走显式 `publish`**(让回退也能先 dry-run 预览再激活,避免盲切)。
  - 可选便利端点 `POST /rules/{ruleId}/rollback?toVersionId=X` 内部 = newVersion(clone X) + 返回新草稿;是否自动 publish 留作开关(默认否,需显式发布)。

## 六、删草稿(级联)

只删 DRAFT,碰到 ACTIVE/SUPERSEDED 一律拒绝(D19):
- **删整条未发布规则** `DELETE /admin/v1/rules/{ruleId}`:仅当该规则**从未发布过**(无任何 ACTIVE/SUPERSEDED 版本,rule_definition 仍 DRAFT)→ 级联删 `rule_definition` + 其全部 `rule_version`(都是 DRAFT)。
- **删单个待发布草稿版本** `DELETE /admin/v1/rules/{ruleId}/versions/{versionId}`:仅当该 version 是 DRAFT → 删那条 rule_version(保留线上 ACTIVE 不动)。
- **级联范围只 `rule_version`**:草稿从未激活 → 无 evaluation_session/node_trace/action_execution 引用、未进 Matcher 索引;`audit_log` 是历史不动。

### 6.1 删除边界 / 引用完整性(已定)

premise A 让"规则 → metric / decision"产生**冻结耦合**(草稿冻结 metric 版本 + decision 快照)。需明确两个方向都不产生悬空引用:

- **删草稿规则(本 spec 的删除)无悬空**:冻结引用只是 `rule_version` 行里的数据,删行不碰被引用的 metric/decision/scene 实体;草稿未激活,无下游。premise A 的"引用须 ACTIVE"是**建/改草稿时**的校验,与删除无关。
- **被依赖资源(metric/decision/scene)当前无「硬删除」**:只有 新版本 / `disable` / `PATCH`。所以草稿冻结的 (code, version) 引用**永不指向被删资源**。`disable` 只改状态、不删旧版本行 → 已冻结的草稿/已发布规则评估时仍按冻结版本取数,照常工作;`disable` 只挡**新草稿**(premise A 要 ACTIVE)再引用它,符合预期。
- **未来护栏**:若将来给 metric/decision/scene 加「硬删除」,**必须先做影响面拦截**(参照 metric 现有 `/{code}/versions/{v}/impact`:被任何 rule_version 引用即拒删)。本 spec **不**实现资源硬删除,仅在此立约束,避免未来踩雷。

## 七、API 面(增/改)

| 端点 | 方法 | 说明 |
|---|---|---|
| `/admin/v1/rules` | POST | createDraft(现有)+ 接 `resolveAndValidate` |
| `/admin/v1/rules/{ruleId}/draft` | PUT | **新增** editDraft:原地更新该规则 DRAFT 版本(不增版本) |
| `/admin/v1/rules/{ruleId}/versions` | POST | **新增** newVersion:出新版本草稿;body 可带 `fromVersionId`(= 回退克隆) |
| `/admin/v1/rules/{ruleId}/publish` | POST | publish(重构为激活,现有路由) |
| `/admin/v1/rules/{ruleId}` | DELETE | **新增** 删整条未发布规则(级联) |
| `/admin/v1/rules/{ruleId}/versions/{versionId}` | DELETE | **新增** 删单个待发布草稿版本 |
| `/api/v1/rule/dry-run` | POST | 改:`ruleId`/`ruleVersionId` 二选一必传 |

## 八、已定取舍(brainstorming)

1. **范围 = 完整生命周期(六块)+ dry-run 修复**,一个内聚 spec(共享 `resolveAndValidate` + 版本模型),不拆。
2. **前提 A**:草稿创建/编辑即跑全套解析+**硬校验**(payload 须声明、metric 须 ACTIVE、decision 须存在);校验不过 → 拒绝建/改草稿。代价:**不能存引用未就绪资源的 WIP 草稿**(用"先备依赖再建规则"的工作流消化)。
3. **freeze-at-draft**:草稿在 create/edit 时刻冻结 metric 版本/decision 快照;依赖之后变了 → 草稿是旧快照,重建/重编辑草稿刷新。**优点:你预览(dry-run)的 = 你发布的**,可预测性优于"publish 时才冻"。
4. **回退 = 新版本号 + 克隆旧内容**(D19),不直接切回旧 version,避免审计断层;回退草稿默认仍需显式 publish。
5. **草稿编辑不增版本**(原地);版本号只在新建版本时 +1;publish 不增版本。
6. **greenfield**:库里旧的 raw(未解析)草稿直接清掉,不做兼容(`publish` 不再兜底解析)。
7. **dry-run 修复**:靠"必须指定目标 → 永远有版本 id"从结构上根除副作用,而非给候选分支打 `!isDryRun` 补丁。

## 九、影响面

- **rule-config-svc**:`PublishService` 抽 `resolveAndValidate` + `publish` 改激活;新增 `editDraft`/`newVersion`/`rollback`/删草稿 逻辑;`RuleVersionMapper`/`RuleDefinitionMapper` 加查询(最新草稿、最高版本、级联删);`ConfigService` 接口扩展。
- **rule-api**:`RuleController` 加 PUT draft / POST versions / DELETE rule / DELETE version 端点 + 请求 DTO;`EvalController.dryRun` 加 `ruleId` 参数。
- **rule-eval-svc**:`RuleVersionReadMapper` 加「按 ruleId 取最新版本 id」查询;`EvalServiceImpl.dryRun` 改签名(ruleId/ruleVersionId 解析);candidate 分支不变(bug 由"永远有版本 id"消除)。
- **docs**:`00-decisions`(追加生命周期决策)、`10-api-contract`(新端点 + dry-run 改)、`01/02`(草稿语义)、`functional-test-coverage`(更新 dry-run 行 + 新流程行)。
- **DB**:无新表/列(复用现有 rule_definition/rule_version + status);删草稿是 DELETE,无迁移。

## 十、测试

- `resolveAndValidate` 抽取后:createDraft/editDraft/newVersion 产出的草稿都是冻结快照(dataType/metricDeps/payloadDeps/decision 冻结);校验不过则拒(payload 未声明/metric 非 ACTIVE/decision 不存在)。
- 版本号:editDraft 不增、newVersion +1、publish 不增、rollback +1。
- publish 激活:DRAFT→ACTIVE + 旧 ACTIVE→SUPERSEDED + SceneChangedEvent;不重解析。
- dry-run:ruleVersionId→精确版本;ruleId→最新含 DRAFT;都不传→400;**dry-run 不落 evaluation_session、不派发 action**(回归断言)。
- 删草稿:删整条未发布规则级联删 rule_version;删单个 DRAFT 版本;碰 ACTIVE/SUPERSEDED 拒绝。
- 回退:从旧版本克隆 → 新版本号 → 内容按当前世界重解析 → publish 激活 supersede 坏版本。
- 功能测试(真服务):建草稿(看冻结)→ editDraft(版本不变、内容变)→ publish(激活)→ newVersion(v2)→ dry-run v2 草稿(忠实、无副作用)→ 发现问题 → rollback 到 v1 内容(出 v3)→ publish → 验线上回到旧逻辑;删一条未发布草稿规则验级联。

## 十一、依赖与顺序

依赖 payload 直接引用(A)+ 场景输入清单(B)已落地(`resolveAndValidate` 复用其 collector/resolver/校验)。本 spec 独立成篇,实现计划另出。
