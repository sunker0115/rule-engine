# 07 — 可运维

> **位置定位**：本文档承载 rule-engine 的**上线后视角**——幂等 / 审计 / 试算 / 灰度 / 监控 / 告警 / 可用性策略 / 运维参数默认值。
>
> **前置阅读**：[`00-decisions.md`](./00-decisions.md) D6 / D11 / D14 / D15 / D17 / D21、[`01-concepts.md`](./01-concepts.md) §3.11 audit_log + §3.14 Pre-Gate
>
> **解决什么疑问**："上线后出问题怎么排？""怎么灰度发布？""怎么 dry-run 验证？""有哪些 Prometheus 指标？""告警阈值怎么定？""v1 的可用性边界是什么？""引擎参数默认值在哪儿配？"
>
> **职责边界**——
> - ✅ 幂等 / 审计 / 试算 / 灰度 / 监控 / 告警 / 可用性策略 / 运维参数默认值集中表
> - ❌ 不写决策权衡（→ 00-decisions）、不写概念字段语义（→ 01-concepts）、不写运行时调度（→ 02-runtime）、不写 DDL（→ 05-storage）、不写前端 UI（→ 06-frontend）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 幂等 | ✅ |
| §三 EvaluationSession 落库策略 | ✅ |
| §四 dry-run 链路 | ✅ |
| §五 灰度 | ✅ |
| §六 Prometheus 指标清单 | ✅ |
| §七 告警阈值 | ✅ |
| §八 可用性策略汇总 | ✅ |
| §九 运维参数默认值表 | ✅ |

---

## 二、幂等

### 双层保障（D11）

| 层 | 实现 | 失效场景 |
|----|------|---------|
| 上半层 | `SET rule:session:{tenantId}:{eventId} <evalResultJson> NX EX 3600` | Redis 宕机 / 键过期 |
| 下半层 | `evaluation_session` UK `(tenant_id, event_id)` | 分布式竞争时最终一致 |

**流程：**
1. 评估前先 Redis SET NX：命中 → 返回缓存 EvalResult，不再评估
2. 未命中 → 正常评估 → evaluation_session INSERT
3. INSERT 遇 DuplicateKeyException → SELECT 已有行 → 返回已有 EvalResult

**幂等范围**：一次"评估"（Matcher + Pre-Gate + AST + 记录 session）幂等；Action 派发**不**幂等（由 ActionHandler 自行保证 execute() 幂等，见 04-extension §三）。

---

## 三、EvaluationSession 落库策略

| 操作 | 模式 | 原因 |
|------|------|------|
| `evaluation_session` 行 INSERT | **同步事务** | 幂等 UK 需先存在；量小（1 行/次），P99 延迟可忽略 |
| `node_trace` 批 INSERT | **异步批写** | 量大（10-1000 行/次）；旁路观察通道，失败降级丢弃，不影响主流程 |
| `action_execution` INSERT | **异步** | Action 派发本身异步，执行结果与评估线程解耦 |

TraceWriter 队列参数（建议默认值，可 `engine.rule.trace.*` 配置覆盖，见 §九）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `queue.capacity` | 100,000 | 内存 ArrayBlockingQueue 容量 |
| `batch.size` | 500 | 每批 INSERT 行数 |
| `flush.interval.ms` | 200 | 超时强制 flush |
| `consumer.threads` | 2 | 批写消费线程数 |

---

## 四、dry-run 链路

dry-run 走完整评估链路（Matcher / Pre-Gate / EvalContext / AST），但：
- **不派发 Action**（Dispatcher 短路）
- **不写** `evaluation_session` / `node_trace` prod 表
- **写** `dry_run_session` / `dry_run_node_trace`（隔离表，D7）
- 返回完整 `nodeTrace`（AST 每个节点的 result / actualValue / errorCode）

**入口**：`POST /api/v1/rule/dry-run`（PULL 模式同步返回，见 10-api-contract §三）

**用途**：
1. 规则发布前验证：编辑器内构造 mockEvent → 查看每个节点求值结果
2. 线上排障：用历史事件 eventId 重放 → 对比 trace 差异

**dry_run_session 保留期**：默认 7 天（见 §九 `engine.rule.retention.dry-run-session-days`）。

---

## 五、灰度

### 灰度算法（ROLLOUT Gate）

```
seed   = subjectId + ":" + (experimentId ?? ruleVersionId)
bucket = (murmur3_32(seed) & 0x7FFFFFFF) % 100
pass   = bucketStart <= bucket < bucketEnd   # 桶区间模式（A/B 互斥，优先）
       | bucket < percentage                 # 百分比模式，等价 [0, percentage)
```

灰度配置在 `pre_gates` 列 `gateType=ROLLOUT` 项的 params（无独立 `rollout` 列，D43）。params 字段见 [`10-api-contract.md`](./10-api-contract.md) ROLLOUT params 表。

- **种子**：默认 `subjectId:ruleVersionId`，同一 subject 在不同版本间 bucket 独立（防止切版本导致漂移）；配 `experimentId` 时种子改为 `subjectId:experimentId`，同实验多规则共享分桶（一致分桶 / 互斥）。
- **命中**：配桶区间 `bucketStart`/`bucketEnd` 时按区间判定（优先）；否则按 `percentage`（等价区间 `[0,percentage)`）。
- murmur3_32 保证分布均匀；hash seed 固定为 0（`Hashing.murmur3_32_fixed()`，不可配置）——稳定哈希是 D6 灰度桶稳定性的硬保证，不开放配置以杜绝误改导致全量桶漂移。

### 灰度验证流程

1. 新版规则发布为 `ACTIVE`，ROLLOUT Gate 设 `percentage=5`
2. 监控 `evaluation_session.error_code` 分布 + Action 派发成功率（5% bucket）
3. 对账无异常 → percentage 逐步调至 100
4. 全量后将旧版 rule_version.status 改为 `SUPERSEDED`

### 灰度回退

将 ROLLOUT.percentage 调回 0（不删规则）→ 新流量全部走其他规则。若需立即停用，将 rule_definition.status 改为 DISABLED（Matcher 倒排索引热摘除，≤15s 生效）。

---

## 六、Prometheus 指标清单

> 本节指标均属**业务层可观测性**（规则命中 / 延迟 / Action 结果 / trace 队列），由 `rule-observability` 的 `RuleMetrics` 通过 Micrometer 注册，Actuator `/actuator/prometheus` 端点暴露供 Prometheus scrape。
>
> 基础设施层可观测性（HTTP 请求分布式 trace、JVM 指标 OTLP 推送、日志聚合到 Loki）为独立演进方向，详见 [`08-evolution.md §2.22`](./08-evolution.md)。

所有指标前缀 `rule_engine_`，label 统一含 `tenant_id` / `scene_code`。

| 指标名 | 类型 | labels | 说明 |
|--------|------|--------|------|
| `rule_engine_eval_total` | Counter | `result`(HIT/MISS/BLOCKED/ERROR) | 评估结果分布 |
| `rule_engine_eval_blocked_total` | Counter | `gate_type`(ROLLOUT/WHITELIST/BLACKLIST/RATE_LIMIT/MUTEX) | Pre-Gate 按类型拦截计数，对应 `blocked_by` 枚举（D22） |
| `rule_engine_eval_duration_ms` | Histogram | `scene_code` | 评估 P50/P95/P99 延迟 |
| `rule_engine_metric_fetch_duration_ms` | Histogram | `source_type`, `metric_code` | MetricSource 取数延迟 |
| `rule_engine_metric_fetch_errors_total` | Counter | `source_type`, `error_type` | 取数失败计数 |
| `rule_engine_action_dispatch_total` | Counter | `action_type`, `status` | Action 派发结果 |
| `rule_engine_action_duration_ms` | Histogram | `action_type` | Action 执行延迟 |
| `rule_engine_trace_queue_size` | Gauge | — | TraceWriter 队列深度 |
| `rule_engine_trace_queue_overflow_total` | Counter | — | trace 丢弃计数（队满） |
| `rule_engine_rule_version_cache_hit_total` | Counter | `scene_code` | Matcher 内存命中次数 |
| `rule_engine_idempotency_hit_total` | Counter | `layer`(REDIS/DB) | 幂等命中次数 |

---

## 七、告警阈值

建议值，不强制；业务侧可按实际基线调整。

| 告警规则 | 阈值 | 级别 | 说明 |
|---------|------|------|------|
| 评估 P99 延迟 | > 200ms 持续 5min | WARNING | 风控场景目标 < 100ms P99 |
| 评估 ERROR 率 | > 1% 持续 2min | WARNING | METRIC_FETCH_FAIL / CONDITION_EVAL_ERROR |
| 评估 ERROR 率 | > 5% 持续 1min | CRITICAL | 批量失败 |
| trace 队列溢出 | > 0 次/min 持续 5min | WARNING | 写入跟不上评估速率 |
| Action 失败率 | > 5% 持续 5min（按 action_type） | WARNING | |
| MetricSource P99 | > 500ms 持续 5min | WARNING | 按 source_type 分组 |

---

## 八、可用性策略汇总

### v1 SPOF 清单与降级矩阵

| 依赖 | 失效影响 | v1 降级策略 |
|------|---------|------------|
| MySQL | 无法写 evaluation_session，评估阻塞 | 评估入口返回 503；Redis 幂等层仍可检查重复 |
| Redis | 幂等上半层失效 | 降级走 DB UK 幂等；metric cache 全部击穿 DB / 外部服务 |
| MetricSource (EXTERNAL_HTTP) | 取数超时 | D15 单节点降级 false，EvalResult.errorCode=METRIC_FETCH_FAIL |
| MetricSource (SQL_AGGREGATE) | DB 慢查询 / 连接池耗尽 | 同上；建议对 SQL 指标设 cache_ttl > 0 |
| TraceWriter 队列满 | trace 行丢弃 | trace 丢弃 + counter 告警；**不影响** EvalResult |
| ActionHandler 外部系统不可用 | execute() 超时 | TIMEOUT retryable=true，入重试队列 |

### v1 不做的高可用（见 08-evolution）

- evaluation_session 异步化（§2.15）
- 嵌入式 SDK 模式（§2.14，无跨进程网络依赖）
- 节点级 trace 冷热分级（§2.5）

---

## 九、运维参数默认值表

所有参数均可通过 Spring 配置（`application.yml` 或配置中心）覆盖，命名空间 `engine.rule.*`。

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `engine.rule.matcher.cache-refresh-interval-seconds` | 15 | Matcher 倒排索引热更间隔（D17 最终一致窗口） |
| `engine.rule.scene.watch-interval-seconds` | 30 | Scene 配置热加载间隔 |
| `engine.rule.idempotency.redis-ttl-seconds` | 3600 | 幂等 Redis key 过期时间 |
| `engine.rule.trace.queue-capacity` | 100000 | TraceWriter 队列容量 |
| `engine.rule.trace.batch-size` | 500 | 批写行数 |
| `engine.rule.trace.flush-interval-ms` | 200 | 强制 flush 间隔 |
| `engine.rule.trace.consumer-threads` | 2 | 批写消费线程数 |
| `engine.rule.context.build-timeout-ms` | 500 | EvalContext 构建超时（含 Subject 加载 + metric 批拉，超时整 session 失败，D25） |
| `engine.rule.subject.load-timeout-ms` | 200 | SubjectLoader 单次加载超时（D25，超出则 EvalContext 失败） |
| `engine.rule.metric.default-cache-ttl-seconds` | 60 | metric 取数结果缓存 TTL（per-metric 可覆盖） |
| `engine.rule.action.default-timeout-ms` | 3000 | ActionHandler 默认超时（per-handler 可覆盖） |
| `engine.rule.retention.evaluation-session-days` | 30 | evaluation_session 保留天数（D9） |
| `engine.rule.retention.node-trace-days` | 30 | node_trace 保留天数 |
| `engine.rule.retention.dry-run-session-days` | 7 | dry_run_session 保留天数 |
| `engine.rule.action.retry-queue-capacity` | 10000 | Action 重试队列容量（内存，进程重启丢失） |
| `engine.rule.action.retry-initial-interval-ms` | 1000 | 指数退避初始间隔 |
| `engine.rule.action.retry-max-interval-ms` | 60000 | 指数退避最大间隔 |
| `engine.rule.action.retry-max-attempts` | 5 | 最大重试次数，超出后落 FAILED 终态 |

---

## 十、维护原则

- 本文档**唯一持有运维参数默认值**——其他文档只引用本表（如 04-extension §六只写"建议短超时 ≤ 200ms"，具体数字在本表）。
- 新增运维参数必须同步登记 §九 默认值表。
- 新增告警必须在 §六 列指标 + §七 列阈值。
- 可用性策略变更（如 v2 异步化路径开通）回写 §八 + 同步指向 08-evolution。

---

## 十一、基础设施可观测性（OpenTelemetry + LGTM）

> 本节记录 rule-engine 基础设施层三信号（metrics / traces / logs）的接入方式。
> 与 §六"业务 Prometheus 指标"正交：§六 由 `rule-observability` 的 `RuleMetrics` 通过 Micrometer 注册、Actuator 暴露；本节由 `spring-boot-starter-opentelemetry` 自动装配，通过 OTLP HTTP 主动推送。

### 11.1 架构分层

```
rule-app 进程
  ├── 业务层可观测（§六）
  │     RuleMetrics（Micrometer） → /actuator/prometheus → 外部 Prometheus scrape
  │
  └── 基础设施层可观测（本节）
        spring-boot-starter-opentelemetry（自动装配）
          ├── metrics  → OTLP HTTP → otel-collector / otel-lgtm → Mimir/Prometheus
          ├── traces   → OTLP HTTP → otel-collector / otel-lgtm → Tempo
          └── logs     → OTLP HTTP（logback-spring.xml OTLP appender）→ Loki
```

**分工边界**：
- `node_trace`（评估树路径，`TraceWriterDbImpl` 异步批写 MySQL）是**业务 trace**，用于排障规则树哪个节点命中，走 `/trace/tree` API 查询。
- OTel trace 是**基础设施 trace**，用于排障 HTTP 请求在哪一层慢、跨服务链路，走 Tempo / Grafana 查询。两者互补，不替代。

### 11.2 依赖清单

`rule-app/pom.xml` 需包含：

```xml
<!-- 基础设施可观测性：metrics + traces OTLP 推送，版本由 Spring Boot BOM 管理 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>

<!-- 日志 OTLP appender（需与 logback-spring.xml 配合） -->
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
</dependency>
```

> `spring-boot-starter-opentelemetry` 引入 `micrometer-tracing-bridge-otel` + OTel SDK，会与
> Micrometer 自带的 `OtlpMetricsExportAutoConfiguration` 产生 protobuf 版本冲突（4.26.1 vs
> 4.32.0），必须排除：
>
> ```yaml
> spring:
>   autoconfigure:
>     exclude: org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration
> ```
>
> metrics 推送改由 `management.opentelemetry.metrics.export.otlp.*` 配置接管，与 OTel SDK 共用同一 protobuf 版本。

### 11.3 application.yml 配置

```yaml
spring:
  autoconfigure:
    # 排除 Micrometer OTLP registry，避免 protobuf 版本冲突 NPE
    exclude: org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration

management:
  opentelemetry:
    metrics:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_METRICS_ENDPOINT:http://localhost:4318/v1/metrics}
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}
    logging:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_LOGS_ENDPOINT:http://localhost:4318/v1/logs}
  tracing:
    sampling:
      probability: 1.0   # 开发环境全采；生产建议 0.1
```

三个 endpoint 均通过环境变量注入，本地开发默认指向 `localhost:4318`（`docker-compose.yml` 中 `otel-lgtm` 的 OTLP HTTP 端口）。

### 11.4 logback-spring.xml（日志 OTLP 推送）

```xml
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>

    <!-- 控制台输出，带 traceId/spanId（由 OTel SDK 自动注入到 MDC） -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} traceId=%X{traceId} spanId=%X{spanId} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- OTLP 日志推送到 Loki（通过 OTLP HTTP，endpoint 由 OTel SDK 统一配置） -->
    <appender name="OTLP" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <captureExperimentalAttributes>true</captureExperimentalAttributes>
        <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="OTLP"/>
    </root>
</configuration>
```

`OpenTelemetryAppender` 不需要独立配置 endpoint，直接复用 OTel SDK 全局实例（由 `spring-boot-starter-opentelemetry` 初始化），endpoint 统一读 `management.opentelemetry.logging.export.otlp.endpoint`。

### 11.5 本地开发环境（docker-compose）

使用 `grafana/otel-lgtm` 单镜像，集成 Grafana + Loki + Tempo + Mimir，无需部署四个独立容器：

```yaml
otel-lgtm:
  image: grafana/otel-lgtm:0.11.5
  ports:
    - "3000:3000"   # Grafana UI
    - "4317:4317"   # gRPC OTLP
    - "4318:4318"   # HTTP OTLP
  environment:
    GF_AUTH_ANONYMOUS_ENABLED: "true"
    GF_AUTH_ANONYMOUS_ORG_ROLE: "Admin"
  healthcheck:
    test: ["CMD", "curl", "-f", "-s", "http://localhost:3000/api/health"]
    interval: 5s
    timeout: 3s
    retries: 20
    start_period: 30s
```

`rule-app` 容器依赖 `service_healthy` 而非 `service_started`，确保 OTLP 端口就绪后再启动应用。

本地 Grafana 地址：`http://localhost:3000`（匿名 Admin，无需登录）

| 数据源 | 用途 | 在 Grafana 里查询 |
|--------|------|-------------------|
| Mimir（Prometheus 兼容） | JVM + HTTP 基础指标 | Explore → Prometheus |
| Tempo | 分布式 trace（span 树） | Explore → Tempo，或从日志/指标 TraceID 跳转 |
| Loki | 结构化日志 | Explore → Loki，`{service_name="rule-engine"}` |

### 11.6 三信号验证

启动后依次验证：

**1. metrics 是否推送成功**

```bash
# Actuator 业务指标（Micrometer → Prometheus scrape）
curl http://localhost:8080/actuator/prometheus | grep rule_engine

# OTel metrics 推送到 Mimir，在 Grafana Explore → Prometheus 查询：
# jvm_memory_used_bytes{service_name="rule-engine"}
```

**2. trace 是否写入 Tempo**

触发任意 HTTP 请求，然后在 Grafana Explore → Tempo 中搜索 `service.name = rule-engine`；
或在 Loki 日志里找 `traceId=xxx` 后点击 "Tempo" 跳转链路。

```bash
curl -X POST http://localhost:8080/api/v1/rule/evaluate \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"1","sceneCode":"smoke.scene","eventType":"order.placed",
       "subjectId":"u1","eventId":"evt-1","occurredAt":"2026-06-05T00:00:00Z",
       "payload":{},"providedMetrics":{"order.amount":200}}'
```

**3. 日志是否推送 Loki**

```
Grafana Explore → Loki
Label filter: service_name = rule-engine
```

能看到带 `traceId` 字段的结构化日志即为成功。

### 11.7 生产部署差异

本地用 `grafana/otel-lgtm` 单机集成镜像；生产建议拆分独立组件：

| 本地（otel-lgtm） | 生产建议 |
|-------------------|----------|
| Mimir（内嵌） | Mimir 集群 或 VictoriaMetrics |
| Tempo（内嵌） | Tempo 集群 |
| Loki（内嵌） | Loki 集群 |
| 无独立 Collector | OpenTelemetry Collector（处理 batching / retry / transform） |

生产环境推荐在 rule-app 和后端存储之间加 **OTel Collector**，好处：
- 应用侧推送失败不阻塞业务（Collector 有缓冲）
- 可在 Collector 做指标/日志/trace 的过滤、采样、enrichment
- 后端存储地址变更只改 Collector 配置，不需要重启 rule-app

`rule-app` 侧 endpoint 改指 Collector：

```yaml
management:
  opentelemetry:
    metrics:
      export:
        otlp:
          endpoint: http://otel-collector:4318/v1/metrics
    tracing:
      export:
        otlp:
          endpoint: http://otel-collector:4318/v1/traces
    logging:
      export:
        otlp:
          endpoint: http://otel-collector:4318/v1/logs
```

生产 tracing 采样率建议调低（全采在高 QPS 下 Tempo 压力大）：

```yaml
management:
  tracing:
    sampling:
      probability: 0.05   # 5% 采样，约合 1000 QPS → 50 trace/s
```

### 11.8 常见问题

| 现象 | 原因 | 排查 |
|------|------|------|
| 启动报 `NullPointerException` 在 `OtlpMeterRegistry` | protobuf 版本冲突（4.26.1 vs 4.32.0） | 确认 `spring.autoconfigure.exclude` 已加 `OtlpMetricsExportAutoConfiguration` |
| Loki 收不到日志 | `logback-spring.xml` 未配置 `OpenTelemetryAppender`，或 OTel SDK 尚未初始化时 Appender 已加载 | 确认 `logback-spring.xml` 存在（不是 `logback.xml`），Spring Boot 的 `logback-spring.xml` 在 Spring Context 初始化后才生效，可保证 OTel SDK 先就绪 |
| Tempo 无 trace | endpoint 指向的容器未就绪（otel-lgtm 启动需约 30s） | 检查 `docker compose ps` 中 otel-lgtm 是否 healthy；`rule-app` 的 `depends_on` 需设 `condition: service_healthy` |
| Grafana 查不到 metrics | Mimir 接收延迟 ≈ 15s | 请求后等 15–30s 再查；或看 Collector 日志确认 export 成功 |
| traceId 在日志里是 `0000000000000000` | 当前请求不在 OTel span 内（如定时任务、应用启动阶段） | 正常现象，HTTP 请求均会有真实 traceId |
