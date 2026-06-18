# 规则↔指标血缘与变更影响分析（B33）— 设计

> 状态：设计稿（2026-06-18，v2 收敛版）。来源 `08-evolution.md` §2.28（治理维度）。
>
> 核心立场（**v2 关键修正**）：metric→规则的影响面查询**本仓已实现**（`MetricWriteService.findReferencingRules` + `GET /admin/v1/metrics/{code}/versions/{version}/impact`，按需扫 DB、版本感知、强一致）。B33 真正缺口只有**反向：Decision→规则**。故放弃 §2.28 字面的「常驻 LineageIndex + D17 热更」——那对已有按需扫机制是 over-engineering 且双轨重复。B33 收敛为「沿用既有按需扫房规，补齐反向方向 + 对称 API + 前端入口」。

---

## 一、范围与不变量

**做什么**：补齐 Decision→规则反向血缘，使「改 metric / 下线 Decision 前看影响面」双向闭环。

- **正向 metric→规则**：已有，不动（`findReferencingRules` + `/metrics/{code}/versions/{version}/impact`）。
- **反向 Decision→规则**（本次新增）：列出产出某 Decision 的 ACTIVE 规则，兼作下线 Decision 前的影响预检。
- **前端入口**（本次新增）：Decision 详情处「产出来源」入口调反向查询。

**v1 范围 = C 收敛实现**：双向查询（正向复用 + 反向新增）+ 变更影响预检（metric 侧已有 / Decision 侧 = `/sources`）+ 前端入口。

**不变量（不得破）**：

1. **沿用既有按需扫 DB 房规**：与 `findReferencingRules` 同一范式——扫 ACTIVE `rule_version`、读已提交 DB（强一致），不引入常驻内存索引、不挂事件、不碰评估热路径。
2. **只反映 ACTIVE 线上现实**：口径对齐既有实现——以 `rv.status=ACTIVE` 判定「参与评估」，不按 `rule_definition.status` 过滤。
3. **复用冻结真相源**：Decision 引用取 `rule_version.decision_bindings`（typed `List<DecisionBinding>`，发布期冻结），不另写 AST 遍历。
4. **外科式新增**：不重构已工作的 `findReferencingRules`；反向查询自成一体，结构上镜像但不强行抽公共扫描器（避免在同一改动里动 proven 代码；如需 DRY 抽取另立）。

---

## 二、总体架构

```
GET /admin/v1/decisions/{code}/sources  (rule-api, admin)
              │
              ▼
DecisionService.findRulesProducingDecision(tenantId, decisionCode)   (config-svc)
              │  按需扫，强一致
              ├─ ruleDefinitionMapper.findByTenant(tenantId)
              ├─ sceneMapper.findByIds(sceneIds)            → sceneId→sceneCode
              ├─ ruleVersionMapper.findActiveByRuleDefIds() → ACTIVE rule_version
              └─ 筛 rv.getDecisionBindings() 含 decisionCode → List<RuleRef>
```

与既有 `MetricWriteServiceImpl.findReferencingRules` 完全同骨架，仅过滤谓词不同（metricDependencies 含 (code,version) → decisionBindings 含 code）。

---

## 三、组件设计

### 3.1 `DecisionService.findRulesProducingDecision`（config-svc）

接口（`config/api/service/DecisionService.java` 新增方法）：

```java
/**
 * 查询产出某 decision 的所有 ACTIVE 规则（下线 decision 前评估影响面 / Decision 覆盖来源）。
 * 口径同 findReferencingRules：以 rv.status=ACTIVE 判定参与评估，不按 rule_definition.status 过滤。
 *
 * @param tenantId     租户 id
 * @param decisionCode decision 编码
 * @return 产出该 decision 的规则引用项；无引用返回空列表
 */
List<RuleRef> findRulesProducingDecision(Long tenantId, String decisionCode);
```

返回类型 `RuleRef`：`DecisionService` 内嵌 record，字段镜像 `MetricWriteService.RuleRef`（不复用 metric 侧嵌套类型——避免 Decision 服务依赖 metric 服务的内部类型；轻微结构重复可接受）：

```java
/** 产出某 decision 的规则引用项。sceneCode 由 rule_definition.scene_id 关联；status 为 rule_definition.status。 */
record RuleRef(Long ruleDefinitionId, String ruleCode, String ruleName,
               String sceneCode, String status) {}
```

实现（`DecisionServiceImpl`）：

- 依赖新增：`RuleDefinitionMapper` / `RuleVersionMapper` / `SceneMapper`（追加 final 字段，`@RequiredArgsConstructor` 自动注入）。
- 方法体四步骤镜像 `findReferencingRules`：findByTenant → findByIds(scene) → findActiveByRuleDefIds → 筛选；过滤谓词 = `containsDecision(rv.getDecisionBindings(), decisionCode)`：

```java
private boolean containsDecision(List<DecisionBinding> bindings, String decisionCode) {
    if (bindings == null || bindings.isEmpty()) return false;
    return bindings.stream().anyMatch(b -> decisionCode.equals(b.decisionCode()));
}
```

- `@Transactional(readOnly = true)`。

### 3.2 `DecisionController` 端点（rule-api，admin 侧）

`/admin/v1/decisions`（既有 controller，新增一个 GET）：

```java
/** GET /admin/v1/decisions/{code}/sources — 产出该 decision 的 ACTIVE 规则清单（兼作下线影响预检）。 */
@GetMapping("/{code}/sources")
public ApiResponse<DecisionSourcesResponse> sources(@PathVariable String code,
                                                    @RequestParam Long tenantId) {
    List<RuleRef> rules = decisionService.findRulesProducingDecision(tenantId, code);
    return ApiResponse.ok(new DecisionSourcesResponse(code, rules, rules.size()));
}
```

响应 DTO（与 `MetricController.ImpactResponse` 同风格，定义在 controller 内嵌或 `web/admin/dto`）：

```java
/** Decision 产出来源响应：产出某 decision 的规则清单（兼作下线影响面）。 */
record DecisionSourcesResponse(String decisionCode, List<RuleRef> sources, int sourceCount) {}
```

`tenantId` 用 `@RequestParam Long`（同 `MetricController`/`DecisionController` 既有端点）。

### 3.3 前端入口

- **Decision 详情/列表**：「产出来源」入口/面板，调 `GET /admin/v1/decisions/{code}/sources`，列出产出规则（sceneCode / ruleCode / ruleName / status）；下线 Decision 前可见影响面。
- metric 侧影响面端点（`/versions/{version}/impact`）已存在；若前端尚无入口，顺带补一个对称入口（实现期确认是否已有，避免重复）。
- 具体 UI 落点（详情页面板 vs 列表行抽屉）实现期定，本 spec 只锁数据契约。

---

## 四、边界 / 不做

- 不建常驻索引、不挂事件、零 DDL、不碰评估热路径。
- 不自动触发 §2.27 效果对照 / §2.25 回放。
- metric→规则不重做（既有 `findReferencingRules` 不动）；不强行抽 metric/decision 公共扫描器（外科式新增，DRY 抽取另立）。
- **已知缺口（沿袭既有实现，文档明记）**：Decision 引用仅取 `decision_bindings`（文档既定绑定模型）；决策树叶子 / 决策表输出格若携带不在 bindings 内的 decisionCode 不计入（与既有 metric 侧「只认 metric_dependencies」对称——只认发布期冻结的结构化引用）。

---

## 五、测试策略

- **config-svc 单测（`DecisionServiceImplTest`，真 MySQL，镜像 `MetricWriteServiceImplTest` 的 findReferencingRules 用例）**：
  - 建 decision + 建引用它的 ACTIVE rule_version → `findRulesProducingDecision` 命中、字段（ruleCode/sceneCode/status）正确。
  - 多规则产出同一 decision → 全部返回。
  - 无引用 → 空列表。
  - 引用别的 decision 的规则 → 不返回（谓词精确）。
  - 口径回归：`rv.status=ACTIVE` 但 `rd.status=DISABLED` 的规则仍返回。
- **rule-api controller 测**：`GET /admin/v1/decisions/{code}/sources` 请求/响应契约 + tenant param + 计数正确。
- **功能端到端**（走配置→发布→查询链路）：起服务，建 Decision、建绑定它的规则并发布，查 `/sources` 验证清单正确；下线该 Decision 前 `/sources` 返回引用规则（影响预检生效）。

---

## 六、落点清单

| 模块 | 新增/改动 |
|---|---|
| rule-config-svc | `DecisionService` 新增 `findRulesProducingDecision` + 内嵌 `RuleRef` record；`DecisionServiceImpl` 加 3 mapper 依赖 + 方法实现 + `containsDecision` 私有谓词 |
| rule-api | `DecisionController` 新增 `GET /{code}/sources` + `DecisionSourcesResponse` DTO；（可选）前端缺失时补 metric impact 对称入口 |
| frontend | Decision「产出来源」入口（调 `/sources`） |
| docs | `08-evolution.md` §2.28 从「演进方向：LineageIndex」重写为「已实装：沿用按需扫，补反向」 |

无 DB 迁移、无 kernel 改动、无 SPI 变更、无评估热路径改动、不动既有 `findReferencingRules`。
