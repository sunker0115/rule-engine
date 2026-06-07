# 统一 RuleEvent 产生路径 设计

> Spec。实现计划见后续 `docs/superpowers/plans/`。

## 背景

`RuleEvent` 进入引擎当前有三条产生 → 注入路径，各自为政：

| 路径 | RuleEvent 怎么来 | 注入入口 | 走哪层 | 落 session |
|---|---|---|---|---|
| HTTP（EvalController） | `@RequestBody RuleEvent`，外部 JSON 全量反序列化 | `acceptEvent` / `evaluate` / `dryRun` | eval-svc | 是 |
| Job（JobRunner） | `new RuleEvent(...)` 合成 | `acceptEvent` | eval-svc | 是 |
| SDK（RuleEngineClient） | 调用方传入 | `evalEngine.evaluate`（kernel 本地） | 不经 eval-svc | 否 |

三个问题：

1. **`source` 没落地**：`EvalServiceImpl.doEvaluate` 里 `insertPending(event, n, "PULL")` 硬编码 "PULL"，且 `acceptEvent` 内部走 `dispatcher.submit → this::evaluate`，连 PUSH 都被记成 PULL。`evaluation_session.source` 这列形同废弃，无法区分来源。
2. **渠道 / 模式两个维度被搅在一列**：渠道（HTTP/MQ/JOB/SDK/REPLAY，事件从哪来）和模式（PUSH 异步 / PULL 同步，怎么评估）是正交的，现在 `session.source` 的 ENUM `PUSH/PULL/REPLAY` 把两者混在一起且只写死 PULL。`RuleEvent` 也没有 source 字段。
3. **构造三套各写各的**：HTTP 靠 Jackson 反序列化、Job 手 `new` + hash、SDK 调用方裸传，必填字段（eventId/occurredAt/source）保证不一，无统一产生入口。

## 设计决策（经澄清逐项确认）

- **范围**：完整（模型 + 注入层 + session 拆列 + 三路径接入）。
- **`source` 渠道**：作为 `RuleEvent` 字段，枚举 `HTTP/MQ/JOB/SDK/REPLAY`。
- **`mode`（PUSH/PULL）不进 RuleEvent**：是"这次怎么评估"的运行时事实，由 `EvalService` 在入口判定、写 session；同一事件既可 push 也可 pull。
- **统一构造用 Builder**：`RuleEvent` 加 Lombok `@Builder`；`eventId` 由产生方提供（幂等键，不自动生成）。
- **`source` 由注入入口权威设置、不信外部 JSON**：HTTP→HTTP、Job→JOB、SDK→SDK，外部不能自称渠道。

## 设计

### 1. 模型层（rule-kernel）

- 新增枚举 `EventSource`（`com.sstlfsj.rule.kernel.api.model`）：`HTTP / MQ / JOB / SDK / REPLAY`。
- `RuleEvent` 加第 9 个字段 `EventSource source`（必填）。compact constructor：`source` 非空校验、`occurredAt` 缺省 `Instant.now()`、`payload`/`providedMetrics` 缺省空 Map（保留现有 `Map.copyOf`）。
- kernel 引 Lombok（首次），`RuleEvent` 加 `@Builder`：
  `RuleEvent.builder().tenantId(..).sceneCode(..).eventType(..).subjectId(..).eventId(..).source(..).payload(..).providedMetrics(..).build()`。
  `eventId` 由产生方给；`occurredAt`/`payload`/`providedMetrics` 可省。
- Lombok 是编译期注解处理器，不进运行时——不破坏 kernel "零运行时依赖 + GraalVM Native Image 兼容"。

### 2. 注入层（rule-eval-svc）

- `mode` 作为 `doEvaluate` 的入参：`acceptEvent`→`PUSH`、`evaluate`→`PULL`。
  - **关键修正**：`acceptEvent` 现在走 `dispatcher.submit → this::evaluate`（PULL 入口），会把 PUSH 记成 PULL。需把 dispatcher 回调改为"以 PUSH 模式评估"（如 `doEvaluate(event, Mode.PUSH, ...)`），不再复用 PULL 入口。
- `session.source` 取自 `event.source()`（替掉硬编码 "PULL"）；`session.mode` 取自入口判定。
- `evaluation_session` 拆列：
  - `source` ENUM 改为渠道：`HTTP/MQ/JOB/SDK/REPLAY`；
  - 新增 `mode` ENUM：`PUSH/PULL`。
  - 迁移 **V1_8**（greenfield 无生产数据，直接改列，不做数据兼容）。
- `dryRun` 仍走 `dry_run_session`，不受影响。

### 3. 三路径接入

- **HTTP**（`EvalController`）：请求体不再直接绑 `RuleEvent`（外部不能伪造 source），改绑一个不含 source 的请求记录 → builder 补 `source=HTTP` → `acceptEvent`/`evaluate`/`dryRun`。
- **Job**（`JobRunner`）：`@RuleJob` 方法返回 `List<JobTarget>`（`subjectId` + `payload` + `providedMetrics`）→ builder 补 `source=JOB` → `acceptEvent`。顺势收尾 job：`Subject`→`JobTarget`、删 `PayloadTemplateRenderer`（主体由方法直接带值，模板渲染多余）。
- **SDK**（`RuleEngineClient`）：builder 补 `source=SDK` 构造 → 本地 `evalEngine`（嵌入式无 session，`mode` 不落库，仅渠道标 SDK）。

### 4. 影响面 / 收尾

- kernel 首次引 Lombok → `09-skeleton.md` 注明（编译期，不破坏运行时零依赖 / Native）。
- 所有现存 `new RuleEvent(...)`（EvalController、JobRunner、benchmark、kernel/eval 若干测试）改 builder + 补 source。
- 文档：`01-concepts`（RuleEvent.source 字段 + §3.10）、`05-storage`（session source/mode + V1_8）、`00-decisions`（新条目）。

## 测试策略

- kernel：`RuleEvent` builder 缺省/校验单测（source 必填、occurredAt 缺省、payload/providedMetrics 缺省空）；`EventSource` 枚举。
- eval-svc：`EvalServiceImpl` PUSH/PULL 两入口分别写对 `session.mode`；`session.source` 取自 `event.source()`（集成测试验证 HTTP→HTTP、Job→JOB 落库正确）。
- rule-api：`EvalController` 三端点补 `source=HTTP`（外部传的 source 被忽略）。
- job：`JobTarget` record 校验；`JobRunner` 用 JobTarget 合成带 `source=JOB` 的 RuleEvent；注解端到端 session.source=JOB。
- SDK：`RuleEngineClient` 构造的事件 `source=SDK`。

## 决策记录（待写入 00-decisions）

- D49：统一 RuleEvent 产生——`RuleEvent` 加 `source`（EventSource 渠道）+ Lombok `@Builder`；`mode`(PUSH/PULL) 入口判定写 session；`evaluation_session` 拆 `source`(渠道)/`mode`(模式) 两列(V1_8)；source 由注入入口权威设置、不信外部；HTTP/Job/SDK 三路径统一经 builder 构造。kernel 首次引 Lombok（编译期，不破坏运行时零依赖/Native）。
