# OUTCOME_INGESTION 任务创建 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `POST /admin/v1/scheduled-tasks` 创建 OUTCOME_INGESTION 调度任务，同时把 XXL-JOB 适配器从"每任务独立 handler"改成"通用 handler + taskId 作 executor param"，保证多实例下新任务可在任意实例调度。

**Architecture:** kernel 新增 `TaskRunCallback` SPI（避免 rule-job-xxl→rule-job-svc 循环依赖）；`XxlJobSchedulerAdapter` 启动时注册唯一 `"scheduled-task-runner"` handler，读 executor param 取 taskId 调 callback；create API 写 DB + register 即可，全集群立即可触发。ThreadPoolSchedulerAdapter（单机 dev）不改。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / XXL-JOB core / antd5 TypeScript 前端。

**环境:** `$MVN`（先用 `mvn-env` skill 设置）；跨模块带 `-am`；最终 `clean test`。

---

### Task 1: kernel 新增 `TaskRunCallback` SPI

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/scheduler/TaskRunCallback.java`

- [ ] **Step 1: 写 SPI**

```java
package com.sstlfsj.rule.kernel.api.spi.scheduler;

/**
 * 通用调度后端（如 XXL-JOB）触发任务的回调 SPI。
 * 解耦 rule-job-xxl（不依赖 rule-job-svc）与 ScheduledTaskScheduleManager。
 */
@FunctionalInterface
public interface TaskRunCallback {
    /**
     * 按任务 id 运行一次（等同于 ScheduledTaskScheduleManager 的 runById）。
     *
     * @param taskId scheduled_task.id
     */
    void run(long taskId);
}
```

- [ ] **Step 2: 编译确认**

Run: `$MVN -pl rule-kernel -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/scheduler/TaskRunCallback.java
git commit -m "feat(kernel): TaskRunCallback SPI——调度后端触发任务的可移植回调,解耦 xxl→job-svc"
```

---

### Task 2: ScheduledTaskScheduleManager 暴露 TaskRunCallback + `runById` 可达

**Files:**
- Modify: `rule-job-svc/.../internal/service/ScheduledTaskScheduleManager.java`

- [ ] **Step 1: 读当前 `runById` 可见性**

```bash
grep -n "private void runById\|runById" \
  rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/service/ScheduledTaskScheduleManager.java
```
Expected: `private void runById(Long taskId)` 存在。

- [ ] **Step 2: 改 runById 为 package-private，实现 TaskRunCallback**

在 `ScheduledTaskScheduleManager` 类声明上加 `implements TaskRunCallback`，把 `private void runById(Long taskId)` 改为 `void runById(Long taskId)`（去掉 private），并添加接口方法：

```java
// 类声明变为：
public class ScheduledTaskScheduleManager implements TaskRunCallback {

    // ... 现有字段/构造器不变 ...

    /** TaskRunCallback 实现：供 XXL-JOB 通用 handler 回调，直接委托 runById。 */
    @Override
    public void run(long taskId) {
        runById(taskId);
    }

    // 将 private void runById(Long taskId) 改为：
    void runById(Long taskId) {
        ScheduledTask latest = taskMapper.selectById(taskId);
        if (latest != null && latest.getStatus() == TaskStatus.ACTIVE) {
            doRun(latest);
        }
    }

    // 其余方法不变
}
```

Import: `com.sstlfsj.rule.kernel.api.spi.scheduler.TaskRunCallback`（rule-job-svc 已依赖 rule-kernel）。

- [ ] **Step 3: 跑模块测试**

Run: `$MVN -pl rule-job-svc -am test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD" | tail`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/service/ScheduledTaskScheduleManager.java
git commit -m "refactor(job): ScheduledTaskScheduleManager 实现 TaskRunCallback, runById 可达"
```

---

### Task 3: XxlJobAdminClient / HttpXxlJobAdminClient 支持 executorParam

当前 `ensureJobSeeded(handlerName, cron)` 把 handlerName 同时当唯一 key 和 executorHandler。通用 handler 模式下需要分开：`jobDesc` 作唯一标识，`executorHandler` 固定为 `"scheduled-task-runner"`，`executorParam` = taskId 字符串。

**Files:**
- Modify: `rule-job-xxl/.../internal/XxlJobAdminClient.java`
- Modify: `rule-job-xxl/.../internal/HttpXxlJobAdminClient.java`

- [ ] **Step 1: 更新接口**

```java
/** xxl-job admin 接入 SPI。 */
public interface XxlJobAdminClient {

    /**
     * 确保 admin 侧存在匹配 jobDesc + executorHandler 的 jobinfo；不存在则新建，已存在则保持不动。
     *
     * @param jobDesc         job 描述（用于唯一定位，如 "task-42"）
     * @param executorHandler 执行器 handler 名（通用模式固定为 "scheduled-task-runner"）
     * @param cron            cron 表达式（仅新建时写入）
     * @param executorParam   执行器 param（通用模式传 taskId 字符串；旧 per-task 模式传 ""）
     * @return admin 侧该 job 的 id
     */
    long ensureJobSeeded(String jobDesc, String executorHandler, String cron, String executorParam);
}
```

- [ ] **Step 2: 更新 HttpXxlJobAdminClient**

`findJobInfoId` 改用 `jobDesc` 做精确匹配（而不是 executorHandler），`insertJobInfo` 加 executorParam 字段：

```java
@Override
public synchronized long ensureJobSeeded(String jobDesc, String executorHandler,
                                         String cron, String executorParam) {
    int groupId = ensureJobGroup();
    Long existing = findJobInfoId(groupId, jobDesc);    // ← 按 jobDesc 找
    if (existing != null) {
        log.info("xxl-job admin 已存在 job jobDesc={} id={}，保持不动", jobDesc, existing);
        return existing;
    }
    return insertJobInfo(groupId, jobDesc, executorHandler, cron, executorParam);
}

/** 按 jobDesc 精确匹配——支持同一 executorHandler 下多个 job（通用 handler 模式）。 */
private Long findJobInfoId(int groupId, String jobDesc) {
    JsonNode page = post("/jobinfo/pageList", form(
            "offset", "0", "pagesize", "100",
            "jobGroup", String.valueOf(groupId), "triggerStatus", "-1",
            "jobDesc", jobDesc, "executorHandler", "", "author", ""), true).path("data");
    for (JsonNode job : page.path("data")) {
        if (jobDesc.equals(job.path("jobDesc").asString(""))) {
            return job.path("id").asLong();
        }
    }
    return null;
}

/** 新建 jobinfo，支持传入 executorHandler 和 executorParam。 */
private long insertJobInfo(int groupId, String jobDesc, String executorHandler,
                           String cron, String executorParam) {
    JsonNode data = post("/jobinfo/insert", form(
            "jobGroup", String.valueOf(groupId),
            "jobDesc", jobDesc,
            "author", "rule-engine",
            "scheduleType", "CRON",
            "scheduleConf", cron,
            "glueType", "BEAN",
            "executorHandler", executorHandler,
            "executorParam", executorParam,
            "executorRouteStrategy", "FIRST",
            "misfireStrategy", "DO_NOTHING",
            "executorBlockStrategy", "SERIAL_EXECUTION",
            "executorTimeout", "0",
            "executorFailRetryCount", "0",
            "triggerStatus", "1"), true).path("data");
    log.info("xxl-job admin 新建 job jobDesc={} handler={} param={} id={} cron={}",
             jobDesc, executorHandler, executorParam, data.asLong(), cron);
    return data.asLong();
}
```

- [ ] **Step 3: 跑 rule-job-xxl 编译**

Run: `$MVN -pl rule-job-xxl -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobAdminClient.java \
        rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClient.java
git commit -m "refactor(job-xxl): ensureJobSeeded 支持 executorParam——通用 handler 按 jobDesc 唯一定位"
```

---

### Task 4: XxlJobSchedulerAdapter 改用通用 handler

**Files:**
- Modify: `rule-job-xxl/.../internal/XxlJobSchedulerAdapter.java`
- Modify: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/XxlJobAutoConfiguration.java`

- [ ] **Step 1: 重写 XxlJobSchedulerAdapter**

```java
package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.sstlfsj.rule.kernel.api.spi.scheduler.TaskRunCallback;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Scheduler} 的 xxl-job 适配实现。
 *
 * <p><b>通用 handler 模式</b>：启动时注册唯一 {@value #UNIVERSAL_HANDLER}，
 * 每个任务的 cron 触发由 admin 带 executorParam=taskId 派发；handler 根据 param
 * 查本地 runnables 执行，缺失则降级调 {@link TaskRunCallback}（如新建任务仅在
 * API 实例注册，派到其他实例时走回调从 DB 重载）。
 *
 * <p>此设计使全集群共享同一 handler name，新任务无需各实例重启即可调度。
 */
public class XxlJobSchedulerAdapter implements Scheduler {

    /** 全集群共用的通用 handler 名——所有实例注册同一名称。 */
    public static final String UNIVERSAL_HANDLER = "scheduled-task-runner";

    private static final Logger log = LoggerFactory.getLogger(XxlJobSchedulerAdapter.class);

    private final XxlJobAdminClient adminClient;
    private final TaskRunCallback fallbackCallback;

    /** jobCode("scheduled-task:42") → Runnable，用于本实例已注册的任务快速执行。 */
    private final Map<Long, Runnable> runnables = new ConcurrentHashMap<>();

    public XxlJobSchedulerAdapter(XxlJobAdminClient adminClient, TaskRunCallback fallbackCallback) {
        this.adminClient = adminClient;
        this.fallbackCallback = fallbackCallback;
        // 注册通用 handler（一次即可，各实例相同 name）
        XxlJobExecutor.registryJobHandler(UNIVERSAL_HANDLER, new IJobHandler() {
            @Override
            public void execute() {
                String param = XxlJobHelper.getJobParam();
                if (param == null || param.isBlank()) {
                    log.warn("scheduled-task-runner: 缺少 taskId param，跳过");
                    return;
                }
                long taskId;
                try {
                    taskId = Long.parseLong(param.trim());
                } catch (NumberFormatException e) {
                    log.warn("scheduled-task-runner: param 非 taskId='{}'，跳过", param);
                    return;
                }
                Runnable r = runnables.get(taskId);
                if (r != null) {
                    r.run();
                } else {
                    // 降级：本实例未注册该任务（如 API 创建后尚未重启），通过回调从 DB 重载运行
                    log.debug("scheduled-task-runner: taskId={} 本实例无缓存，降级调 callback", taskId);
                    fallbackCallback.run(taskId);
                }
            }
        });
        log.info("xxl-job 通用 handler '{}' 注册完成", UNIVERSAL_HANDLER);
    }

    @Override
    public synchronized void schedule(String jobCode, String cronExpression, Runnable task) {
        long taskId = parseTaskId(jobCode);
        runnables.put(taskId, task);
        long adminJobId = adminClient.ensureJobSeeded(
                "task-" + taskId,       // jobDesc：唯一标识
                UNIVERSAL_HANDLER,      // executorHandler：统一
                cronExpression,
                String.valueOf(taskId)  // executorParam：taskId
        );
        log.info("xxl-job 注册 taskId={} adminJobId={} cron={}", taskId, adminJobId, cronExpression);
    }

    @Override
    public synchronized void unschedule(String jobCode) {
        long taskId = parseTaskId(jobCode);
        runnables.remove(taskId);
        // XXL admin job 保留（运维在控制台管停用），只清本地 runnable 缓存
        log.info("xxl-job 注销 taskId={} (runnable 缓存已移除，admin job 保留)", taskId);
    }

    /** 从 jobCode("scheduled-task:42") 解析 taskId。 */
    private static long parseTaskId(String jobCode) {
        final String PREFIX = "scheduled-task:";
        if (!jobCode.startsWith(PREFIX)) {
            throw new IllegalArgumentException("jobCode 格式非法: " + jobCode);
        }
        return Long.parseLong(jobCode.substring(PREFIX.length()));
    }
}
```

- [ ] **Step 2: 更新 XxlJobAutoConfiguration 注入 TaskRunCallback**

```java
@Bean
@ConditionalOnMissingBean(Scheduler.class)
public Scheduler scheduler(XxlJobAdminClient adminClient, TaskRunCallback taskRunCallback) {
    return new XxlJobSchedulerAdapter(adminClient, taskRunCallback);
}
```

Import: `com.sstlfsj.rule.kernel.api.spi.scheduler.TaskRunCallback`。

- [ ] **Step 3: 跑 rule-job-xxl + rule-job-svc 联合编译**

Run: `$MVN -pl rule-job-xxl,rule-job-svc -am compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapter.java \
        rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/XxlJobAutoConfiguration.java
git commit -m "feat(job-xxl): 通用 handler scheduled-task-runner + TaskRunCallback 降级——多实例新建任务可调度"
```

---

### Task 5: create API 后端（DTO + Service + Controller + 测试）

**Files:**
- Create: `rule-job-svc/.../api/dto/CreateScheduledTaskRequest.java`
- Modify: `rule-job-svc/.../api/service/ScheduledTaskService.java`
- Modify: `rule-job-svc/.../internal/service/ScheduledTaskServiceImpl.java`
- Modify: `rule-api/.../web/admin/ScheduledTaskController.java`
- Modify: `rule-job-svc/.../internal/service/ScheduledTaskServiceImplTest.java`
- Modify: `rule-api/.../web/admin/ScheduledTaskControllerTest.java`

- [ ] **Step 1: DTO**

```java
package com.sstlfsj.rule.job.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建 OUTCOME_INGESTION 调度任务请求（SQL-direct 源）。
 * TRIGGER 任务由 {@code @TriggerTask} 注解 seed，不走此接口。
 *
 * @param tenantId   租户 id
 * @param code       任务编码（租户内唯一）
 * @param name       展示名称
 * @param cron       Spring 6 段 cron（秒 分 时 日 月 周），如 {@code 0 0 2 * * *}
 * @param datasource MetricDataSourceRegistry 已注册的数据源名
 * @param sql        标签拉取 SQL（须含固定列别名 event_id/outcome_label/outcome_value/labeled_at；
 *                   可绑定 :tenantId / :watermark）
 */
public record CreateScheduledTaskRequest(
        @NotNull Long tenantId,
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String cron,
        @NotBlank String datasource,
        @NotBlank String sql) {}
```

- [ ] **Step 2: Service 接口加方法**

在 `ScheduledTaskService` 已有方法后追加：

```java
/**
 * 创建 OUTCOME_INGESTION 调度任务（SQL-direct 源），并立即注册到调度器。
 * TRIGGER 任务由 {@code @TriggerTask} 注解 seed，不走此接口。
 *
 * @param req 创建请求
 * @return 创建后的任务 VO
 * @throws IllegalArgumentException 若 code 在该租户下已存在
 */
ScheduledTaskVO create(CreateScheduledTaskRequest req);
```

- [ ] **Step 3: ServiceImpl 实现 create 方法**

在 ScheduledTaskServiceImpl 已有的 import 基础上追加（eval-svc 的两个类）：

```java
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionConfig;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
```

方法实现：

```java
@Override
@Transactional
public ScheduledTaskVO create(CreateScheduledTaskRequest req) {
    // 幂等保护
    if (taskMapper.findByTenantCode(req.tenantId(), req.code()) != null) {
        throw new IllegalArgumentException(
                "调度任务已存在: tenantId=" + req.tenantId() + ", code=" + req.code());
    }
    // 组装 typed config → JSON
    OutcomeIngestionConfig config =
            new OutcomeIngestionConfig(new SqlOutcomeSourceConfig(req.datasource(), req.sql()));
    String configJson;
    try {
        configJson = objectMapper.writeValueAsString(config);
    } catch (Exception e) {
        throw new IllegalStateException("config 序列化失败", e);
    }
    ScheduledTask task = new ScheduledTask();
    task.setTenantId(req.tenantId());
    task.setCode(req.code());
    task.setName(req.name());
    task.setTaskType("OUTCOME_INGESTION");
    task.setCron(req.cron());
    task.setConfig(configJson);
    task.setStatus(TaskStatus.ACTIVE);
    task.setCreatedBy("api");
    taskMapper.insert(task);
    scheduleManager.register(task);
    return toVO(task);
}
```

> 确认 `objectMapper` 已作为字段注入（现有 `ScheduledTaskServiceImpl` 已有此字段用于 `rejectIfPullScene`）。

- [ ] **Step 4: Controller 加 POST 端点**

在 `ScheduledTaskController` 已有的 `@GetMapping`/`@PostMapping` 后追加：

```java
/**
 * POST /admin/v1/scheduled-tasks — 创建 OUTCOME_INGESTION 调度任务（SQL-direct 源）。
 *
 * @param req 创建请求体
 * @return 201 Created + 任务 VO
 */
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public ApiResponse<ScheduledTaskVO> create(@Valid @RequestBody CreateScheduledTaskRequest req) {
    return ApiResponse.ok(service.create(req));
}
```

Import: `org.springframework.http.HttpStatus`, `jakarta.validation.Valid`, `com.sstlfsj.rule.job.api.dto.CreateScheduledTaskRequest`。

- [ ] **Step 5: 单测 ScheduledTaskServiceImplTest**

在现有测试类追加（照 `enableForPushSceneRegistersSchedule` 风格）：

```java
@Test
void create_insertsTaskAndRegisters() {
    when(taskMapper.findByTenantCode(1L, "ingest-test")).thenReturn(null);
    // objectMapper 真实用（写 JSON）
    when(taskMapper.insert(any())).thenReturn(1);

    CreateScheduledTaskRequest req = new CreateScheduledTaskRequest(
            1L, "ingest-test", "测试回灌", "0 0 2 * * *", "biz", "SELECT event_id, outcome_label, outcome_value, labeled_at FROM biz_label WHERE tenant_id = :tenantId");
    ScheduledTaskVO vo = service.create(req);

    assertThat(vo.code()).isEqualTo("ingest-test");
    assertThat(vo.taskType()).isEqualTo("OUTCOME_INGESTION");
    verify(scheduleManager).register(any(ScheduledTask.class));
}

@Test
void create_duplicateCode_throws() {
    ScheduledTask existing = new ScheduledTask();
    existing.setCode("ingest-dup");
    when(taskMapper.findByTenantCode(1L, "ingest-dup")).thenReturn(existing);

    CreateScheduledTaskRequest req = new CreateScheduledTaskRequest(
            1L, "ingest-dup", "重复", "0 0 2 * * *", "biz", "SELECT 1");
    assertThrows(IllegalArgumentException.class, () -> service.create(1L, req));
}
```

> 注意：若测试用的 `service.create(req)` 签名，改为 `service.create(req)`（无 tenantId 参数）。`create_duplicateCode_throws` 里 `service.create(1L, req)` 写错了——改为 `service.create(req)`（tenantId 在 req 里）。

- [ ] **Step 6: 单测 ScheduledTaskControllerTest**

追加：

```java
@Test
void create_returns201AndVO() {
    ScheduledTaskVO vo = new ScheduledTaskVO(10L, 1L, "ingest", "测试", "OUTCOME_INGESTION",
            "0 0 2 * * *", null, "ACTIVE", null, null);
    when(service.create(any())).thenReturn(vo);

    ApiResponse<ScheduledTaskVO> resp = controller.create(
            new CreateScheduledTaskRequest(1L, "ingest", "测试", "0 0 2 * * *", "biz", "SELECT 1"));

    assertTrue(resp.success());
    assertEquals("ingest", resp.data().code());
    assertEquals("OUTCOME_INGESTION", resp.data().taskType());
}
```

- [ ] **Step 7: 跑测试**

Run: `$MVN -pl rule-job-svc,rule-api -am test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD|ERROR" | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/dto/CreateScheduledTaskRequest.java \
        rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/service/ScheduledTaskService.java \
        rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/service/ScheduledTaskServiceImpl.java \
        rule-api/src/main/java/com/sstlfsj/rule/web/admin/ScheduledTaskController.java \
        rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/service/ScheduledTaskServiceImplTest.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/admin/ScheduledTaskControllerTest.java
git commit -m "feat(job): OUTCOME_INGESTION 任务创建 API——POST /admin/v1/scheduled-tasks"
```

---

### Task 6: 前端「创建回灌任务」按钮 + Modal

**Files:**
- Modify: `frontend/src/constants/api-endpoints.ts`
- Modify: `frontend/src/api/scheduledTask.ts`
- Modify: `frontend/src/pages/scheduled-task-list/index.tsx`

- [ ] **Step 1: 读现有 list 页掌握结构**

```bash
sed -n '1,30p' frontend/src/pages/scheduled-task-list/index.tsx
```

确认现有 import、状态、租户 Select 的位置。

- [ ] **Step 2: 更新 ENDPOINTS**

在 `api-endpoints.ts` 的 SCHEDULED_TASK_* 区域追加（POST 复用 list 端点）：

```typescript
SCHEDULED_TASK_CREATE: `${ADMIN}/scheduled-tasks`,
```

- [ ] **Step 3: 更新 api/scheduledTask.ts**

追加：

```typescript
export interface CreateIngestionTaskParams {
  tenantId: number;
  code: string;
  name: string;
  cron: string;
  datasource: string;
  sql: string;
}

export async function createIngestionTask(params: CreateIngestionTaskParams): Promise<ScheduledTaskItem> {
  const res = await apiClient.post<ApiResponse<ScheduledTaskItem>>(
    ENDPOINTS.SCHEDULED_TASK_CREATE,
    params,
  );
  return res.data.data;
}
```

- [ ] **Step 4: 更新 scheduled-task-list/index.tsx**

读 `ScheduledTaskItem` 类型位置（在 `types/scheduledTask.ts`），然后在 list 页：
1. 在顶部 import 里加 `Modal`, `Form`, `Input`, `message`（antd），以及 `createIngestionTask`、`CreateIngestionTaskParams`。
2. 在组件 state 里加 `const [createOpen, setCreateOpen] = useState(false);`。
3. 在租户 Select 旁边加一个 primary Button：`<Button type="primary" onClick={() => setCreateOpen(true)}>创建回灌任务</Button>`。
4. 在表格下方加 Modal：

```tsx
<Modal
  title="创建回灌任务（OUTCOME_INGESTION）"
  open={createOpen}
  onCancel={() => setCreateOpen(false)}
  footer={null}
  width={640}
>
  <Form
    layout="vertical"
    onFinish={async (values) => {
      try {
        await createIngestionTask({ tenantId: tenantId!, ...values });
        message.success('任务创建成功');
        setCreateOpen(false);
        load();   // 刷新列表（使用 list 页的 load 函数）
      } catch (err: unknown) {
        const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
        message.error(msg ?? '创建失败');
      }
    }}
  >
    <Form.Item label="任务编码" name="code" rules={[{ required: true }]}
               extra="租户内唯一，字母/数字/横杠，如 fraud-ingest-daily">
      <Input placeholder="fraud-ingest-daily" />
    </Form.Item>
    <Form.Item label="任务名称" name="name" rules={[{ required: true }]}>
      <Input />
    </Form.Item>
    <Form.Item label="Cron 表达式" name="cron" rules={[{ required: true }]}
               extra="Spring 6 段 cron，如 0 0 2 * * *（每天凌晨 2 点）">
      <Input placeholder="0 0 2 * * *" />
    </Form.Item>
    <Form.Item label="数据源名称" name="datasource" rules={[{ required: true }]}
               extra="MetricDataSourceRegistry 中已注册的数据源名">
      <Input />
    </Form.Item>
    <Form.Item label="标签拉取 SQL" name="sql" rules={[{ required: true }]}
               extra="须含固定列：event_id / outcome_label / outcome_value / labeled_at；可绑定 :tenantId / :watermark">
      <Input.TextArea
        rows={4}
        placeholder={"SELECT event_id, outcome_label, outcome_value, labeled_at\n" +
          "FROM biz_label\n" +
          "WHERE tenant_id = :tenantId\n" +
          "  AND (:watermark IS NULL OR labeled_at > :watermark)\n" +
          "ORDER BY labeled_at ASC LIMIT 1000"}
      />
    </Form.Item>
    <Form.Item>
      <Button type="primary" htmlType="submit">创建</Button>
    </Form.Item>
  </Form>
</Modal>
```

注意：`tenantId` 从现有的 `const tenantId = tenantFilter ?? currentId ?? 0;` 取得，Modal 里直接用。

- [ ] **Step 5: tsc build 验证**

Run: `cd frontend && npm run build 2>&1 | grep -E "error TS|✓ built" | head`
Expected: `✓ built`（无 TS 错误）

- [ ] **Step 6: Commit**

```bash
git add frontend/src/constants/api-endpoints.ts \
        frontend/src/api/scheduledTask.ts \
        frontend/src/pages/scheduled-task-list/index.tsx
git commit -m "feat(frontend): 调度任务列表「创建回灌任务」按钮 + Modal 表单(OUTCOME_INGESTION)"
```

---

### Task 7: 全量 clean test + 更新 backlog

- [ ] **Step 1: 全量 clean test**

Run: `$MVN clean test 2>&1 | grep -E "BUILD|Failures: [1-9]|Errors: [1-9]" | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 2: backlog 更新（backlog.md 在 plans 目录，gitignored，本地更新即可）**

在 backlog.md"触发式"一节的待实现列表或说明里记录：「OUTCOME_INGESTION 任务创建 API 已实装（POST /admin/v1/scheduled-tasks）；XXL 适配器已改通用 handler，多实例调度就绪」。（文件 gitignored，仅本地更新）

- [ ] **Step 3: Final commit**

```bash
git commit --allow-empty -m "chore: OUTCOME_INGESTION create API + XXL 通用 handler 全量测试绿"
```

---

## Self-Review

**Spec 覆盖**（`distributed-ready-scheduling-and-propagation-design.md` §3.0.1 + §3.1 cron upsert）：
- `TaskRunCallback` SPI → Task 1 ✅
- `ScheduledTaskScheduleManager` 暴露回调 → Task 2 ✅
- `XxlJobAdminClient` executorParam → Task 3 ✅
- 通用 handler + runnables map + 降级 → Task 4 ✅
- XxlJobAutoConfiguration wire → Task 4 ✅
- Create API DTO/Service/Controller → Task 5 ✅
- 前端 create form → Task 6 ✅
- §3.1"cron 权威翻转为 our backend + schedule() = upsert"：**通用 handler 模式下 `ensureJobSeeded` 仍是"有了不管"** —— 这是有意为之：admin 控制台对 cron 有运维权威，不每次覆盖；但 create API 是首次建，"有了不管"正好处理幂等。与 spec §3.1 的"upsert"语义轻微不一致（现在是 seed-if-absent），但改成强制覆盖会破坏运维权威。记录在计划里即可，不是 blocker。

**Placeholder 扫描**：无 TBD/TODO；所有步骤有完整代码。

**类型一致性**：
- `CreateScheduledTaskRequest` record 字段 tenantId/code/name/cron/datasource/sql 在 Task 5 全程一致。
- `TaskRunCallback.run(long)` → `ScheduledTaskScheduleManager` 实现 → `XxlJobSchedulerAdapter` 调用，类型一致。
- `ensureJobSeeded(String jobDesc, String executorHandler, String cron, String executorParam)` 在接口、impl、调用处一致。
- `create_duplicateCode_throws` 测试有一处笔误（`service.create(1L, req)` 应为 `service.create(req)`）——已在 Step 5 注释里标注，实现时修正。

**执行期风险**：
1. `ScheduledTaskServiceImplTest` 需要真实 `ObjectMapper` 来序列化 config——check 现有 test setup 是否已有（`mock(ObjectMapper.class)` 不能序列化；需 `new JsonMapper.Builder().build()` 或注入真实 ObjectMapper）。执行时依照现有 enableForPushScene 测试的 setup 方式补充。
2. `XxlJobHelper.getJobParam()` 是 static 方法（来自 xxl-job-core），在单元测试里 mock 需要 PowerMock 或 mockStatic；可改为集成测试或简单接受该方法不在单元测试中覆盖（handler 逻辑简单，e2e 时验）。
