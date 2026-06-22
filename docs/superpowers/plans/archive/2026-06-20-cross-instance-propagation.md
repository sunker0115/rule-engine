# 跨实例传播（track #3）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `Scheduler` SPI 加广播能力（`scheduleBroadcast`/`triggerBroadcast`），让 config 变更经调度后端（本地 ThreadPool 退化 / XXL 分片广播）推送到所有 eval 实例刷新索引，不引 MQ/Redis。

**Architecture:** `Scheduler` SPI 加两个广播方法（`Consumer<String>` 透传 param），两个适配器实现（ThreadPool 本地 map 直调、XXL 第二 handler + `SHARDING_BROADCAST` 路由）。config-svc 用 `@ApplicationModuleListener` 监听既有 `SceneChangedEvent`/`RulePublishedEvent` → `triggerBroadcast`；eval-svc `@PostConstruct` 注册广播 handler → 收 param → 复用现有 loader/index/warmer 重载该 scene。两侧 `Scheduler` 均 `ObjectProvider` 惰性注入（条件装配 bean，无后端时退化为仅进程内事件）。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / xxl-job-core 3.4.0 / JUnit5 + Mockito + AssertJ。前置：`mvn-env` skill 设 `$MVN`（JDK25）。

设计依据：`docs/superpowers/specs/2026-06-20-cross-instance-propagation-design.md`（经 rule-engine-reviewer 两轮评审 + 业界模式对照）。

---

## 文件结构

**rule-kernel（SPI）：**
- Modify: `api/spi/scheduler/Scheduler.java` — 加 `scheduleBroadcast(String, Consumer<String>)` + `triggerBroadcast(String, String)`
- Modify: `src/test/.../api/spi/scheduler/SchedulerTest.java` — 已存在，追加广播契约形状测试

**rule-job-svc（本地适配器）：**
- Modify: `internal/scheduler/ThreadPoolSchedulerAdapter.java` — 加 `Map<String,Consumer<String>>` + 两方法实现
- Modify: `src/test/.../internal/scheduler/ThreadPoolSchedulerAdapterTest.java` — 已存在，追加广播测试

**rule-job-xxl（XXL 适配器）：**
- Modify: `internal/XxlJobAdminClient.java` — `ensureJobSeeded` 加 `routeStrategy` 形参 + 加 `triggerJob`
- Modify: `internal/HttpXxlJobAdminClient.java` — `insertJobInfo` 透传 routeStrategy + 实现 `triggerJob`
- Modify: `internal/XxlJobSchedulerAdapter.java` — 注册 `BROADCAST_HANDLER` + 构造期 seed 广播 jobinfo + 实现两方法 + `schedule()` 现有 seed 传 `"FIRST"`
- Modify: `src/test/.../internal/HttpXxlJobAdminClientTest.java` — 追加 routeStrategy + triggerJob 测试
- Modify: `src/test/.../internal/XxlJobSchedulerAdapterTest.java` — 追加广播 handler 测试

**rule-config-svc（生产者接线）：**
- Create: `internal/broadcast/ConfigChangeBroadcaster.java` — `@ApplicationModuleListener` → `triggerBroadcast`
- Create: `src/test/.../internal/broadcast/ConfigChangeBroadcasterTest.java`

**rule-eval-svc（消费者接线）：**
- Create: `internal/listener/ConfigChangeBroadcastHandler.java` — `@PostConstruct` 注册 + 收 param 重载
- Create: `src/test/.../internal/listener/ConfigChangeBroadcastHandlerTest.java`

---

## Task 1: Scheduler SPI 扩广播两方法

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/scheduler/Scheduler.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/scheduler/SchedulerTest.java`（已存在）

- [ ] **Step 1: 看现有 SchedulerTest 结构**

Run: `sed -n '1,40p' rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/scheduler/SchedulerTest.java`
目的：了解现有测试如何构造匿名 `Scheduler` 实现，新增方法后该匿名实现需补两个方法。

- [ ] **Step 2: 给 Scheduler 接口加两方法**

修改 `Scheduler.java`，在 `unschedule` 后追加（import `java.util.function.Consumer`）：

```java
import java.util.function.Consumer;

// ... 接口内，unschedule 之后 ...

    /**
     * 注册广播 handler（code 全局唯一）。所有实例同时执行。
     *
     * @param code       广播标识，全局唯一
     * @param onEachNode 广播处理器，接收 {@link #triggerBroadcast} 透传的 param
     */
    void scheduleBroadcast(String code, Consumer<String> onEachNode);

    /**
     * 触发一次广播：所有实例同时执行对应 handler。
     *
     * @param code  已注册的广播 handler 标识
     * @param param 透传到 onEachNode 的业务 payload
     */
    void triggerBroadcast(String code, String param);
```

- [ ] **Step 3: 跑确认现有 SchedulerTest 编译失败**

Run（先设环境，见 mvn-env skill）: `$MVN -pl rule-kernel test -Dtest='SchedulerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败——现有匿名 `Scheduler` 实现缺新方法。

- [ ] **Step 4: 补 SchedulerTest 的匿名实现 + 加广播契约测试**

在 `SchedulerTest.java` 的匿名 `Scheduler` 实现里补两个方法（用 list 记录调用），并加一个验证广播契约的测试。完整追加：

```java
    @Test
    void broadcastContract_recordsRegistrationAndTrigger() {
        java.util.Map<String, java.util.function.Consumer<String>> handlers = new java.util.HashMap<>();
        java.util.List<String> triggered = new java.util.ArrayList<>();
        Scheduler scheduler = new Scheduler() {
            @Override public void schedule(String c, String cron, Runnable t) {}
            @Override public void unschedule(String c) {}
            @Override public void scheduleBroadcast(String c, java.util.function.Consumer<String> h) {
                handlers.put(c, h);
            }
            @Override public void triggerBroadcast(String c, String param) {
                handlers.get(c).accept(param);
                triggered.add(c + "=" + param);
            }
        };
        scheduler.scheduleBroadcast("config-change", p -> triggered.add("handled:" + p));
        scheduler.triggerBroadcast("config-change", "scene:9100:fraud_check:true");
        assertThat(triggered).containsExactly(
                "handled:scene:9100:fraud_check:true",
                "config-change=scene:9100:fraud_check:true");
    }
```

> 若 `SchedulerTest` 已有的其它测试用了匿名 `Scheduler` 实现，同样给它们补 `scheduleBroadcast`/`triggerBroadcast` 空实现，否则编译不过。

- [ ] **Step 5: 跑测试通过**

Run: `$MVN -pl rule-kernel test -Dtest='SchedulerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/scheduler/Scheduler.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/scheduler/SchedulerTest.java
git commit -m "feat(kernel): Scheduler SPI 扩 scheduleBroadcast/triggerBroadcast"
```

---

## Task 2: ThreadPoolSchedulerAdapter 实现广播（本地退化）

**Files:**
- Modify: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/scheduler/ThreadPoolSchedulerAdapter.java`
- Test: `rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/scheduler/ThreadPoolSchedulerAdapterTest.java`（已存在）

- [ ] **Step 1: 写失败测试（追加到既有 ThreadPoolSchedulerAdapterTest）**

```java
    @Test
    void broadcast_localDirectInvokeWithParam() {
        ThreadPoolSchedulerAdapter adapter = new ThreadPoolSchedulerAdapter();
        java.util.List<String> received = new java.util.ArrayList<>();
        adapter.scheduleBroadcast("config-change", received::add);

        adapter.triggerBroadcast("config-change", "scene:9100:fraud_check:true");

        assertThat(received).containsExactly("scene:9100:fraud_check:true");
        adapter.close();
    }

    @Test
    void broadcast_triggerUnknownCode_noop() {
        ThreadPoolSchedulerAdapter adapter = new ThreadPoolSchedulerAdapter();
        // 未注册的 code，触发不抛异常（其它实例可能没注册该 handler）
        adapter.triggerBroadcast("never-registered", "x");
        adapter.close();
    }
```

> 若测试类无 `import static org.assertj.core.api.Assertions.assertThat;`，补上。

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-job-svc -am test -Dtest='ThreadPoolSchedulerAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（方法不存在）。

- [ ] **Step 3: 实现广播方法**

在 `ThreadPoolSchedulerAdapter.java`：加 import `java.util.function.Consumer`，加字段，加两方法（放 `unschedule` 之后、`close` 之前）：

```java
    private final Map<String, Consumer<String>> broadcastHandlers = new ConcurrentHashMap<>();

    @Override
    public void scheduleBroadcast(String code, Consumer<String> onEachNode) {
        broadcastHandlers.put(code, onEachNode);
        log.info("广播 handler 已注册 code={}", code);
    }

    @Override
    public void triggerBroadcast(String code, String param) {
        // 本地退化：单实例直调（无集群，本节点即"所有节点"）
        Consumer<String> handler = broadcastHandlers.get(code);
        if (handler != null) {
            handler.accept(param);
        }
    }
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-job-svc -am test -Dtest='ThreadPoolSchedulerAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/scheduler/ThreadPoolSchedulerAdapter.java \
        rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/scheduler/ThreadPoolSchedulerAdapterTest.java
git commit -m "feat(job-svc): ThreadPoolSchedulerAdapter 广播本地退化实现"
```

---

## Task 3: XxlJobAdminClient ensureJobSeeded 加 routeStrategy + triggerJob

**Files:**
- Modify: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobAdminClient.java`
- Modify: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClient.java`
- Test: `rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClientTest.java`（已存在）

- [ ] **Step 1: 改 XxlJobAdminClient 接口**

`XxlJobAdminClient.java`：`ensureJobSeeded` 加 `routeStrategy` 形参，加 `triggerJob`：

```java
    /**
     * 确保 admin 侧存在匹配 jobDesc 的 jobinfo；不存在则按给定路由策略新建，已存在保持不动。
     *
     * @param jobDesc         job 描述（唯一定位，如 "task-42" / "config-broadcast"）
     * @param executorHandler 执行器 handler 名
     * @param cron            cron 表达式（仅新建时写入）
     * @param routeStrategy   路由策略（"FIRST" 单派发 / "SHARDING_BROADCAST" 广播）
     * @param executorParam   执行器 param
     * @return admin 侧该 job 的 id
     */
    long ensureJobSeeded(String jobDesc, String executorHandler, String cron,
                         String routeStrategy, String executorParam);

    /**
     * 触发现有 jobinfo（路由策略由 jobinfo 自身决定），覆盖 executorParam。
     *
     * @param adminJobId    admin 侧 jobinfo id
     * @param executorParam 本次触发的执行器 param
     */
    void triggerJob(long adminJobId, String executorParam);
```

- [ ] **Step 2: 写 HttpXxlJobAdminClient 失败测试（追加到既有 Test）**

```java
    @Test
    void insertCarriesGivenRouteStrategy() {
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":7,\"appname\":\"rule-engine\"}],\"total\":1}}");
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}");
        responses.put("/xxl-job-admin/jobinfo/insert", "{\"code\":200,\"data\":77}");

        client().ensureJobSeeded("config-broadcast", "config-broadcast-runner",
                "0 0 0 1 1 ?", "SHARDING_BROADCAST", "");

        String insertBody = capturedBodies.stream()
                .filter(b -> b.startsWith("/xxl-job-admin/jobinfo/insert?"))
                .findFirst().orElseThrow();
        assertThat(insertBody).contains("executorRouteStrategy=SHARDING_BROADCAST");
    }

    @Test
    void triggerJobPostsIdAndParam() {
        responses.put("/xxl-job-admin/jobinfo/trigger", "{\"code\":200,\"msg\":null}");

        client().triggerJob(77L, "scene:9100:fraud_check:true");

        String body = capturedBodies.stream()
                .filter(b -> b.startsWith("/xxl-job-admin/jobinfo/trigger?"))
                .findFirst().orElseThrow();
        assertThat(body).contains("id=77");
        assertThat(body).contains("executorParam=scene");  // URL 编码后 ':' → %3A，前缀匹配即可
    }
```

- [ ] **Step 3: 跑确认失败**

Run: `$MVN -pl rule-job-xxl -am test -Dtest='HttpXxlJobAdminClientTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（签名变更 + triggerJob 不存在）。

- [ ] **Step 4: 改 HttpXxlJobAdminClient 实现**

4a. `ensureJobSeeded` 加形参并透传给 `insertJobInfo`：

```java
    @Override
    public synchronized long ensureJobSeeded(String jobDesc, String executorHandler,
                                             String cron, String routeStrategy, String executorParam) {
        int groupId = ensureJobGroup();
        Long existing = findJobInfoId(groupId, jobDesc);
        if (existing != null) {
            log.info("xxl-job admin 已存在 job jobDesc={} id={}，保持不动（有了不管）", jobDesc, existing);
            return existing;
        }
        return insertJobInfo(groupId, jobDesc, executorHandler, cron, routeStrategy, executorParam);
    }
```

4b. `insertJobInfo` 加 `routeStrategy` 形参，把硬编码 `"executorRouteStrategy", "FIRST"` 改为透传：

```java
    private long insertJobInfo(int groupId, String jobDesc, String executorHandler,
                               String cron, String routeStrategy, String executorParam) {
        JsonNode data = post("/jobinfo/insert", form(
                "jobGroup", String.valueOf(groupId),
                "jobDesc", jobDesc,
                "author", "rule-engine",
                "scheduleType", "CRON",
                "scheduleConf", cron,
                "glueType", "BEAN",
                "executorHandler", executorHandler,
                "executorParam", executorParam,
                "executorRouteStrategy", routeStrategy,
                "misfireStrategy", "DO_NOTHING",
                "executorBlockStrategy", "SERIAL_EXECUTION",
                "executorTimeout", "0",
                "executorFailRetryCount", "0",
                "triggerStatus", "1"), true).path("data");
        log.info("xxl-job admin 新建 job jobDesc={} handler={} route={} param={} id={} cron={}",
                 jobDesc, executorHandler, routeStrategy, executorParam, data.asLong(), cron);
        return data.asLong();
    }
```

4c. 加 `triggerJob` 实现（放在 `ensureJobSeeded` 之后）：

```java
    @Override
    public void triggerJob(long adminJobId, String executorParam) {
        post("/jobinfo/trigger", form(
                "id", String.valueOf(adminJobId),
                "executorParam", executorParam,
                "addressList", ""), true);
        log.info("xxl-job admin 触发 job id={} param={}", adminJobId, executorParam);
    }
```

- [ ] **Step 5: 改现有 schedule() 调用点传 "FIRST"**

`XxlJobSchedulerAdapter.java` 的 `schedule()` 里现有 `adminClient.ensureJobSeeded("task-" + taskId, UNIVERSAL_HANDLER, cronExpression, String.valueOf(taskId))` 改为：

```java
        long adminJobId = adminClient.ensureJobSeeded(
                "task-" + taskId,       // jobDesc：唯一标识
                UNIVERSAL_HANDLER,      // executorHandler：统一
                cronExpression,
                "FIRST",                // 路由策略：cron 单派发
                String.valueOf(taskId)  // executorParam：taskId
        );
```

- [ ] **Step 6: 跑测试通过**

Run: `$MVN -pl rule-job-xxl -am test -Dtest='HttpXxlJobAdminClientTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。现有 seed 测试若断言了 insert body，确认 routeStrategy=FIRST 仍在。

- [ ] **Step 7: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobAdminClient.java \
        rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClient.java \
        rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapter.java \
        rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClientTest.java
git commit -m "feat(job-xxl): ensureJobSeeded 加 routeStrategy 形参 + triggerJob"
```

---

## Task 4: XxlJobSchedulerAdapter 广播 handler + 实现广播两方法

**Files:**
- Modify: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapter.java`
- Test: `rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapterTest.java`（已存在）

> **背景**：现有 adapter 构造器注册单一 `UNIVERSAL_HANDLER="scheduled-task-runner"`，handler 内按 `executorParam` `Long.parseLong` 取 taskId。广播 param 是 `"scene:9100:..."`（非数字），必须挂**独立** handler `BROADCAST_HANDLER="config-broadcast-runner"`，否则进通用 handler 会 `NumberFormatException` 被静默吞掉。

- [ ] **Step 1: 写失败测试（追加到既有 XxlJobSchedulerAdapterTest）**

```java
    @Test
    void scheduleBroadcastRegistersConsumerAndTriggerSeeds() {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        when(admin.ensureJobSeeded(eq("config-broadcast"), eq(XxlJobSchedulerAdapter.BROADCAST_HANDLER),
                anyString(), eq("SHARDING_BROADCAST"), eq(""))).thenReturn(55L);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin, mockProvider(id -> {}));

        java.util.List<String> received = new java.util.ArrayList<>();
        adapter.scheduleBroadcast("config-change", received::add);
        adapter.triggerBroadcast("config-change", "scene:9100:fraud_check:true");

        // 触发走 admin triggerJob（覆盖 executorParam）
        verify(admin).triggerJob(55L, "scene:9100:fraud_check:true");
    }

    @Test
    void broadcastHandlerDispatchesToConsumerByParam() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        when(admin.ensureJobSeeded(eq("config-broadcast"), eq(XxlJobSchedulerAdapter.BROADCAST_HANDLER),
                anyString(), eq("SHARDING_BROADCAST"), eq(""))).thenReturn(55L);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin, mockProvider(id -> {}));

        java.util.List<String> received = new java.util.ArrayList<>();
        adapter.scheduleBroadcast("config-change", received::add);

        // 模拟 XXL 派发广播 handler（param 是业务 payload，非 taskId）
        XxlJobContext.setXxlJobContext(new XxlJobContext(0L, "scene:9100:fraud_check:true", 0L, 0L, "", 0, 1));
        try {
            IJobHandler handler = XxlJobExecutor.loadJobHandler(XxlJobSchedulerAdapter.BROADCAST_HANDLER);
            assertThat(handler).isNotNull();
            handler.execute();
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }

        assertThat(received).containsExactly("scene:9100:fraud_check:true");
    }
```

> import：`org.mockito.ArgumentMatchers.anyString`。

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-job-xxl -am test -Dtest='XxlJobSchedulerAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`BROADCAST_HANDLER` / `scheduleBroadcast` 不存在）。

- [ ] **Step 3: 实现广播 handler + 两方法**

`XxlJobSchedulerAdapter.java`：

3a. 加常量 + 字段（在 `UNIVERSAL_HANDLER` 常量后、`runnables` 字段后）：

```java
    /** 广播 handler 名（独立于单派发 UNIVERSAL_HANDLER，避免 param 进 Long.parseLong）。 */
    public static final String BROADCAST_HANDLER = "config-broadcast-runner";

    /** 广播 jobinfo 的 admin id（构造期 seed 得到，triggerBroadcast 复用）。 */
    private volatile long broadcastJobId = -1;

    /** code → 广播处理器。 */
    private final Map<String, java.util.function.Consumer<String>> broadcastConsumers = new ConcurrentHashMap<>();
```

3b. 构造器末尾（现有通用 handler 注册之后、`log.info` 之前）加广播 handler 注册 + seed：

```java
        // 注册广播 handler（独立 name，param 是业务 payload 非 taskId）
        XxlJobExecutor.registryJobHandler(BROADCAST_HANDLER, new IJobHandler() {
            @Override
            public void execute() {
                String param = XxlJobHelper.getJobParam();
                if (param == null || param.isBlank()) {
                    log.warn("config-broadcast-runner: 缺少 param，跳过");
                    return;
                }
                // 广播 code 固定为 "config-change"（当前唯一广播用途）
                java.util.function.Consumer<String> consumer = broadcastConsumers.get("config-change");
                if (consumer != null) {
                    consumer.accept(param);
                } else {
                    log.debug("config-broadcast-runner: 本实例未注册 config-change handler，跳过 param={}", param);
                }
            }
        });
        // seed 广播 jobinfo：cron 永不自动触发（每年 1/1 零点），路由=SHARDING_BROADCAST，只等手动 trigger
        this.broadcastJobId = adminClient.ensureJobSeeded(
                "config-broadcast", BROADCAST_HANDLER, "0 0 0 1 1 ?", "SHARDING_BROADCAST", "");
```

3c. 加两方法（放 `unschedule` 之后）：

```java
    @Override
    public void scheduleBroadcast(String code, java.util.function.Consumer<String> onEachNode) {
        broadcastConsumers.put(code, onEachNode);
        log.info("xxl-job 广播 handler 已注册 code={}", code);
    }

    @Override
    public void triggerBroadcast(String code, String param) {
        // 触发预 seed 的广播 jobinfo，覆盖 executorParam，XXL admin 分片广播到所有在线 executor
        adminClient.triggerJob(broadcastJobId, param);
    }
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-job-xxl -am test -Dtest='XxlJobSchedulerAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 跑 rule-job-xxl 全量**

Run: `$MVN -pl rule-job-xxl -am test`
Expected: 全绿（现有 5 个 adapter 测试 + 4 个 client 测试 + 新增的都过）。

- [ ] **Step 6: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapter.java \
        rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapterTest.java
git commit -m "feat(job-xxl): 广播 handler config-broadcast-runner + scheduleBroadcast/triggerBroadcast"
```

---

## Task 5: config-svc ConfigChangeBroadcaster（生产者接线）

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/broadcast/ConfigChangeBroadcaster.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/broadcast/ConfigChangeBroadcasterTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.config.internal.broadcast;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigChangeBroadcasterTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Scheduler> provider(Scheduler s) {
        ObjectProvider<Scheduler> p = mock(ObjectProvider.class);
        // ifAvailable(consumer)：s 非 null 时调用 consumer
        doAnswer(inv -> {
            ((java.util.function.Consumer<Scheduler>) inv.getArgument(0)).accept(s);
            return null;
        }).when(p).ifAvailable(org.mockito.ArgumentMatchers.any());
        return p;
    }

    @Test
    void sceneChanged_triggersBroadcastWithSceneParam() {
        Scheduler scheduler = mock(Scheduler.class);
        ConfigChangeBroadcaster b = new ConfigChangeBroadcaster(provider(scheduler));

        b.onSceneChanged(new SceneChangedEvent("9100", "fraud_check", true));

        verify(scheduler).triggerBroadcast("config-change", "scene:9100:fraud_check:true");
    }

    @Test
    void rulePublished_triggersBroadcastWithRuleParam() {
        Scheduler scheduler = mock(Scheduler.class);
        ConfigChangeBroadcaster b = new ConfigChangeBroadcaster(provider(scheduler));

        b.onRulePublished(new RulePublishedEvent("9100", "fraud_check", 42L));

        verify(scheduler).triggerBroadcast("config-change", "rule:9100:fraud_check");
    }

    @Test
    void noScheduler_doesNotThrow() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Scheduler> empty = mock(ObjectProvider.class);
        // ifAvailable 不调 consumer（无 bean）
        ConfigChangeBroadcaster b = new ConfigChangeBroadcaster(empty);
        b.onSceneChanged(new SceneChangedEvent("9100", "fraud_check", true));
        // 不抛异常即通过
    }
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest='ConfigChangeBroadcasterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 ConfigChangeBroadcaster**

```java
package com.sstlfsj.rule.config.internal.broadcast;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 把 config 变更经调度后端广播到所有 eval 实例（多实例索引收敛）。
 *
 * <p>用 {@code @ApplicationModuleListener}（Modulith 提交后异步）与 eval-svc 既有索引
 * listener 对齐，保证事务落地后才广播——其他实例回 DB 重载时数据已可见。
 * 同 JVM 的 eval-svc 已被进程内事件即时通知，广播只为通知其他实例。
 *
 * <p>{@code Scheduler} 是条件装配 bean（仅配了调度后端才存在），用 {@link ObjectProvider}
 * 惰性注入：无后端时不广播（单 JVM 进程内事件已足够），不致启动失败。
 */
@Component
@RequiredArgsConstructor
public class ConfigChangeBroadcaster {

    /** 广播 code，与 eval-svc ConfigChangeBroadcastHandler 注册的 code 一致。 */
    private static final String BROADCAST_CODE = "config-change";

    private final ObjectProvider<Scheduler> schedulerProvider;

    /**
     * 场景变更广播。param 格式 {@code scene:tenantId:sceneCode:active}。
     *
     * @param event 场景变更事件
     */
    @ApplicationModuleListener
    public void onSceneChanged(SceneChangedEvent event) {
        String param = "scene:" + event.tenantId() + ":" + event.sceneCode() + ":" + event.active();
        schedulerProvider.ifAvailable(s -> s.triggerBroadcast(BROADCAST_CODE, param));
    }

    /**
     * 规则发布广播。param 格式 {@code rule:tenantId:sceneCode}。
     *
     * @param event 规则发布事件
     */
    @ApplicationModuleListener
    public void onRulePublished(RulePublishedEvent event) {
        String param = "rule:" + event.tenantId() + ":" + event.sceneCode();
        schedulerProvider.ifAvailable(s -> s.triggerBroadcast(BROADCAST_CODE, param));
    }
}
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest='ConfigChangeBroadcasterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（3 用例）。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/broadcast/ConfigChangeBroadcaster.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/broadcast/ConfigChangeBroadcasterTest.java
git commit -m "feat(config-svc): ConfigChangeBroadcaster 经调度后端广播 config 变更"
```

---

## Task 6: eval-svc ConfigChangeBroadcastHandler（消费者接线）

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/ConfigChangeBroadcastHandler.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/listener/ConfigChangeBroadcastHandlerTest.java`

> **复用**：重载逻辑与 `RuleIndexEventListener` / `SceneIndexEventListener` 同构——`loader.loadBySceneWithStrategy` + `index.replaceScene` + `scriptWarmer.warmUpIfEager`。本任务直接内联同样三步（与现有 listener 重复约 6 行；保持与现有模式一致，不强制抽取共享组件——抽取留作后续清理，避免本次改动面扩大）。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.eval.internal.snapshot.ScriptWarmer;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigChangeBroadcastHandlerTest {

    @Test
    void onConfigChange_reloadsSceneByParam() {
        SceneRuleIndex index = mock(SceneRuleIndex.class);
        SceneSnapshotLoader loader = mock(SceneSnapshotLoader.class);
        ScriptWarmer warmer = mock(ScriptWarmer.class);
        Map<String, List<RuleVersionSnapshot>> byType = Map.of();
        when(loader.loadBySceneWithStrategy(eq("9100"), eq("fraud_check"), any())).thenReturn(byType);

        ConfigChangeBroadcastHandler handler =
                new ConfigChangeBroadcastHandler(null, index, loader, warmer);
        handler.onConfigChange("scene:9100:fraud_check:true");

        verify(loader).loadBySceneWithStrategy(eq("9100"), eq("fraud_check"), any());
        verify(index).replaceScene(eq("9100"), eq("fraud_check"), eq(byType));
    }

    @Test
    void onConfigChange_ruleParam_alsoReloads() {
        SceneRuleIndex index = mock(SceneRuleIndex.class);
        SceneSnapshotLoader loader = mock(SceneSnapshotLoader.class);
        ScriptWarmer warmer = mock(ScriptWarmer.class);
        when(loader.loadBySceneWithStrategy(eq("9100"), eq("fraud_check"), any())).thenReturn(Map.of());

        ConfigChangeBroadcastHandler handler =
                new ConfigChangeBroadcastHandler(null, index, loader, warmer);
        handler.onConfigChange("rule:9100:fraud_check");

        verify(index).replaceScene(eq("9100"), eq("fraud_check"), any());
    }
}
```

- [ ] **Step 2: 跑确认失败**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='ConfigChangeBroadcastHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 ConfigChangeBroadcastHandler**

```java
package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.eval.internal.snapshot.ScriptWarmer;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨实例 config 变更广播消费端：收 {@code Scheduler.scheduleBroadcast} 透传的 param，
 * 从 DB 全量重载该 scene 的索引（与进程内 {@link RuleIndexEventListener} /
 * {@link SceneIndexEventListener} 同构，幂等，重复触发无害）。
 *
 * <p>{@code Scheduler} 条件装配，{@link ObjectProvider} 惰性注入：无调度后端时不注册广播
 * handler（单 JVM 进程内事件已够）。
 */
@Component
public class ConfigChangeBroadcastHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfigChangeBroadcastHandler.class);
    private static final String BROADCAST_CODE = "config-change";

    private final ObjectProvider<Scheduler> schedulerProvider;
    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;
    private final ScriptWarmer scriptWarmer;

    public ConfigChangeBroadcastHandler(ObjectProvider<Scheduler> schedulerProvider,
                                        SceneRuleIndex index, SceneSnapshotLoader loader,
                                        ScriptWarmer scriptWarmer) {
        this.schedulerProvider = schedulerProvider;
        this.index = index;
        this.loader = loader;
        this.scriptWarmer = scriptWarmer;
    }

    /** 启动期注册广播 handler；无 Scheduler bean 时跳过（仅进程内事件）。 */
    @PostConstruct
    void register() {
        schedulerProvider.ifAvailable(s -> s.scheduleBroadcast(BROADCAST_CODE, this::onConfigChange));
    }

    /**
     * 广播回调：param 格式 {@code type:tenantId:sceneCode[:active]}，全量重载该 scene 索引。
     *
     * @param param 广播透传的业务 payload
     */
    void onConfigChange(String param) {
        String[] parts = param.split(":");
        if (parts.length < 3) {
            log.warn("config-change 广播 param 非法，跳过 param={}", param);
            return;
        }
        String tenantId = parts[1];
        String sceneCode = parts[2];
        // 全量重载（含已删除/禁用规则；scene=false 时空结果自动清索引，等价 remove）
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadBySceneWithStrategy(tenantId, sceneCode, index);
        index.replaceScene(tenantId, sceneCode, byEventType);
        Set<RuleVersionSnapshot> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : byEventType.entrySet()) {
            distinct.addAll(entry.getValue());
        }
        scriptWarmer.warmUpIfEager(new ArrayList<>(distinct));
        log.debug("config-change 广播重载完成 tenant={} scene={}", tenantId, sceneCode);
    }
}
```

- [ ] **Step 4: 跑测试通过**

Run: `$MVN -pl rule-eval-svc -am test -Dtest='ConfigChangeBroadcastHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（2 用例）。

- [ ] **Step 5: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/listener/ConfigChangeBroadcastHandler.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/listener/ConfigChangeBroadcastHandlerTest.java
git commit -m "feat(eval-svc): ConfigChangeBroadcastHandler 消费广播重载索引"
```

---

## Task 7: 全量 clean test 兜底

- [ ] **Step 1: 全量**

Run: `$MVN clean test`
Expected: 27 模块全绿（跨模块改了 kernel SPI，必须 clean 全量重编重测）。

- [ ] **Step 2: 若有失败，修复后重跑**

不得用 `-DskipTests` 绕过。常见点：其它模块若有匿名 `Scheduler` 实现（如测试桩）需补两个广播方法。

---

## Task 8: 端到端验证（真 XXL admin 双实例）

> 需 Docker。验证 config 变更经 XXL 分片广播到第二个实例。可选但推荐——单测 mock 不掉"真分片广播到多 executor"。

- [ ] **Step 1: 起 XXL admin（已有容器则 start）**

```bash
docker start xxl-job-admin || docker run -d --name xxl-job-admin -p 8088:8080 \
  -e PARAMS="--spring.datasource.url=jdbc:mysql://host.docker.internal:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false --spring.datasource.username=root --spring.datasource.password=123456" \
  xuxueli/xxl-job-admin:3.4.2
```

- [ ] **Step 2: 打包 + 起两个实例（不同端口）**

```bash
$MVN -q -pl rule-app -am package -Pxxl -DskipTests
# 实例 A（默认 8080 + executor 9999）
ENGINE_RULE_JOB_SCHEDULER=xxl-job XXL_ADMIN_ADDRESSES=http://127.0.0.1:8088 \
  XXL_ADMIN_USERNAME=admin XXL_ADMIN_PASSWORD=123456 \
  nohup java -jar rule-app/target/rule-app-1.0.0-SNAPSHOT.jar > /tmp/rule-app-A.log 2>&1 &
# 实例 B（8081 + executor 9998）
ENGINE_RULE_JOB_SCHEDULER=xxl-job XXL_ADMIN_ADDRESSES=http://127.0.0.1:8088 \
  XXL_ADMIN_USERNAME=admin XXL_ADMIN_PASSWORD=123456 \
  XXL_EXECUTOR_PORT=9998 \
  nohup java -jar rule-app/target/rule-app-1.0.0-SNAPSHOT.jar --server.port=8081 > /tmp/rule-app-B.log 2>&1 &
sleep 35
```

- [ ] **Step 3: 确认两 executor 都注册 + 广播 jobinfo seed**

查 admin DB：`SELECT registry_value FROM xxl_job.xxl_job_registry WHERE registry_key='rule-engine'`（应两条），`SELECT job_desc, executor_route_strategy FROM xxl_job.xxl_job_info WHERE job_desc='config-broadcast'`（应 SHARDING_BROADCAST）。

- [ ] **Step 4: 触发 config 变更，验证两实例都重载**

经实例 A 的 API 改一个 scene（如 disable 再 enable），查两个实例日志都出现 `config-change 广播重载完成`。

- [ ] **Step 5: 清理**

```bash
pkill -f "rule-app-1.0.0-SNAPSHOT"; docker stop xxl-job-admin
# 清 xxl_job_info 测试 seed；scheduled_task 测试数据按需清
```

---

## Self-Review

**Spec 覆盖：**
- §2 SPI 扩两方法（Consumer<String>）→ Task 1 ✓
- §2.1 ThreadPool 实现 → Task 2 ✓；XXL 实现 → Task 4 ✓
- §5 ensureJobSeeded 加 routeStrategy + triggerJob → Task 3 ✓
- §6 双 handler（BROADCAST_HANDLER 独立，避免 Long.parseLong 吞）→ Task 4 ✓
- §3.2 ConfigChangeBroadcaster（@ApplicationModuleListener + ObjectProvider）→ Task 5 ✓
- §3.2 ConfigChangeBroadcastHandler（@PostConstruct + ObjectProvider）→ Task 6 ✓
- §3.3 重复 reload 幂等（复用 replaceScene）→ Task 6 内联 ✓
- §4 param 透传（XXL 侧 XxlJobHelper，eval 侧只认 Consumer）→ Task 4 + Task 6 ✓
- §9 测试策略（契约/adapter/broadcaster/handler/e2e）→ Task 1-8 ✓
- §7 数据流端到端 → Task 8 ✓

**类型一致性：**
- `scheduleBroadcast(String, Consumer<String>)` / `triggerBroadcast(String, String)`：Task 1 定义，Task 2/4/5/6 引用一致
- `ensureJobSeeded(jobDesc, handler, cron, routeStrategy, param)`：Task 3 定义五参，Task 3 Step5（schedule 传 FIRST）+ Task 4（seed 传 SHARDING_BROADCAST）一致
- `triggerJob(long, String)`：Task 3 定义，Task 4 调用一致
- `BROADCAST_HANDLER="config-broadcast-runner"` / 广播 code `"config-change"`：Task 4 定义，Task 5/6 引用一致
- event 字段：`SceneChangedEvent(tenantId, sceneCode, active)` / `RulePublishedEvent(tenantId, sceneCode, ruleVersionId)`——真实核对过，Task 5 用 tenantId()/sceneCode()/active() 一致
- 复用方法签名：`loadBySceneWithStrategy(String,String,SceneRuleIndex)` / `replaceScene(String,String,Map)` / `warmUpIfEager(Collection)`——真实核对过，Task 6 一致

**占位符扫描：** 无 TBD/TODO，每步含完整代码。
