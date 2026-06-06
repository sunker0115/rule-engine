# 嵌入式 SDK 取数（FETCHED in SDK）设计

> 来源：B21 取数层落地后的 SDK 适用性讨论（2026-06-06）。暂定编号 B23。
> 前置：B21（取数层 + `MetricDefinitionResolver`/`MetricCache`/`MetricSourceHandler` SPI + `EvalContextAssembler` 富构造）。
> **状态：设计冻结，实现待排期——不在 B21 内。** 本文档的目的是：在 B21 定契约时就把 SDK 取数形态固化，避免将来在已定型的 SPI/快照上打补丁。

## 目标

让嵌入式 `RuleEngineClient` 也能 FETCHED，且：

1. **复用 B21 全部取数编排**（provided 优先 → 缓存 → 并发 fetch → 失败降级），不重写。
2. **不内置 SQL/HTTP handler**——宿主自带（SDK 跑在宿主进程，宿主已有 DataSource/HttpClient/凭证）。
3. **metric 定义随独立通道下发**，不冻进 `rule_version` 快照（保持"操作配置可热调"，与服务端 resolver 读库语义一致）。
4. **默认行为不变**：不注入取数 SPI = 维持 providedMetrics-only。

---

## 现状基线（实装证据）

- `RuleEngineClient`（`rule-sdk`）用旧 2 参 `new EvalContextAssembler(List.of(), List.of())` → resolver=null → **仅 providedMetrics 生效**，不取数。
- 仅注册 `Map.of("AST_BOOLEAN", executor)`——评分卡/决策树/表需宿主自传 executor。
- 规则快照下发：`SnapshotPoller` 拉 `GET /api/v1/sdk/snapshots?tenantId=&scenes=`，JSON `{data:[RuleVersionSnapshot...]}`，写入本地 `SceneRuleIndex`。来源经 `RuleSource.loadInto(index)` SPI（HTTP 轮询 / JSON 文件 / 代码 DSL，可多源）。
- 服务端已有 `MetadataController`（`getSceneMetadata` / `getProvidedMetrics`）暴露 metric 元数据——B2 定义下发的天然落点。

---

## 核心架构决策

| # | 决策 | 依据 |
|---|------|------|
| D-A | 取数编排统一在 `EvalContextAssembler` 富构造（服务端与 SDK **共用同一入口**） | B21 已建；SDK 富构造照用，零重写 |
| D-B | metric 定义是**独立可下发配置**，不进 `rule_version` 快照；新增 metric 定义下发通道 + SDK 本地缓存 + `SnapshotMetricDefinitionResolver` | 保持 B21"定义不冻进快照、可热调"；嵌入式无 DB 时定义有来源 |
| D-C | handler 由**宿主注入**（`@MetricSourceType` 路由），SDK **不内置** SQL/HTTP handler | SDK 跑宿主进程，不该自建 DataSource/HTTP/凭证；避免与宿主连接管理冲突 |
| D-D | 默认 providedMetrics-only；注入 resolver + handler 才启用 fetch | 嵌入式"轻"定位；行为渐进可选 |
| D-E | metric 定义来源**对称于规则来源**（HTTP / 文件 / DSL 三种），local 模式定义本地提供 | 镜像 SDK 现有多源（`RuleSource`）；local 无服务端，靠 resolver 数据源无关统一三种 |

---

## 与 B21 契约对齐（零改动证明）

SDK 取数落地时，下列 B21 契约**一行不改**——这正是 Option A（resolver SPI）相对"冻进快照"的价值：编排被抽象成数据源无关的 SPI。

| 抽象（B21 建立） | 服务端实现 | 嵌入式（B2）实现 | 契约改动 |
|---|---|---|---|
| `EvalContextAssembler` 富构造 | eval-svc 装配 | SDK `RuleEngineClient` 装配 | **无**（同一入口） |
| `MetricDefinitionResolver.resolve(tenantId, code)` | `DbMetricDefinitionResolver` 读表 | `SnapshotMetricDefinitionResolver` 读下发缓存 | **无**（换实现不换契约） |
| `MetricSourceHandler` + `@MetricSourceType` | SQL/HTTP handler | **宿主自带** handler | **无** |
| `MetricCache` | `CaffeineMetricCache` | 宿主提供或不提供 | **无** |
| `MetricDescriptor` / `MetricQuery` / `MetricValue` | 读库装配 | 下发 JSON 反序列化 | **无**（字段中性） |
| `RuleVersionSnapshot.metricDependencies` | 取数范围 | 同左 | **无** |

→ B2 只需**新增**：metric 定义下发协议 + 一个新 resolver 实现 + Builder 注入入口。**不修改** B21 任何方法签名。

---

## 新增组件（B2 实现期，不在 B21）

### 1. metric 定义来源（对称于 RuleSource）

metric 定义来源**镜像规则来源**——规则有几种来源，定义就有对称的几种，统一经 `MetricDefinitionSource.loadInto(MetricDefinitionRegistry)` 写入本地定义缓存；`SnapshotMetricDefinitionResolver` 从 registry 读。resolver 数据源无关，三种来源对它透明。

```
interface MetricDefinitionSource { void loadInto(MetricDefinitionRegistry registry); }
```

| 模式 | 规则来源 | metric 定义来源（对称） | 定义从哪来 |
|---|---|---|---|
| HTTP 轮询 | `PollingRuleSource` → `/api/v1/sdk/snapshots` | `PollingMetricDefinitionSource` → `/api/v1/sdk/metric-definitions` | 服务端 `metric_definition` 映射下发（仅元数据，不含凭证） |
| JSON 文件 | `FileRuleSource`（classpath JSON） | `FileMetricDefinitionSource`（classpath JSON of `MetricDescriptor`） | 本地文件 |
| 代码 DSL | `DslRuleSource`（`List<RuleVersionSnapshot>`） | `DslMetricDefinitionSource`（`List<MetricDescriptor>`）/ `Builder.localMetric(...)` | 代码声明 |

- **HTTP 模式**才需服务端新增 `GET /api/v1/sdk/metric-definitions?tenantId=&scenes=` → `{data:[MetricDescriptor...]}`，仅下发定义元数据，**不含凭证**（凭证在宿主 handler）；`MetricDefinitionPoller` 复用 `pollInterval` 热更。
- **local 模式（DSL/文件）无服务端**：定义由本地 JSON / 代码提供，与规则**同源同地声明**——你在哪定义规则，就在哪定义它引用的 metric。
- **local 多数场景不需要 fetch**：DSL/文件模式 metric 值常直接走 `providedMetrics`（最简单）；fetch 是 local 的**可选高级用法**（自定义 metric 定义 + 自注入 handler 查本地库/服务）。

### 2. `MetricDefinitionRegistry` + `SnapshotMetricDefinitionResolver`

- `MetricDefinitionRegistry`：SDK 本地 `tenantId:metricCode → MetricDescriptor` 缓存，下发写入、热更替换。
- `SnapshotMetricDefinitionResolver implements MetricDefinitionResolver`：`resolve()` 从 registry 读。即 B21 resolver SPI 的嵌入式实现。

### 3. `RuleEngineClient.Builder` 注入入口

```
.metricSourceHandler(MetricSourceHandler...)   // 宿主自带，按 @MetricSourceType 归类
.metricDefinitionResolver(MetricDefinitionResolver)  // 默认 SnapshotMetricDefinitionResolver；可覆盖
.metricCache(MetricCache)                       // 可选
.fetchExecutor(Executor)                        // 可选，默认 ForkJoinPool.commonPool
.metricDefinitionSource(MetricDefinitionSource) // 定义下发来源
```

`build()`：若注入了 handler/resolver，则用 B21 **富构造**装配 assembler；否则旧 2 参（providedMetrics-only）——**默认行为不变**。

---

## 数据流（B2）

```
启动（HTTP 模式）：
  SnapshotPoller                 → 拉规则快照      → SceneRuleIndex
  PollingMetricDefinitionSource  → 拉 metric 定义  → MetricDefinitionRegistry
启动（local 模式 DSL/文件，无服务端）：
  DslRuleSource / FileRuleSource                         → SceneRuleIndex
  DslMetricDefinitionSource / FileMetricDefinitionSource → MetricDefinitionRegistry
  （规则与定义本地同源声明）

评估 RuleEvent：
  EvalEngine.evaluate
    → matcher（候选） → Pre-Gate
    → EvalContextAssembler.assemble（富构造）
        → 候选 metricDependencies 并集
        → SnapshotMetricDefinitionResolver.resolve（读 registry）
        → provided 优先 → 缓存 → 宿主 handler.fetch → 失败降级
    → executor（含 B21 门面三态降级）
```

宿主 handler 用**自己的** DataSource/HttpClient 查值，凭证在宿主侧。

---

## 前向兼容约束（B21 现在就钉，防打补丁）

下列四条写入 `00-decisions`（B21 决策条目），并体现在 `MetricDefinitionResolver` / `MetricDescriptor` 的 Javadoc：

1. **metric 定义是独立可下发配置，不冻进 `rule_version` 快照**；`rule_version` 仅带 `metricDependencies`（代码列表）。
2. **`MetricDefinitionResolver` 为数据源无关 SPI**：服务端读库 / 嵌入式读下发缓存，共用同一抽象。
3. **`EvalContextAssembler` 富构造为服务端与 SDK 的统一取数入口**；旧 2 参构造保留为 providedMetrics-only 退化路径。
4. **`MetricDescriptor` 是定义下发的序列化契约**：字段保持中性、可 JSON 序列化（B2 下发用同一 record）。

钉死这四条后，SDK 取数只能走"独立下发 + 宿主 handler"这条路，无法图省事把定义塞进规则快照。

---

## 不做（Out of scope）

- SDK 内置 SQL_AGGREGATE / EXTERNAL_HTTP handler（永远由宿主提供）。
- 把 metric 定义冻进 `rule_version` 快照。
- HTTP 模式下"本地算不了就回源服务端评估"（破坏零网络/本地决策定位）。
- 宿主 handler 的连接池/凭证管理（属宿主职责）。

---

## 开放问题（B2 实现期）

- **Q1** 定义下发 scope：按 `scenes`（DECLARED）还是全量租户定义？倾向复用 `FetchMode`。
- **Q2** 定义热更：复用 `pollInterval` 单独轮询，还是与规则快照合并为一个下发包？
- **Q3** 文件/DSL 模式的定义来源格式（JSON schema / DSL builder）。
- **Q4** `MetricDescriptor` 作为下发契约，需要稳定的 JSON schema（目前是 kernel 内部 record，B2 时评估是否提升为 api.model 并固定字段顺序/兼容策略）。
- **Q5** 评分卡/决策树/表 executor 在 SDK 默认未注册——SDK 取数 + 这些 kind 需宿主同时注入 executor（既有现状，B2 文档提示）。
