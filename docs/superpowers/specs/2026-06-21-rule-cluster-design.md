# rule-cluster: SOFAJRaft 可插拔集群模块设计

**状态**: 设计中  
**日期**: 2026-06-21  
**关联决策**: D00（Scheduler 接口化）、D14（Modulith 事件解耦）

## 1. 目标

为 rule-engine 增加一个**可插拔的集群模块**，基于 SOFAJRaft 实现：

1. **Leader 选举** — 多实例部署时自动选主，仅 Leader 执行进程内 cron 调度
2. **配置变更强一致分发** — 规则/场景发布后经 Raft 日志复制到所有节点，同一 log index 原子生效，消除索引窗口期

**核心约束**：引入即用，不引入就不用。未引入 Raft 模块时，现有行为完全不变。

## 2. 模块结构

两个平级 Maven 模块，无聚合 POM：

```
rule-cluster/                            ← 纯 Java 核心，零 Spring 依赖
├── pom.xml                              ←   依赖: jraft-core + bolt + hessian + rule-kernel
└── src/main/java/com/sstlfsj/rule/cluster/
    ├── ClusterNode.java                 ← ★ 唯一 public 入口
    ├── ClusterConfig.java               ←   配置 record
    ├── JraftLeaderElector.java          ←   implements LeaderElector (kernel SPI)
    ├── RaftConfigPropagatorImpl.java    ←   implements ConfigPropagator (kernel SPI)
    ├── ConfigApplyStateMachine.java     ←   extends StateMachineAdapter
    └── ConfigApplierLoader.java         ←   ServiceLoader 加载 ConfigApplier

rule-cluster-spring-boot-starter/        ← Spring Boot 集成（可选）
├── pom.xml                              ←   依赖: rule-cluster + spring-boot-starter + rule-web
└── src/main/java/com/sstlfsj/rule/cluster/spring/
    ├── ClusterAutoConfiguration.java    ←   @AutoConfiguration
    ├── ClusterProperties.java           ←   @ConfigurationProperties("rule.cluster")
    ├── ClusterHealthIndicator.java      ←   HealthIndicator
    └── ClusterController.java           ←   /api/v1/cluster/status
```

命名对齐项目既有模块（`rule-kernel`、`rule-config-svc` 均为单层）。

## 3. kernel SPI 定义（3 个接口）

位置：`rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/cluster/`

### 3.1 LeaderElector

```java
/** 集群 Leader 选举 SPI。未引入集群模块时无实现，调用方通过 Optional 判空。 */
public interface LeaderElector {
    /** 当前节点是否为 Leader。 */
    boolean isLeader();

    /** 当前 Leader term，非 Leader 时返回上次已知 term。 */
    long leaderTerm();

    /** 注册 Leader 状态变更监听器。 */
    void addListener(LeaderElectionListener listener);
}

/** Leader 状态变更回调。 */
public interface LeaderElectionListener {
    /** 本节点成为 Leader。 */
    void onBecomeMaster(long term);

    /** 本节点失去 Leader 资格。 */
    void onBecomeSlave(int code, String reason);
}
```

### 3.2 ConfigPropagator

```java
/** 配置变更传播 SPI。默认实现走 Modulith 事件（最终一致），引入 raft 后走 Raft 日志（强一致）。 */
public interface ConfigPropagator {
    /**
     * 传播配置变更到所有节点。
     * @param cmd 变更命令（含类型 + 序列化后的具体事件）
     * @throws PropagationException 传播失败（Raft 共识超时/多数不可达）
     */
    void propagate(ConfigChangeCommand cmd) throws PropagationException;
}

/** 配置变更命令，在 config-svc（生产者）和 eval-svc（消费者）间共享。 */
public record ConfigChangeCommand(
        ConfigChangeType type,
        String tenantId,
        String sceneCode,
        String jsonPayload   // SceneChangedEvent / RulePublishedEvent 的 JSON 序列化
) {}

/** 配置变更类型枚举。 */
public enum ConfigChangeType {
    SCENE_CHANGED,
    RULE_PUBLISHED
}
```

### 3.3 ConfigApplier

```java
/** 配置变更应用 SPI。eval-svc 等模块实现此接口，供 state machine 在 onApply 时路由。 */
public interface ConfigApplier {
    /** 本 applier 处理的变更类型。 */
    ConfigChangeType supportedType();

    /**
     * 应用一次配置变更。由 state machine 在 Raft 日志 apply 时调用。
     * 实现必须是幂等的（Raft 日志可能重放）。
     */
    void apply(ConfigChangeCommand cmd);
}
```

## 4. rule-cluster 核心设计

### 4.1 ClusterNode — 唯一 public 入口

封装所有 SOFAJRaft 内部细节，外部不接触 JRaft 类型：

```java
public final class ClusterNode {
    private final ClusterConfig config;
    private Node raftNode;
    private RaftGroupService raftGroupService;
    private RpcServer rpcServer;
    private ConfigApplyStateMachine stateMachine;
    private JraftLeaderElector leaderElector;
    private RaftConfigPropagatorImpl configPropagator;

    private ClusterNode(ClusterConfig config, List<ConfigApplier> appliers) { ... }

    /**
     * 创建未启动的节点（纯 Java/ServiceLoader 路径）。
     * ConfigApplier 由 ServiceLoader 自动发现。
     */
    public static ClusterNode create(ClusterConfig config) {
        return new ClusterNode(config, ConfigApplierLoader.load());
    }

    /**
     * 创建未启动的节点（Spring DI 路径）。
     * ConfigApplier 由 AutoConfiguration 收集 List&lt;ConfigApplier&gt; 传入。
     */
    public static ClusterNode create(ClusterConfig config, List<ConfigApplier> appliers) {
        return new ClusterNode(config, List.copyOf(appliers));
    }

    /** 启动 Raft 节点：RPC Server → StateMachine → RaftGroupService。 */
    public synchronized void start() throws IOException { ... }

    /** 有序关闭：RaftGroupService → RPC Server。 */
    public synchronized void shutdown() { ... }

    public boolean isLeader()                         { return stateMachine.isLeader(); }
    public LeaderElector leaderElector()              { return leaderElector; }
    public ConfigPropagator configPropagator()        { return configPropagator; }
}
```

### 4.2 ClusterConfig

```java
public record ClusterConfig(
        String groupId,           // Raft Group ID，默认 "rule-engine"
        String serverAddr,        // 本节点地址，如 "192.168.1.10:9101"
        String dataPath,          // Raft 数据目录
        String initConf,          // 初始集群，如 "ip1:9101,ip2:9102,ip3:9103"
        int electionTimeoutMs,    // 选举超时，默认 5000
        int snapshotIntervalSecs  // Snapshot 间隔，默认 3600
) {
    public ClusterConfig {
        if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("groupId required");
        if (serverAddr == null || serverAddr.isBlank()) throw new IllegalArgumentException("serverAddr required");
        // ...
    }
}
```

### 4.3 ConfigApplyStateMachine

SOFAJRaft 状态机，职责：

- `onLeaderStart(term)` / `onLeaderStop(status)` — 更新 isLeader 标志，通知 LeaderElectionListener
- `onApply(iter)` — 反序列化 `ConfigChangeCommand`，按 type 路由到 ConfigApplier
- `onSnapshotSave` / `onSnapshotLoad` — 首版不做增量，直接 OK
- `onError` — log + 通知所有 listener 变为 Slave

ConfigApplier 集合由构造注入：Spring 环境走 `List<ConfigApplier>` DI；纯 Java 环境走 `ServiceLoader.load(ConfigApplier.class)`。

StateMachine、JraftLeaderElector、RaftConfigPropagatorImpl 均为 package-private，外部只通过 `ClusterNode` 的 `leaderElector()` / `configPropagator()` 获取。

### 4.4 RaftConfigPropagatorImpl

```java
class RaftConfigPropagatorImpl implements ConfigPropagator {
    private final Node raftNode;
    private final ConfigApplyStateMachine stateMachine;

    @Override
    public void propagate(ConfigChangeCommand cmd) throws PropagationException {
        if (!stateMachine.isLeader()) {
            throw new PropagationException("当前节点非 Leader，无法提交配置变更");
        }
        byte[] data = serialize(cmd);
        Task task = new Task();
        task.setData(ByteBuffer.wrap(data));
        // 同步等待 Raft 多数确认（超时由 electionTimeoutMs 控制）
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Status> result = new AtomicReference<>();
        task.setDone(status -> { result.set(status); latch.countDown(); });
        raftNode.apply(task);
        latch.await(electionTimeoutMs * 2, TimeUnit.MILLISECONDS);
        if (!result.get().isOk()) throw new PropagationException(result.get().getErrorMsg());
    }
}
```

**注意**：`propagate()` 是同步阻塞的，调用方（SceneServiceImpl）在 Raft 多数确认后才返回 HTTP 响应。如果 Raft 集群不可用，发布操作会超时报错。这是强一致的代价。

## 5. 既有模块改动

### 5.1 rule-kernel

新增三个 SPI 接口（§3），位于 `api/spi/cluster/` 包。零新依赖。

### 5.2 rule-config-svc

**SceneServiceImpl**：用 `ConfigPropagator` 替代直接 `publisher.publishEvent()`：

```java
// 原代码
publisher.publishEvent(new SceneChangedEvent(...));

// 改为
configPropagator.propagate(new ConfigChangeCommand(
    ConfigChangeType.SCENE_CHANGED, tenantId, sceneCode,
    objectMapper.writeValueAsString(sceneChangedEvent)));
```

默认 `ConfigPropagator` bean 为 `ModulithEventPropagator`（内部走 `publisher.publishEvent()`，行为不变）。引入 rule-cluster-starter 后 `RaftConfigPropagatorImpl` 自动替换。

RuleDefinition 的发布链路同理，通过 `ConfigChangeType.RULE_PUBLISHED` 走 `ConfigPropagator`。

### 5.3 rule-eval-svc

**SceneIndexEventListener** / **RuleIndexEventListener** 的刷新逻辑提取为 `ConfigApplier` 实现：

```java
@Component
public class SceneConfigApplier implements ConfigApplier {
    @Override
    public ConfigChangeType supportedType() { return ConfigChangeType.SCENE_CHANGED; }

    @Override
    public void apply(ConfigChangeCommand cmd) {
        SceneChangedEvent event = deserialize(cmd.jsonPayload());
        // 复用现有 SceneSnapshotLoader 刷新逻辑（幂等）
        indexRefresh(event);
    }
}
```

**引入 rule-cluster 后**：`@ApplicationModuleListener` 不再触发（Modulith 事件被 Raft 日志替代），索引刷新由 state machine 在 `onApply` 时统一驱动。

**不引入时**：`@ApplicationModuleListener` 正常触发，`ConfigApplier` 虽然注册到 Spring 但不被 state machine 调用，只被 ServiceLoader 发现（不会被用到，无副作用）。

### 5.4 rule-job-svc

**ThreadPoolSchedulerAdapter**：

- 构造注入 `Optional<LeaderElector>`
- `schedule()` 前判断 `isLeader()`，非 Leader 时跳过，把任务暂存
- 实现 `LeaderElectionListener`：
  - `onBecomeMaster` → 重新注册所有 ACTIVE 任务
  - `onBecomeSlave` → 撤销所有已注册任务
- `Optional.empty()` 时行为完全不变

### 5.5 rule-app

引入 starter 后，`application.yml` 增加 `rule.cluster.*` 配置。不引入时零改动。

## 6. 三种使用方式

| 场景 | 引入的模块 | 行为 |
|---|---|---|
| 单机开发 / 不需要集群 | 无 | ThreadPoolSchedulerAdapter 单实例模式；配置变更走 Modulith 事件（最终一致） |
| Spring Boot 集群 | rule-cluster-spring-boot-starter | application.yml 配置 Raft 参数；AutoConfiguration 自动装配 LeaderElector + ConfigPropagator |
| 纯 Java 嵌入 | rule-cluster（无 starter） | 手动 `ClusterNode.create(config).start()`，手动注入 SPI 实现到业务对象 |

## 7. AutoConfiguration 关键条件

```java
@AutoConfiguration
@ConditionalOnClass(ClusterNode.class)
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    ClusterNode clusterNode(ClusterProperties props, List<ConfigApplier> appliers) {
        ClusterConfig config = props.toClusterConfig();
        ClusterNode node = ClusterNode.create(config, appliers);
        node.start();
        return node;
    }

    @Bean
    LeaderElector leaderElector(ClusterNode node) { return node.leaderElector(); }

    @Bean
    ConfigPropagator configPropagator(ClusterNode node) { return node.configPropagator(); }

    @Bean
    ClusterHealthIndicator clusterHealth(ClusterNode node) { return new ClusterHealthIndicator(node); }
}
```

## 8. 集群信息 API（starter 提供）

| 端点 | 方法 | 返回 |
|---|---|---|
| `/api/v1/cluster/status` | GET | `{nodeId, role, isLeader, leaderId, term, peers}` |
| `/api/v1/cluster/health` | GET | `{status:"UP"/"DOWN", isLeader, term}` |

由 `ClusterController` 提供，仅引入 starter 时注册。

## 9. 错误处理

| 场景 | 处理 |
|---|---|
| Raft 集群不可达（多数节点宕机） | `ConfigPropagator.propagate()` 抛 `PropagationException`，发布 API 返回 503 |
| 非 Leader 提交配置 | 抛 `PropagationException("当前节点非 Leader")`，调用方应检查 `isLeader()` 或接受异常 |
| State machine onApply 失败 | 当前实现吞掉异常记 log，不阻塞 Raft 日志回放（后续版本可加死信队列） |
| Raft 节点启动失败 | `ClusterNode.start()` 抛 `IOException`，Spring 容器启动失败 |
| Leader 宕机后 cron 切换 | `electionTimeoutMs` 默认 5s，5~15s 内新 Leader 接管 cron |

## 10. 测试策略

| 层级 | 内容 |
|---|---|
| **rule-cluster 单测** | ClusterConfig 校验；StateMachine onApply 路由正确性；ConfigApplierLoader ServiceLoader 发现；Mock Node 测 LeaderElectionListener 回调 |
| **rule-job-svc 改动** | ThreadPoolSchedulerAdapter 注入 Optional LeaderElector → isLeader=false 时不注册 / isLeader=true 时注册 / onBecomeMaster 重新注册 |
| **rule-config-svc 改动** | SceneServiceImpl 走 ConfigPropagator 而非直接 fire event；ModulithEventPropagator 行为等价 |
| **集成测试** | 引入 rule-cluster-starter → 单节点启动（单节点即 Leader，共识 1/1 有效）→ 发布规则 → 确认 state machine onApply 触发索引刷新 |
| **端到端（需要 3 节点）** | 后续补：kill Leader → 新 Leader 接管 cron + 索引刷新正常。首版不强制 3 节点 e2e |

## 11. 不在首版范围

- Raft Snapshot 增量（首版不做 Snapshot，日志不压缩，单机测试够用）
- Follower read（客户端路由到 Leader 的读请求）
- 成员动态变更（节点增减需重启 + 改配置）
- 多 Raft Group

## 12. 依赖版本

```xml
<properties>
    <jraft.version>1.3.14</jraft.version>
    <bolt.version>1.6.7</bolt.version>
    <hessian.version>3.3.6</hessian.version>
</properties>
```

版本号集中到根 pom 的 `<dependencyManagement>`，模块 pom 只写坐标不写 version（遵循项目规范）。

## 13. 决策记录

- **D01** — 范围：Leader 选举 + 配置强一致分发，不含评估结果同步（评估无状态，每个节点独立评估）
- **D02** — 模块拆分：`rule-cluster`（纯 Java）+ `rule-cluster-spring-boot-starter`（Spring 集成），对齐 kernel/app 分层哲学
- **D03** — SPI 在 kernel：`LeaderElector` + `ConfigPropagator` + `ConfigApplier`，rule-cluster 只实现不定义
- **D04** — 配置回退：未引入 raft 时走 `ModulithEventPropagator`，行为完全不变
- **D05** — `ClusterNode` 为唯一 public 类，其余 package-private，不暴露 JRaft 类型
