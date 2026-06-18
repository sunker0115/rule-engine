# B21 FETCHED 取数层 + SQL/HTTP 指标范式 设计文档

> 来源：backlog B21。设计日期 2026-06-05。
> 前置：B19（比较策略 + 发布校验框架）、B20（时间框架，提供 `EvalContext.now`）。

## 目标

让引擎真正具备 **FETCHED 取数**能力：

1. `EvalContextAssembler` 按 metric `source_type` 调 `MetricSourceHandler` 拉取指标值（当前只支持 PROVIDED，handler 从不被调用）。
2. 定义 `SQL_AGGREGATE` / `EXTERNAL_HTTP` 两种取数范式。
3. 外部资源（DB / HTTP）以 **infra 注册的命名句柄**引用：凭证不落配置表、目标白名单锁死 SSRF。

---

## 现状基线（实装证据）

### EvalContextAssembler 是空壳

`rule-kernel/.../context/EvalContextAssembler.java` 的 `assemble()` 只做两件事：加载 Subject、把 `event.providedMetrics()` 塞进 metrics（`valueSource="PROVIDED"`、`dataType="UNKNOWN"`），然后直接返回。构造时收的 `List<MetricSourceHandler> metricHandlers` **从不调用**（注释自承"v1 仅 providedMetrics 生效，v2 再加 CompletableFuture.allOf 并发装配"）。

→ **今天所有指标只能 PROVIDED**（调用方推值），引擎不取任何数。

### SPI 已就位未接线

| 组件 | 现状 |
|------|------|
| `MetricSourceHandler` | 已定义：`MetricValue fetch(MetricQuery)` |
| `MetricQuery` | 已定义：metricCode / tenantId / subjectId / params / eventPayload（**无 now**） |
| `@MetricSourceType` | 已定义：value / paramsSchema |
| `MetricDefinition` | 已有：sourceType / params / cacheTtlSeconds / allowProvided |
| `metric_dependencies` | 发布期已冻结 metricCode 列表，运行时未消费 |

### §7.3 示例 SQL 的隐患

`03-rule-expression §7.3` 示例用 `created_at >= NOW() - INTERVAL 7 DAY`，`NOW()` 是 DB 函数——dry-run 重放历史事件时滚动窗口失真。这是取数层与 B20 时间框架的关键耦合点。

---

## 设计决策

| # | 决策 | 取舍 / 依据 |
|---|------|-----------|
| 1 | `EvalContextAssembler` 接线：扫**过 Pre-Gate 的候选** `metric_dependencies` 并集 → provided 优先 → 查缓存 → 并发 fetch | 精确取数（被 Pre-Gate 拦截的规则不取数）；对齐 `02-runtime §3.4` / D25 |
| 2 | 并发装配：`CompletableFuture` + 专用线程池 `fetchExecutor` + 全局超时 | 延迟 = max 而非 sum；对齐 D25；虚拟线程留待评估（JDBC pin 风险） |
| 3 | provided 优先：`providedMetrics` 有值且 `allowProvided=true` → 用，跳过 fetch；`allowProvided=false` 即使传也忽略（WARN） | D30 已定，忠实落地 |
| 4 | 失败降级：单 metric 取数失败/超时 → `MetricValue` 置 ERROR，引用节点 `satisfied=false` + `errorCode=METRIC_FETCH_FAIL`，整树继续 | D15；监控 `session.status=ERROR` 兜底；`required` 字段留 v2 |
| 5 | `MetricQuery` 加 `Instant now` 字段，assembler 绑 `EvalContext.now` | 请求对象天然带上下文（与 subjectId 平级）；保 dry-run 重放；**纯算法不收 ctx、请求对象收**（与 B19/B20 同一原则） |
| 6 | SQL 范式：命名参数 + 禁拼接 + 禁 DB 时间函数 + `:now` 绑引擎时钟 + 窗口长度写 SQL + 结果首行首列按 dataType 强转 | 防注入 + dry-run 可重放 |
| 7 | 数据源：**命名 DataSource Bean 引用**，账密在 secrets 不落表，走**只读副本**，发布期校验名已注册 | 安全姿态；杜绝误写 + 卸载主库 + 防 SSRF |
| 8 | HTTP：**命名 HTTP 端点注册**（baseURL+鉴权+超时+TLS），metric 引用名+path+jsonPath；v1 静态 Header/api-key + mTLS，OAuth2 留 v2 | 灭 SSRF；凭证不落表；B10 复用 |
| 9 | 缓存：key = `tenant:metricCode:subjectId:stableHash(params)`；`ttl=0` 不缓存；v1 进程内 Caffeine | 单机为主；Redis 路径增故障面，留 v2 |
| 10 | `STREAM` sourceType v1 占位，`fetch` 抛 `UnsupportedOperationException` → `METRIC_FETCH_FAIL` | v2 接入 |

---

## §1 取数管线（assemble 接线）

`EvalContextAssembler.assemble(event, candidates, now)` 重写为：

```
1. 收集 metricCode：扫 candidates（已过 Pre-Gate 的 RuleVersionSnapshot）的 metric_dependencies 取并集
2. provided 优先：对每个 metricCode，providedMetrics 有值且 def.allowProvided=true → 直接用（valueSource=PROVIDED），跳过 fetch
3. 查缓存：剩余 metric 按 cacheKey 查 Caffeine；命中则用
4. 并发 fetch：未命中的按 sourceType 路由 handler.fetch(query)，CompletableFuture.allOf + 全局超时
5. 失败/超时降级：失败的 metric 置 ERROR（不阻断其他）
6. 汇总所有 MetricValue → 构建 EvalContext（含 now）
```

**candidates 的 metricDependencies 来源**：需确认 `RuleVersionSnapshot` 已带 `metricDependencies` 字段可读；若无则接线前先补（实现期核对）。

## §2 MetricQuery 加 now

```java
public record MetricQuery(
        String metricCode, String tenantId, String subjectId,
        Map<String, Object> params, Map<String, Object> eventPayload,
        Instant now                       // 新增：引擎统一时钟，B20 提供
) {}
```

assembler 构建 query 时绑 `EvalContext.now`。SQL handler 的 `:now` 参数即取自此字段（**非 DB `NOW()`**）。

## §3 SQL_AGGREGATE 范式

### 3.1 命名参数（禁拼接）

SQL 文本只允许命名参数，由 `PreparedStatement` / MyBatis 绑定，**禁止 `${}` 原值替换或字符串拼接**：

| 参数 | 来源 |
|------|------|
| `:subjectId` / `:tenantId` | `MetricQuery` |
| `:now` | `MetricQuery.now`（引擎统一时钟） |
| `:payload.xxx` | `MetricQuery.eventPayload` 字段 |
| `:params.xxx` | `MetricDefinition.params` 字段 |

### 3.2 `:now` 强制约束

SQL 文本**禁止出现 DB 时间函数**（`NOW()` / `SYSDATE()` / `CURRENT_TIMESTAMP`）；需要"当前时间"必须用 `:now`。滚动窗口长度写在 SQL 文本（`INTERVAL 7 DAY`），引擎不做 duration 运算。

```sql
-- 正确
SELECT COUNT(*) FROM transfer WHERE user_id = :subjectId AND created_at >= :now - INTERVAL 7 DAY
-- 禁止：created_at >= NOW() - INTERVAL 7 DAY
```

### 3.3 结果取值

取首行首列，按 `MetricDefinition.dataType` 强转（LONG/DOUBLE/STRING/BOOLEAN/DATE/DATETIME）；无行 → null（按算子 null 规则处理，见 `03-rule-expression §3.1`）；多行只取第一行（建议 `LIMIT 1`）。

### 3.4 数据源（命名 + 只读）

- `metric.params.datasource` 写**逻辑 Bean 名**；infra 预注册命名 DataSource（连接池 + 账密在 secrets，**不落 `metric_definition`**）。
- metric 查询走**只读数据源 / 读副本**。
- 发布期校验 datasource 名已注册；Scene 级数据源白名单留 v2。

## §4 EXTERNAL_HTTP 范式

### 4.1 命名端点（灭 SSRF）

infra 预注册**命名 HTTP 客户端**：baseURL + 鉴权 + 超时 + TLS。metric 只引用「端点名 + path + jsonPath」，**不写自由 URL、不嵌凭证**。metric 只能打白名单端点。

### 4.2 请求契约（metric.params）

| 字段 | 说明 |
|------|------|
| `endpoint` | 注册的命名端点 |
| `path` | 相对路径，支持 `{payload.xxx}`/`{params.xxx}` 占位符（替换后做 URL 编码） |
| `method` | 默认 GET |
| `jsonPath` | 响应取值路径 |

### 4.3 响应映射

200 + jsonPath 命中 → `MetricValue(value, dataType, "FETCHED")`；200 无匹配 → null；非 200 / 超时 / 连接失败 → `METRIC_FETCH_FAIL`。

### 4.4 鉴权

配在注册的客户端上（秘钥来自 env/secrets），不进 metric。v1：静态 Header/api-key + 网络层/mTLS；OAuth2 ClientCredentials 留 v2。

## §5 缓存

- key = `{tenantId}:{metricCode}:{subjectId}:{stableHash(params)}`。`params` 用 JSON（字段排序稳定）哈希，避免 Map 顺序不定。`eventPayload` **不进 key**（如查询依赖 payload，通过 `:payload.xxx` 体现）。
- `cacheTtlSeconds=0` → 不缓存，实时取数。
- v1 进程内 Caffeine；多实例压力大时 v2 升 Redis（`04-extension §4.4` 已占位 key 规范）。

## §6 失败与超时

- 单 metric 失败 → ERROR 降级，节点 `satisfied=false` + `METRIC_FETCH_FAIL`，整树继续（D15）。
- `CompletableFuture.allOf().join(timeout)` 超时：已完成有效，未完成视同失败。
- 超时阈值放 `07-operability` 统一管理（实现期）。
- PUSH 模式：靠 `session.status=ERROR` 监控告警弥补"缺数据仍决策"的风险。

## §7 provided 优先（D30）

```
for metricCode in metric_dependencies.union():
    if providedMetrics.has(metricCode):
        if def.allowProvided: 用 provided 值（PROVIDED），continue
        else: WARN 忽略
    进入 缓存 → fetch（FETCHED）
```

`allowProvided` 推荐默认（`04-extension §4.3`）：ATTRIBUTE/EXTERNAL_HTTP=true，SQL_AGGREGATE/STREAM=false。

## §8 发布期校验（配套）

规则发布时（接 B19 的 `AstDataTypeResolver` / PublishService 校验链）追加：

1. **SQL 安全扫描**：拒绝含 DB 时间函数（`NOW()` 等）或 `${}` 拼接的 SQL。
2. **资源名校验**：metric 引用的 `datasource` / `endpoint` 名必须已注册，否则拒绝发布。

## §9 与 B19 / B20 / B10 的关系

- **依赖 B20**：`MetricQuery.now` 取自 `EvalContext.now`；B20 与 B21 联合在该 record 上加 `now`（避免改两次）。
- **与 B19 同源原则**：纯算法（ComparisonStrategy）不收上下文，请求对象（MetricQuery）收 `now`。
- **B10（MetricFetcher SDK）**：本层定义的 EXTERNAL_HTTP 命名端点范式即 B10 要标准化的协议基础；B10 在 B21 之后，复用不另起。

## §10 不做（Out of scope）

- `STREAM` sourceType 实装（v1 占位抛异常）。
- OAuth2 自动刷 token、`required` 字段分级、Scene 级数据源白名单（均 v2）。
- 相对 duration 运算（滚动窗口长度写在 SQL 文本，引擎不算）。
- Redis 缓存（v1 Caffeine，v2 视压力）。

## §11 实现期待定（不阻塞设计）

- **Q4** 缓存实现：Caffeine vs 复用已有 Redis——看 rule-eval-svc 依赖现状定。
- **Q5** 全局取数超时阈值：放 `07-operability`，不硬编码。
- **Q6** SQL 占位符库：MyBatis `#{}` vs Spring `NamedParameterJdbcTemplate :xxx`；对用户统一写 `:xxx`，实现层转换。

## §12 影响文档

| 文档 | 变更 |
|------|------|
| `02-runtime.md §3.4` | EvalContext 构建五步标注"已实装" |
| `01-concepts.md §3.9` | sourceType 范式（SQL_AGGREGATE/EXTERNAL_HTTP）实装说明 |
| `03-rule-expression.md §7.3` | 示例 SQL 改 `:now`，加 B21 注入说明 |
| `04-extension.md` | MetricSourceHandler 接线、命名 DataSource/端点注册约定、缓存 key |
| `00-decisions.md` | 追加 B21 决策条目（命名句柄 / :now / 降级 / provided 优先落地） |
| `05-storage.md` | `metric_definition.params` 的 datasource/endpoint 字段约定 |

## §13 测试要点

- **接线**：candidates metric_dependencies 并集正确；provided（allowProvided=true）跳过 fetch；allowProvided=false 传值被忽略 + WARN。
- **并发**：多 metric 并发，延迟≈max；单 metric 慢不拖垮其他。
- **失败降级**：handler 抛异常 → 节点 `satisfied=false` + `METRIC_FETCH_FAIL`，整树继续；全局超时 → 未完成视同失败。
- **SQL `:now`**：`:now` 绑 `EvalContext.now` 而非 DB `NOW()`——**dry-run 重放历史事件，滚动窗口范围与原始评估一致**（核心回归用例）。
- **SQL 安全**：含 `NOW()` / `${}` 的 SQL 发布被拒。
- **数据源**：未注册的 datasource 名发布被拒；只读数据源拒绝写操作。
- **HTTP**：未注册 endpoint 发布被拒；200+jsonPath 命中正确取值；非 200 → `METRIC_FETCH_FAIL`。
- **缓存**：`ttl>0` 命中复用；`ttl=0` 每次实时；params 顺序不同但语义相同 → 同一 cacheKey。
