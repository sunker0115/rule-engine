# 跨实例传播：SPI 扩 broadcast/trigger + config→eval 广播接线 — 设计

D73 调度子系统的 track #3。track #1（`ScheduledTask`/executor SPI）、#2（`OUTCOME_INGESTION` executor）已实装；通信子系统的 A 类 cross-instance propagation 现在补齐。

## 1. 问题

- **Scheduler SPI 只有单派发**：`schedule(code, cron, task)` 仅 cron 单实例触发，没有广播机制
- **config→eval 没有跨实例推送**：`SceneChangedEvent` / `RulePublishedEvent` 走 Spring Modulith 进程内事件，多实例下其他 eval 实例不知道索引该刷新
- **poll watcher 是纸上安全网**：spec 里写了 15s/30s 兜底轮询，从未实现

## 2. Scheduler SPI 扩两个方法

```java
// rule-kernel: com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler

/**
 * 注册广播 handler（code 全局唯一）。
 * @param code         全局唯一标识
 * @param onEachNode   广播处理器，接收 triggerBroadcast 透传的 param
 */
void scheduleBroadcast(String code, Consumer<String> onEachNode);

/**
 * 触发一次广播：所有实例同时执行。
 * @param code  已注册的广播 handler 标识
 * @param param 透传到 onEachNode 的参数（不含广播语义的业务 payload）
 */
void triggerBroadcast(String code, String param);
```

### 2.1 三后端实现

|  | ThreadPool | XXL | 测试 |
|---|---|---|---|
| `scheduleBroadcast` | 存 map[code]=consumer | 空操作 | 同 ThreadPool |
| `triggerBroadcast` | `consumer.accept(param)` | admin `/jobinfo/trigger`，覆盖 executorParam | 记录调用次数+param |

**XXL 侧实现细节：**

1. `XxlJobSchedulerAdapter` 构造时注册**第二个 handler** `BROADCAST_HANDLER = "config-broadcast-runner"`（与 cron 单派发的 `UNIVERSAL_HANDLER = "scheduled-task-runner"` 独立，`registryJobHandler` 按 name 存 map，两者互不覆盖）
2. 惰性 seed 广播 jobinfo（首次 `triggerBroadcast` 时 DCL，避免构造期 admin 网络 I/O 拖垮启动，与既有懒登录风格一致）：`ensureJobSeeded("config-broadcast", BROADCAST_HANDLER, "0 0 0 1 1 ?", SHARDING_BROADCAST, "")`——cron 设永不自动触发（每年 1/1 零点），**路由策略=`SHARDING_BROADCAST`**，只等手动 trigger
3. `triggerBroadcast("config-change", param)` → `adminClient.triggerJob(configBroadcastJobId, param)`——覆盖 executorParam，XXL admin 分片广播到所有在线 executor
4. `config-broadcast-runner` handler `execute()` 收到 `XxlJobHelper.getJobParam()` → 查 `consumers["config-change"]` → `accept(param)`

> **关键：** 广播 job 必须挂 `BROADCAST_HANDLER`（非 `UNIVERSAL_HANDLER`）。否则广播 param（如 `"scene:9100:fraud_check"`）会进通用 handler 的 `Long.parseLong` → `NumberFormatException` → 被现有 catch 分支静默吞掉。两 handler name 分开是广播生效的前提。

## 3. config→eval 广播接线

### 3.1 现状

- config-svc 发布 → `ApplicationEventPublisher.publishEvent(SceneChangedEvent/RulePublishedEvent)`
- eval-svc `@ApplicationModuleListener` → 同 JVM 即时重载索引（毫秒）
- 无跨实例机制

### 3.2 接线

**不加新事件类型，不替代现有 Modulith 事件。** 在现有事件之上叠一层广播推送：

**config-svc 侧——`ConfigChangeBroadcaster`（新增组件）：**

```java
// rule-config-svc 内部
@Component
public class ConfigChangeBroadcaster {

    // Scheduler 是条件装配 bean（仅配了调度后端才存在），惰性注入避免无后端时启动失败
    private final ObjectProvider<Scheduler> schedulerProvider;

    @ApplicationModuleListener
    public void onSceneChanged(SceneChangedEvent event) {
        String param = "scene:" + event.tenantId() + ":" + event.sceneCode() + ":" + event.active();
        schedulerProvider.ifAvailable(s -> s.triggerBroadcast("config-change", param));
    }

    @ApplicationModuleListener
    public void onRulePublished(RulePublishedEvent event) {
        String param = "rule:" + event.tenantId() + ":" + event.sceneCode();
        schedulerProvider.ifAvailable(s -> s.triggerBroadcast("config-change", param));
    }
}
```

- 用 `@ApplicationModuleListener`（Modulith 提交后异步）与现有 `RuleIndexEventListener` / `SceneIndexEventListener` 对齐，符合 CLAUDE.md「副作用与事件解耦」A 类规范，且天然保证事务提交后才广播——其他实例回 DB 重载时数据已可见
- `ObjectProvider<Scheduler>.ifAvailable` 惰性：`Scheduler` 是条件装配 bean，无调度后端时不广播（单 JVM 进程内事件已足够），不致启动失败
- 同一 JVM 的 eval-svc **已被进程内事件即时通知**，广播只为通知**其他实例**

**eval-svc 侧——`ConfigChangeBroadcastHandler`（新增组件）：**

```java
// rule-eval-svc 内部
@Component
public class ConfigChangeBroadcastHandler {

    // Scheduler 条件装配，惰性注入；无调度后端时不注册广播 handler（单 JVM 进程内事件已够）
    private final ObjectProvider<Scheduler> schedulerProvider;

    @PostConstruct
    void register() {
        // Consumer<String> 接收 Scheduler 透传的 param
        schedulerProvider.ifAvailable(s -> s.scheduleBroadcast("config-change", this::onConfigChange));
    }

    void onConfigChange(String param) {
        // 格式: "type:tenantId:sceneCode[:active]"
        String[] parts = param.split(":");
        String type = parts[0];      // "rule" | "scene"
        String tenantId = parts[1];
        String sceneCode = parts[2];

        // 全量重载该 scene 的 ACTIVE 快照（含已删除/禁用规则）
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadBySceneWithStrategy(tenantId, sceneCode, index);
        index.replaceScene(tenantId, sceneCode, byEventType);
        scriptWarmer.warmUpIfEager(distinct(byEventType));

        // scene=false 时空结果自动清掉索引（效果等价 remove）
    }
}
```

### 3.3 与现有进程内事件的关系

| 层 | 路径 | 延时 | 场景 |
|---|---|---|---|
| 同 JVM | Modulith `@ApplicationModuleListener` | 毫秒 | 单机 / 多实例本实例 |
| 跨实例 | `Scheduler.triggerBroadcast` → XXL 分片广播 | 亚秒 | 多实例其它实例 |
| 兜底 | 无 | — | 广播已推送，不需要 poll |

**semantics：** 同一 JVM 内 eval-svc 会被进程内事件和广播 handler **两次触发同一个 reload**（先毫秒级 Modulith 事件，后 ThreadPool 本地退化直调 handler）。但重载逻辑幂等（DB 真相源 + replaceScene 原子替换），两次重载同一 scene 无害。

### 3.4 复用与现有监听器的关系

`ConfigChangeBroadcastHandler` 和 `RuleIndexEventListener` / `SceneIndexEventListener` 共享：
- `SceneSnapshotLoader.loadBySceneWithStrategy()`
- `SceneRuleIndex.replaceScene()`
- `ScriptWarmer.warmUpIfEager()`

如果两者的 reload 逻辑重复超过 5 行，抽取一个 `SceneIndexReloader` 组件供三方调用。不在本次设计中硬性要求。

## 4. param 透传机制

`Consumer<String>` 接口屏蔽了 param 来源差异：

- **ThreadPool**：`triggerBroadcast` → `consumer.accept(param)`，直接传
- **XXL**：adapter 在 dispatch 时从 `XxlJobHelper.getJobParam()` 取 param → `consumer.accept(param)`

eval-svc 的 handler 只认 `Consumer<String>`，不碰 `XxlJobHelper`。

## 5. XxlJobAdminClient 改 ensureJobSeeded + 加 triggerJob

**改 `ensureJobSeeded` 签名加路由策略形参**——现有 `insertJobInfo`（`HttpXxlJobAdminClient.java`）把 `executorRouteStrategy` 硬编码为 `"FIRST"`，cron 单派发用 FIRST、广播用 SHARDING_BROADCAST，必须参数化：

```java
/**
 * 确保 admin 侧存在匹配 jobDesc 的 jobinfo；不存在则按给定路由策略新建。
 * @param routeStrategy 路由策略（"FIRST" 单派发 / "SHARDING_BROADCAST" 广播）
 */
long ensureJobSeeded(String jobDesc, String executorHandler, String cron,
                     String routeStrategy, String executorParam);
```

- 现有调用点（cron 单派发 `schedule()`）传 `"FIRST"`，行为不变
- 广播 seed 传 `"SHARDING_BROADCAST"`
- `insertJobInfo` 把硬编码 `"FIRST"` 改为透传 `routeStrategy`

**加 `triggerJob`**：

```java
/** 触发现有 jobinfo（路由策略由 jobinfo 自身决定），覆盖 executorParam。 */
void triggerJob(long adminJobId, String executorParam);
```

`HttpXxlJobAdminClient` 实现：POST `/jobinfo/trigger`，form 参数 `id` + `executorParam`。

## 6. 广播 handler vs 通用 handler 共存

当前 `XxlJobSchedulerAdapter` 只注册了一个 `scheduled-task-runner` handler，按 `executorParam=taskId` 取 `Runnable`。

扩广播后要区分两种 handler：
- `UNIVERSAL_HANDLER = "scheduled-task-runner"`——cron 单派发，param=taskId → `Runnable`
- `BROADCAST_HANDLER = "config-broadcast-runner"`——广播，param=payload → `Consumer<String>`

两个 handler name 区分 **route strategy 不同**（单派发用 FIRST，广播用 SHARDING_BROADCAST）。

## 7. 数据流

```
config-svc publish(SceneChangedEvent)
  → @ApplicationModuleListener（Modulith 提交后异步）
  → ConfigChangeBroadcaster
  → scheduler.triggerBroadcast("config-change", "scene:9100:fraud_check:true")

  ┌─ ThreadPool: consumers["config-change"].accept("scene:9100:fraud_check:true")
  └─ XXL: POST /jobinfo/trigger id=<configBroadcastJobId> executorParam="scene:9100:fraud_check:true"
       → XXL admin 分片广播到所有在线 executor
       → config-broadcast-runner handler dispatch
       → consumers["config-change"].accept(param)

eval-svc ConfigChangeBroadcastHandler.onConfigChange(param)
  → loader.loadBySceneWithStrategy(...)
  → index.replaceScene(...)
  → scriptWarmer.warmUpIfEager(...)
```

## 8. 非目标（YAGNI）

- 不补 poll watcher（广播已推送，漏信号概率极低）
- 不引 Redis / MQ（广播走调度后端）
- 不动 B 类同事务事件、C 类 Outbox
- scene disable 不需要单独 clean（重载时空结果自动清索引）
- 不确保重复 reload 无副作用（幂等已由 replaceScene 保证）

## 9. 测试策略

- **Scheduler 契约测试**：`scheduleBroadcast` + `triggerBroadcast` 的 ThreadPool 退化行为
- **XXL adapter 测试**：`triggerBroadcast` seed+trigger 循环，验证 admin 侧 jobinfo 的路由策略 + executorParam
- **ConfigChangeBroadcaster 测试**：`@ApplicationModuleListener` 触发 → `triggerBroadcast` 被调，param 正确
- **ConfigChangeBroadcastHandler 测试**：模拟 param 输入 → `replaceScene` 被调用
- 端到端：docker-admin + 两台规则引擎（端口不同）→ 改配置 → 另一个实例的索引也刷新

## 10. 文件清单

| 模块 | 文件 | 操作 |
|---|---|---|
| rule-kernel | `api/spi/scheduler/Scheduler.java` | 修改：加两个方法 |
| rule-kernel | `api/spi/scheduler/SchedulerTest.java` | 追加测试 |
| rule-job-svc | `ThreadPoolSchedulerAdapter.java` | 修改：实现新方法 |
| rule-job-svc | `ThreadPoolSchedulerAdapterTest.java` | 追加测试 |
| rule-job-xxl | `XxlJobAdminClient.java` | 修改：`ensureJobSeeded` 加 routeStrategy 形参 + 加 triggerJob |
| rule-job-xxl | `HttpXxlJobAdminClient.java` | 修改：`insertJobInfo` 透传 routeStrategy（去硬编码 FIRST）+ 实现 triggerJob |
| rule-job-xxl | `XxlJobSchedulerAdapter.java` | 修改：注册 BROADCAST_HANDLER + 惰性 seed 广播 jobinfo（SHARDING_BROADCAST，首次 triggerBroadcast 时 DCL）+ schedule() 现有调用传 "FIRST" |
| rule-job-xxl | 测试 | 追加广播 handler 测试；现有 seed 测试补 routeStrategy 参数 |
| rule-config-svc | `ConfigChangeBroadcaster.java` | 新建 |
| rule-eval-svc | `ConfigChangeBroadcastHandler.java` | 新建 |
