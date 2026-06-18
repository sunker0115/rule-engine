# Metric 取数子系统标准化设计

> 日期：2026-06-15
> 关联缺口：`MetricSource` 外部 HTTP 无统一契约 → 多团队各搞一套 `EXTERNAL_HTTP`，联调贵。
> 参照系：OpenFeature（provider SPI + spec + conformance）、OpenTelemetry（exporter SPI + OTLP 信封 + semantic conventions）。
> 范围红线：本轮只做"取数子系统标准化"。AI/ML 模型打分、LLM condition、一等 CEP 序列模式**只进「演进位」一节、不实现**。

## 1. 问题与目标

### 1.1 现状

`EXTERNAL_HTTP` 取数（`ExternalHttpMetricSourceHandler`）契约太薄：

- 只支持 `GET`，URL = 命名端点 `baseUrl` + `path`（仅 `path` 渲染 `{payload.x}`/`{params.x}` 占位符）；
- 鉴权只有单个静态 header（来自 infra `HttpEndpointRegistry`）；
- 响应解析只有单条点号 `jsonPath` 取一个标量；
- 失败一律归 `METRIC_FETCH_FAIL`；超时仅连接/读超时，无 retry / 熔断 / 响应信封约定。

各团队的外部服务形态各异（POST/body、token 刷新、`{code,msg,data}` 信封、批量），于是各自 fork 一套 `EXTERNAL_HTTP`，接入靠口头对齐、联调贵。

`SQL_AGGREGATE`（`SqlAggregateMetricSourceHandler`）契约本身较均匀（命名参数绑定、首行首列、禁拼接），**但缺少与 HTTP 侧一致的横切能力**（statement 超时、错误细码、变量命名空间、自助测试），形成"HTTP 路径将锃亮、SQL 路径落后半截"的不对称。

### 1.2 目标

1. 把 `EXTERNAL_HTTP` 升级为**声明式连接器**：一份描述符覆盖 ~90% 团队、零代码接入；怪服务写一次薄 provider。
2. 抽出**跨源共用脊**：变量命名空间+渲染、`ResiliencePolicy`、`MetricFetchError` 细码、`MetricFetchTester`、可观测标签——HTTP 与 SQL 两源共担。
3. 配**一致性套件 + 自助测试端点**，把"联调贵"变成"各自跑套件 / 点测试，绿了就接"。
4. 守住三条"别焊死"边界纪律，让未来 AI/ML、CEP 能当 handler/新 kind 增量接入，不返工。

### 1.3 非目标（YAGNI）

- 不给 `SQL_AGGREGATE` 加对称的"SQL 连接器中间层"（SQL 无请求形态/信封/鉴权变异，强加是为对称而对称）。
- 不实现 LLM condition、模型打分 sourceType、一等 CEP 序列模式匹配（仅留演进位）。
- 不引 Resilience4j（热路径最小手写）；不把表达式引擎拉进取数（留给 EXPRESSION_SCRIPT 规则）。
- 不做连接器 per-version 冻结（v1 可变 + 热加载，像 endpoint/datasource）。

## 2. 借鉴对照（各借什么）

| 维度 | OpenFeature / OTel | 本项目现状 | 本轮动作 |
|---|---|---|---|
| SPI 形状 | OF：`Provider.resolveXxx(key, default, ctx) → ResolutionDetails` | ✅ `MetricSourceHandler.fetch(query) → MetricValue` | 不动 |
| 结果结构 | OF：`{value, reason, errorCode, errorMessage, ...}` | `MetricValue{value, dataType, valueSource, errorCode}` | 加可选 `reason`（可观测，不改降级语义） |
| 失败语义 | OF/OTel：错误即返回值、绝不抛到应用；retry 是实现者的事 | ✅ 一致（D15 降级、引擎不重试） | 不动，细化 errorCode |
| 生命周期 | OF：`initialize/dispose/onContextChanged` + 事件 | ✅ `init/destroy` + `SceneChangedEvent` | 复用 |
| 入参上下文 | OF：`EvaluationContext{targetingKey + attributes}` | ✅ `MetricQuery{subjectId, eventPayload, params}` | 扩统一命名空间 |
| **响应信封** | OTel：OTLP 标准信封 + semantic conventions | ❌ 单 jsonPath 取标量，各家外壳各异 | **声明式 ResponseMapping** |
| **一致性** | OF：带编号 Requirement + conformance 测试 | ❌ 口头对齐 | **连接器契约 + conformance kit + 测试端点** |

结论：SPI / 失败语义 / 生命周期 / 入参已对齐 OpenFeature 形状；真正缺的是**响应信封映射**（OTel 那一课）和**一致性套件**（OpenFeature 那一课）。

## 3. 架构分层

连接器是**可复用命名资源**，不塞进 `metric.params`。三层（现状已分出第 1、3 层，本轮补第 2 层）：

```
传输层 · Endpoint   (沿用 HttpEndpointRegistry，运维持有)
   baseUrl / 凭证 / HttpClient / mTLS      ← 密钥只在这层
        ▲ 被引用 (endpointRef)
契约层 · Connector  (新增，声明式描述符，平台/业务持有，命名可复用)
   request 模板 + response 映射 + auth 方案 + resilience + error 映射
        ▲ 被引用 (params.connector)
绑定层 · Metric     (sourceType=EXTERNAL_HTTP)
   params = { connector: "<name>", vars: { ... } }
```

**为什么不塞进 `metric.params`**：题目即"同一上游被多 metric/多团队复用、联调贵"。塞进 metric 会让信封映射复制 N 份，改一处改 N 处。命名可复用连接器 = OpenFeature「provider 复用 / flag 按 key」、OTel「exporter 复用 / signal 按数据」的同构，是消灭复制、可单独联调的正解。

### 3.1 共用脊：内外两圈

```
外圈 · 调用无关 (external-call agnostic)
  变量命名空间+渲染 · ResiliencePolicy · MetricFetchError 归一 · MetricFetchTester · 可观测标签
  └─ 服务于：HTTP metric · SQL metric · (未来) 模型打分 · (未来) LLMCondition · SubjectLoader
内圈 · metric-fetch 专属
  @MetricSourceType 路由 · MetricValue 结构 · MetricCache
源专属
  HTTP：envelope 映射         SQL：取首行首列
```

外圈**不焊进 HTTP handler**，做成调用无关，让后续外部调用类扩展点都能继承（演进位纪律 A）。

## 4. 组件落位

不破坏 kernel 纯净——连接器是 `EXTERNAL_HTTP` 实现细节，不是新 SPI。

| 模块 | 改动 |
|---|---|
| **rule-kernel** | SPI `MetricSourceHandler`/`MetricQuery`/`MetricValue` 不动；`MetricValue` 加可选 `reason`（String，可观测）。新增 closed enum `MetricFetchError`（kernel `api/model`）。 |
| **rule-config-svc** | 连接器 CRUD + 发布（与 scene/rule/metric/binding 同构）；typed 请求 DTO + MapStruct（`web/admin/convert/`）；`connector_definition` 表。发布期 `ConnectorSafetyValidator`（endpointRef 已注册、auth 方案合法、占位符引用闭合）。 |
| **rule-eval-svc** | `ExternalHttpMetricSourceHandler` 重构为 `DeclarativeHttpConnectorHandler`；新增 `ConnectorRegistry`（镜像 `HttpEndpointRegistry`/`MetricDataSourceRegistry`）+ `ConnectorDefinitionResolver`（镜像 `MetricDefinitionResolver`，DB 实现 + Caffeine）。`SqlAggregateMetricSourceHandler` 继承共用脊。`MetricFetchTester`（跨源）。 |
| **rule-connector-conformance**（test-support）| 嵌入式 mock 上游 + 黄金用例集；可被接入方独立跑。 |
| **frontend** | 连接器编辑器（JSON Schema 驱动动态表单，沿用 metadata 机制）+ 信封预设 + 内联自助测试面板。 |

**热加载**：连接器变更走现有事件失效机制（与 `SceneChangedEvent` 同款），不重启。

## 5. 描述符模型（全 typed record，禁 Map/String）

```
ConnectorDescriptor {
  connectorCode:  String
  endpointRef:    String                      // 指向已注册 Endpoint
  request:        HttpRequestTemplate
  response:       ResponseMapping
  auth:           AuthScheme
  resilience:     ResiliencePolicy
  errorMapping:   List<ErrorRule>
}

HttpRequestTemplate {
  method:         HttpMethod (enum: GET/POST/PUT)
  pathTemplate:   String                       // 含占位符
  query:          List<TemplateParam>          // name + valueTemplate
  headers:        List<TemplateParam>
  bodyTemplate:   String?                       // POST/PUT，含占位符
}

ResponseMapping {
  successWhen:    Predicate { path: String, op: CompareOp(enum), value: Object }  // 如 $.code == 0
  valuePath:     String                         // 点号 jsonPath，从任意外壳取值
}

AuthScheme {
  kind:           AuthKind (enum: STATIC_HEADER / BEARER / OAUTH2_CLIENT_CREDENTIALS)
  params:         (按 kind 的 typed 子结构；密钥引用 infra，不内联)
}

ResiliencePolicy {
  connectTimeoutMs: int
  readTimeoutMs:    int
  retries:          int
  retryOn:          Set<RetryTrigger> (enum: TIMEOUT / UPSTREAM_5XX)
  circuitBreaker:   CircuitBreakerPolicy { failureRateThreshold, windowSeconds, openSeconds }
}

ErrorRule { when: ErrorMatch (status 范围 / 信封码), to: MetricFetchError }
```

### 5.1 变量命名空间（跨源统一）

可绑定引用集与命名两源一致，用户学一套心智：

| 引用 | 含义 | HTTP 占位符 | SQL 命名参数 |
|---|---|---|---|
| `payload.x` | 事件 payload 字段 | `{payload.x}` | `:payload.x` |
| `params.x` | metric.params.params 子项 | `{params.x}` | `:params.x` |
| `vars.x` | metric.params.vars（连接器入参） | `{vars.x}` | `:vars.x` |
| `subject.x` | **主体属性**（新增，SQL 侧补齐） | `{subject.x}` | `:subject.x` |
| `now` | 引擎统一时钟 | `{now}` | `:now` |
| `subjectId` / `tenantId` | 主体/租户 id | `{subjectId}` | `:subjectId` |

统一渲染器：HTTP 现在只 path 渲染，提升为 path/query/header/body 共用同一渲染器。`successWhen`/`valuePath` 用**声明式比较 + 现有点号 extractor**，不引表达式引擎。声明式表达不了的怪服务 → 走 §7 薄 provider。

## 6. 错误模型（跨源）

新增 closed enum（CLAUDE.md：封闭取值用 enum）：

```
MetricFetchError: NOT_FOUND | TIMEOUT | UNAUTHORIZED | UPSTREAM_ERROR | PARSE_ERROR | MAPPING_ERROR | TYPE_MISMATCH
```

- **降级行为完全不变**：仍返回 error `MetricValue`、引用该 metric 的条件不命中、整树继续短路、引擎不重试（D15）。
- 细码只进 `MetricValue.errorCode`（字段已是 String，开放码原样穿透）+ 可观测标签：`rule_engine_metric_fetch_*` 加 `error_code` 维度。
- 两源都映射：HTTP（超时→TIMEOUT、非 2xx→UPSTREAM_ERROR、信封 error→errorMapping、jsonPath 未命中→PARSE_ERROR、强转失败→TYPE_MISMATCH）；SQL（statement 超时→TIMEOUT、DB 异常→UPSTREAM_ERROR、强转失败→TYPE_MISMATCH）。
- `METRIC_FETCH_FAIL` 保留为评估语义层的伞码（条件不命中判定仍认它），细码是其下的可观测细分。

这是 OpenFeature「带 errorCode 但绝不抛到应用」的低成本照抄。

## 7. provider 逃生舱（L2）

声明式描述符表达不了的（签名鉴权、多次调用拼装、gRPC、私有协议）→ 实现一次薄 `MetricSourceHandler`，`@MetricSourceType("<自定义>")` 注册，复用现有 `init/destroy` 生命周期与外圈共用脊。`MetricSourceHandler` 已是这个口子，几乎零新增。

## 8. SQL 侧优化（继承共用脊，不加中间层）

1. **错误细码**：SQL 失败映射进 `MetricFetchError`（见 §6）。
2. **statement 超时**：把 query timeout 纳入同一 `ResiliencePolicy` 并真正下到 statement，与 HTTP 对热路径保护对等（现状缺 statement 级超时，慢查询顶着热路径）。retry/熔断对 SQL 价值低，不强加。
3. **变量命名空间对齐**：补 `:subject.<attr>`（主体属性绑定），与 HTTP `{subject.x}` 对称。
4. **自助测试**：纳入跨源 `MetricFetchTester`（见 §9）。

**保留的合理例外**：SQL 跑完全动态的用户配置 SQL，`NamedParameterJdbcTemplate` 装不下、MyBatis `@Select` mapper 不适用——这是"不引 JdbcTemplate"规约的合理例外，不迁。

## 9. 一致性与自助测试（L3，砍联调）

### 9.1 带编号连接器契约（docs）

仿 OpenFeature spec，把连接器行为写成带编号 Requirement（如「C2.1 successWhen 不命中且无 errorMapping 命中 → 归 UPSTREAM_ERROR」），作为接入方与套件的单一真相源。落 `docs/04-extension.md` §四扩展或新章节。

### 9.2 conformance kit（`rule-connector-conformance`）

嵌入式 mock 上游（轻量 HTTP stub）+ 黄金用例集：请求期望（method/path/body）+ 预置响应 + 期望 `MetricValue`/`errorCode`。跑绿 = 合规。接入方可独立运行对照自己的 staging。

### 9.3 自助测试端点（跨源，用户便利核心）

```
POST /admin/v1/metrics/{metricCode}:test     // 任意 sourceType 通吃
POST /admin/v1/connectors/{connectorCode}:test
body: { sampleVars, samplePayload, sampleSubjectId }
```

`MetricFetchTester` 实打实发一次，返回**分阶段 trace**：

- HTTP：渲染后 request（method/url/headers/body）/ 原始响应 / successWhen 判定 / 映射结果 / 命中的 errorCode；
- SQL：绑定后的 SQL / 原始首行 / 强转结果 / errorCode。

前端编辑器内联此面板——映射写错当场可见，不用部署才发现。

## 10. 用户便利（贯穿）

- 连接器定义一次、多 metric 引用。
- 信封**预设**：`{code,msg,data}` / 裸 JSON / `{success,data}` 一选即填再微调。
- 自助测试 + 分阶段 trace。
- 描述符字段走 JSON Schema 驱动前端动态表单（沿用现有 metadata 机制，业务方新增连接器前端无需改代码）。

## 11. 演进位（只记录边界，本轮不实现）

### 11.1 AI / ML 模型打分

- **模型打分当 metric（MODEL_SCORE / INFERENCE）**：调模型服务/LLM API，送特征收分。本质是声明式 HTTP 连接器（request 拼特征、response 映射分数）。= 新 sourceType + 复用连接器/脊，非新抽象。Nected 的模型节点、特征平台接 ML 同此。
- **LLM 当 condition（D10）**：是 `ConditionEvaluator`，不是 metric 源；但同发外部调用，复用外圈脊。

### 11.2 CEP / 时序聚合

- **CEP 产出当 metric（`STREAM`/`CEP_STATE`，近期可落、不返工）**：Flink/Esper 引擎外算滑窗/序列计数，按 subject 写快存；评估时引擎一次低延迟 fetch = MetricSource。`SourceType.STREAM` 本就给此留位。memory「实时两阶段漏斗」中可表达成"每主体一计数/标志"的部分走此。
- **一等事件序列模式（Drools Fusion / Flink CEP 风格，v2+，坚决不当 metric）**：让引擎自身跨事件保留窗口、匹配模式，是有状态时序求值——新 evaluation kind，不是取数。**绝不为它掰弯取数脊**（不搞偷偷维护窗口的"MetricSource"）。其输出经上一条当 metric 被消费。

### 11.3 三条"别焊死"边界纪律（本轮成本近零，需现在守住）

- **A** 外圈做成**调用无关**（resilience/错误归一/渲染/测试器/可观测不焊进 HTTP handler）→ 未来 MODEL_SCORE、LLMCondition、SubjectLoader 都继承。
- **B** assembler 别把"纯并行、无依赖"写死，留"某源依赖另一些值先就绪"的表达余地（给模型取特征铺路）——但**不建 DAG**，只是不堵。
- **C** `SourceType` 保持真开放（已是 String 常量 + SPI）；`MetricValue` 留非标量余地（已有 LIST/struct dataType 苗头），让模型一次调用多输出日后可扩。

## 12. 关键决策小结

| # | 决策 | 理由 |
|---|---|---|
| 1 | 连接器=可复用命名资源，不塞 metric.params | 消灭信封映射复制、可单独联调 |
| 2 | v1 连接器可变 + 热加载，不做 per-version 冻结 | 以简为先；冻结等真咬到再加 |
| 3 | 映射用声明式比较，不引表达式引擎；resilience 最小手写，不引 Resilience4j | 热路径要轻 |
| 4 | SQL 不加中间层，只继承共用脊 | SQL 无请求/信封/鉴权变异，加层是为对称而对称 |
| 5 | 外圈做成调用无关 | 让 AI/ML、CEP、LLMCondition 增量接入不返工 |
| 6 | AI/ML、一等 CEP 只进演进位 | YAGNI；本轮范围红线 |
