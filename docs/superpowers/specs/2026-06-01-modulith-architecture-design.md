# rule-engine v1 工程架构设计

**目标**：确定 v1 Spring Modulith 单服务的模块拆分方案，使模块边界既是当前的内聚单元，也是未来任意微服务拓扑的自然切割点，同时满足嵌入式 SDK 模式需求。

**前置文档**：`docs/09-skeleton.md`（工程结构契约）、`docs/00-decisions.md` D17/D24（热加载机制）、`docs/08-evolution.md` §2.14（嵌入式 SDK）

---

## 一、核心约束

1. **终态微服务拓扑不锁定**：模块边界按"最大未来灵活性"设计，不预设最终拆几个服务
2. **嵌入式 SDK 必须可行**：评估核心必须能以纯 Java jar 形式嵌入调用方进程（§2.14）
3. **平台型产品**：内外兼顾，多租户，HTTP API + SDK 双接入模式
4. **v1 单服务**：Spring Modulith 2.x + Spring Boot 4.0.x，模块边界由框架在编译/测试期强制
5. **Native Image 部分支持**：`rule-kernel` 零 Spring 零 DB，Native Image 完全兼容，SDK 路径（§2.14）可用于 Native Image 调用方；主服务因 MyBatis-Plus 动态代理/反射机制，v1 不支持 Native Image 编译。若未来有 Serverless 部署需求，需将 DB 访问层迁移至 MyBatis 原生或 jOOQ。

---

## 二、模块全貌

```
com.sstlfsj.rule
├── rule-kernel          # 零 Spring，零 DB，纯评估逻辑 + 所有 SPI 接口定义
├── rule-config-svc      # 规则/Scene/元数据 CRUD、发布、快照生成
├── rule-eval-svc        # 评估入口、metric 预拉、session 落库、调度任务
├── rule-audit-svc       # 审计查询、dry-run 结果存储、日志聚合
├── rule-observability   # TraceWriter DB 实现、Prometheus 指标常量、告警默认配置
├── rule-api             # 所有 HTTP controller、鉴权、限流、API 版本前缀
└── rule-app             # Spring Boot 启动类，组装所有模块，无业务逻辑
```

---

## 三、依赖方向（有向无环，Modulith 强制校验）

```
rule-api
  ├──► rule-config-svc
  ├──► rule-eval-svc
  └──► rule-audit-svc

rule-config-svc    ──► rule-kernel
rule-eval-svc      ──► rule-kernel
rule-audit-svc     ──► rule-kernel
rule-observability ──► rule-kernel

rule-app ──► 所有模块（仅组装，不含业务逻辑）
```

**禁止的依赖方向**：
- `rule-kernel` 不依赖任何其他模块（零 Spring 硬约束，ArchUnit 测试保证）
- svc 模块之间不直接依赖，只通过 Modulith 事件通信
- `rule-api` 只能依赖各 svc 模块的 `api` 子包，不能依赖 `internal` 子包
- svc 模块不依赖 `rule-observability`（TraceWriter 通过依赖倒置，实现在 observability，接口在 kernel，`rule-app` 装配时注入）

---

## 四、模块职责

### rule-kernel

- **SPI 接口**：`RuleVersionWatcher`、`SceneWatcher`、`RuleVersionExecutor`、`TraceWriter`、`ConditionEvaluator`、`ActionHandler`、`MetricSource`、`SubjectLoader`、`Scheduler`、Pre-Gate 各接口
- **核心数据结构**：`EvalContext`、`EvalResult`、`RuleVersionSnapshot`、`DryRunResult`、AST 节点树
- **默认实现**：`InterpretedExecutor`（`RuleVersionExecutor` v1 实现，在 kernel 内，无 Spring 依赖）
- **不包含**：任何 `@Component`、Spring 注解、JDBC、HTTP 依赖

此模块 = 未来嵌入式 SDK jar 的核心内容（§2.14）。

### rule-config-svc

- 规则/Scene/元数据的 CRUD 和发布流程
- 发布成功后通过 `ApplicationEventPublisher` 发出 `RulePublishedEvent` / `SceneChangedEvent`
- 生成并持久化 `rule_version` 快照（`ast_snapshot`、`pre_gates_snapshot`、`rollout_snapshot`、`metric_dependencies`）
- 输入闭合校验（D20 §3）：发布时校验 metric code / action type / condition type 均已注册

### rule-eval-svc

- 订阅 `RulePublishedEvent` / `SceneChangedEvent`，维护内存倒排索引 `(tenantId, sceneCode, eventType) → List<RuleVersionSnapshot>`
- PUSH / PULL / dry-run 三种评估入口的编排逻辑
- Subject 加载 + metric 批量预拉（Virtual Threads 并发，D25）
- `evaluation_session` 幂等落库（同步写，幂等键语义见 D22）
- Action 派发（自研 BlockingQueue，D20）
- 调度任务（`Scheduler` SPI，Spring `@Scheduled` 默认实现）
- 评估完成后发出 `DryRunCompletedEvent`（dry-run 路径）

### rule-audit-svc

- 订阅 `DryRunCompletedEvent`，落 dry-run 结果到独立存储（D22）
- `evaluation_session` 历史查询、审计日志查询
- 不参与热路径，所有操作均为读取或异步写入

### rule-observability

- `TraceWriterDbImpl`：`TraceWriter` SPI 的 DB 实现，异步 BlockingQueue + 批量落库（D21）
- Prometheus 指标名常量（所有模块引用此处，保证命名一致，防止指标名拼写漂移）
- 告警阈值默认配置（`engine.rule.observability.*` 命名空间，默认值见 `07-operability.md`）
- `NoopTraceWriter`：SDK 模式或测试环境使用

### rule-api

- 所有 `@RestController`，覆盖 `10-api-contract.md` 全部端点（PUSH/PULL/dry-run/规则管理/元数据/审计查询）
- 鉴权（API Key / JWT）、限流、`X-API-Version` 路由
- 参数校验、errorCode 映射（清单来自 `10-api-contract.md`）
- **不含任何业务逻辑**，只做协议层委托

### rule-app

- Spring Boot 启动类
- 组装所有模块，装配 SPI 实现（如把 `TraceWriterDbImpl` 注入到 eval-svc 使用 `TraceWriter` 的地方）
- Profile 配置（dev / test / prod）

---

## 五、模块间通信规则

| 场景 | 通信方式 | 拆服务演进路径 |
|------|---------|--------------|
| 规则发布 → eval 刷新索引 | Modulith `RulePublishedEvent` | 加 `@Externalized` → MQ 消息 |
| Scene 变更 → eval 热加载 | Modulith `SceneChangedEvent` | 同上 |
| eval 完成 → audit 落 dry-run | Modulith `DryRunCompletedEvent` | 同上 |
| Action 派发 | 自研 BlockingQueue（D20） | 热路径，保持不变 |
| node_trace 写出 | 自研 BlockingQueue（D21） | 热路径，保持不变 |
| 嵌入式 SDK 感知规则变更 | `DbPollingRuleWatcher` / `MqRuleWatcher` | SDK 模式无共享容器，不能收 Modulith 事件 |

---

## 六、嵌入式 SDK 边界（§2.14）

SDK 模式下调用方只依赖 `rule-kernel` jar：

- 配置源通过 `RuleVersionWatcher` SPI 接入（`DbPollingRuleWatcher` 或 `MqRuleWatcher`）
- `SceneWatcher` SPI 同理（`DbPollingSceneWatcher`）
- Action 派发走调用方提供的 `ActionHandler` 实现，不经过中心服务
- `TraceWriter` 由调用方提供实现，或使用 `NoopTraceWriter`
- `DbPollingRuleWatcher` / `DbPollingSceneWatcher` 是 `rule-kernel` 的可选依赖实现，不在 kernel 内，由使用方选择引入

---

## 七、Modulith 边界校验策略

- 每个模块加 `@ApplicationModule` 注解
- `rule-kernel` 的零 Spring 约束用 ArchUnit 测试保证（检测任何 `org.springframework` import）
- `rule-api` 对各 svc 的访问通过 `@ApplicationModuleTest` 验证只走 public API
- CI 阶段跑 `ApplicationContext` 全量模块测试，防止边界悄悄腐化

---

## 八、与 09-skeleton.md 的对应关系

本设计文档是 `09-skeleton.md` 各章节的输入基线：

| 09-skeleton 章节 | 本文档对应内容 |
|----------------|--------------|
| §二 Maven 模块拆分 | 第二节模块全貌 |
| §三 包命名与包结构 | 模块名即顶层包名，包根 `com.sstlfsj.rule` |
| §四 SPI 接口落点 | rule-kernel 职责中的 SPI 清单 |
| §五 依赖方向与禁止环 | 第三节依赖方向 |
| §六 配置文件分布 | `engine.rule.observability.*` 命名空间 |
| §七 测试组织 | 第七节 Modulith 边界校验策略 |
| §八 v1 不做的拆分 | 终态微服务拓扑不锁定，v2 触发时按模块边界切割 |
