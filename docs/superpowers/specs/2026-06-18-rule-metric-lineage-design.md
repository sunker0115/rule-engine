# 规则↔指标血缘与变更影响分析（B33）— 设计

> 状态：设计稿（2026-06-18）。来源 `08-evolution.md` §2.28（治理维度）。配套底座：D6 快照、D17 索引热更（`SceneRuleIndex` + `SceneIndexEventListener` + `IndexStartupLoader`）、B31 静态分析读快照范式。
>
> 核心立场：**血缘是「同源同刷新的第二投影」，不是独立读模型。** 与运行时 `SceneRuleIndex` 共用同一批 ACTIVE 快照、同一刷新触发，一次 reload 喂两个投影，永不分叉。纯读快照，零 DDL，不碰评估热路径。

---

## 一、范围与不变量

**做什么**：在已发布规则集上建双向血缘索引，回答两类治理问题，并提供变更前影响预检。

- **正向**：metric → 引用它的规则/Scene（改 metric 口径前看炸点）。
- **反向**：Decision → 产出它的规则（Decision 覆盖来源）。
- **变更影响预检**：改 metric / 下线 Decision 前，列出受影响规则清单。

**v1 范围 = C**：双向查询 API + 变更影响预检端点 + 前端入口。

**不变量（不得破）**：

1. **只反映 ACTIVE 线上现实**：索引随发布事件更新，草稿态不进索引。语义对齐「改 metric 看线上炸点」。
2. **与运行时索引强一致**：`LineageIndex` 与 `SceneRuleIndex` 来自同一快照列表、同一刷新瞬间构建；不允许两索引分叉（分叉会给出假安全信号——说 metric 没人用、实则评估仍在引用）。
3. **零 AST 重走，复用冻结真相源**：metric 引用取 `snapshot.metricDependencies()`、Decision 引用取 `snapshot.decisionBindings()`，两者均为发布期已冻结的快照顶层字段，不另写遍历器。
4. **零 DDL、不碰热路径、不自动触发下游**：纯内存读索引；不自动触发 §2.27 效果对照 / §2.25 回放，只留挂钩位。

---

## 二、总体架构

```
配置发布 ── SceneChangedEvent / RulePublishedEvent ──▶ eval-svc 既有 reload 路径
                                                          │
                          loader.loadBySceneWithStrategy(scene)  ← 单次快照加载
                                                          │
                              ┌───────────────────────────┴───────────────────────────┐
                              ▼                                                         ▼
                  sceneRuleIndex.replaceScene(...)                       lineageIndex.replaceScene(...)
                  （运行时倒排索引，评估用）                              （血缘反向索引，治理用）
                                                                                        │
                                                          ┌─────────────────────────────┘
                                                          ▼
                                          LineageQueryService（eval-svc，纯读内存）
                                                          ▲
                                                          │
                                   LineageController（rule-api，/admin/v1，admin 侧）
```

启动全量：`IndexStartupLoader.onApplicationReady` 同样一次加载喂两个索引。

---

## 三、组件设计

### 3.1 `LineageIndex`（rule-kernel `internal/index`，纯 Java）

`SceneRuleIndex` 的姊妹，纯 Java 无 Spring，可在 eval-svc / sdk 共用。两张反向索引 + 租户隔离：

- `metricCode → Set<RuleRef>`
- `decisionCode → Set<RuleRef>`

`RuleRef`（kernel `api/model` 或 `internal/index`，纯 record）：

```
RuleRef(String tenantId, String sceneCode, String ruleCode, long version, Long ruleVersionId)
```

足够定位产出规则；`equals/hashCode` 按 `ruleVersionId`（不可变唯一）去重。

方法：

- `replaceScene(String tenantId, String sceneCode, Collection<RuleVersionSnapshot> snapshots)`
  原子替换该 (tenant, scene) 在两张反向 map 中的全部贡献。仿 `SceneRuleIndex.replaceScene`：先按新快照集重算该 scene 的所有 (metricCode/decisionCode → RuleRef) 条目，再摘除该 scene 已不在新集合中的旧条目。空集合也能摘干净（scene 规则全禁用/删除）。
- `remove(String tenantId, String sceneCode)`：scene 禁用时移除该 scene 全部贡献。
- `metricUsages(String tenantId, String metricCode)` → `List<RuleRef>`（按 sceneCode、ruleCode 确定性排序）。
- `decisionSources(String tenantId, String decisionCode)` → `List<RuleRef>`。

**抽取逻辑（封装在 `replaceScene` 内）**：对每条 snapshot——
- metric：`snapshot.metricDependencies()` 取 `metricCode`（已覆盖 AST_BOOLEAN/评分卡/决策树/决策表，见 `MetricDependencyCollector`）。
- decision：`snapshot.decisionBindings()` 取 `decisionCode`。

**索引内部组织**：反向 map 用扁平 `metricCode → Set<RuleRef>`（`RuleRef` 自带 sceneCode）；为支持「按 scene 摘除旧条目」而不全表扫，另维护正向反查表 `sceneKey → Set<String>`（该 scene 引用过的 metricCode/decisionCode 集合，`sceneKey = tenant:scene`）。`replaceScene` 流程：

1. 用反查表取该 scene 旧引用的 code 集，对这些 code 的 `Set<RuleRef>` 删除 sceneKey 匹配的项（targeted，O(本 scene 引用数) 而非 O(全部)）；
2. 从新快照抽 code → 对应 `Set<RuleRef>` 加入新 RuleRef；
3. 反查表更新为新 code 集。

正向反查表是「为风控规模」的实质需要（避免每次发布 O(全部 metric) 扫描），非投机。

**并发**：反向 map 与反查表用 `ConcurrentHashMap`；治理查询是冷路径，按 scene 维度增删的近原子语义（与 `SceneRuleIndex` 一致）足够，不追求跨 scene 全局快照隔离。

### 3.2 喂数据（eval-svc，扩既有 reload 路径）

`LineageIndex` 注册为 bean（`EvalAutoConfiguration`，仿 `sceneRuleIndex()`）。

- **`SceneIndexEventListener.onSceneChanged`**：listener 已拿到 `byEventType` 快照（且已 dedup 成 `distinct` 喂 scriptWarmer）。把同一份 `distinct` 快照集分发给第二个 sink：
  - `active=false` → `lineageIndex.remove(tenant, scene)`（与 `sceneRuleIndex.remove` 并列）。
  - `active=true` → `lineageIndex.replaceScene(tenant, scene, distinct)`（与 `sceneRuleIndex.replaceScene` 并列）。
- **`IndexStartupLoader.onApplicationReady`**：启动全量加载时，同一批快照按 scene 分组后同样喂 `lineageIndex.replaceScene`。

listener 只多「路由到第二 sink」一行调用，抽取逻辑全在 `LineageIndex` 内。两索引同源同刷新，结构上不可能分叉。

> **设计决策**：为何不用独立 `LineageIndexEventListener`？血缘与运行时索引数据完全同源、同刷新触发、无独立重建节奏——是「二级索引/单次 build 多投影」（DB 二级索引、Drools KieBase reload 范式），不是「可独立演进的 CQRS 读模型」。独立 listener 的收益（独立演进/重建/故障隔离）对不上本场景，而其代价（两索引分叉→假安全信号、同批快照扫两遍 DB）正好砸在风控用例上。故否决。

### 3.3 `LineageQueryService`（eval-svc）

纯读 `LineageIndex` 的 service 接口（`api/service`）+ 实现（`internal`）：

```
List<RuleRef> metricUsages(String tenantId, String metricCode);
List<RuleRef> decisionSources(String tenantId, String decisionCode);
```

变更影响预检复用上述两查询——见 §3.4 端点语义。

### 3.4 `LineageController`（rule-api，admin 侧）

rule-api 读 eval 索引已有 `SdkSnapshotController` 先例。tenant 从请求上下文取（同其它 admin API）。

- `GET /admin/v1/metrics/{code}/usages` → 引用该 metric 的规则清单（正向）。
- `GET /admin/v1/decisions/{code}/sources` → 产出该 Decision 的规则清单（反向）。
- `GET /admin/v1/metrics/{code}/impact` → **变更影响预检**：改/下线该 metric 前的受影响规则清单。语义 = `usages` 结果 + 「将失效」标注（受影响规则数、涉及 Scene 列表）。**不**自动触发回放/效果对照。
- `GET /admin/v1/decisions/{code}/impact` → 下线该 Decision 前的受影响（产出）规则清单。

响应 DTO（rule-api `web/admin/dto`，DTO↔RuleRef 走 MapStruct 或字段极少时手写）：

```
LineageUsageResponse(String code, List<RuleRefView> rules)
RuleRefView(String sceneCode, String ruleCode, long version, Long ruleVersionId)
ImpactResponse(String code, int affectedRuleCount, List<String> affectedScenes, List<RuleRefView> rules)
```

`RuleRefView` 按 (sceneCode, ruleCode) 确定性排序。

### 3.5 前端入口

参考 B31 落点风格（不照搬右栏方案，以实际信息架构为准）：

- **metric 详情页 / 列表行**：「被引用」面板/入口，调 `usages`；编辑或下线 metric 前可点「影响预检」调 `impact`。
- **Decision 详情页**：「产出来源」面板，调 `sources`。

具体 UI 落点（独立面板 vs 抽屉 vs 行内徽标）在前端实现阶段定，本 spec 不锁死视觉形态，只锁定「两个查询入口 + 两个影响预检入口」的数据契约。

---

## 四、边界 / 不做

- 不持久化血缘（纯内存，随索引热更重建；进程重启由 `IndexStartupLoader` 全量重建）。
- 不自动触发 §2.27 效果对照 / §2.25 回放——`impact` 端点只返回清单，挂钩位留给后续。
- 草稿态血缘不做（只反映 ACTIVE）；若未来要「草稿态预检」另立。
- **已知缺口（文档明记）**：EXPRESSION_SCRIPT 规则的 metric 引用藏在脚本体内、不进 `metricDependencies` → 血缘漏报该类引用，与 §2.28「脚本不透明」一致。Decision 引用仅取 `decisionBindings`（文档既定绑定模型）。

---

## 五、测试策略

- **kernel 单测（`LineageIndexTest`）**：`replaceScene` 增/改/摘除、`remove`、`metricUsages`/`decisionSources` 查询、租户隔离、同 metric 被多规则引用去重、scene 规则全删后摘干净、两层结构按 scene 差量摘除正确性。
- **eval-svc 集成测（真 MySQL，仿 `SceneIndexEventListenerTest`）**：
  - 发布 → `SceneChangedEvent` 喂入 → `usages`/`sources` 命中。
  - `IndexStartupLoader` 启动全量加载后查询命中。
  - scene 禁用（`active=false`）→ 血缘条目摘除。
  - 同一次 reload 后 `SceneRuleIndex` 与 `LineageIndex` 对该 scene 的规则集一致（强一致回归）。
- **rule-api controller 测**：四个端点请求/响应契约 + tenant 上下文。
- **功能端到端**（schema 无改动，但走配置→发布→查询链路）：起服务，建 metric/Decision/规则并发布，查四个端点验证清单正确，验证 metric 下线预检拦到引用规则。

---

## 六、落点清单

| 模块 | 新增/改动 |
|---|---|
| rule-kernel | `internal/index/LineageIndex`（新）、`RuleRef` record（新） |
| rule-eval-svc | `EvalAutoConfiguration` 注册 `lineageIndex` bean；`SceneIndexEventListener` / `IndexStartupLoader` 各加第二 sink 调用；`LineageQueryService` + impl（新） |
| rule-api | `LineageController`（新）、`web/admin/dto` 响应 DTO + MapStruct convert（新） |
| frontend | metric/Decision 血缘入口 + 影响预检入口（新） |
| docs | `08-evolution.md` §2.28 从「演进方向」改写为「已实装」块（实装后） |

无 DB 迁移、无 kernel SPI 变更、无评估热路径改动。
