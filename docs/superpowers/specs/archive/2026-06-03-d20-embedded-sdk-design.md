# D20 Embedded SDK 设计文档

## 目标

把 `rule-kernel` 打包成嵌入式 SDK，让业务服务在自己的 JVM 内本地评估规则，消除评估热路径的网络跳转，同时所有性能优化只需改 `rule-kernel` 一处，中心服务和 SDK 同时受益。

---

## 架构

### 模块关系

```
rule-kernel（下沉编排逻辑）
    ↑
    ├── rule-eval-svc（Spring 壳，加 DB session写入 + Action派发）
    └── rule-sdk（纯 Java，加 HTTP 轮询快照）
              ↑
              └── rule-sdk-spring-boot-starter（可选，AutoConfiguration 胶水）
```

`rule-kernel` 是唯一的热路径实现：`EvalEngine`、`SceneRuleIndex`、`RuleVersionExecutor` 均在此。所有性能优化（D8 预编译、alpha 节点共享、无锁索引）只改 `rule-kernel`，两条路径（中心服务 / 嵌入式）自动受益。

---

## 模块职责

### `rule-kernel`（改动）

**下沉以下类（从 `rule-eval-svc` 迁入，去掉 Spring 注解）：**

| 类 | 迁移方式 |
|----|---------|
| `SceneRuleIndex` | 去掉 `@Component`，纯 Java `ConcurrentHashMap` |
| `EvalContextAssembler` | 去掉 `@Component`，构造器注入 `SubjectLoader` SPI |

**新增：**

- `EvalEngine`：提取自 `EvalServiceImpl.doEvaluate()` 的 matcher → pre-gate → context 组装 → executor 编排逻辑，纯 Java 类，不依赖 Spring，不写 DB，不派发 Action

`EvalEngine` 签名：
```java
public class EvalEngine {
    public EvalEngine(SceneRuleIndex index,
                      EvalContextAssembler contextAssembler,
                      Map<String, PreGate> preGates,
                      Map<String, RuleVersionExecutor> executors) { ... }

    /** 对单个事件求值，返回纯计算结果，无副作用。 */
    public EvalResult evaluate(RuleEvent event) { ... }
}
```

**`rule-eval-svc` 的 `EvalServiceImpl` 变薄：**

```java
// 改造后：薄壳，调用 EvalEngine，然后负责副作用
EvalResult result = evalEngine.evaluate(event);
sessionWriter.write(event, result);          // DB session写入
actionDispatchService.dispatch(...);         // Action异步派发
```

---

### `rule-sdk`（新建）

纯 Java 模块（零 Spring），依赖 `rule-kernel`。

**核心类：**

`RuleEngineClient`（门面）：
```java
// 非 Spring 接入方
RuleEngineClient client = RuleEngineClient.builder()
    .serverUrl("http://rule-api:8080")
    .tenantId("t1")
    .fetchMode(FetchMode.DECLARED)
    .scenes("payment", "fraud")
    .pollInterval(Duration.ofSeconds(30))
    .evalResultListener(myListener)     // 可选
    .evalSessionListener(myListener)    // 可选
    .build();

EvalResult result = client.evaluate(event);
```

`SnapshotPoller`（规则快照同步）：
- 启动时全量拉取，后台线程定时增量刷新
- 支持三种 fetch-mode（见下方"订阅范围"节）
- 快照写入本地 `SceneRuleIndex`，`ConcurrentHashMap` 原子替换，评估线程无感知

**SPI 扩展点：**

```java
/** 规则命中后回调，业务方自行决定如何处理 Decision（替代中心服务的 Action 派发）。 */
public interface EvalResultListener {
    void onResult(RuleEvent event, EvalResult result);
}

/** 可选审计回调，业务方自行决定是否写评估日志。 */
public interface EvalSessionListener {
    void onSession(RuleEvent event, EvalResult result);
}
```

---

### `rule-sdk-spring-boot-starter`（新建）

只有一个 `RuleEngineClientAutoConfiguration`，读 `application.yml` 配置，构造 `RuleEngineClient` Bean：

```yaml
rule:
  sdk:
    server-url: http://rule-api:8080
    tenant-id: t1
    fetch-mode: DECLARED        # DECLARED / ALL / LAZY
    scenes: payment,fraud       # fetch-mode=DECLARED 时必填
    poll-interval: 30s
```

Spring Boot 接入方：
```java
@Autowired
RuleEngineClient ruleEngineClient;

EvalResult result = ruleEngineClient.evaluate(event);
```

---

## 数据流

### 启动阶段（冷路径）

```
RuleEngineClient.build()
  └─ SnapshotPoller.start()
       └─ GET /api/v1/sdk/snapshots?tenantId=t1&scenes=payment,fraud
            └─ 返回 List<RuleVersionSnapshot>
                 └─ 写入本地 SceneRuleIndex
```

### 评估阶段（热路径，零网络）

```
client.evaluate(RuleEvent)
  └─ EvalEngine.evaluate(event)
       ├─ SceneRuleIndex.match(tenantId, sceneCode, eventType)   ← 内存
       ├─ applyPreGates(...)                                      ← 内存
       ├─ EvalContextAssembler.assemble(...)                      ← 内存
       └─ RuleVersionExecutor.execute(snapshot, ctx)              ← 内存
  └─ EvalResultListener.onResult(event, result)   ← 可选业务回调
  └─ EvalSessionListener.onSession(event, result) ← 可选审计回调
```

### 热更新（后台，默认 30s 轮询）

```
SnapshotPoller（后台线程）
  └─ GET /api/v1/sdk/snapshots?tenantId=t1&scenes=payment,fraud&since={lastModified}
       └─ SceneRuleIndex.update(...)   ← ConcurrentHashMap 原子替换
```

---

## 订阅范围（fetch-mode）

| 模式 | 行为 | 适用场景 |
|------|------|---------|
| `DECLARED`（默认） | 只拉 `scenes` 配置列表中的 scene | 接入方明确知道自己需要哪些规则，内存可控 |
| `ALL` | 拉取该 tenantId 下所有 ACTIVE 规则 | 小租户、规则少、不想配置 scene 列表 |
| `LAZY` | 首次 `evaluate(event)` 时按 sceneCode 按需拉取，后台定时刷新 | 业务方不确定会评估哪些 scene |

可叠加 `kinds` 过滤：

```yaml
rule:
  sdk:
    fetch-mode: DECLARED
    subscriptions:
      - scene: payment
        kinds: [SCORECARD]
      - scene: fraud          # kinds 缺省 = 该 scene 所有 kind
```

---

## rule-api 新增端点

```
GET /api/v1/sdk/snapshots
  ?tenantId=t1
  &scenes=payment,fraud      # 可选，ALL 模式不传
  &since=1717430400000       # 可选，增量拉取用毫秒时间戳
```

响应：`List<RuleVersionSnapshot>`（JSON），直接复用现有 `RuleVersionReadMapper.loadActiveByScene()` 查询，不需要新 SQL。

---

## 性能收益

| 对比项 | 中心服务模式 | Embedded SDK 模式 |
|--------|------------|-----------------|
| 评估热路径延迟 | 网络 RTT（1~10ms） | 内存操作（<1μs） |
| 规则更新延迟 | 事件驱动，秒级 | 轮询间隔（默认 30s） |
| 依赖基础设施 | rule-api 可用 | 启动时需访问 rule-api，运行时完全离线 |
| 审计 | 中心库统一 | 业务方自选（EvalSessionListener） |

---

## 不做的（范围边界）

- 不内置 Action 派发（业务方通过 `EvalResultListener` 自行处理 Decision）
- 不内置评估 session 写库（业务方通过 `EvalSessionListener` 自行决定）
- 不支持 dry-run（SDK 场景不需要）
- 不支持跨租户（一个 `RuleEngineClient` 实例对应一个 `tenantId`）
- SDK 本身不做鉴权（HTTP 轮询时由业务方在 builder 上配置 `Authorization` header）

---

## 新模块清单

| 模块 | 说明 |
|------|------|
| `rule-sdk` | 新建，纯 Java，依赖 `rule-kernel` |
| `rule-sdk-spring-boot-starter` | 新建，薄 Spring 胶水层 |
| `rule-kernel`（改动） | 下沉 `SceneRuleIndex`、`EvalContextAssembler`，新增 `EvalEngine` |
| `rule-eval-svc`（改动） | `EvalServiceImpl` 变薄，委托 `EvalEngine`，保留 DB + Action 副作用 |
| `rule-api`（改动） | 新增 `GET /api/v1/sdk/snapshots` 端点 |
