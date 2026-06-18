# 规则↔指标血缘与变更影响分析（B33）— 设计

> 状态：设计稿（2026-06-18，v3 = v2 后端收敛 + 前端设计）。来源 `08-evolution.md` §2.28。
>
> 核心立场：metric→规则影响面查询本仓已实现（`findReferencingRules` + `/metrics/{code}/versions/{version}/impact`，按需扫 DB、版本感知、强一致）。B33 后端真正缺口是**反向 Decision→规则**；前端则需补齐 Decision 信息架构、统一治理信息（血缘/影响面）的呈现范式。全程**按需扫 DB、零常驻索引、零 DDL、不碰评估热路径**。

---

## 一、范围与不变量

**做什么**：
- 后端：补 Decision→规则反向血缘查询 + Decision 详情/批量计数端点 + Metric 批量计数端点。
- 前端：复用 B31「摘要/抽屉 + 可点定位」治理范式，统一 Decision 与 Metric 两侧血缘呈现，补 Decision 详情页（修信息架构不对称），列表徽标 + 编辑器反向提示，停用前影响拦截。

**不变量（不得破）**：
1. **按需扫 DB 房规**：与 `MetricWriteServiceImpl.findReferencingRules` 同范式；不引入常驻索引/事件，读已提交 DB（强一致）。
2. **ACTIVE 现实口径**：以 `rv.status=ACTIVE` 判定参与评估，不按 `rule_definition.status` 过滤（对齐既有）。
3. **复用冻结真相源**：Decision 引用取 `rule_version.decision_bindings`（typed），metric 取 `metric_dependencies`，不另写 AST 遍历。
4. **外科式新增**：不重构已工作的 `findReferencingRules`；前端复用既有组件范式（AntD 5 + B31 抽屉），不另造设计语言。
5. **批量计数一次扫**：列表「被 N 引用」徽标走批量计数端点（一次扫聚合全表 `code→count`），不每行单查。

---

## 二、后端设计

### 2.1 `DecisionService`（config-svc，新增 3 方法 + 2 record）

```java
/** 产出某 decision 的 ACTIVE 规则（抽屉/详情 Tab/停用拦截）。口径同 findReferencingRules。 */
List<RuleRef> findRulesProducingDecision(Long tenantId, String decisionCode);

/** 单个 decision（详情页加载）；不存在抛 IllegalArgumentException。 */
DecisionDefinition get(Long tenantId, String code);

/** 一次扫聚合 tenant 下每个 decisionCode 的 ACTIVE 规则产出计数（列表徽标）。 */
List<UsageCount> countRuleUsages(Long tenantId);

/** 产出某 decision 的规则引用项。sceneCode 关联 rule_definition.scene_id；status 为 rule_definition.status。 */
record RuleRef(Long ruleDefinitionId, String ruleCode, String ruleName, String sceneCode, String status) {}

/** code → 引用计数。 */
record UsageCount(String code, int count) {}
```

`DecisionServiceImpl` 实现：新增依赖 `RuleDefinitionMapper` / `RuleVersionMapper` / `SceneMapper`；`findRulesProducingDecision` 四步骤镜像 `findReferencingRules`，谓词 `containsDecision(rv.getDecisionBindings(), code)`；`countRuleUsages` 单次扫所有 ACTIVE rule_version、按 decisionBindings 的 decisionCode 累加（去重每规则对同一 code 只计一次）；`get` 走 `mapper.findByCode` 包一层非空校验。均 `@Transactional(readOnly = true)`。

### 2.2 Metric 批量计数（config-svc）

`MetricWriteService` 新增（与 `findReferencingRules` 同处，保持 metric usage 逻辑聚合）：

```java
/** 一次扫聚合 tenant 下每个 metricCode 的 ACTIVE 规则引用计数（版本无关，列表徽标用）。 */
List<UsageCount> countRuleUsages(Long tenantId);
```

`UsageCount` 复用 `DecisionService.UsageCount`（或各自内嵌，二选一时优先共享一个 `config.api.service` 级 record；实现期定，避免双 record）。实现单次扫 ACTIVE rule_version、按 `metric_dependencies` 的 metricCode 去重累加（同一规则引用同 metric 多版本只计一次）。版本感知的单 metric 影响面沿用既有 `findReferencingRules`，不动。

### 2.3 HTTP 端点（rule-api，admin）

| 端点 | service | 用途 |
|---|---|---|
| `GET /admin/v1/decisions/{code}/sources?tenantId=` | findRulesProducingDecision | 抽屉/Tab/停用拦截 |
| `GET /admin/v1/decisions/{code}?tenantId=` | get | 详情页加载 |
| `GET /admin/v1/decisions/usage-counts?tenantId=` | countRuleUsages | decision-list 徽标 + 编辑器 decision 徽标 |
| `GET /admin/v1/metrics/usage-counts?tenantId=` | MetricWriteService.countRuleUsages | metric-list 徽标 + 编辑器 metric 徽标 |
| `GET /admin/v1/metrics/{code}/versions/{version}/impact`（既有，不动） | findReferencingRules | metric 详情 impact Tab |

响应 DTO（controller 内嵌 record，`ApiResponse` 包装）：`DecisionSourcesResponse(String decisionCode, List<RuleRef> sources, int sourceCount)`；usage-counts 直接返回 `List<UsageCount>`；`get` 返回 `DecisionDefinition`。`tenantId` 用 `@RequestParam Long`。

---

## 三、前端设计

技术栈：React 18 + TS + AntD 5 + Zustand + react-router 6 + i18next（zh-CN/en，强类型 key）。复用项目最成熟治理范式 = B31 规则集分析的「摘要/抽屉 + 左色条可点卡片 + 行内 Tag」（`rule-editor/RuleAnalysisDrawer.tsx`）。

### 3.1 信息架构（方向 C 混合）

- Decision 列表行点「被 N 引用」徽标 → **血缘抽屉**（快速查，不跳页）；点行其它区域 → **Decision 详情页**（看全貌/编辑）。
- 补 Decision 详情页消除与 Metric 详情的不对称。

### 3.2 共享血缘组件（一份数据，两种呈现）

- **抽屉版**（width≈460，列表行徽标 / 编辑器徽标触发）：按 sceneCode 分组的左色条可点卡片，卡片含 ruleCode + 名称 + 状态 Tag（PUBLISHED 绿 / DISABLED 灰），点击下钻规则编辑器（`/rule-editor/:ruleId` 路由）。复用 B31 配色。
- **详情页 Tab 版**（全宽表格）：列 ruleCode / 名称 / 场景 / 状态，可按场景/状态筛选，行点击下钻。
- **空态**：`Empty` +「暂无规则产出该 Decision，可安全停用/下线」。**加载态**：AntD `Skeleton`。
- 两者共用同一数据 hook（拉 `/sources`）+ 同一下钻逻辑；抽屉=卡片、Tab=表格，仅形态随上下文不同。

### 3.3 Decision 详情页（新路由 `/decisions/:code`）

镜像 `metric-detail`：`Descriptions` + `Tabs[基本信息 | 被引用规则·N]`。
- 基本信息：内联编辑（name/priority/description），编辑从列表 Modal 挪到详情页（创建仍走列表 Modal，对齐 Metric）。
- 被引用规则 Tab：懒加载（进 Tab 才查 `/sources`），用共享组件表格版。
- 停用按钮：点击时若该 decision 仍被 ACTIVE 规则产出，弹二次确认列出受影响规则（复用 metric breaking-change 二次确认范式）——「变更影响预检」运营落点。

### 3.4 Metric 侧统一

- metric-list 加「被引用」徽标（数据来自 `/metrics/usage-counts`）+ 点徽标弹同一抽屉。
- metric-detail「影响面」Tab 升级：自动加载（进 Tab 即查，去掉手动按钮）+ 行可下钻 + 版本切换；表格换共享组件。版本感知沿用既有 impact 端点。

### 3.5 规则编辑器反向提示

- `ConditionCard.tsx`（metric 下拉旁）、`DecisionBindingEditor.tsx`（decision 绑定旁）各挂一个可点小徽标「还被 N 条引用 ›」，点开同一血缘抽屉。
- 计数来源：编辑器加载时取一次 `/metrics/usage-counts` + `/decisions/usage-counts`，徽标按 code 查表，不为每个条件单查。

### 3.6 落点文件（前端）

| 类型 | 文件 |
|---|---|
| API client | `src/api/decision.ts`（+getDecisionSources/getDecision/getDecisionUsageCounts）、`src/api/metric.ts`（+getMetricUsageCounts）、`src/constants/api-endpoints.ts`（+端点常量） |
| 共享组件 | `src/components/lineage/`（新）：`LineageDrawer.tsx` + `LineageTable.tsx` + `useLineage.ts` hook |
| Decision | `src/pages/decision-detail/`（新页）、`src/pages/decision-list/index.tsx`（行徽标+抽屉+行点跳详情）、`src/config/columns/decision.tsx`（徽标列）、`src/constants/routes.ts` + `src/router.tsx`（新路由） |
| Metric | `src/pages/metric-list/index.tsx` + `src/config/columns/metric.tsx`（徽标列+抽屉）、`src/pages/metric-detail/index.tsx`（impact Tab 升级） |
| 编辑器 | `src/pages/rule-editor/ConditionCard.tsx`、`DecisionBindingEditor.tsx`（徽标） |
| i18n | `src/i18n/locales/{zh-CN,en}/decision.ts` + `metric.ts` + 新建 `lineage.ts`；先在 `src/i18n/types.ts` 加 key |

---

## 四、边界 / 不做

- 不建常驻索引、不挂事件、零 DDL、不碰评估热路径、不动既有 `findReferencingRules`。
- 不自动触发 §2.27 效果对照 / §2.25 回放（停用拦截只列清单）。
- 不做跨资源全局「关系图谱」视图（problem #8 留后续）；不强行抽 metric/decision 公共扫描器。
- **已知缺口**：Decision 引用仅认 `decision_bindings`，metric 仅认 `metric_dependencies`（只认发布期冻结的结构化引用，与既有对称）。

---

## 五、测试策略

- **config-svc 单测（Mockito，镜像 `MetricWriteServiceImplTest`）**：`DecisionServiceImplTest` 覆盖 findRulesProducingDecision（命中/反例/空/DISABLED口径）、countRuleUsages（多规则累加/去重）、get（命中/不存在抛异常）；`MetricWriteServiceImplTest` 加 countRuleUsages 用例。
- **rule-api controller 测**：decision `/sources`、`/{code}`、`/usage-counts`、metric `/usage-counts` 委托 + 契约。
- **前端**：`npm run build` 类型通过；关键交互（徽标→抽屉、行→详情、停用拦截）手动核对（端到端类无法自动验证的明确标注「未自动验证」）。
- **功能端到端**：起服务，建 Scene/Decision/绑定规则并发布，验 `/sources` + `/usage-counts` + 详情页 + 停用拦截真实生效，查 `decision_bindings` 真冻结。

---

## 六、落点清单（汇总）

| 模块 | 新增/改动 |
|---|---|
| rule-config-svc | `DecisionService` +3 方法 +RuleRef/UsageCount；`DecisionServiceImpl` +3 mapper 依赖 +实现 +谓词；`MetricWriteService(Impl)` +countRuleUsages |
| rule-api | `DecisionController` +/sources +/{code} +/usage-counts +DTO；`MetricController` +/usage-counts |
| frontend | 共享 lineage 组件 + Decision 详情页 + 两侧列表徽标/抽屉 + metric impact Tab 升级 + 编辑器徽标 + i18n |
| docs | `08-evolution.md` §2.28 重写为已实装 |

无 DB 迁移、无 kernel 改动、无 SPI 变更、无评估热路径改动。
