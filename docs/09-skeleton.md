# 09 — 项目骨架与工程结构

> **位置定位**：本文档承载 rule-engine 的**工程契约层**——Maven 模块拆分、包命名根、SPI 接口落点、依赖方向、配置分布。
>
> **前置阅读**：[`README.md`](./README.md) §四 抽象表、[`00-decisions.md`](./00-decisions.md)、[`01-concepts.md`](./01-concepts.md) §三 名词全景
>
> **解决什么疑问**："新代码放哪个模块 / 哪个包？""业务方接入要依赖哪个 jar？""SPI 接口在哪个模块？""模块间依赖方向是什么？"
>
> **职责边界**——
> - ✅ 模块划分 / 包结构 / SPI 落点 / 依赖方向 / 配置文件分布
> - ❌ 不写决策权衡（→ 00-decisions）、不写一等概念字段表（→ 01-concepts）、不写 DDL（→ 05-storage）、不写运维参数默认值（→ 07-operability）、不写扩展实操步骤（→ 04-extension）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 Maven 模块拆分 | ✅ 已展开 |
| §三 包命名与包结构 | ✅ 已展开 |
| §四 SPI 接口落点 | ✅ 已展开 |
| §五 依赖方向与禁止环 | ✅ 已展开 |
| §六 配置文件分布 | ✅ 已展开 |
| §七 测试组织 | ✅ 已展开 |
| §八 v1 不做的拆分 | ✅ 已展开 |

---

## 二、Maven 模块拆分

v1 阶段 8 个模块（6 个 Spring 模块 + 1 个零 Spring 内核库 + 1 个可选轮询库），单 Spring Boot 服务部署。

| 模块 | 职责 | 部署形态 |
|------|------|---------|
| `rule-kernel` | 零 Spring 零 DB，所有 SPI 接口定义 + 纯评估逻辑 | 库（jar），未来 = 嵌入式 SDK jar |
| `rule-kernel-polling` | `DbPollingRuleWatcher` / `DbPollingSceneWatcher` 实现，SDK 使用方按需引入 | 可选库（jar），仅 SDK 模式使用 |
| `rule-config-svc` | 规则/Scene/元数据 CRUD、发布、快照生成 | Spring 模块，内嵌于主服务 |
| `rule-eval-svc` | 评估入口（PUSH/PULL/dry-run）、metric 预拉、session 落库、调度任务 | Spring 模块，内嵌于主服务 |
| `rule-audit-svc` | 审计查询、dry-run 结果存储、日志聚合 | Spring 模块，内嵌于主服务 |
| `rule-observability` | TraceWriter DB 实现、Prometheus 指标名常量、告警默认配置 | Spring 模块，内嵌于主服务 |
| `rule-api` | 所有 HTTP controller、鉴权、限流、API 版本前缀 | Spring 模块，内嵌于主服务 |
| `rule-app` | Spring Boot 启动类，组装所有模块，无业务逻辑 | 可执行 jar（主服务） |

**`rule-kernel` Native Image 说明**：零 Spring 零 DB，完全兼容 GraalVM Native Image，SDK 路径下调用方可用于 Native Image 应用。主服务（`rule-app`）因 MyBatis-Plus 动态代理机制，v1 不支持 Native Image 编译（详见架构设计文档约束 5）。

---

## 三、包命名与包结构

包命名根：`com.sstlfsj.rule`

组织原则：**按职责分包**（不按类型分包）。每个模块下 `api` 子包对外开放，`internal` 子包仅模块内使用，禁止跨模块直接引用 `internal` 内的类。

```
com.sstlfsj.rule
├── kernel                          # rule-kernel 模块
│   ├── api                         # 对外公开：SPI 接口、核心数据结构
│   │   ├── spi                     # 所有 SPI 接口（见 §四）
│   │   ├── model                   # EvalContext / EvalResult / RuleVersionSnapshot / DryRunResult / AST 节点
│   │   └── annotation              # @ConditionType / @ActionType / @MetricSourceType
│   └── internal
│       └── evaluator               # InterpretedExecutor（默认 RuleVersionExecutor 实现）
│
├── config                          # rule-config-svc 模块
│   ├── api
│   │   └── service                 # ConfigService / SceneService / MetadataService（供 rule-api 调用）
│   └── internal
│       ├── domain                  # Scene / Rule / RuleVersion / MetricDefinition / ActionTypeDefinition
│       ├── repository              # MyBatis-Plus Mapper
│       ├── publish                 # 发布流程、快照生成、输入闭合校验
│       └── event                  # RulePublishedEvent / SceneChangedEvent（Modulith 事件定义）
│
├── eval                            # rule-eval-svc 模块
│   ├── api
│   │   └── service                 # EvalService（PUSH/PULL/dry-run 入口，供 rule-api 调用）
│   └── internal
│       ├── index                   # 倒排索引维护、RulePublishedEvent / SceneChangedEvent 监听
│       ├── context                 # EvalContext 装配（Subject 加载 + metric 预拉，Virtual Threads）
│       ├── session                 # evaluation_session 幂等落库
│       ├── dispatcher              # Action Dispatcher（自研 BlockingQueue，D20）
│       └── scheduler              # Scheduler SPI Spring @Scheduled 默认实现
│
├── audit                           # rule-audit-svc 模块
│   ├── api
│   │   └── service                 # AuditService（审计查询，供 rule-api 调用）
│   └── internal
│       ├── repository              # MyBatis-Plus Mapper
│       └── listener                # DryRunCompletedEvent 监听，落 dry_run_session
│
├── observability                   # rule-observability 模块
│   ├── api
│   │   └── metrics                 # Prometheus 指标名常量（所有模块引用此处）
│   └── internal
│       ├── trace                   # TraceWriterDbImpl（异步 BlockingQueue + 批量落库，D21）
│       └── config                  # ObservabilityAutoConfiguration（告警阈值默认配置绑定）
│
├── web                             # rule-api 模块
│   ├── eval                        # EvalController（PUSH/PULL/dry-run 端点）
│   ├── config                      # RuleController / SceneController / MetadataController
│   ├── audit                       # AuditController
│   └── filter                     # 鉴权 / 限流 / 版本路由 Filter
│
└── app                             # rule-app 模块
    └── RuleEngineApplication       # @SpringBootApplication 启动类
```

**一等概念到包的映射**：

| 一等概念 | 落包 |
|---------|------|
| Scene | `config.internal.domain` / `eval.internal.index` |
| Rule / RuleVersion | `config.internal.domain` / `kernel.api.model` |
| Condition / AST | `kernel.api.model` |
| Action / ActionHandler | `kernel.api.spi` / 业务方自实现 |
| Metric / MetricSource（即 `MetricSourceHandler` 接口） | `kernel.api.spi` / 业务方自实现 |
| Subject / SubjectLoader | `kernel.api.spi` / 业务方自实现 |
| Pre-Gate | `kernel.api.spi` |
| RuleEvent / EvalResult | `kernel.api.model` |
| EvalContext | `kernel.api.model` |
| evaluation_session | `eval.internal.session` |

---

## 四、SPI 接口落点

所有 SPI 接口定义在 `rule-kernel` 模块的 `com.sstlfsj.rule.kernel.api.spi` 包下，对业务方公开（业务方依赖 `rule-kernel` jar 即可实现）。

| SPI 接口 | 包路径 | 决策来源 | 业务方可实现替换 |
|---------|-------|---------|----------------|
| `ConditionEvaluator` | `kernel.api.spi.condition` | D12 / §3.6 | ✅ |
| `ActionHandler` | `kernel.api.spi.action` | D16 / §3.7 | ✅ |
| `MetricSourceHandler` | `kernel.api.spi.metric` | §3.9 | ✅ |
| `SubjectLoader` | `kernel.api.spi.subject` | §3.13 | ✅ |
| `RuleVersionWatcher` | `kernel.api.spi.watcher` | D17 / §3.12 | ✅（SDK 模式） |
| `SceneWatcher` | `kernel.api.spi.watcher` | D24 | ✅（SDK 模式） |
| `RuleVersionExecutor` | `kernel.api.spi.executor` | D20 §5 | ⚠️ 高风险，谨慎替换 |
| `Scheduler` | `kernel.api.spi.scheduler` | D11 | ✅ |
| `TraceWriter` | `kernel.api.spi.trace` | D21 | ✅ |
| Pre-Gate 各接口 | `kernel.api.spi.pregate` | §3.14 | ✅ |

**SPI 实现归属**：

| SPI 实现类 | 所在模块 | 用途 |
|-----------|---------|------|
| `DbPollingRuleWatcher` | `rule-kernel-polling`（独立 artifact） | 嵌入式 SDK 模式（§2.14），无共享 Spring 容器时 polling |
| `DbPollingSceneWatcher` | `rule-kernel-polling`（独立 artifact） | 嵌入式 SDK 模式（§2.14），同上 |
| `InterpretedExecutor` | `rule-kernel` | v1 默认 RuleVersionExecutor |
| `TraceWriterDbImpl` | `rule-observability` | 主服务，异步批写 DB |
| `NoopTraceWriter` | `rule-observability` | SDK 模式 / 测试环境 |
| `SpringSchedulerAdapter` | `rule-eval-svc` | Spring `@Scheduled` 包装 |

> **单服务模式热加载**：`rule-eval-svc` 内部直接以 `@ApplicationModuleListener` 订阅 `RulePublishedEvent` / `SceneChangedEvent`，不经 `RuleVersionWatcher` / `SceneWatcher` SPI 通道（D17 Modulith 补充段）。这是框架内部机制，不对外暴露为可替换 SPI；替换方向是切到 MQ（加 `@Externalized`），而非换 Watcher 实现。

新增 SPI 接口必须回填本表，并同步 [`04-extension.md`](./04-extension.md)。

---

## 五、依赖方向与禁止环

```
rule-api
  ├──► rule-config-svc   (仅依赖 config.api.service)
  ├──► rule-eval-svc     (仅依赖 eval.api.service)
  └──► rule-audit-svc    (仅依赖 audit.api.service)

rule-config-svc    ──► rule-kernel
rule-eval-svc      ──► rule-kernel
rule-audit-svc     ──► rule-kernel
rule-observability ──► rule-kernel

rule-app ──► 所有模块（组装层，不含业务逻辑）
```

**禁止的依赖**：

| 禁止方向 | 原因 |
|---------|------|
| `rule-kernel` → 任何其他模块 | 零 Spring 零 DB 硬约束，kernel 是 SDK jar 核心 |
| svc 模块之间直接依赖 | 只通过 Modulith 事件通信，保证未来拆服务时只需加 `@Externalized` |
| `rule-api` → svc 模块的 `internal` 子包 | API 层只依赖 svc 的 public API，隔离实现细节 |
| svc 模块 → `rule-observability` | TraceWriter 通过依赖倒置注入，svc 只知道 `TraceWriter` 接口（在 kernel） |

**校验工具**：

- `rule-kernel` 的零 Spring 约束：ArchUnit 测试，检测任何 `org.springframework` import
- 跨模块访问边界：Modulith `@ApplicationModuleTest`，测试期静态分析
- 禁止环：Maven Enforcer `banCircularDependencies` 规则

---

## 六、配置文件分布

**主配置**：`rule-app/src/main/resources/application.yml`，统一入口，按模块分块注释。

**命名空间**：`engine.rule.*`，全部配置项默认值见 [`07-operability.md`](./07-operability.md) §九运维参数表。本节只列结构，不列默认值。

```yaml
engine:
  rule:
    matcher:
      cache-refresh-interval-seconds: ...   # Matcher 倒排索引热更间隔（D17，SDK 模式适用）
    scene:
      watch-interval-seconds: ...           # Scene 热加载间隔（D24，SDK 模式适用）
    idempotency:
      redis-ttl-seconds: ...
    trace:
      queue-capacity: ...
      batch-size: ...
      flush-interval-ms: ...
      consumer-threads: ...
    metric:
      default-cache-ttl-seconds: ...
    action:
      default-timeout-ms: ...
    retention:
      evaluation-session-days: ...
      node-trace-days: ...
      dry-run-session-days: ...
    rollout:
      hash-seed: ...                        # murmur3 seed，上线后不要改（D6）
    observability:
      # 告警阈值，由 rule-observability 模块绑定
      eval-error-rate-threshold: ...
      trace-queue-full-threshold: ...
```

**各模块 AutoConfiguration**：

| 模块 | AutoConfiguration 类 | 说明 |
|------|---------------------|------|
| `rule-observability` | `ObservabilityAutoConfiguration` | 注册 `TraceWriterDbImpl` Bean，绑定 `engine.rule.observability.*` |
| `rule-eval-svc` | `EvalAutoConfiguration` | 注册 `SpringSchedulerAdapter`；内置 `@ApplicationModuleListener` 订阅 `RulePublishedEvent` / `SceneChangedEvent` 触发索引热更（不经 SPI 通道，见 §四注记） |
| `rule-config-svc` | `ConfigAutoConfiguration` | 注册发布流程 Bean |

Spring Boot 4.0.x 使用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册（替代旧版 `spring.factories`）。

---

## 七、测试组织

### 7.1 测试目录结构

每个模块 `src/test/java` 下按功能分包，与 `src/main/java` 包结构镜像：

```
rule-kernel/src/test/java/com/sstlfsj/rule/kernel/
  evaluator/      # InterpretedExecutor 单测（AST 求值、短路语义、四态结果）
  model/          # EvalContext / EvalResult 构建单测

rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/
  index/          # 倒排索引增量热更单测
  context/        # EvalContext 装配（Subject + metric 并发预拉）单测
  session/        # evaluation_session 幂等落库单测
  integration/    # PUSH / PULL / dry-run 端到端集成测

rule-config-svc/src/test/java/com/sstlfsj/rule/config/
  publish/        # 发布流程 + 快照生成单测
  integration/    # 规则 CRUD + 发布完整链路集成测

rule-app/src/test/java/com/sstlfsj/rule/app/
  module/         # Modulith @ApplicationModuleTest（边界校验）
  arch/           # ArchUnit（rule-kernel 零 Spring 检测）
```

### 7.2 单测策略

- **mock 边界**：只 mock SPI 边界（`MetricSourceHandler`、`ActionHandler`、`SubjectLoader`），不 mock 内部协作类
- **最低覆盖率**：`rule-kernel`（evaluator / AST / Pre-Gate）≥ 85%；`rule-eval-svc`（index / context / session）≥ 75%；其余模块 ≥ 60%
- `rule-kernel` 单测不引入任何 Spring Test 依赖（保证零 Spring 约束可验证）

### 7.3 集成测策略

- **DB 用真实 schema**：集成测使用 Testcontainers MySQL，不 mock 数据库（避免 mock 与生产迁移行为分歧，对应 07-operability.md 测试原则）
- **Scene / Rule 端到端用例**：覆盖 PUSH / PULL / dry-run 三种模式 + 灰度路由 + Pre-Gate 拦截
- **幂等验证**：相同 `idempotency_key` 重复请求，验证 `evaluation_session` 只写一条
- **Modulith 边界测试**：`@ApplicationModuleTest` 覆盖所有模块，检测非法跨模块访问

### 7.4 性能基线

| 指标 | 基线目标 | 测试工具 |
|------|---------|---------|
| 单次评估 P99（无 metric IO） | ≤ 5ms | JMH |
| metric 预拉 P99（3 个 metric 并发） | ≤ 50ms | JMH |
| Action Dispatcher 吞吐 | ≥ 5000 TPS | JMH |

性能基线测试放 `rule-app/src/test/java/.../perf/`，仅在 CI `performance` profile 下触发，不随每次 PR 全跑。

### 7.5 测试数据

- `docs/examples/` 目录下的示例文件同时作为集成测 fixture（seed SQL + 规则 JSON）
- 公共 fixture 工厂类放 `rule-app/src/test/java/com/sstlfsj/rule/app/fixture/`，各集成测复用

---

## 八、v1 不做的拆分

v1 阶段以下模块暂时合并，v2 触发时按对应演进锚点拆分：

| 暂时合并的内容 | 合并原因 | v2 拆分触发条件 | 演进锚点 |
|-------------|---------|---------------|---------|
| `rule-eval-svc` 含调度任务（Scheduler） | v1 评估量不足以独立部署调度服务 | 调度任务资源抢占影响评估 P99 | [`08-evolution.md`](./08-evolution.md) §2.4 |
| `rule-audit-svc` 含 dry-run 结果存储 | v1 审计量小，独立部署成本高 | 审计查询影响热路径或存储独立扩容需求出现 | [`08-evolution.md`](./08-evolution.md) §2.15 |
| `rule-kernel` 不单独发布到 Maven 仓库 | v1 无外部 SDK 使用方 | 外部业务方需要嵌入式 SDK 接入 | [`08-evolution.md`](./08-evolution.md) §2.14 |
| `rule-kernel-polling`（独立 artifact）未发布到 Maven 仓库 | v1 无外部 SDK 使用方，无需对外发布 | 外部业务方需要嵌入式 SDK 接入 | [`08-evolution.md`](./08-evolution.md) §2.14 |

---

## 九、维护原则

- 本文档只承载**工程结构契约**。具体类名 / 方法签名 / 实现代码不入。
- 模块边界 / 包结构变更必须回写本文档对应章节，且若变更影响业务方依赖（如 SPI 模块更名 / 拆分），同步在 [`README.md`](./README.md) §七 版本史登记。
- 新增 SPI 接口必须回填 §四 SPI 接口落点表，并同步 [`04-extension.md`](./04-extension.md)。
- 新增测试维度（如契约测试 / 混沌测试）必须回填 §七 测试组织。
- v1 阶段任何"暂时合并"的模块在演进时拆分前，回写 §八 + [`08-evolution.md`](./08-evolution.md) 对应演进锚点。
