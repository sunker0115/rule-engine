# metric 取数 fan-out 虚拟线程化 设计

> Status: 设计待批准(2026-06-08)。源自「规则引擎是否原生支持 reactive」的架构讨论。结论:本仓已基本是「同步纯核 + 边缘虚拟线程 + bounded-queue 背压」形态,唯一线程模型遗漏是 `metricFetchExecutor` 仍是固定平台池(core8/max32/queue256)。本期把取数 fan-out 换成虚拟线程 + `ExecutorService.invokeAll(timeout)`,去掉人为 32 上限、拿到干净的超时/取消语义,并为日后接入 async 数据源就绪——**不引入 Reactor,不改 SPI 契约**。

## 1. 动机与范围(经 Q1–Q4 收敛)

四种可能驱动,排序 **B > A > C > D**:
- **B(首要):取数路径伸缩** —— `metricFetchExecutor` 固定 32 平台线程是人为天花板。
- **A(次要):集成 ergonomics** —— 让 async 数据源日后能少摩擦接入。
- **C / D**:流式摄入背压 / 纯架构演进,**不在本期**。

进一步澄清(关键):
- 取数主力源是 **SQL_AGGREGATE(打 DB)**,下游是 metric datasource 的 JDBC 连接池。
- 因此 **B 不追 cold 峰值**:cold 路径(压测实测 2,840 req/s)的真实绑定约束是连接池大小 + DB 读延迟,而非 32 线程;虚拟线程化**不抬峰值**,只是把排队从「人为 32 线程池」还原到「连接池」这个本该在的闸,并去掉人为上限。
- 本期成功标准 = **去掉 32 天花板 + 结构化的干净超时/取消 + async 源接入就绪**;明确接受峰值仍由连接池/DB 封顶。
- **SPI 保持 sync-only**(Q4):虚拟线程让 `MetricValue fetch()` 里的 `.block()` 从反模式变廉价桥接,`CompletionStage` 异步变体在没有具体原生-async 源前是 YAGNI,日后加 `fetchAsync()` 默认方法是非破坏增量。

## 2. 决策

| # | 决策 | 选择 | 理由 |
|---|---|---|---|
| 并发机制 | vthread-per-task + `invokeAll` vs `StructuredTaskScope` | **`Executors.newVirtualThreadPerTaskExecutor()` + `ExecutorService.invokeAll(tasks, timeout)`** | `StructuredTaskScope` 在 JDK 25 仍是 preview(JEP 505,需 `--enable-preview`,API 还要再变 JDK 26/27),与 **GraalVM native 硬约束**冲突;其「ScopedValue 继承子任务」亮点在取数路径用不上(fetch 在 `assemble()`,早于 EvalEngine 绑定 `TraceScope`)。invokeAll 是 JDK 25 正式 API,自带「超时→中断未完成」,native 零新风险。 |
| SPI 契约 | sync vs 加 async 变体 | **sync-only 不动** | 见 §1;vthread 上 `.block()` 廉价,async SPI YAGNI。 |
| 流控 | 保留 executor 有界队列 vs 交给连接池 | **交给连接池** | vthread-per-task 不设上限,背压自然发生在连接池获取处;旧 256 队列+max32 的拒绝式背压被连接池获取超时 + fetch 超时取代(见 §5 风险)。 |

## 3. 改造面(仅两处文件)

### 3.1 `EvalAutoConfiguration.metricFetchExecutor`(rule-eval-svc)
```java
// 前：固定平台池
@Bean(name = "metricFetchExecutor")
public Executor metricFetchExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(8); ex.setMaxPoolSize(32); ex.setQueueCapacity(256);
    ex.setThreadNamePrefix("metric-fetch-"); ex.initialize();
    return ex;
}
// 后：虚拟线程-per-task
@Bean(name = "metricFetchExecutor")
public ExecutorService metricFetchExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```
- 返回类型 `Executor` → `ExecutorService`;删 `ThreadPoolTaskExecutor` import,加 `java.util.concurrent.{ExecutorService, Executors}`。
- 生命周期:`ExecutorService` 自 Java 19 起 `AutoCloseable`,Spring 默认 destroyMethod 推断会在关闭时 `close()`,无需手动管理。
- 注入点 `evalContextAssembler`:`@Qualifier("metricFetchExecutor") Executor fetchExecutor` → `ExecutorService fetchExecutor`。

### 3.2 `EvalContextAssembler`(rule-kernel)
- 字段 + 6 参构造形参 `Executor fetchExecutor` → `ExecutorService fetchExecutor`(+ Javadoc 一字)。
- 2 参兼容构造仍传 `null`(无 resolver 时不走 fetch,安全)。
- 防御 fallback `ForkJoinPool.commonPool()` 保留(本身是 `ExecutorService`,实际为死防御)。
- `java.util.concurrent.*` 已 import,`invokeAll/Future/TimeUnit` 现成。
- `fetchConcurrently` 改写见 §4。

**明确不动**:`MetricSourceHandler` SPI、kernel `EvalEngine`/AST/决策语义、Caffeine 缓存/键/TTL、降级语义(失败→`MetricValue.error`)、超时配置 `engine.rule.fetch.*`、providedMetrics 优先/allowProvided/版本化解析、PUSH 路径、持久化事件链。

## 4. 数据流 + 错误处理(`fetchConcurrently` 改写)

`invokeAll(tasks, timeout, unit)` 按集合迭代序返回 `List<Future>`,超时未完成子任务由 invokeAll 自身中断/取消。用「有序 code 列表 + 平行 task 列表」下标回填:

```java
private void fetchConcurrently(RuleEvent event, Instant now, Set<String> codes,
                               Map<String, MetricDescriptor> descriptors,
                               Map<String, MetricValue> metrics) {
    ExecutorService exec = fetchExecutor != null ? fetchExecutor : ForkJoinPool.commonPool();
    long timeoutMs = fetchTimeoutMs > 0 ? fetchTimeoutMs : Long.MAX_VALUE;

    List<String> orderedCodes = new ArrayList<>(codes);
    List<Callable<MetricValue>> tasks = new ArrayList<>(orderedCodes.size());
    for (String code : orderedCodes) {
        MetricDescriptor def = descriptors.get(code);
        MetricQuery query = new MetricQuery(code, event.tenantId(), event.subjectId(),
                def.params(), event.payload(), now);
        MetricSourceHandler handler = handlersBySourceType.get(def.sourceType());
        tasks.add(() -> {
            if (handler == null) return MetricValue.error(METRIC_FETCH_FAIL);
            try {
                MetricValue v = handler.fetch(query);
                return v != null ? v : MetricValue.error(METRIC_FETCH_FAIL);
            } catch (Exception e) {                 // 子任务内吞异常→降级（替代旧 .exceptionally）
                return MetricValue.error(METRIC_FETCH_FAIL);
            }
        });
    }

    List<Future<MetricValue>> results;
    try {
        results = exec.invokeAll(tasks, timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();         // 恢复中断位（比旧码静默更干净）
        for (String code : orderedCodes) metrics.put(code, MetricValue.error(METRIC_FETCH_FAIL));
        return;
    }

    for (int i = 0; i < orderedCodes.size(); i++) {
        String code = orderedCodes.get(i);
        Future<MetricValue> f = results.get(i);
        MetricValue v;
        if (f.isCancelled()) {
            v = MetricValue.error(METRIC_FETCH_FAIL);   // 超时→invokeAll 已中断该子任务
        } else {
            try { v = f.get(); } catch (Exception e) { v = MetricValue.error(METRIC_FETCH_FAIL); }
        }
        metrics.put(code, v);
        if (cache != null && !v.isError()) {
            MetricDescriptor def = descriptors.get(code);
            if (def.cacheTtlSeconds() > 0) {
                cache.put(cacheKey(event.tenantId(), code + ":" + def.metricVersion(),
                        event.subjectId(), def.params()), v, def.cacheTtlSeconds());
            }
        }
    }
}
```

语义保持对照:

| 场景 | 旧 | 新 | 一致 |
|---|---|---|---|
| handler 缺失 | error | error | ✓ |
| fetch 返回 null | error | error | ✓ |
| fetch 抛异常 | `.exceptionally`→error | Callable 内 catch→error | ✓ |
| 超时未完成 | `allOf.get(timeout)`+手动 `cancel(true)` | invokeAll 自动中断+取消 | ✓(更干净) |
| 调用线程被中断 | 静默吞 | 恢复中断位+全降级 | ✓(更正确) |
| 成功写缓存(键含 version) | 是 | 是(`code+":"+metricVersion()`) | ✓ |
| 单批共享一个超时 deadline | 是 | 是 | ✓ |
| 取数并发度 | 立即提交全部+等 | invokeAll 立即 fork 全部+等 | ✓ |

## 5. 测试 + native/风险

**回归(守门,必须全绿):**
- `EvalContextAssemblerFetchTest` 7 个:`Runnable::run` → 共享 `ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()`(`@AfterAll` close)。stub 确定性返回、断言不依赖顺序 → 全绿即证 routing/provided/cache/now/降级不变。
- 全量 `rule-kernel` + `rule-eval-svc` 绿;实现期 grep `metricFetchExecutor`,若有测试按 `Executor` 类型引用该 bean 顺带改 `ExecutorService`。

**新增(英文方法名/中文注释,锁 invokeAll 超时/取消):**
1. `fetchTimeout_degradesToError`:handler `sleep(500)`、timeout=50ms → 指标降级 `METRIC_FETCH_FAIL` 且 `assemble` 及时返回(不挂 500ms)。
2. `partialTimeout_fastSucceedsSlowDegrades`:两个 dep(fast 瞬时 / slow sleep500),timeout=50ms → fast 得值、slow 降级。锁「慢指标不毒化快指标、整批不被拖挂」。

**不做单测**:「去 32 上限/伸到连接池」是配置+负载属性,时序 flaky,由压测 cold-fetch 方法学覆盖。

**native / 风险:**
- **native 零新风险**:`newVirtualThreadPerTaskExecutor()` 是 JDK 25 正式 API,GraalVM(JDK 21+)原生支持 vthread,无 `--enable-preview`、无新反射。这是弃 `StructuredTaskScope` 的根本原因。
- **流控点迁移(行为差异,需知会)**:旧平台池满会 `RejectedExecution` → 瞬时 fail 成 error;新 vthread-per-task 不拒绝,极限负载下大量 vthread park 在「等 DB 连接」,由 `fetchTimeoutMs` + 连接池获取超时兜底。失败从「瞬时拒绝」变「等到 fetch 超时再降级」,都收敛 `METRIC_FETCH_FAIL`,延迟画像不同。对本期目标(去人为上限、让连接池当真实闸)是预期可接受。
- **取消即时性**:vthread 中断对阻塞 JDBC 是否即时取决于驱动,与旧 `cancel(true)` 同款,无回退。

## 6. 非目标
- `MetricSourceHandler` 的 `CompletionStage`/reactive 异步变体(YAGNI,日后非破坏增量)。
- 抬 cold 峰值(连接池/DB 读容量/缓存策略题,与线程模型正交)。
- PUSH 流式摄入真背压(C)、纯架构形态坐实(D)。
- 引入 Reactor / WebFlux 容器。
- metric datasource 连接池调优(可作为后续「取数容量」独立项)。
